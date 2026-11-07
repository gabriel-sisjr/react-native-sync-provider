package com.margelo.nitro.syncprovider.retry

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-shot, in-process retry timer. A new [arm] replaces the previous one.
 *
 * The dispatcher never checks connectivity, so a timer that flushed offline would
 * spend every attempt and dead-letter valid items while the device simply has no
 * network. When the timer fires offline it therefore does not flush: it remembers
 * the wake, and [onNetworkAvailable] delivers it.
 *
 * The gates the dispatcher applies to every flush are checked here first, the same way,
 * so a wake they would turn back is held without a flush and without an event:
 * - the network-quality gate: [onDispatchPossible] delivers the wake once the quality
 *   reaches the minimum. Until then it is checked again after a [GateRecheck] step, 30 s
 *   and doubling for each consecutive check the gate turns back too, up to 15 min;
 * - a health-check backoff: checked again when it ends.
 *
 * @param isOffline live connectivity check, evaluated when the timer fires.
 * @param flush the normal, mutex-guarded flush. Failures are logged, never thrown:
 *   no JS caller exists to receive them.
 * @param holdReason why a wake may not dispatch now, or null when it may. Side effects are
 *   allowed: it may start a new quality measurement.
 */
internal class RetryTimer(
  private val scope: CoroutineScope,
  private val isOffline: () -> Boolean,
  private val flush: suspend () -> Unit,
  private val holdReason: () -> WakeHold? = { null },
) {
  private val lock = Any()
  private var waiting: Job? = null
  private val owed = AtomicReference(Owed.NOTHING)

  /** Consecutive checks the quality gate turned back. Guarded by [lock]. */
  private var qualityStreak = 0

  /** Whether [waiting] is the next check of a wake the quality gate holds. Guarded by [lock]. */
  private var heldForQuality = false

  /** Whether a timer is counting down. */
  val isWaiting: Boolean
    get() = synchronized(lock) { waiting?.isActive == true }

  /** Fire once after [delayMs], replacing any timer still waiting. Starts the backoff over. */
  fun arm(delayMs: Long) {
    synchronized(lock) {
      waiting?.cancel()
      owed.set(Owed.NOTHING)
      qualityStreak = 0
      heldForQuality = false
      waiting = fireAfter(delayMs)
    }
  }

  /**
   * Hold a wake the quality gate turned back after it reached the dispatcher, because the
   * quality dropped once this timer had checked it.
   */
  fun holdForQuality() {
    hold(WakeHold.Quality)
  }

  /** Drop the waiting timer and any wake owed. A running cycle finishes. */
  fun cancel() {
    synchronized(lock) {
      waiting?.cancel()
      waiting = null
      owed.set(Owed.NOTHING)
      qualityStreak = 0
      heldForQuality = false
    }
  }

  /**
   * Start the quality backoff over, because the link or the configuration changed. A wake
   * the quality gate holds is checked again after the first step.
   */
  fun resetGateBackoff() {
    synchronized(lock) {
      qualityStreak = 0
      if (owed.get() != Owed.GATE || !heldForQuality) return
      waiting?.cancel()
      waiting = fireAfter(GateRecheck.delayMs(qualityStreak++))
    }
  }

  /**
   * The device is online again: deliver the wake owed by a timer that fired offline, if
   * there is one. A wake a gate holds waits for [onDispatchPossible] or its next check.
   */
  fun onNetworkAvailable() {
    deliverIf(Owed.NETWORK)
  }

  /** Online and at the minimum quality: deliver any wake owed, a held one included. */
  fun onDispatchPossible() {
    deliverIf(Owed.NETWORK, Owed.GATE)
  }

  private fun deliverIf(vararg kinds: Owed) {
    synchronized(lock) {
      val current = owed.get()
      if (current !in kinds || !owed.compareAndSet(current, Owed.NOTHING)) return
      // A held wake's next check would only repeat this delivery.
      if (current == Owed.GATE) {
        waiting?.cancel()
        waiting = null
        heldForQuality = false
      }
    }
    fire()
  }

  private fun fire() {
    if (isOffline()) {
      owed.set(Owed.NETWORK)
      // The network may have returned between the check and the flag, in which case
      // its callback already passed and nothing else would deliver this wake.
      if (!isOffline()) onNetworkAvailable()
      return
    }
    val reason = holdReason()
    if (reason != null) {
      hold(reason)
      return
    }
    synchronized(lock) {
      owed.set(Owed.NOTHING)
      qualityStreak = 0
    }
    dispatch()
  }

  private fun hold(reason: WakeHold) {
    synchronized(lock) {
      val delayMs = when (reason) {
        WakeHold.Quality -> GateRecheck.delayMs(qualityStreak++)
        is WakeHold.Health -> reason.remainingMs.coerceAtLeast(0L)
      }
      waiting?.cancel()
      waiting = fireAfter(delayMs)
      heldForQuality = reason == WakeHold.Quality
      owed.set(Owed.GATE)
    }
    // The gate may have opened between the check and the flag; its signal then came first.
    if (holdReason() == null) onDispatchPossible()
  }

  private fun fireAfter(delayMs: Long): Job = scope.launch {
    delay(delayMs)
    fire()
  }

  /**
   * Launched apart from [waiting], so that a new [arm] only ever replaces a timer
   * that is still counting down and never aborts a cycle that already started.
   */
  private fun dispatch() {
    scope.launch {
      try {
        flush()
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        Log.e(TAG, "Timer-driven flush failed", t)
      }
    }
  }

  private enum class Owed { NOTHING, NETWORK, GATE }

  private companion object {
    const val TAG = "RetryTimer"
  }
}
