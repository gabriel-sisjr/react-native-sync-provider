package com.margelo.nitro.syncprovider.config

import com.margelo.nitro.syncprovider.SyncOptions

/**
 * `SyncOptions.callbackTimeoutMs`: how long native waits for JS to answer a callback (the retry
 * decider, the header resolver and the dispatch hooks) before it applies the fallback.
 */
internal object CallbackTimeout {

  /** The wait when the option is unset. */
  const val DEFAULT_MS: Long = 3_000L

  /** The longest wait, 2^31 - 1 ms (about 24.8 days), as on iOS. A longer value is capped at use. */
  const val MAX_MS: Long = Int.MAX_VALUE.toLong()

  /** What `configureSync` accepts, and what a stored value must be to load: finite, at least 1. */
  fun isValid(value: Double): Boolean = value.isFinite() && value >= 1.0

  /** The wait [options] give. See [ms]. */
  fun ms(options: SyncOptions): Long = ms(options.callbackTimeoutMs)

  /** [DEFAULT_MS] when unset, otherwise [value] with its fraction dropped, at most [MAX_MS]. */
  fun ms(value: Double?): Long =
    if (value == null || !isValid(value)) DEFAULT_MS else minOf(value, MAX_MS.toDouble()).toLong()
}
