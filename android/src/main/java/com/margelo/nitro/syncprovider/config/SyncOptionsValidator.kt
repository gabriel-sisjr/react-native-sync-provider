package com.margelo.nitro.syncprovider.config

import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncOptions
import com.margelo.nitro.syncprovider.error.SyncException
import com.margelo.nitro.syncprovider.util.UrlValidator

/**
 * The native checks `configureSync` runs before anything is applied or persisted,
 * mirroring the JS facade's, in the same order. `minNetworkQuality` needs none: it
 * arrives as a `NetworkQuality`, so an unknown value never reaches native.
 */
internal object SyncOptionsValidator {

  /**
   * @throws SyncException [SyncErrorCode.INVALID_URL] when `healthCheckUrl` is not an
   *   absolute http(s) URL, the check item URLs pass. The message leaves the URL out:
   *   its query can carry a token.
   * @throws SyncException [SyncErrorCode.INVALID_PAYLOAD] when `healthCheckTtlMs` or
   *   `healthCheckBackoffMs` is not a finite number of at least 0.
   */
  fun validate(options: SyncOptions) {
    val healthCheckUrl = options.healthCheckUrl
    if (healthCheckUrl != null && !UrlValidator.isValid(healthCheckUrl)) {
      throw SyncException(
        SyncErrorCode.INVALID_URL,
        "SyncOptions.healthCheckUrl must be an absolute http(s) URL.",
      )
    }
    requireNonNegativeFinite("healthCheckTtlMs", options.healthCheckTtlMs)
    requireNonNegativeFinite("healthCheckBackoffMs", options.healthCheckBackoffMs)
  }

  private fun requireNonNegativeFinite(field: String, value: Double?) {
    if (value == null || (value.isFinite() && value >= 0.0)) return
    throw SyncException(
      SyncErrorCode.INVALID_PAYLOAD,
      "SyncOptions.$field must be a finite number of at least 0, was $value.",
    )
  }
}
