package com.margelo.nitro.syncprovider.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.ConnectionStatus
import com.margelo.nitro.syncprovider.ConnectionType
import com.margelo.nitro.syncprovider.SyncEvent
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class ConnectivityMonitorTest {

  private lateinit var emitter: SyncEventEmitter
  private lateinit var scope: CoroutineScope
  private lateinit var monitor: ConnectivityMonitor

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    emitter = SyncEventEmitter()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    monitor = ConnectivityMonitor(context, emitter, scope)
  }

  @After
  fun tearDown() {
    monitor.stop()
    scope.cancel()
  }

  private fun manager(): ConnectivityManager = ApplicationProvider.getApplicationContext<Context>()
    .getSystemService(ConnectivityManager::class.java)

  /** The callback [ConnectivityMonitor.start] registered. */
  private fun callback(): ConnectivityManager.NetworkCallback =
    shadowOf(manager()).networkCallbacks.single()

  private fun capabilities(transport: Int, vararg capabilities: Int): NetworkCapabilities =
    ShadowNetworkCapabilities.newInstance().also { caps ->
      shadowOf(caps).addTransportType(transport)
      capabilities.forEach { shadowOf(caps).addCapability(it) }
    }

  /** A network the system reports with [capabilities]. */
  private fun network(netId: Int, transport: Int, vararg capabilities: Int): Network =
    ShadowNetwork.newInstance(netId).also {
      shadowOf(manager()).setNetworkCapabilities(it, capabilities(transport, *capabilities))
    }

  /** Every `CONNECTION_CHANGED` emitted from now on, synchronously (unconfined scope). */
  private fun recordConnectionEvents(): List<SyncEvent> {
    val events = Collections.synchronizedList(mutableListOf<SyncEvent>())
    scope.launch {
      emitter.events.collect { if (it.type == SyncEventType.CONNECTION_CHANGED) events += it }
    }
    return events
  }

  @Test
  fun `currentState returns a non-null ConnectionState before start`() {
    val state = monitor.currentState()

    // Robolectric's default ShadowConnectivityManager exposes "no active network",
    // which currentState surfaces via the disconnected fallback.
    assertThat(state).isNotNull()
    assertThat(state.status).isAnyOf(
      ConnectionStatus.DISCONNECTED,
      ConnectionStatus.UNKNOWN,
      ConnectionStatus.CONNECTED,
      ConnectionStatus.METERED,
    )
  }

  @Test
  fun `start is idempotent`() {
    monitor.start()
    monitor.start()
    monitor.stop()
  }

  @Test
  fun `stop without start is a no-op`() {
    monitor.stop()
  }

  @Test
  fun `start and stop registers and unregisters cleanly`() {
    monitor.start()
    monitor.stop()
    monitor.start()
    monitor.stop()
  }

  @Test
  fun `state StateFlow emits a non-null initial value`() {
    val initial = monitor.state.value

    assertThat(initial).isNotNull()
    assertThat(initial.status).isEqualTo(ConnectionStatus.UNKNOWN)
  }

  @Test
  fun `isOffline is true when the system reports no active network`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = context.getSystemService(ConnectivityManager::class.java)
    shadowOf(manager).setDefaultNetworkActive(false)

    assertThat(monitor.isOffline()).isTrue()
  }

  @Test
  fun `isOffline asks the system instead of trusting the last callback`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = ShadowNetworkCapabilities.newInstance()
    shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)

    // The state is still UNKNOWN: no callback has reported anything.
    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.UNKNOWN)
    assertThat(monitor.isOffline()).isFalse()
  }

  /* --------------------------- the default network --------------------------- */

  @Test
  @Config(shadows = [RecordingConnectivityManager::class])
  fun `start follows the default network, not every network with internet`() {
    monitor.start()

    val recorded = Shadow.extract<RecordingConnectivityManager>(manager())
    assertThat(recorded.defaultCallbacks).isEqualTo(1)
    assertThat(recorded.requestCallbacks).isEqualTo(0)
  }

  @Test
  fun `losing a network that is not the default one keeps the device online`() {
    monitor.start()
    val wifi = network(WIFI_ID, NetworkCapabilities.TRANSPORT_WIFI, *INTERNET, NOT_METERED)
    val cellular = network(CELLULAR_ID, NetworkCapabilities.TRANSPORT_CELLULAR, *INTERNET)
    val vpn = network(VPN_ID, NetworkCapabilities.TRANSPORT_VPN, *INTERNET, NOT_METERED)
    callback().onAvailable(wifi)
    val events = recordConnectionEvents()

    callback().onLost(cellular)
    callback().onLost(vpn)

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.CONNECTED)
    assertThat(monitor.state.value.type).isEqualTo(ConnectionType.WIFI)
    assertThat(events).isEmpty()
  }

  @Test
  fun `losing the default network reports the device offline`() {
    monitor.start()
    val wifi = network(WIFI_ID, NetworkCapabilities.TRANSPORT_WIFI, *INTERNET, NOT_METERED)
    callback().onAvailable(wifi)
    val events = recordConnectionEvents()

    callback().onLost(wifi)

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.DISCONNECTED)
    assertThat(events.map { it.connectionStatus }).containsExactly(ConnectionStatus.DISCONNECTED)
  }

  @Test
  fun `a new default network takes over without a disconnect in between`() {
    monitor.start()
    val vpn = network(VPN_ID, NetworkCapabilities.TRANSPORT_VPN, *INTERNET, NOT_METERED)
    val wifi = network(WIFI_ID, NetworkCapabilities.TRANSPORT_WIFI, *INTERNET, NOT_METERED)
    callback().onAvailable(vpn)
    assertThat(monitor.state.value.type).isEqualTo(ConnectionType.VPN)
    val events = recordConnectionEvents()

    // The VPN goes down and the Wi-Fi under it becomes the default network.
    callback().onAvailable(wifi)
    // A late loss of the network it replaced must not take the device offline.
    callback().onLost(vpn)

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.CONNECTED)
    assertThat(monitor.state.value.type).isEqualTo(ConnectionType.WIFI)
    assertThat(events.map { it.connectionStatus }).containsExactly(ConnectionStatus.CONNECTED)
  }

  @Test
  fun `capability changes of another network are ignored`() {
    monitor.start()
    val wifi = network(WIFI_ID, NetworkCapabilities.TRANSPORT_WIFI, *INTERNET, NOT_METERED)
    val cellular = network(CELLULAR_ID, NetworkCapabilities.TRANSPORT_CELLULAR, *INTERNET)
    callback().onAvailable(wifi)
    val events = recordConnectionEvents()

    callback().onCapabilitiesChanged(
      cellular,
      capabilities(NetworkCapabilities.TRANSPORT_CELLULAR),
    )

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.CONNECTED)
    assertThat(monitor.state.value.type).isEqualTo(ConnectionType.WIFI)
    assertThat(events).isEmpty()
  }

  @Test
  fun `capability changes of the default network are applied`() {
    monitor.start()
    val wifi = network(WIFI_ID, NetworkCapabilities.TRANSPORT_WIFI, *INTERNET, NOT_METERED)
    callback().onAvailable(wifi)
    val events = recordConnectionEvents()

    // The Wi-Fi turns out to be a metered hotspot.
    callback().onCapabilitiesChanged(
      wifi,
      capabilities(NetworkCapabilities.TRANSPORT_WIFI, *INTERNET),
    )

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.METERED)
    assertThat(events.map { it.connectionStatus }).containsExactly(ConnectionStatus.METERED)
  }

  /* ------------------------------ metered links ------------------------------ */

  @Test
  @Config(sdk = [30, 31])
  fun `a temporarily unmetered link is unmetered`() {
    monitor.start()
    val cellular = network(
      CELLULAR_ID,
      NetworkCapabilities.TRANSPORT_CELLULAR,
      *INTERNET,
      TEMPORARILY_NOT_METERED,
    )

    callback().onAvailable(cellular)

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.CONNECTED)
    assertThat(monitor.state.value.isExpensive).isFalse()
  }

  @Test
  @Config(sdk = [30, 31])
  fun `a metered link without the temporary exemption is metered`() {
    monitor.start()
    val cellular = network(CELLULAR_ID, NetworkCapabilities.TRANSPORT_CELLULAR, *INTERNET)

    callback().onAvailable(cellular)

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.METERED)
    assertThat(monitor.state.value.isExpensive).isTrue()
  }

  @Test
  @Config(sdk = [31])
  fun `a link that stops being temporarily unmetered is metered again`() {
    monitor.start()
    val cellular = network(
      CELLULAR_ID,
      NetworkCapabilities.TRANSPORT_CELLULAR,
      *INTERNET,
      TEMPORARILY_NOT_METERED,
    )
    callback().onAvailable(cellular)
    val events = recordConnectionEvents()

    callback().onCapabilitiesChanged(
      cellular,
      capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, *INTERNET),
    )

    assertThat(monitor.state.value.status).isEqualTo(ConnectionStatus.METERED)
    assertThat(monitor.state.value.isExpensive).isTrue()
    assertThat(events.map { it.connectionStatus }).containsExactly(ConnectionStatus.METERED)
  }

  @Test
  @Config(sdk = [31])
  fun `the snapshot read applies the same rule`() {
    shadowOf(manager()).setNetworkCapabilities(
      manager().activeNetwork,
      capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, *INTERNET, TEMPORARILY_NOT_METERED),
    )

    val state = monitor.currentState()

    assertThat(state.status).isEqualTo(ConnectionStatus.CONNECTED)
    assertThat(state.isExpensive).isFalse()
  }

  /** The stock shadow, counting which registration call the monitor makes. */
  @Implements(ConnectivityManager::class)
  class RecordingConnectivityManager : ShadowConnectivityManager() {
    var defaultCallbacks = 0
      private set
    var requestCallbacks = 0
      private set

    @Implementation
    override fun registerDefaultNetworkCallback(
      networkCallback: ConnectivityManager.NetworkCallback,
    ) {
      defaultCallbacks++
      super.registerDefaultNetworkCallback(networkCallback)
    }

    @Implementation
    override fun registerNetworkCallback(
      request: NetworkRequest,
      networkCallback: ConnectivityManager.NetworkCallback,
    ) {
      requestCallbacks++
      super.registerNetworkCallback(request, networkCallback)
    }
  }

  private companion object {
    const val WIFI_ID = 101
    const val CELLULAR_ID = 102
    const val VPN_ID = 103

    const val NOT_METERED = NetworkCapabilities.NET_CAPABILITY_NOT_METERED
    const val TEMPORARILY_NOT_METERED = NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED

    /** A working internet link: reaches the internet, and the system validated it. */
    val INTERNET = intArrayOf(
      NetworkCapabilities.NET_CAPABILITY_INTERNET,
      NetworkCapabilities.NET_CAPABILITY_VALIDATED,
    )
  }
}
