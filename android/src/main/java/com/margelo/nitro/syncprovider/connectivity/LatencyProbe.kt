package com.margelo.nitro.syncprovider.connectivity

import com.margelo.nitro.syncprovider.http.HeadProbe
import com.margelo.nitro.syncprovider.http.HeadResult

/**
 * One latency measurement for the shared threshold table: [SAMPLES] `HEAD` requests to the
 * same URL, one after the other on one [HeadProbe], so the later ones reuse the first
 * one's connection.
 *
 * Cancelling the caller cancels the request in flight and throws, so a partial set is never
 * returned.
 */
internal class LatencyProbe(private val probe: HeadProbe) {

  /** One result per request, in order. */
  suspend fun measure(url: String): List<HeadResult> =
    List(SAMPLES) { probe.head(url, SAMPLE_TIMEOUT_MS) }

  companion object {
    const val SAMPLES: Int = 3
    const val SAMPLE_TIMEOUT_MS: Long = 2_000L

    /** Latencies for the classifier: a request without an answer counts as `null`. */
    fun latencies(results: List<HeadResult>): List<Long?> =
      results.map { (it as? HeadResult.Response)?.latencyMs }
  }
}
