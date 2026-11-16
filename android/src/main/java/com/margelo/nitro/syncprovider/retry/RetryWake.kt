package com.margelo.nitro.syncprovider.retry

import com.margelo.nitro.syncprovider.http.CycleEnding
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Decides when the queue is woken next, and arms both wake mechanisms from one
 * place: the in-process [RetryTimer] and the opt-in WorkManager kick.
 *
 * It also keeps the credential hold. When the server rejects a credential restored
 * from disk, the cycle reports when the hold ends, and no wake fires before then, even
 * when other rows are due. That holds for every path that arms one: the end of a cycle,
 * `resumeSync`, a JS engine attaching or reloading, and recovery. The timer and the kick
 * both wait, so a kick that REPLACEs the armed one is never earlier than the hold. A
 * cycle that is not rejected again leaves the hold in place, a later rejection can only
 * extend it, and once its instant passes it delays nothing. [clearCredentialHold] lifts
 * it.
 *
 * The hold lives in memory only. A new process starts without it, so a row that is
 * already due there can cost one more request with the rejected credential, whose
 * rejection sets the hold again.
 *
 * It keeps the failure hold too. After a cycle that failed, one that a cause other than a
 * cancellation ended (a storage read or write, an event emit, an [Error]), no wake on any path
 * fires before the next [GateRecheck] step: 30 s after the first such cycle, doubling for each
 * one that follows, at most 15 min. Otherwise a row that cycle gave back with a cooldown already
 * past would re-arm both mechanisms at once, and a failure that persists would run cycles back
 * to back. A cycle that completes lifts the hold and starts the steps over; a cancelled one
 * leaves both as they are. Explicit flushes and the periodic worker never wait for it. It lives
 * in memory only, like the credential hold, so a new process starts at the first step.
 *
 * @param scheduleKick arms the WorkManager kick. Applies the `isEnabled` and
 *   `foregroundOnly` gates itself and never throws.
 * @param isHalted true while nothing may be woken: paused, `MANUAL` strategy, or
 *   default headers that cannot be recovered.
 * @param isEngineLive true while a JS engine is attached to this process. A process
 *   started only for a worker or a boot broadcast relies on the kick: a timer there
 *   would dispatch outside the constraints the consumer asked WorkManager to honor.
 * @param isQualityGateOpen whether the current network quality passes `minNetworkQuality`.
 * @param scheduleGatedKick arms the kick for the check after [armAfterGate], carrying the
 *   number of consecutive kicks the gate turned back.
 * @param healthBackoffUntil when wakes may run again after a failed health check, or `0`: the
 *   end of its backoff, and at least a second after the failure, so a backoff of `0` cannot
 *   re-arm a wake at once. Like the credential hold, no wake on any path fires before it.
 */
@Suppress("LongParameterList")
internal class RetryWake(
  private val storage: SyncQueueStorage,
  private val timer: RetryTimer,
  private val scheduleKick: (delayMs: Long) -> Unit,
  private val isHalted: () -> Boolean,
  private val isEngineLive: () -> Boolean,
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val isQualityGateOpen: () -> Boolean = { true },
  private val scheduleGatedKick: (delayMs: Long, gateStreak: Int) -> Unit = { delayMs, _ ->
    scheduleKick(delayMs)
  },
  private val healthBackoffUntil: () -> Long = { 0L },
) {

  /**
   * One arm at a time, each reading the queue and the hold under the lock, so the last
   * one decides from what is current. Otherwise an arm that read the queue before a hold
   * began could finish after it, and REPLACE the held kick with an earlier one.
   */
  private val armMutex = Mutex()

  /** No wake before this instant, in epoch millis. `0` means no hold. */
  private val credentialHoldUntil = AtomicLong(0L)

  /** Cycles that failed in a row, since the last one that completed. Guarded by [armMutex]. */
  private var failedCycles = 0

  /**
   * No wake before this instant after a cycle that failed, in epoch millis: one [GateRecheck]
   * step after it, the step set by [failedCycles]. `0` once a cycle completes. Guarded by
   * [armMutex].
   */
  private var failureHoldUntil = 0L

  /**
   * Arm for the soonest retry deadline, but not before the holds end. A deadline already
   * in the past yields delay 0, so a row whose cooldown elapsed during a cycle is never
   * stranded.
   *
   * @param notBefore when the hold on a rejected credential ends. Only the end of a
   *   cycle passes it. It outlives this call, so a later arm cannot wake earlier.
   * @param ending how the cycle that just ended finished. Only the end of a cycle passes it: a
   *   failed cycle sets the failure hold one step further, a completed one lifts it, and a
   *   cancelled one leaves it as it is.
   */
  suspend fun arm(notBefore: Long? = null, ending: CycleEnding? = null) {
    armMutex.withLock {
      // Before the gates: a cycle that ends while paused, or with nothing left to wake
      // for, still holds back the wake that comes next.
      if (notBefore != null) {
        credentialHoldUntil.accumulateAndGet(notBefore) { held, reported -> maxOf(held, reported) }
      }
      ending?.let(::recordEnding)
      armLocked()
    }
  }

  /**
   * Re-arm the kick after the network-quality gate turned it back, [gateStreak] kicks in a
   * row: no earlier than the next [GateRecheck] step, nor before a credential hold ends. The
   * next kick carries `gateStreak + 1`, so the delay doubles up to its cap; any other arm
   * schedules a kick without it, which starts the backoff over.
   *
   * The in-process timer is left alone: it checks the gate itself and holds its own wakes.
   * A gate that opened meanwhile, through a `configureSync`, makes this a plain [arm].
   */
  suspend fun armAfterGate(gateStreak: Int) {
    armMutex.withLock {
      if (isHalted()) {
        timer.cancel()
        return@withLock
      }
      if (isQualityGateOpen()) {
        armLocked()
        return@withLock
      }
      val deadline = storage.nextRetryDeadline() ?: return@withLock
      val notBefore = maxOf(clock() + GateRecheck.delayMs(gateStreak), notBeforeAny())
      scheduleGatedKick(delayUntil(maxOf(deadline, notBefore)), gateStreak + 1)
    }
  }

  /**
   * Arm after `resumeSync`. The timer considers every `PENDING` row, so rows with no
   * cooldown (`nextRetryAt = 0`) dispatch at once, unless a credential hold is active.
   */
  suspend fun armForResume() {
    armMutex.withLock {
      if (isHalted()) {
        timer.cancel()
        return@withLock
      }
      val pendingDeadline = storage.nextPendingDeadline()
      if (pendingDeadline == null) {
        timer.cancel()
        return@withLock
      }
      val holdUntil = notBeforeAny()
      timer.arm(delayUntil(maxOf(pendingDeadline, holdUntil)))
      storage.nextRetryDeadline()?.let { scheduleKick(delayUntil(maxOf(it, holdUntil))) }
    }
  }

  /**
   * Lift the credential hold. `configureSync` does it on every call, whether or not the
   * headers changed: the app has just supplied its credentials, so the next arm may wake
   * earlier.
   */
  fun clearCredentialHold() {
    credentialHoldUntil.set(0L)
  }

  /** Caller holds [armMutex]. */
  private suspend fun armLocked() {
    if (isHalted()) {
      timer.cancel()
      return
    }
    val deadline = storage.nextRetryDeadline()
    if (deadline == null) {
      timer.cancel()
      return
    }
    val delayMs = delayUntil(maxOf(deadline, notBeforeAny()))
    if (isEngineLive()) timer.arm(delayMs)
    scheduleKick(delayMs)
  }

  /** Caller holds [armMutex]. */
  private fun recordEnding(ending: CycleEnding) {
    when (ending) {
      CycleEnding.COMPLETED -> {
        failedCycles = 0
        failureHoldUntil = 0L
      }
      CycleEnding.FAILED -> failureHoldUntil = clock() + GateRecheck.delayMs(failedCycles++)
      CycleEnding.CANCELLED -> Unit
    }
  }

  /**
   * No wake before the credential hold, the health-check backoff or the failure hold ends.
   * Caller holds [armMutex].
   */
  private fun notBeforeAny(): Long =
    maxOf(credentialHoldUntil.get(), healthBackoffUntil(), failureHoldUntil)

  /**
   * The wait until [deadline]: 0 for a deadline already past, and at most
   * [RetryPolicyEvaluator.MAX_DELAY_MS], a ceiling shared with iOS and far below the delays
   * WorkManager refuses (`Long.MAX_VALUE - now` or more). Only a hold with no end, or a deadline
   * an earlier version stored, reaches it: new deadlines stop at
   * [RetryPolicyEvaluator.MAX_DEADLINE_MS].
   */
  private fun delayUntil(deadline: Long): Long =
    (deadline - clock()).coerceIn(0L, RetryPolicyEvaluator.MAX_DELAY_MS)
}
