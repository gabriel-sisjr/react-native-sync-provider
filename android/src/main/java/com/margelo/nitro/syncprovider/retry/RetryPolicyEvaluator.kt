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
   * Compute the back-off delay, in milliseconds, before [attempt] should
   * be issued. `attempt` is 1-indexed: the first retry uses `attempt = 1`.
   *
   * @param attempt 1-indexed retry attempt number.
   * @param policy active retry policy.
   * @param random PRNG used for jitter; injectable for tests.
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
      BackoffStrategy.FIBONACCI -> base * fibonacci(attempt).toDouble()
    }
    val capped = min(raw, cap)

    val withJitter = if (policy.jitter && capped > 0) {
      // Full jitter [0, capped] (matches the JS-side default behavior).
      capped * random.nextDouble()
    } else {
      capped
    }
    return withJitter.toLong()
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

  private fun fibonacci(n: Int): Long {
    if (n <= 1) return n.toLong()
    var a = 1L
    var b = 1L
    for (i in 3..n) {
      val sum = a + b
      a = b
      b = sum
    }
    return b
  }
}
