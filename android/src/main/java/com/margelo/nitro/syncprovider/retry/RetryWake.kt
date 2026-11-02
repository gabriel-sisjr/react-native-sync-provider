package com.margelo.nitro.syncprovider.retry

import com.margelo.nitro.syncprovider.storage.SyncQueueStorage

/**
 * Decides when the queue is woken next, and arms both wake mechanisms from one
 * place: the in-process [RetryTimer] and the opt-in WorkManager kick.
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
   * Arm for the soonest retry deadline. A deadline already in the past yields delay
   * 0, so a row whose cooldown elapsed during a cycle is never stranded.
   *
   * @param notBefore keeps the wake from firing earlier than this instant, even if
   *   some row is due sooner.
   */
  suspend fun arm(notBefore: Long? = null) {
    if (isHalted()) {
      timer.cancel()
      return
    }
    val deadline = storage.nextRetryDeadline()
    if (deadline == null) {
      timer.cancel()
      return
    }
    val delayMs = delayUntil(maxOf(deadline, notBefore ?: 0L))
    if (isEngineLive()) timer.arm(delayMs)
    scheduleKick(delayMs)
  }

  /**
   * Arm after `resumeSync`. The timer considers every `PENDING` row, so
   * never-attempted rows dispatch at once.
   */
  suspend fun armForResume() {
    if (isHalted()) {
      timer.cancel()
      return
    }
    val pendingDeadline = storage.nextPendingDeadline()
    if (pendingDeadline == null) {
      timer.cancel()
      return
    }
    timer.arm(delayUntil(pendingDeadline))
    storage.nextRetryDeadline()?.let { scheduleKick(delayUntil(it)) }
  }

  private fun delayUntil(deadline: Long): Long = (deadline - clock()).coerceAtLeast(0L)
}
