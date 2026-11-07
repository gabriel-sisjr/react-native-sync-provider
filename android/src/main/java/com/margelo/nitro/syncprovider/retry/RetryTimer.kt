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
 * spend every attempt and mark valid items `FAILED` while the device simply has no
 * network. When the timer fires offline it therefore does not flush: it remembers
 * the wake, and [onNetworkAvailable] delivers it.
 *
 * A wake the network-quality gate turned back is remembered the same way, through
 * [hold]: [onDispatchPossible] delivers it once the classification reaches the minimum,
 * and the hold's own deadline brings it back otherwise, so the gate is evaluated again.
 *
 * @param isOffline live connectivity check, evaluated when the timer fires.
 * @param flush the normal, mutex-guarded flush. Failures are logged, never thrown:
 *   no JS caller exists to receive them.
 * @param canDispatch whether a held wake could pass the gate now: online and at the
 *   minimum quality.
 */
internal class RetryTimer(
  private val scope: CoroutineScope,
  private val isOffline: () -> Boolean,
  private val flush: suspend () -> Unit,
  private val canDispatch: () -> Boolean = { !isOffline() },
) {
  private val lock = Any()
  private var waiting: Job? = null
  private val owed = AtomicReference(Owed.NOTHING)

  /** Whether a timer is counting down. */
  val isWaiting: Boolean
    get() = synchronized(lock) { waiting?.isActive == true }

  /** Fire once after [delayMs], replacing any timer still waiting. */
  fun arm(delayMs: Long) {
    synchronized(lock) {
      waiting?.cancel()
      owed.set(Owed.NOTHING)
      waiting = scope.launch {
        delay(delayMs)
        onFire()
      }
    }
  }

  /**
   * Remember a wake the network-quality gate turned back. [onDispatchPossible] delivers it
   * as soon as the gate would let it through. Otherwise the timer fires after [delayMs],
   * replacing any timer still waiting, and the gate decides again.
   */
  fun hold(delayMs: Long) {
    synchronized(lock) {
      waiting?.cancel()
      waiting = scope.launch {
        delay(delayMs)
        onFire()
      }
      owed.set(Owed.QUALITY)
    }
    // The gate may have opened while the flush that it held ran; its signal then came first.
    if (canDispatch()) onDispatchPossible()
  }

  /** Drop the waiting timer and any wake owed. A running cycle finishes. */
  fun cancel() {
    synchronized(lock) {
      waiting?.cancel()
      waiting = null
      owed.set(Owed.NOTHING)
    }
  }

  /**
   * The device is online again: deliver the wake owed by a timer that fired offline, if
   * there is one. A wake the gate holds waits for [onDispatchPossible].
   */
  fun onNetworkAvailable() {
    deliverIf(Owed.NETWORK)
  }

  /** Online and at the minimum quality: deliver any wake owed, the gate's included. */
  fun onDispatchPossible() {
    deliverIf(Owed.NETWORK, Owed.QUALITY)
  }

  private fun deliverIf(vararg kinds: Owed) {
    synchronized(lock) {
      val current = owed.get()
      if (current !in kinds || !owed.compareAndSet(current, Owed.NOTHING)) return
      // A held wake's own deadline would only repeat this delivery.
      if (current == Owed.QUALITY) {
        waiting?.cancel()
        waiting = null
      }
    }
    dispatch()
  }

  private fun onFire() {
    if (!isOffline()) {
      owed.set(Owed.NOTHING)
      dispatch()
      return
    }
    owed.set(Owed.NETWORK)
    // The network may have returned between the check and the flag, in which case
    // its callback already passed and nothing else would deliver this wake.
    if (!isOffline()) onNetworkAvailable()
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

  private enum class Owed { NOTHING, NETWORK, QUALITY }

  private companion object {
    const val TAG = "RetryTimer"
  }
}
