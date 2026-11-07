package com.margelo.nitro.syncprovider.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.margelo.nitro.syncprovider.ConnectionState
import com.margelo.nitro.syncprovider.ConnectionStatus
import com.margelo.nitro.syncprovider.ConnectionType
import com.margelo.nitro.syncprovider.NetworkQuality
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Wraps [ConnectivityManager.NetworkCallback] in a [StateFlow] so the rest
 * of the module reads connectivity reactively without leaking the callback
 * registration.
 *
 * Only the app's default network is followed: the one the system routes this
 * app's traffic through, a VPN included when one applies. A device can hold
 * several networks at once (Wi-Fi and cellular, or a VPN over either); losing
 * one that is not the default leaves the app online, so it is not reported.
 *
 * The monitor is started lazily by [start] and stopped via [stop]; the bridge
 * class's `moduleScope` owns the lifecycle.
 */
internal class ConnectivityMonitor(
  context: Context,
  private val emitter: SyncEventEmitter,
  private val scope: CoroutineScope,
) {

  private val appContext = context.applicationContext
  private val cm: ConnectivityManager? =
    appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

  private val _state = MutableStateFlow(unknownState())
  val state: StateFlow<ConnectionState> = _state.asStateFlow()

  private var callback: ConnectivityManager.NetworkCallback? = null

  /**
   * The network the last `onAvailable` reported, or null while there is none.
   * Callbacks about any other network are stale and ignored.
   */
  @Volatile private var defaultNetwork: Network? = null

  fun start() {
    if (callback != null || cm == null) return
    val cb = object : ConnectivityManager.NetworkCallback() {
      // Also how a new default network replaces the previous one: the system
      // reports no loss for the network it replaced.
      override fun onAvailable(network: Network) {
        defaultNetwork = network
        update(extractState(network))
      }

      // On a default-network callback, only once no default network is left.
      override fun onLost(network: Network) {
        if (network != defaultNetwork) return
        defaultNetwork = null
        update(disconnectedState())
      }

      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (network != defaultNetwork) return
        update(stateFromCapabilities(caps))
      }

      override fun onUnavailable() {
        defaultNetwork = null
        update(disconnectedState())
      }
    }

    try {
      cm.registerDefaultNetworkCallback(cb)
      callback = cb
      // Seed the StateFlow with the current snapshot.
      val seeded = currentState()
      update(seeded, emit = false)
    } catch (t: Throwable) {
      Log.e(TAG, "registerDefaultNetworkCallback failed", t)
    }
  }

  fun stop() {
    val cb = callback ?: return
    try {
      cm?.unregisterNetworkCallback(cb)
    } catch (t: Throwable) {
      Log.e(TAG, "unregisterNetworkCallback failed", t)
    }
    callback = null
    defaultNetwork = null
  }

  /** Snapshot read used by `getConnectionStatus()`. */
  fun currentState(): ConnectionState = _state.value.takeIf { it.status != ConnectionStatus.UNKNOWN }
    ?: extractCurrentSnapshot()

  /**
   * Asks the system rather than reading [state]. The system switches networks
   * before its callbacks reach [state], so a decision taken in between would read
   * the previous network; the system's own answer is already current.
   */
  fun isOffline(): Boolean = extractCurrentSnapshot().status == ConnectionStatus.DISCONNECTED

  private fun extractCurrentSnapshot(): ConnectionState {
    val mgr = cm ?: return unknownState()
    val active = mgr.activeNetwork ?: return disconnectedState()
    val caps = mgr.getNetworkCapabilities(active) ?: return disconnectedState()
    return stateFromCapabilities(caps)
  }

  private fun extractState(network: Network): ConnectionState {
    val mgr = cm ?: return unknownState()
    val caps = mgr.getNetworkCapabilities(network) ?: return disconnectedState()
    return stateFromCapabilities(caps)
  }

  private fun stateFromCapabilities(caps: NetworkCapabilities): ConnectionState {
    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val unmetered = isUnmetered(caps)
    val type = transportToType(caps)

    val status = when {
      !hasInternet -> ConnectionStatus.DISCONNECTED
      !unmetered -> ConnectionStatus.METERED
      else -> ConnectionStatus.CONNECTED
    }
    return ConnectionState(
      status = status,
      type = type,
      networkQuality = provisionalQuality(status),
      isInternetReachable = validated,
      isExpensive = !unmetered,
    )
  }

  /**
   * Unmetered permanently, or, from Android 11 (API 30), temporarily: a link that is
   * usually metered but currently is not, such as some 5G plans. The system can
   * withdraw the temporary state at any time; the capability change that follows
   * reports the link metered again.
   */
  private fun isUnmetered(caps: NetworkCapabilities): Boolean {
    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
  }

  private fun transportToType(caps: NetworkCapabilities): ConnectionType = when {
    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionType.VPN
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> ConnectionType.BLUETOOTH
    else -> ConnectionType.OTHER
  }

  private fun update(next: ConnectionState, emit: Boolean = true) {
    val previous = _state.value
    if (previous == next) return
    _state.value = next
    if (!emit) return
    scope.launch {
      emitter.emit(
        SyncEventEmitter.event(
          type = SyncEventType.CONNECTION_CHANGED,
          connectionStatus = next.status,
        ),
      )
    }
  }

  companion object {
    private const val TAG = "ConnectivityMonitor"

    private fun unknownState() = ConnectionState(
      status = ConnectionStatus.UNKNOWN,
      type = ConnectionType.UNKNOWN,
      networkQuality = NetworkQuality.OFFLINE,
      isInternetReachable = null,
      isExpensive = null,
    )

    private fun disconnectedState() = ConnectionState(
      status = ConnectionStatus.DISCONNECTED,
      type = ConnectionType.NONE,
      networkQuality = NetworkQuality.OFFLINE,
      isInternetReachable = false,
      isExpensive = false,
    )

    /**
     * Provisional, from the link status alone, until latency measurements classify
     * live links: `OFFLINE` exactly when the status is `DISCONNECTED` or `UNKNOWN`,
     * as the public contract requires, and `GOOD` for every live link.
     */
    private fun provisionalQuality(status: ConnectionStatus): NetworkQuality = when (status) {
      ConnectionStatus.DISCONNECTED, ConnectionStatus.UNKNOWN -> NetworkQuality.OFFLINE
      ConnectionStatus.CONNECTED, ConnectionStatus.METERED -> NetworkQuality.GOOD
    }
  }
}
