package com.margelo.nitro.syncprovider.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.margelo.nitro.syncprovider.ConnectionState
import com.margelo.nitro.syncprovider.ConnectionStatus
import com.margelo.nitro.syncprovider.ConnectionType
import com.margelo.nitro.syncprovider.NetworkQuality
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.http.HeadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * Every state carries a network quality from the shared threshold table (see
 * [NetworkQualityClassifier]). With `healthCheckUrl` set, a latency measurement
 * ([LatencyProbe]) feeds it:
 * - one is requested when the default network or its capabilities change, when the URL
 *   changes, when the monitor starts, and when the flush gate reads one older than
 *   [QUALITY_CACHE_TTL_MS] ([qualityForGate]). Requests are debounced by [probeDebounceMs],
 *   and one probe runs at a time;
 * - a result is reused for [QUALITY_CACHE_TTL_MS]: a request inside that window sends
 *   nothing. An older result keeps classifying until a new one replaces it;
 * - a result belongs to the network it measured, that network's validation state and the
 *   URL, and never classifies anything else. A probe its link outdated is cancelled, and its
 *   samples are discarded;
 * - nothing is sent without `healthCheckUrl` or while offline.
 * A change of quality alone is reported like any other change: a new [state] and a
 * `CONNECTION_CHANGED` event.
 *
 * The monitor is started lazily by [start] and stopped via [stop]; the bridge
 * class's `moduleScope` owns the lifecycle. Until then (a process with no JS
 * engine), [currentState] reads the system on every call.
 *
 * @param healthCheckUrl read on every request, so a new configuration applies at once.
 * @param measure one latency measurement, cancellable. Production uses [LatencyProbe].
 * @param clock monotonic millis, for the reuse window.
 * @param onDefaultNetworkChanged called when another network becomes the default, or the
 *   default one is lost.
 * @param onHealthySample called with the URL when a kept measurement had a `2xx` answer, which
 *   counts as a passed health check.
 */
internal class ConnectivityMonitor(
  context: Context,
  private val emitter: SyncEventEmitter,
  private val scope: CoroutineScope,
  private val healthCheckUrl: () -> String? = { null },
  private val measure: suspend (url: String) -> List<HeadResult> = { emptyList() },
  private val clock: () -> Long = SystemClock::elapsedRealtime,
  private val probeDebounceMs: Long = PROBE_DEBOUNCE_MS,
  private val onDefaultNetworkChanged: () -> Unit = {},
  private val onHealthySample: (url: String) -> Unit = {},
) {

  private val appContext = context.applicationContext
  private val cm: ConnectivityManager? =
    appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

  private val _state = MutableStateFlow(unknownState())
  val state: StateFlow<ConnectionState> = _state.asStateFlow()

  @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

  /**
   * The network the last `onAvailable` reported, or null while there is none.
   * Callbacks about any other network are stale and ignored.
   */
  @Volatile private var defaultNetwork: Network? = null

  /**
   * Serializes publishing, so a state computed from an older measurement never replaces one
   * computed from a newer one. Taken before [probeLock], never after it.
   */
  private val stateLock = Any()
  private val probeLock = Any()

  /** Guarded by [probeLock]. */
  private var measurement: Measurement? = null
  private var probe: RunningProbe? = null

  fun start() {
    if (callback != null || cm == null) return
    val cb = object : ConnectivityManager.NetworkCallback() {
      // Also how a new default network replaces the previous one: the system
      // reports no loss for the network it replaced.
      override fun onAvailable(network: Network) {
        val changed = network != defaultNetwork
        defaultNetwork = network
        publish(linkState(network), network)
        requestMeasurement()
        if (changed) onDefaultNetworkChanged()
      }

      // On a default-network callback, only once no default network is left.
      override fun onLost(network: Network) {
        if (network != defaultNetwork) return
        defaultNetwork = null
        publish(disconnectedState(), null)
        requestMeasurement()
        onDefaultNetworkChanged()
      }

      override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        if (network != defaultNetwork) return
        publish(linkFromCapabilities(caps), network)
        requestMeasurement()
      }

      override fun onUnavailable() {
        val changed = defaultNetwork != null
        defaultNetwork = null
        publish(disconnectedState(), null)
        requestMeasurement()
        if (changed) onDefaultNetworkChanged()
      }
    }

    try {
      cm.registerDefaultNetworkCallback(cb)
      callback = cb
      // Seed the StateFlow with the current snapshot.
      synchronized(stateLock) { set(currentState(), emit = false) }
      requestMeasurement()
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
    synchronized(probeLock) { cancelProbe() }
  }

  /** Snapshot read used by `getConnectionStatus()`. */
  fun currentState(): ConnectionState = _state.value.takeIf { it.status != ConnectionStatus.UNKNOWN }
    ?: extractCurrentSnapshot()

  /**
   * The quality the flush gate compares, as [currentState] reports it. When the measurement
   * behind it is missing or older than [QUALITY_CACHE_TTL_MS], a new one starts; this
   * decision does not wait for it.
   */
  fun qualityForGate(): NetworkQuality {
    val quality = currentState().networkQuality
    requestMeasurement()
    return quality
  }

  /**
   * After `configureSync`. A new `healthCheckUrl` outdates the result for the old one; an
   * unchanged one keeps it, and nothing is sent.
   */
  fun onConfigurationChanged() {
    republish()
    requestMeasurement()
  }

  /**
   * Asks the system rather than reading [state]. The system switches networks
   * before its callbacks reach [state], so a decision taken in between would read
   * the previous network; the system's own answer is already current.
   */
  fun isOffline(): Boolean = snapshotLink().second.status == ConnectionStatus.DISCONNECTED

  /* --------------------------------- link ---------------------------------- */

  /** The system's default network and its link, unclassified. */
  private fun snapshotLink(): Pair<Network?, ConnectionState> {
    val mgr = cm ?: return null to unknownState()
    val active = mgr.activeNetwork ?: return null to disconnectedState()
    return active to linkState(active)
  }

  private fun extractCurrentSnapshot(): ConnectionState {
    val (network, link) = snapshotLink()
    return classified(link, network)
  }

  private fun linkState(network: Network): ConnectionState {
    val caps = cm?.getNetworkCapabilities(network) ?: return disconnectedState()
    return linkFromCapabilities(caps)
  }

  /** The link part of a state. Its quality is a placeholder until [classified] sets it. */
  private fun linkFromCapabilities(caps: NetworkCapabilities): ConnectionState {
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
      networkQuality = NetworkQuality.OFFLINE,
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

  /* -------------------------------- quality -------------------------------- */

  /** [link] with its quality from the shared threshold table. */
  private fun classified(link: ConnectionState, network: Network?): ConnectionState {
    val quality = NetworkQualityClassifier.classify(signalsOf(link), samplesFor(network, link))
    return if (quality == link.networkQuality) link else link.copy(networkQuality = quality)
  }

  /** The samples of the measurement that belongs to this link, if there is one. */
  private fun samplesFor(network: Network?, link: ConnectionState): List<Long?>? {
    val url = healthCheckUrl() ?: return null
    val key = MeasurementKey(network, link.isInternetReachable == true, url)
    return synchronized(probeLock) { measurement?.takeIf { it.key == key }?.samplesMs }
  }

  /**
   * Probe the link unless a result for it is fresh or a probe of it is running. A probe of
   * another link is cancelled first. Never without a URL, never offline.
   */
  private fun requestMeasurement() {
    val url = healthCheckUrl()
    val (network, link) = measuredLink()
    val job = synchronized(probeLock) {
      if (url == null || !link.isOnline()) {
        cancelProbe()
        return
      }
      val key = MeasurementKey(network, link.isInternetReachable == true, url)
      val fresh = measurement?.let { it.key == key && isFresh(it) } == true
      if (fresh || probe?.key == key) return
      cancelProbe()
      // Lazy, and started outside the lock: an unconfined scope would run it right here.
      scope.launch(start = CoroutineStart.LAZY) { runProbe(key) }
        .also { probe = RunningProbe(key, it) }
    }
    job.start()
  }

  private suspend fun runProbe(key: MeasurementKey) {
    try {
      delay(probeDebounceMs)
      // A cancelled probe throws here: its samples never reach the classifier.
      val results = measure(key.url)
      val samples = LatencyProbe.latencies(results)
      synchronized(probeLock) {
        if (probe?.key != key) return
        measurement = Measurement(key, samples, clock())
      }
      if (results.any { it is HeadResult.Response && it.statusCode in HEALTHY_STATUSES }) {
        onHealthySample(key.url)
      }
      republish()
    } finally {
      synchronized(probeLock) { if (probe?.key == key) probe = null }
    }
  }

  private fun isFresh(measurement: Measurement): Boolean =
    clock() - measurement.takenAt < QUALITY_CACHE_TTL_MS

  /** The tracked default network once started; until then, the system's. */
  private fun measuredLink(): Pair<Network?, ConnectionState> =
    if (callback != null) defaultNetwork to _state.value else snapshotLink()

  /** Caller holds [probeLock]. */
  private fun cancelProbe() {
    probe?.job?.cancel()
    probe = null
  }

  /* ------------------------------- publishing ------------------------------ */

  private fun publish(link: ConnectionState, network: Network?) {
    synchronized(stateLock) { set(classified(link, network), emit = true) }
  }

  /** Reclassify the published state after its measurement changed. */
  private fun republish() {
    if (callback == null) return
    synchronized(stateLock) {
      val current = _state.value
      if (current.status == ConnectionStatus.UNKNOWN) return
      set(classified(current, defaultNetwork), emit = true)
    }
  }

  /**
   * Publish [next] when it differs from the reported state in any field; the seed of [start]
   * passes `emit = false`. The `CONNECTION_CHANGED` event is launched on [scope], after
   * [state] already holds the new value, so it reaches listeners asynchronously; iOS delivers
   * its event synchronously. Caller holds [stateLock].
   */
  private fun set(next: ConnectionState, emit: Boolean) {
    if (_state.value == next) return
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

  private data class MeasurementKey(val network: Network?, val validated: Boolean, val url: String)

  private class Measurement(val key: MeasurementKey, val samplesMs: List<Long?>, val takenAt: Long)

  private class RunningProbe(val key: MeasurementKey, val job: Job)

  companion object {
    private const val TAG = "ConnectivityMonitor"

    /** How long a measurement is reused. Also how long a wake the gate held waits to retry. */
    const val QUALITY_CACHE_TTL_MS: Long = 30_000L

    /** Collapses the callbacks of one network change into one probe. */
    const val PROBE_DEBOUNCE_MS: Long = 1_000L

    private val HEALTHY_STATUSES = 200..299

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

    private fun signalsOf(link: ConnectionState) = LinkSignals(
      known = link.status != ConnectionStatus.UNKNOWN,
      connected = link.isOnline(),
      metered = link.isExpensive == true,
      validated = link.isInternetReachable == true,
    )

    private fun ConnectionState.isOnline(): Boolean =
      status == ConnectionStatus.CONNECTED || status == ConnectionStatus.METERED
  }
}
