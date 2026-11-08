import type { ConnectionStatus, ConnectionType, NetworkQuality } from './enums';

/**
 * Snapshot of network connectivity reported by the native layer.
 *
 * Returned by `getConnectionStatus()` and surfaced through the
 * `useConnection` hook. The corresponding `CONNECTION_CHANGED` sync event is
 * emitted whenever one of these fields changes, never for the initial
 * snapshot, and carries only the coarse {@link ConnectionStatus}, including
 * when only {@link ConnectionState.networkQuality} changed; consumers that need
 * the full state should call `getConnectionStatus()` (or read the hook).
 *
 * On Android the snapshot follows the default network only, the one the app's
 * traffic uses. Losing a secondary network, such as cellular alongside Wi-Fi or
 * a VPN that does not apply to the app, changes nothing, and when a VPN that was
 * the default network drops, the snapshot moves to the network underneath
 * without reporting a disconnect.
 */
export interface ConnectionState {
  /** Coarse connectivity status. */
  status: ConnectionStatus;
  /** Physical/logical transport currently in use. */
  type: ConnectionType;
  /**
   * Coarse quality of the current link, always present. `OFFLINE` exactly when
   * {@link ConnectionState.status} is `DISCONNECTED` or `UNKNOWN`.
   *
   * Without {@link SyncOptions.healthCheckUrl} it comes from link signals only
   * and never reaches `EXCELLENT`. See {@link NetworkQuality} for the full
   * classification table, and {@link SyncOptions.minNetworkQuality} for the
   * flush gate that reads it.
   */
  networkQuality: NetworkQuality;
  /**
   * If reported by the OS, whether internet is actually reachable
   * (vs. merely associated with a network). Undefined when the OS has not
   * yet probed reachability.
   *
   * On Android it is the system's validation of the link
   * (`NET_CAPABILITY_VALIDATED`), and a link that is not validated grades at
   * most `POOR` in {@link ConnectionState.networkQuality}.
   */
  isInternetReachable?: boolean;
  /**
   * If reported by the OS, whether the active link is considered expensive
   * (typically equivalent to {@link ConnectionStatus.METERED}, but the two
   * can disagree on Wi-Fi captive portals or paid roaming). An expensive link
   * grades at most `GOOD` in {@link ConnectionState.networkQuality}.
   *
   * On Android 11 (API 30) and later, a metered link the network marks as
   * temporarily unmetered, as some 5G plans do, reports `false` with the status
   * `CONNECTED`, and `true` with `METERED` again once that exemption is
   * withdrawn.
   */
  isExpensive?: boolean;
}
