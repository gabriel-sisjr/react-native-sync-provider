package com.margelo.nitro.syncprovider.connectivity

import com.margelo.nitro.syncprovider.NetworkQuality

/**
 * What the classifier knows about the link.
 *
 * @property known the system reported the link. Android has no window where a link is up but
 *   not known: missing capabilities already read as no link.
 * @property connected the link reaches the internet (`NET_CAPABILITY_INTERNET`).
 * @property metered the link is billed by use, permanently or for now.
 * @property validated the system verified internet access on it (`NET_CAPABILITY_VALIDATED`).
 */
internal data class LinkSignals(
  val known: Boolean,
  val connected: Boolean,
  val metered: Boolean,
  val validated: Boolean,
)

/**
 * Classifies a link with the shared threshold table, the single source both platforms
 * implement:
 *
 * - no link, or a link not known yet → `OFFLINE`;
 * - measured: the median of the samples, a failure or a timeout counting as infinitely slow.
 *   Under 200 ms → `EXCELLENT`, 200–499 ms → `GOOD`, 500–999 ms → `MODERATE`, 1000 ms or
 *   more, two or more failures included → `POOR`;
 * - not measured (no `healthCheckUrl`, or the first measurement still pending): unmetered →
 *   `GOOD`, metered → `MODERATE`;
 * - caps, applied last, the lowest one wins: not validated → at most `POOR`; metered → at
 *   most `GOOD`. The iOS cap for Low Data Mode has no Android counterpart.
 *
 * A sample is one `HEAD` request's latency in ms, whatever its HTTP status; `null` is a
 * request that failed or timed out.
 */
internal object NetworkQualityClassifier {
  /** Medians under these bounds classify as `EXCELLENT`, `GOOD` and `MODERATE`. */
  const val EXCELLENT_BELOW_MS: Long = 200L
  const val GOOD_BELOW_MS: Long = 500L
  const val MODERATE_BELOW_MS: Long = 1_000L

  /** @param samplesMs `null` or empty when there is no measurement. */
  fun classify(link: LinkSignals, samplesMs: List<Long?>?): NetworkQuality {
    if (!link.known || !link.connected) return NetworkQuality.OFFLINE
    val measured = samplesMs?.takeIf { it.isNotEmpty() }?.let { fromLatency(median(it)) }
    var quality = measured ?: if (link.metered) NetworkQuality.MODERATE else NetworkQuality.GOOD
    if (link.metered) quality = atMost(quality, NetworkQuality.GOOD)
    if (!link.validated) quality = atMost(quality, NetworkQuality.POOR)
    return quality
  }

  /** The middle sample, failures sorted last. Upper median for an even count. */
  private fun median(samplesMs: List<Long?>): Long =
    samplesMs.map { it ?: Long.MAX_VALUE }.sorted()[samplesMs.size / 2]

  private fun fromLatency(ms: Long): NetworkQuality = when {
    ms < EXCELLENT_BELOW_MS -> NetworkQuality.EXCELLENT
    ms < GOOD_BELOW_MS -> NetworkQuality.GOOD
    ms < MODERATE_BELOW_MS -> NetworkQuality.MODERATE
    else -> NetworkQuality.POOR
  }

  private fun atMost(quality: NetworkQuality, cap: NetworkQuality): NetworkQuality =
    if (quality.value > cap.value) cap else quality
}

/**
 * Whether this classification passes a `minNetworkQuality` gate. Unset and `OFFLINE`
 * minimums gate nothing; otherwise the levels compare worst to best.
 */
internal fun NetworkQuality.meetsMinimum(minimum: NetworkQuality?): Boolean =
  minimum == null || minimum == NetworkQuality.OFFLINE || value >= minimum.value
