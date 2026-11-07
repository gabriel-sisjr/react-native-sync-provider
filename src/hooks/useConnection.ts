import { useEffect, useState } from 'react';

import {
  ConnectionStatus,
  ConnectionType,
  NetworkQuality,
  SyncEventType,
} from '../types/enums';
import type { ConnectionState } from '../types/connection';
import type { SyncEvent } from '../types/sync';
import type { UseConnectionResult } from '../types/hooks';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import {
  addSyncEventListener,
  getConnectionStatus,
  removeSyncEventListener,
} from '../index';

const INITIAL: UseConnectionResult = {
  status: ConnectionStatus.UNKNOWN,
  type: ConnectionType.UNKNOWN,
  isOnline: false,
  isMetered: false,
  networkQuality: NetworkQuality.OFFLINE,
};

type ConnectionFields = Pick<
  ConnectionState,
  'status' | 'type' | 'networkQuality'
>;

function isOnlineStatus(status: ConnectionStatus): boolean {
  return (
    status === ConnectionStatus.CONNECTED || status === ConnectionStatus.METERED
  );
}

function deriveResult(fields: ConnectionFields): UseConnectionResult {
  return {
    status: fields.status,
    type: fields.type,
    isOnline: isOnlineStatus(fields.status),
    isMetered: fields.status === ConnectionStatus.METERED,
    networkQuality: fields.networkQuality,
  };
}

/** Keep the previous object when nothing visible changed, so React bails out. */
function nextState(
  previous: UseConnectionResult,
  fields: ConnectionFields
): UseConnectionResult {
  return previous.status === fields.status &&
    previous.type === fields.type &&
    previous.networkQuality === fields.networkQuality
    ? previous
    : deriveResult(fields);
}

/**
 * Quality to show when a `CONNECTION_CHANGED` arrives but the snapshot cannot
 * be read again. Keeps `OFFLINE` tied to an offline status, as native does: a
 * link that came online without a known quality gets the classification for a
 * link with no measurement.
 */
function qualityWithoutSnapshot(
  status: ConnectionStatus,
  previous: NetworkQuality
): NetworkQuality {
  if (!isOnlineStatus(status)) return NetworkQuality.OFFLINE;
  if (previous !== NetworkQuality.OFFLINE) return previous;
  return status === ConnectionStatus.METERED
    ? NetworkQuality.MODERATE
    : NetworkQuality.GOOD;
}

/**
 * Subscribe to live network connectivity reported by the native layer.
 *
 * Reads an initial snapshot via `getConnectionStatus()` and then reads it
 * again on every `CONNECTION_CHANGED` event through the `'sync-event'`
 * channel, including the ones emitted when only the network quality changed.
 * The hook is safe to call before the native module is loaded — when
 * unavailable it returns the inert initial state and never throws on render.
 *
 * The result object is kept when a new snapshot changes none of its fields, so
 * an event that changes nothing visible causes no re-render.
 *
 * @returns a {@link UseConnectionResult} with `status`, `type`, `isOnline`,
 *   `isMetered` and `networkQuality`. Before the first snapshot that is
 *   `UNKNOWN` / `UNKNOWN` / `false` / `false` / `OFFLINE`.
 *
 * @example
 * const { isOnline, networkQuality } = useConnection();
 * if (!isOnline) return <Banner>Offline — your changes will sync later.</Banner>;
 * if (networkQuality === NetworkQuality.POOR) return <Banner>Slow network.</Banner>;
 *
 * @public
 */
export function useConnection(): UseConnectionResult {
  const [state, setState] = useState<UseConnectionResult>(INITIAL);

  useEffect(() => {
    if (!isNativeModuleAvailable()) return undefined;

    let cancelled = false;
    let subscriptionId: string | null = null;

    const init = async () => {
      try {
        const snapshot = await getConnectionStatus();
        if (!cancelled) setState((prev) => nextState(prev, snapshot));
      } catch {
        // Keep initial state on probe failure.
      }
      if (cancelled) return;
      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          if (event.type !== SyncEventType.CONNECTION_CHANGED) return;
          const status = event.connectionStatus;
          if (status === undefined) return;
          // Re-fetch the full snapshot: the event carries only the status.
          getConnectionStatus()
            .then((snap) => {
              if (!cancelled) setState((prev) => nextState(prev, snap));
            })
            .catch(() => {
              if (cancelled) return;
              setState((prev) =>
                nextState(prev, {
                  status,
                  type: prev.type,
                  networkQuality: qualityWithoutSnapshot(
                    status,
                    prev.networkQuality
                  ),
                })
              );
            });
        });
      } catch {
        // Subscription failure: keep the snapshot we already have.
      }
    };

    void init();

    return () => {
      cancelled = true;
      if (subscriptionId !== null) {
        void removeSyncEventListener(subscriptionId).catch(() => undefined);
      }
    };
  }, []);

  return state;
}
