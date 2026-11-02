package com.margelo.nitro.syncprovider.retry

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-shot, in-process retry timer. A new [arm] replaces the previous one.
 *
 * The dispatcher never checks connectivity, so a timer that flushed offline would
 * spend every attempt and mark valid items `FAILED` while the device simply has no
 * network. When the timer fires offline it therefore does not flush: it remembers
 * the wake, and [onNetworkAvailable] delivers it.
 *
 * @param isOffline live connectivity check, evaluated when the timer fires.
 * @param flush the normal, mutex-guarded flush. Failures are logged, never thrown:
 *   no JS caller exists to receive them.
 */
internal class RetryTimer(
  private val scope: CoroutineScope,
  private val isOffline: () -> Boolean,
  private val flush: suspend () -> Unit,
) {
  private val lock = Any()
  private var waiting: Job? = null
  private val wakeOwed = AtomicBoolean(false)

  /** Whether a timer is counting down. */
  val isWaiting: Boolean
    get() = synchronized(lock) { waiting?.isActive == true }

  /** Fire once after [delayMs], replacing any timer still waiting. */
  fun arm(delayMs: Long) {
    synchronized(lock) {
      waiting?.cancel()
      wakeOwed.set(false)
      waiting = scope.launch {
        delay(delayMs)
        onFire()
      }
    }
  }

  /** Drop the waiting timer and any wake owed to the network. A running cycle finishes. */
  fun cancel() {
    synchronized(lock) {
      waiting?.cancel()
      waiting = null
      wakeOwed.set(false)
    }
  }

  /** Deliver the wake owed by a timer that fired offline, if there is one. */
  fun onNetworkAvailable() {
    if (wakeOwed.compareAndSet(true, false)) dispatch()
  }

  private fun onFire() {
    if (!isOffline()) {
      dispatch()
      return
    }
    wakeOwed.set(true)
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

  private companion object {
    const val TAG = "RetryTimer"
  }
}
