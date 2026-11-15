package com.margelo.nitro.syncprovider.retry

import com.margelo.nitro.syncprovider.BackoffStrategy
import com.margelo.nitro.syncprovider.RetryPolicy
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Pure-Kotlin port of the JS-side retry algorithm.
 *
 * The native side and the JS-only fallback MUST agree on the back-off curve
 * for any given `(attempt, policy)` pair, modulo the random jitter component.
 *
 * @see com.margelo.nitro.syncprovider.RetryPolicy
 */
internal object RetryPolicyEvaluator {

  /**
   * The longest delay [computeDelayMs] returns: 2^53 - 1 ms, about 285,000 years, the largest
   * integer a JS number holds exactly. A ceiling shared with iOS, far below the delays that
   * overflow a `Long` deadline; it shortens only an `Infinity` or huge `baseDelayMs`, or an
   * uncapped curve at a huge attempt. The deadline itself stops at [MAX_DEADLINE_MS].
   */
  const val MAX_DELAY_MS: Long = 9_007_199_254_740_991L

  /**
   * The latest instant a retry deadline may name: 8,640,000,000,000,000 ms after the epoch, the
   * largest valid JavaScript Date. Being below 2^53, it is exact as a JS number, so `nextRetryAt`
   * always makes a valid `Date` in JS. The same rule holds on iOS.
   */
  const val MAX_DEADLINE_MS: Long = 8_640_000_000_000_000L

  /**
   * Compute the back-off delay, in milliseconds, before [attempt] should
   * be issued. `attempt` is 1-indexed: the first retry uses `attempt = 1`.
   *
   * @param attempt 1-indexed retry attempt number.
   * @param policy active retry policy.
   * @param random PRNG used for jitter; injectable for tests.
   * @return from 0 to [MAX_DELAY_MS]. A NaN `baseDelayMs` gives 0: no delay.
   */
  fun computeDelayMs(
    attempt: Int,
    policy: RetryPolicy,
    random: Random = Random.Default,
  ): Long {
    if (attempt < 1) return 0L
    val base = policy.baseDelayMs.coerceAtLeast(0.0)
    val cap = policy.maxDelayMs.coerceAtLeast(base)

    val raw = when (policy.backoff) {
      BackoffStrategy.LINEAR -> base * attempt
      BackoffStrategy.EXPONENTIAL -> base * 2.0.pow((attempt - 1).toDouble())
      BackoffStrategy.FIBONACCI -> base * fibonacci(attempt)
    }

    val jittered = if (policy.jitter && raw > 0) {
      // Equal jitter [0.75, 1.25): spread the back-off ±25% around `raw`,
      // matching JS (src/utils/retryBackoff.ts) and iOS
      // (ios/Retry/RetryPolicyEvaluator.swift). Cap is applied AFTER jitter so
      // the clamped value never exceeds maxDelayMs, exactly like both peers.
      raw * (0.75 + random.nextDouble() * 0.5)
    } else {
      raw
    }
    // A NaN passes coerceIn unchanged, and its toLong() is 0.
    return min(jittered, cap).coerceIn(0.0, MAX_DELAY_MS.toDouble()).toLong()
  }

  /**
   * [from] plus [delayMs], at most [MAX_DEADLINE_MS]. The sum saturates rather than overflow: a
   * wrapped sum would be a deadline long past, and the row it is for would be due at once.
   */
  fun deadline(from: Long, delayMs: Long): Long {
    val sum = if (delayMs > 0L && from > Long.MAX_VALUE - delayMs) Long.MAX_VALUE else from + delayMs
    return sum.coerceAtMost(MAX_DEADLINE_MS)
  }

  /**
   * Decide whether a request that produced [statusCode] should be retried
   * under [policy]. `null` means the request errored at the transport level
   * (no HTTP response); the caller decides retryability separately.
   */
  fun shouldRetryStatus(statusCode: Int?, policy: RetryPolicy): Boolean {
    if (statusCode == null) return false
    val list = policy.retryOnStatusCodes
    for (i in list.indices) {
      if (list[i].toInt() == statusCode) return true
    }
    return false
  }

  /**
   * Decide whether [attempt] (already incremented past the failed attempt)
   * still has budget under [policy].
   */
  fun hasBudget(attempt: Int, policy: RetryPolicy): Boolean {
    return attempt < policy.maxAttempts.toInt().coerceAtLeast(1)
  }

  /**
   * Computed as a [Double] because a `Long` overflows at F(93), which would turn
   * the delay negative. Doubles saturate at infinity, and `maxDelayMs` then caps
   * the result; precision is irrelevant at magnitudes that far above any cap.
   */
  private fun fibonacci(n: Int): Double {
    if (n <= 1) return n.toDouble()
    var a = 1.0
    var b = 1.0
    for (i in 3..n) {
      if (b.isInfinite()) break
      val sum = a + b
      a = b
      b = sum
    }
    return b
  }
}
