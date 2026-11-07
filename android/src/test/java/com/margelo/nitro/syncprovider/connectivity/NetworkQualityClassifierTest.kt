package com.margelo.nitro.syncprovider.connectivity

import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.NetworkQuality
import com.margelo.nitro.syncprovider.NetworkQuality.EXCELLENT
import com.margelo.nitro.syncprovider.NetworkQuality.GOOD
import com.margelo.nitro.syncprovider.NetworkQuality.MODERATE
import com.margelo.nitro.syncprovider.NetworkQuality.OFFLINE
import com.margelo.nitro.syncprovider.NetworkQuality.POOR
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The shared threshold table's `quality_*` scenarios, one case each. */
@RunWith(Parameterized::class)
internal class NetworkQualityClassifierTest(
  @Suppress("unused") private val scenario: String,
  private val link: LinkSignals,
  private val samplesMs: List<Long?>?,
  private val expected: NetworkQuality,
) {

  @Test
  fun classify() {
    assertThat(NetworkQualityClassifier.classify(link, samplesMs)).isEqualTo(expected)
  }

  companion object {
    private val UNMETERED = LinkSignals(known = true, connected = true, metered = false, validated = true)
    private val METERED = UNMETERED.copy(metered = true)
    private val NOT_VALIDATED = UNMETERED.copy(validated = false)
    private val NO_LINK = UNMETERED.copy(connected = false)
    private val UNKNOWN = LinkSignals(known = false, connected = true, metered = false, validated = true)

    private fun same(ms: Long): List<Long?> = listOf(ms, ms, ms)

    private fun case(scenario: String, link: LinkSignals, samplesMs: List<Long?>?, expected: NetworkQuality) =
      arrayOf(scenario, link, samplesMs, expected)

    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun scenarios(): List<Array<Any?>> = listOf(
      case("quality_noLink_isOffline", NO_LINK, null, OFFLINE),
      case("quality_noLink_isOffline, samples ignored", NO_LINK, same(100), OFFLINE),
      case("quality_unknownLink_isOffline", UNKNOWN, null, OFFLINE),
      case("quality_unknownLink_isOffline, samples ignored", UNKNOWN, same(100), OFFLINE),
      case("quality_noMeasurement_unmetered_isGood", UNMETERED, null, GOOD),
      case("quality_noMeasurement_unmetered_isGood, empty samples", UNMETERED, emptyList(), GOOD),
      case("quality_noMeasurement_metered_isModerate", METERED, null, MODERATE),
      case("quality_median_boundaries 150 ms", UNMETERED, same(150), EXCELLENT),
      case("quality_median_boundaries 199 ms", UNMETERED, same(199), EXCELLENT),
      case("quality_median_boundaries 200 ms", UNMETERED, same(200), GOOD),
      case("quality_median_boundaries 499 ms", UNMETERED, same(499), GOOD),
      case("quality_median_boundaries 500 ms", UNMETERED, same(500), MODERATE),
      case("quality_median_boundaries 999 ms", UNMETERED, same(999), MODERATE),
      case("quality_median_boundaries 1000 ms", UNMETERED, same(1_000), POOR),
      case("quality_median_boundaries, the middle sample decides", UNMETERED, listOf(999L, 100L, 150L), EXCELLENT),
      case("quality_oneFailure_usesMedianOfRest", UNMETERED, listOf(100L, 120L, null), EXCELLENT),
      case("quality_twoFailures_isPoor", UNMETERED, listOf(100L, null, null), POOR),
      case("quality_twoFailures_isPoor, every sample failed", UNMETERED, listOf(null, null, null), POOR),
      case("quality_caps metered + 150 ms", METERED, same(150), GOOD),
      case("quality_caps not validated + 150 ms", NOT_VALIDATED, same(150), POOR),
      case("quality_caps metered and not validated, the lowest cap wins", METERED.copy(validated = false), same(150), POOR),
      case("quality_caps not validated, no measurement", NOT_VALIDATED, null, POOR),
      case("quality_caps metered keeps a slower measurement", METERED, same(700), MODERATE),
    )
  }
}
