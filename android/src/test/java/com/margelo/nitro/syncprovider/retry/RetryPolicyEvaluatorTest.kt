package com.margelo.nitro.syncprovider.retry

import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.BackoffStrategy
import com.margelo.nitro.syncprovider.testutil.TestData
import org.junit.Test
import kotlin.random.Random

internal class RetryPolicyEvaluatorTest {

  @Test
  fun `linear backoff scales with attempt`() {
    val policy = TestData.retryPolicy(
      backoff = BackoffStrategy.LINEAR,
      baseDelayMs = 100L,
      maxDelayMs = 100_000L,
      jitter = false,
    )

    assertThat(RetryPolicyEvaluator.computeDelayMs(1, policy)).isEqualTo(100L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(2, policy)).isEqualTo(200L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(5, policy)).isEqualTo(500L)
  }

  @Test
  fun `exponential backoff doubles each attempt`() {
    val policy = TestData.retryPolicy(
      backoff = BackoffStrategy.EXPONENTIAL,
      baseDelayMs = 50L,
      maxDelayMs = 100_000L,
      jitter = false,
    )

    assertThat(RetryPolicyEvaluator.computeDelayMs(1, policy)).isEqualTo(50L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(2, policy)).isEqualTo(100L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(3, policy)).isEqualTo(200L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(4, policy)).isEqualTo(400L)
  }

  @Test
  fun `fibonacci backoff follows the sequence`() {
    val policy = TestData.retryPolicy(
      backoff = BackoffStrategy.FIBONACCI,
      baseDelayMs = 10L,
      maxDelayMs = 1_000_000L,
      jitter = false,
    )

    // fib(1)=1, fib(2)=1, fib(3)=2, fib(4)=3, fib(5)=5, fib(6)=8.
    assertThat(RetryPolicyEvaluator.computeDelayMs(1, policy)).isEqualTo(10L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(2, policy)).isEqualTo(10L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(3, policy)).isEqualTo(20L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(4, policy)).isEqualTo(30L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(5, policy)).isEqualTo(50L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(6, policy)).isEqualTo(80L)
  }

  @Test
  fun `delay is capped at maxDelayMs`() {
    val policy = TestData.retryPolicy(
      backoff = BackoffStrategy.EXPONENTIAL,
      baseDelayMs = 1_000L,
      maxDelayMs = 5_000L,
      jitter = false,
    )

    // 1000 * 2^9 = 512000, but cap is 5000.
    assertThat(RetryPolicyEvaluator.computeDelayMs(10, policy)).isEqualTo(5_000L)
  }

  @Test
  fun `non-positive attempt yields zero delay`() {
    val policy = TestData.retryPolicy()

    assertThat(RetryPolicyEvaluator.computeDelayMs(0, policy)).isEqualTo(0L)
    assertThat(RetryPolicyEvaluator.computeDelayMs(-3, policy)).isEqualTo(0L)
  }

  @Test
  fun `full-jitter delay stays within zero and capped`() {
    val policy = TestData.retryPolicy(
      backoff = BackoffStrategy.EXPONENTIAL,
      baseDelayMs = 100L,
      maxDelayMs = 10_000L,
      jitter = true,
    )

    val random = Random(42)
    repeat(50) { iteration ->
      val attempt = (iteration % 5) + 1
      val capped = minOf(100.0 * Math.pow(2.0, (attempt - 1).toDouble()), 10_000.0).toLong()
      val delay = RetryPolicyEvaluator.computeDelayMs(attempt, policy, random)

      assertThat(delay).isAtLeast(0L)
      assertThat(delay).isAtMost(capped)
    }
  }

  @Test
  fun `jitter disabled returns the deterministic capped value`() {
    val policy = TestData.retryPolicy(
      backoff = BackoffStrategy.EXPONENTIAL,
      baseDelayMs = 100L,
      maxDelayMs = 200L,
      jitter = false,
    )

    val first = RetryPolicyEvaluator.computeDelayMs(5, policy)
    val second = RetryPolicyEvaluator.computeDelayMs(5, policy)
    assertThat(first).isEqualTo(second)
    assertThat(first).isEqualTo(200L)
  }

  @Test
  fun `shouldRetryStatus returns true for codes in the policy list`() {
    val policy = TestData.retryPolicy(retryOnStatusCodes = intArrayOf(429, 500, 503))

    assertThat(RetryPolicyEvaluator.shouldRetryStatus(429, policy)).isTrue()
    assertThat(RetryPolicyEvaluator.shouldRetryStatus(500, policy)).isTrue()
    assertThat(RetryPolicyEvaluator.shouldRetryStatus(503, policy)).isTrue()
  }

  @Test
  fun `shouldRetryStatus returns false for codes outside the list`() {
    val policy = TestData.retryPolicy(retryOnStatusCodes = intArrayOf(429, 500))

    assertThat(RetryPolicyEvaluator.shouldRetryStatus(404, policy)).isFalse()
    assertThat(RetryPolicyEvaluator.shouldRetryStatus(200, policy)).isFalse()
  }

  @Test
  fun `shouldRetryStatus returns false when statusCode is null`() {
    val policy = TestData.retryPolicy()

    assertThat(RetryPolicyEvaluator.shouldRetryStatus(null, policy)).isFalse()
  }

  @Test
  fun `hasBudget returns true when attempt is less than maxAttempts`() {
    val policy = TestData.retryPolicy(maxAttempts = 5)

    assertThat(RetryPolicyEvaluator.hasBudget(0, policy)).isTrue()
    assertThat(RetryPolicyEvaluator.hasBudget(4, policy)).isTrue()
  }

  @Test
  fun `hasBudget returns false when attempt reaches or exceeds maxAttempts`() {
    val policy = TestData.retryPolicy(maxAttempts = 3)

    assertThat(RetryPolicyEvaluator.hasBudget(3, policy)).isFalse()
    assertThat(RetryPolicyEvaluator.hasBudget(10, policy)).isFalse()
  }

  @Test
  fun `hasBudget treats maxAttempts below 1 as 1`() {
    val policy = TestData.retryPolicy(maxAttempts = 0)

    assertThat(RetryPolicyEvaluator.hasBudget(0, policy)).isTrue()
    assertThat(RetryPolicyEvaluator.hasBudget(1, policy)).isFalse()
  }
}
