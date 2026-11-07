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
}
