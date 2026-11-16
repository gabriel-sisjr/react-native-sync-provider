package com.margelo.nitro.syncprovider.http

/**
 * How a flush cycle ended, for the wake armed after it. The wake after a cycle that failed is
 * held back; see [com.margelo.nitro.syncprovider.retry.RetryWake].
 */
internal enum class CycleEnding {
  /** The cycle ran to its end, whatever its items' outcomes. */
  COMPLETED,

  /** A cancellation ended it, a worker WorkManager stopped for example. It failed at nothing. */
  CANCELLED,

  /**
   * Any other cause ended it before it completed: a storage read or write, an event emit, an
   * [Error]. The next cycle may well meet the same failure.
   */
  FAILED,
}
