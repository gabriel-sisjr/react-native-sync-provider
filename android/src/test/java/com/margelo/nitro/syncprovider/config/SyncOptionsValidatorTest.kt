package com.margelo.nitro.syncprovider.config

import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.NetworkQuality
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncOptions
import com.margelo.nitro.syncprovider.error.SyncException
import com.margelo.nitro.syncprovider.testutil.TestData
import org.junit.Assert.assertThrows
import org.junit.Test

internal class SyncOptionsValidatorTest {

  private fun rejection(options: SyncOptions): SyncException =
    assertThrows(SyncException::class.java) { SyncOptionsValidator.validate(options) }

  @Test
  fun `options without the network-quality and health-check fields pass`() {
    SyncOptionsValidator.validate(TestData.syncOptions())
  }

  @Test
  fun `every network quality and every valid health-check value pass`() {
    NetworkQuality.entries.forEach { quality ->
      SyncOptionsValidator.validate(
        TestData.syncOptions(
          minNetworkQuality = quality,
          healthCheckUrl = "https://api.example.com/health?token=abc",
          healthCheckTtlMs = 0.0,
          healthCheckBackoffMs = 86_400_000.0,
        ),
      )
    }
    SyncOptionsValidator.validate(
      TestData.syncOptions(healthCheckUrl = "http://10.0.2.2:8080/health"),
    )
  }

  @Test
  fun `a health-check URL that is not absolute http(s) is INVALID_URL, and is never repeated`() {
    val invalid = listOf(
      "",
      "   ",
      "health",
      "/health",
      "ftp://example.com/health?token=secret",
      "ws://example.com/socket?token=secret",
    )

    invalid.forEach { url ->
      val rejected = rejection(TestData.syncOptions(healthCheckUrl = url))
      assertThat(rejected.code).isEqualTo(SyncErrorCode.INVALID_URL)
      assertThat(rejected.message).doesNotContain("secret")
    }
  }

  @Test
  fun `a TTL or backoff that is negative or not finite is INVALID_PAYLOAD`() {
    val invalid = listOf(-1.0, -0.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

    invalid.forEach { value ->
      val ttl = rejection(TestData.syncOptions(healthCheckTtlMs = value))
      assertThat(ttl.code).isEqualTo(SyncErrorCode.INVALID_PAYLOAD)
      assertThat(ttl.rawMessage).contains("healthCheckTtlMs")

      val backoff = rejection(TestData.syncOptions(healthCheckBackoffMs = value))
      assertThat(backoff.code).isEqualTo(SyncErrorCode.INVALID_PAYLOAD)
      assertThat(backoff.rawMessage).contains("healthCheckBackoffMs")
    }
  }

  @Test
  fun `the URL is checked first, as in the JS facade`() {
    val rejected = rejection(
      TestData.syncOptions(
        healthCheckUrl = "not a url",
        healthCheckTtlMs = -1.0,
        healthCheckBackoffMs = Double.NaN,
      ),
    )

    assertThat(rejected.code).isEqualTo(SyncErrorCode.INVALID_URL)
  }

  @Test
  fun `options_callbackTimeoutMs_validation, only a finite number of at least 1 passes`() {
    val invalid = listOf(
      Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0, 0.5, 0.999,
    )

    invalid.forEach { value ->
      val rejected = rejection(TestData.syncOptions(callbackTimeoutMs = value))
      assertThat(rejected.code).isEqualTo(SyncErrorCode.INVALID_PAYLOAD)
      assertThat(rejected.rawMessage).isEqualTo(
        "SyncOptions.callbackTimeoutMs must be a finite number of at least 1, was $value.",
      )
    }
    listOf(1.0, 3_000.7, 1e300).forEach { value ->
      SyncOptionsValidator.validate(TestData.syncOptions(callbackTimeoutMs = value))
    }
    // Checked last, as in the JS facade: an earlier field's failure is the one reported.
    val first = rejection(TestData.syncOptions(healthCheckTtlMs = -1.0, callbackTimeoutMs = 0.0))
    assertThat(first.rawMessage).contains("healthCheckTtlMs")
  }

  @Test
  fun `options_callbackTimeoutMs_validation at use, unset waits 3000 ms, a fraction is dropped, and the wait stops at 2147483647 ms`() {
    fun waitFor(value: Double?): Long = CallbackTimeout.ms(TestData.syncOptions(callbackTimeoutMs = value))

    assertThat(waitFor(null)).isEqualTo(3_000L)
    assertThat(waitFor(1.0)).isEqualTo(1L)
    assertThat(waitFor(1.9)).isEqualTo(1L)
    assertThat(waitFor(3_000.7)).isEqualTo(3_000L)
    assertThat(waitFor(2_147_483_647.9)).isEqualTo(2_147_483_647L)
    assertThat(waitFor(1e300)).isEqualTo(2_147_483_647L)
  }
}
