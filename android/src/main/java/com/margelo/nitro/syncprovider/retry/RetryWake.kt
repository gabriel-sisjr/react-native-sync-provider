package com.margelo.nitro.syncprovider.retry

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
 * @param scheduleKick arms the WorkManager kick. Applies the `isEnabled` and
 *   `foregroundOnly` gates itself and never throws.
 * @param isHalted true while nothing may be woken: paused, `MANUAL` strategy, or
 *   default headers that cannot be recovered.
 * @param isEngineLive true while a JS engine is attached to this process. A process
 *   started only for a worker or a boot broadcast relies on the kick: a timer there
 *   would dispatch outside the constraints the consumer asked WorkManager to honor.
 */
internal class RetryWake(
  private val storage: SyncQueueStorage,
  private val timer: RetryTimer,
  private val scheduleKick: (delayMs: Long) -> Unit,
  private val isHalted: () -> Boolean,
  private val isEngineLive: () -> Boolean,
  private val clock: () -> Long = { System.currentTimeMillis() },
) {

  /**
   * One arm at a time, each reading the queue and the hold under the lock, so the last
   * one decides from what is current. Otherwise an arm that read the queue before a hold
   * began could finish after it, and REPLACE the held kick with an earlier one.
   */
  private val armMutex = Mutex()

  /** No wake before this instant, in epoch millis. `0` means no hold. */
  private val credentialHoldUntil = AtomicLong(0L)

  /**
   * Arm for the soonest retry deadline, but not before the credential hold ends. A
   * deadline already in the past yields delay 0, so a row whose cooldown elapsed during
   * a cycle is never stranded.
   *
   * @param notBefore when the hold on a rejected credential ends. Only the end of a
   *   cycle passes it. It outlives this call, so a later arm cannot wake earlier.
   */
  suspend fun arm(notBefore: Long? = null) {
    armMutex.withLock {
      // Before the gates: a cycle that ends while paused, or with nothing left to wake
      // for, still holds back the wake that comes next.
      if (notBefore != null) {
        credentialHoldUntil.accumulateAndGet(notBefore) { held, reported -> maxOf(held, reported) }
      }
      if (isHalted()) {
        timer.cancel()
        return@withLock
      }
      val deadline = storage.nextRetryDeadline()
      if (deadline == null) {
        timer.cancel()
        return@withLock
      }
      val delayMs = delayUntil(maxOf(deadline, credentialHoldUntil.get()))
      if (isEngineLive()) timer.arm(delayMs)
      scheduleKick(delayMs)
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
      val holdUntil = credentialHoldUntil.get()
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

  private fun delayUntil(deadline: Long): Long = (deadline - clock()).coerceAtLeast(0L)
}
