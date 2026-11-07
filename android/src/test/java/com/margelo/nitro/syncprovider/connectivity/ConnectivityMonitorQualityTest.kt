package com.margelo.nitro.syncprovider.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.ConnectionStatus
import com.margelo.nitro.syncprovider.NetworkQuality
import com.margelo.nitro.syncprovider.SyncEvent
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.http.HeadResult
import com.margelo.nitro.syncprovider.retry.GateRecheck
import com.margelo.nitro.syncprovider.retry.RetryTimer
import com.margelo.nitro.syncprovider.retry.WakeHold
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.Collections

/**
 * The latency measurement behind `ConnectionState.networkQuality`: when it is taken, how
 * long it is reused, what it classifies, and what is reported when it changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class ConnectivityMonitorQualityTest {

  private lateinit var context: Context
  private val emitter = SyncEventEmitter()

  private var url: String? = URL_A

  /** The URL of every measurement taken, in order. */
  private val probed = Collections.synchronizedList(mutableListOf<String>())

  /** What the next measurement answers. */
  private var answers: List<HeadResult> = latencies(50, 60, 70)

  /** While set, a measurement waits for it, so a test can act while one is in flight. */
  private var inFlight: CompletableDeferred<Unit>? = null

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  private fun TestScope.monitor(
    started: Boolean = true,
    onDefaultNetworkChanged: () -> Unit = {},
  ): ConnectivityMonitor = ConnectivityMonitor(
    context = context,
    emitter = emitter,
    scope = backgroundScope,
    healthCheckUrl = { url },
    measure = { target ->
      probed += target
      inFlight?.await()
      answers
    },
    clock = { testScheduler.currentTime },
    probeDebounceMs = DEBOUNCE_MS,
    onDefaultNetworkChanged = onDefaultNetworkChanged,
  ).also { if (started) it.start() }

  private fun manager(): ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)

  private fun callback(): ConnectivityManager.NetworkCallback =
    shadowOf(manager()).networkCallbacks.single()

  private fun capabilities(transport: Int, vararg capabilities: Int): NetworkCapabilities =
    ShadowNetworkCapabilities.newInstance().also { caps ->
      shadowOf(caps).addTransportType(transport)
      capabilities.forEach { shadowOf(caps).addCapability(it) }
    }

  private fun network(netId: Int, transport: Int, vararg capabilities: Int): Network =
    ShadowNetwork.newInstance(netId).also {
      shadowOf(manager()).setNetworkCapabilities(it, capabilities(transport, *capabilities))
    }

  private fun wifi(validated: Boolean = true): Network = if (validated) {
    network(WIFI_ID, NetworkCapabilities.TRANSPORT_WIFI, INTERNET, VALIDATED, NOT_METERED)
  } else {
    network(WIFI_ID, NetworkCapabilities.TRANSPORT_WIFI, INTERNET, NOT_METERED)
  }

  private fun cellular(): Network =
    network(CELLULAR_ID, NetworkCapabilities.TRANSPORT_CELLULAR, INTERNET, VALIDATED)

  /** Past the debounce, and through the measurement it lets start. */
  private fun TestScope.settle() {
    advanceTimeBy(DEBOUNCE_MS)
    runCurrent()
  }

  private fun TestScope.recordConnectionEvents(): List<SyncEvent> {
    val events = Collections.synchronizedList(mutableListOf<SyncEvent>())
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      emitter.events.collect { if (it.type == SyncEventType.CONNECTION_CHANGED) events += it }
    }
    return events
  }

  /* -------------------------------- reporting ------------------------------- */

  @Test
  fun `connection_qualityOnlyChange_emitsConnectionChanged`() = runTest {
    val monitor = monitor()
    val wifi = wifi()
    callback().onAvailable(wifi)
    runCurrent()
    // Not measured yet: the link signals decide.
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.GOOD)
    val events = recordConnectionEvents()

    settle()

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.CONNECTED)
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.EXCELLENT)
    assertThat(events.map { it.connectionStatus }).containsExactly(ConnectionStatus.CONNECTED)

    // Measured again with the same outcome: nothing changed, so nothing is emitted.
    advanceTimeBy(ConnectivityMonitor.QUALITY_CACHE_TTL_MS)
    callback().onCapabilitiesChanged(wifi, checkNotNull(manager().getNetworkCapabilities(wifi)))
    settle()
    assertThat(probed).hasSize(2)
    assertThat(events).hasSize(1)
  }

  @Test
  fun `connection_offlineInvariant holds with a measurement in place`() = runTest {
    val monitor = monitor()
    val wifi = wifi()
    callback().onAvailable(wifi)
    settle()
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.EXCELLENT)

    callback().onLost(wifi)
    runCurrent()

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.DISCONNECTED)
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.OFFLINE)

    // Back within the reuse window: that network's result applies again, nothing is sent.
    callback().onAvailable(wifi)
    settle()
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.EXCELLENT)
    assertThat(probed).hasSize(1)
  }

  @Test
  fun `the initial state is reported without an event`() = runTest {
    url = null
    shadowOf(manager()).setNetworkCapabilities(
      manager().activeNetwork,
      capabilities(NetworkCapabilities.TRANSPORT_WIFI, INTERNET, VALIDATED, NOT_METERED),
    )
    val events = recordConnectionEvents()

    val monitor = monitor()
    runCurrent()

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.CONNECTED)
    assertThat(events).isEmpty()
  }

  @Test
  fun `a callback that repeats the reported state emits nothing`() = runTest {
    url = null
    monitor()
    val wifi = wifi()
    callback().onAvailable(wifi)
    runCurrent()
    val events = recordConnectionEvents()

    repeat(3) {
      callback().onCapabilitiesChanged(wifi, checkNotNull(manager().getNetworkCapabilities(wifi)))
    }
    callback().onAvailable(wifi)
    runCurrent()

    assertThat(events).isEmpty()
  }

  @Test
  fun `a change of default network, and only that, is reported to the owner`() = runTest {
    var changes = 0
    monitor(onDefaultNetworkChanged = { changes++ })
    val wifi = wifi()

    callback().onAvailable(wifi)
    callback().onCapabilitiesChanged(wifi, checkNotNull(manager().getNetworkCapabilities(wifi)))
    callback().onAvailable(wifi)
    val cellular = cellular()
    callback().onAvailable(cellular)
    callback().onLost(cellular)

    assertThat(changes).isEqualTo(3)
  }

  /* ------------------------------ when to probe ----------------------------- */

  @Test
  fun `nothing is measured without healthCheckUrl`() = runTest {
    url = null
    val monitor = monitor()
    callback().onAvailable(wifi())
    settle()

    assertThat(monitor.qualityForGate()).isEqualTo(NetworkQuality.GOOD)
    settle()

    assertThat(probed).isEmpty()
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.GOOD)
  }

  @Test
  fun `nothing is measured while offline, and going offline stops a pending measurement`() = runTest {
    val monitor = monitor()
    val noInternet = network(WIFI_ID, NetworkCapabilities.TRANSPORT_WIFI, NOT_METERED)
    callback().onAvailable(noInternet)
    settle()
    assertThat(probed).isEmpty()

    val wifi = wifi()
    callback().onAvailable(wifi)
    callback().onLost(wifi)
    settle()

    assertThat(probed).isEmpty()
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.OFFLINE)
  }

  @Test
  fun `the callbacks of one network change cost one measurement`() = runTest {
    monitor()
    val wifi = wifi()
    callback().onAvailable(wifi)
    repeat(3) {
      callback().onCapabilitiesChanged(wifi, checkNotNull(manager().getNetworkCapabilities(wifi)))
    }

    settle()

    assertThat(probed).containsExactly(URL_A)
  }

  @Test
  fun `a result is reused for 30 s, then measured again`() = runTest {
    monitor()
    val wifi = wifi()
    callback().onAvailable(wifi)
    settle()
    val caps = checkNotNull(manager().getNetworkCapabilities(wifi))

    // 1 ms short of the window: reused.
    advanceTimeBy(ConnectivityMonitor.QUALITY_CACHE_TTL_MS - 1)
    callback().onCapabilitiesChanged(wifi, caps)
    runCurrent()
    assertThat(probed).hasSize(1)

    // The window is over: measured again.
    advanceTimeBy(1)
    callback().onCapabilitiesChanged(wifi, caps)
    settle()
    assertThat(probed).hasSize(2)
  }

  @Test
  fun `a new default network is measured at once, and an older result never classifies it`() = runTest {
    val monitor = monitor()
    callback().onAvailable(wifi())
    settle()
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.EXCELLENT)

    callback().onAvailable(cellular())
    runCurrent()
    // The Wi-Fi result does not apply: a metered link with no measurement is MODERATE.
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.MODERATE)

    answers = latencies(300, 320, 340)
    settle()

    assertThat(probed).hasSize(2)
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.GOOD)
  }

  @Test
  fun `gaining validation measures again`() = runTest {
    val monitor = monitor()
    callback().onAvailable(wifi(validated = false))
    settle()
    // Fast, but capped: an Android link the system has not validated is at most POOR.
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.POOR)

    val validated = wifi(validated = true)
    callback().onCapabilitiesChanged(validated, checkNotNull(manager().getNetworkCapabilities(validated)))
    settle()

    assertThat(probed).hasSize(2)
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.EXCELLENT)
  }

  @Test
  fun `a new healthCheckUrl is measured again, and the old result stops classifying`() = runTest {
    val monitor = monitor()
    callback().onAvailable(wifi())
    settle()
    val events = recordConnectionEvents()

    url = URL_B
    monitor.onConfigurationChanged()
    runCurrent()
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.GOOD)

    settle()

    assertThat(probed).containsExactly(URL_A, URL_B).inOrder()
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.EXCELLENT)
    assertThat(events).hasSize(2)
  }

  @Test
  fun `a configureSync that keeps the URL sends nothing`() = runTest {
    val monitor = monitor()
    callback().onAvailable(wifi())
    settle()

    monitor.onConfigurationChanged()
    settle()

    assertThat(probed).hasSize(1)
  }

  /* ------------------------------ cancellation ------------------------------ */

  @Test
  fun `a measurement the network outdated is discarded`() = runTest {
    val monitor = monitor()
    inFlight = CompletableDeferred()
    val wifi = wifi()
    callback().onAvailable(wifi)
    settle()
    assertThat(probed).hasSize(1)

    // The Wi-Fi measurement is still in flight when the cellular network takes over.
    callback().onAvailable(cellular())
    answers = latencies(300, 320, 340)
    settle()
    checkNotNull(inFlight).complete(Unit)
    runCurrent()
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.GOOD)

    // Back on Wi-Fi within 30 s: its cancelled measurement left nothing to reuse.
    answers = latencies(50, 60, 70)
    callback().onAvailable(wifi)
    settle()
    assertThat(probed).hasSize(3)
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.EXCELLENT)
  }

  @Test
  fun `stopping during a measurement caches nothing`() = runTest {
    val monitor = monitor()
    inFlight = CompletableDeferred()
    // Slow and failing: had it been kept, it would report POOR.
    answers = listOf(failure(), failure(), failure())
    callback().onAvailable(wifi())
    settle()

    monitor.stop()
    checkNotNull(inFlight).complete(Unit)
    runCurrent()

    answers = latencies(50, 60, 70)
    monitor.start()
    callback().onAvailable(wifi())
    settle()
    assertThat(probed).hasSize(2)
    assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.EXCELLENT)
  }

  /* --------------------------- the gate's reading --------------------------- */

  @Test
  fun `while the first measurement is pending, the gate compares the link signals as they are`() =
    runTest {
      val monitor = monitor()
      callback().onAvailable(wifi())
      runCurrent()

      val pending = monitor.qualityForGate()

      assertThat(probed).isEmpty()
      assertThat(pending).isEqualTo(NetworkQuality.GOOD)
      assertThat(pending.meetsMinimum(NetworkQuality.GOOD)).isTrue()
      assertThat(pending.meetsMinimum(NetworkQuality.EXCELLENT)).isFalse()
    }

  @Test
  fun `old results start one new measurement at a time, however often the gate reads`() = runTest {
    val monitor = monitor()
    callback().onAvailable(wifi())
    settle()
    advanceTimeBy(ConnectivityMonitor.QUALITY_CACHE_TTL_MS)
    inFlight = CompletableDeferred()

    repeat(3) { monitor.qualityForGate() }
    settle()
    repeat(3) { monitor.qualityForGate() }
    settle()

    assertThat(probed).hasSize(2)
    checkNotNull(inFlight).complete(Unit)
    runCurrent()
  }

  @Test
  fun `a held wake whose check finds the result 30 s old is released by the new measurement`() =
    runTest {
      val monitor = monitor()
      answers = latencies(1_200, 1_300, 1_400)
      callback().onAvailable(wifi())
      settle()
      assertThat(monitor.state.value.networkQuality).isEqualTo(NetworkQuality.POOR)
      var dispatched = 0
      // Wired as the runtime does: the timer checks the gate, and the minimum releases it.
      val timer = RetryTimer(
        scope = backgroundScope,
        isOffline = { false },
        flush = { dispatched++ },
        holdReason = {
          if (monitor.qualityForGate().meetsMinimum(NetworkQuality.EXCELLENT)) null else WakeHold.Quality
        },
      )
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        monitor.state.collect {
          if (it.networkQuality.meetsMinimum(NetworkQuality.EXCELLENT)) timer.onDispatchPossible()
        }
      }

      timer.arm(0L)
      runCurrent()
      assertThat(dispatched).isEqualTo(0)
      assertThat(probed).hasSize(1)

      // The held wake's check finds the result 30 s old and starts a new measurement.
      answers = latencies(50, 60, 70)
      advanceTimeBy(GateRecheck.FIRST_DELAY_MS)
      runCurrent()
      assertThat(dispatched).isEqualTo(0)
      settle()

      assertThat(probed).hasSize(2)
      assertThat(dispatched).isEqualTo(1)
    }

  @Test
  fun `without an engine, the gate's reading starts the first measurement`() = runTest {
    shadowOf(manager()).setNetworkCapabilities(
      manager().activeNetwork,
      capabilities(NetworkCapabilities.TRANSPORT_WIFI, INTERNET, VALIDATED, NOT_METERED),
    )
    val monitor = monitor(started = false)
    val events = recordConnectionEvents()

    // Pending: the link signals decide this flush.
    assertThat(monitor.qualityForGate()).isEqualTo(NetworkQuality.GOOD)
    settle()

    assertThat(probed).hasSize(1)
    assertThat(monitor.currentState().networkQuality).isEqualTo(NetworkQuality.EXCELLENT)
    assertThat(monitor.qualityForGate()).isEqualTo(NetworkQuality.EXCELLENT)
    settle()
    assertThat(probed).hasSize(1)
    assertThat(events).isEmpty()
  }

  @Test
  fun `the gate's reading measures again once the result is older than 30 s`() = runTest {
    val monitor = monitor()
    callback().onAvailable(wifi())
    settle()

    advanceTimeBy(ConnectivityMonitor.QUALITY_CACHE_TTL_MS)
    answers = latencies(600, 650, 700)
    // The old result still decides this flush; the next one sees the new measurement.
    assertThat(monitor.qualityForGate()).isEqualTo(NetworkQuality.EXCELLENT)
    settle()

    assertThat(probed).hasSize(2)
    assertThat(monitor.qualityForGate()).isEqualTo(NetworkQuality.MODERATE)
  }

  private companion object {
    const val URL_A = "https://api.example.com/health"
    const val URL_B = "https://status.example.com/ping"
    const val DEBOUNCE_MS = 1_000L
    const val WIFI_ID = 101
    const val CELLULAR_ID = 102

    const val INTERNET = NetworkCapabilities.NET_CAPABILITY_INTERNET
    const val VALIDATED = NetworkCapabilities.NET_CAPABILITY_VALIDATED
    const val NOT_METERED = NetworkCapabilities.NET_CAPABILITY_NOT_METERED

    fun latencies(vararg ms: Long): List<HeadResult> = ms.map { HeadResult.Response(200, it) }

    fun failure(): HeadResult = HeadResult.Failure(HeadResult.FailureKind.TIMEOUT, "No answer within 2000 ms")
  }
}
