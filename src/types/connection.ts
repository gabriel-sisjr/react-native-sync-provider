import type { ConnectionStatus, ConnectionType } from './enums';

/**
 * Snapshot of network connectivity reported by the native layer.
 *
 * Returned by `getConnectionStatus()` and surfaced through the
 * `useConnection` hook. The corresponding `CONNECTION_CHANGED` sync event
 * carries only the coarse {@link ConnectionStatus}; consumers that need the
 * full state should call `getConnectionStatus()` (or read the hook).
 */
export interface ConnectionState {
  /** Coarse connectivity status. */
  status: ConnectionStatus;
  /** Physical/logical transport currently in use. */
  type: ConnectionType;
  /**
   * If reported by the OS, whether internet is actually reachable
   * (vs. merely associated with a network). Undefined when the OS has not
   * yet probed reachability.
   */
  isInternetReachable?: boolean;
  /**
   * If reported by the OS, whether the active link is considered expensive
   * (typically equivalent to {@link ConnectionStatus.METERED}, but the two
   * can disagree on Wi-Fi captive portals or paid roaming).
   */
  isExpensive?: boolean;
}
