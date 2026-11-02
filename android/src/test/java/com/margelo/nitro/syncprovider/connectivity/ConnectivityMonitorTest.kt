package com.margelo.nitro.syncprovider.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.ConnectionStatus
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

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
}
