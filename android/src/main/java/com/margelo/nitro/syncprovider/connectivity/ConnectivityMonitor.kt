package com.margelo.nitro.syncprovider.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.margelo.nitro.syncprovider.ConnectionState
import com.margelo.nitro.syncprovider.ConnectionStatus
import com.margelo.nitro.syncprovider.ConnectionType
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

  fun start() {
    if (callback != null || cm == null) return
    val cb = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        update(extractState(network))
      }

      override fun onLost(network: Network) {
        update(disconnectedState())
      }

      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        update(stateFromCapabilities(caps))
      }

      override fun onUnavailable() {
        update(disconnectedState())
      }
    }
    val request = NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .build()

    try {
      cm.registerNetworkCallback(request, cb)
      callback = cb
      // Seed the StateFlow with the current snapshot.
      val seeded = currentState()
      update(seeded, emit = false)
    } catch (t: Throwable) {
      Log.e(TAG, "registerNetworkCallback failed", t)
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
  }

  /** Snapshot read used by `getConnectionStatus()`. */
  fun currentState(): ConnectionState = _state.value.takeIf { it.status != ConnectionStatus.UNKNOWN }
    ?: extractCurrentSnapshot()

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
    val notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    val type = transportToType(caps)

    val status = when {
      !hasInternet -> ConnectionStatus.DISCONNECTED
      !notMetered -> ConnectionStatus.METERED
      else -> ConnectionStatus.CONNECTED
    }
    val isExpensive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } else {
      !notMetered
    }
    return ConnectionState(
      status = status,
      type = type,
      isInternetReachable = validated,
      isExpensive = isExpensive,
    )
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
      isInternetReachable = null,
      isExpensive = null,
    )

    private fun disconnectedState() = ConnectionState(
      status = ConnectionStatus.DISCONNECTED,
      type = ConnectionType.NONE,
      isInternetReachable = false,
      isExpensive = false,
    )
  }
}
