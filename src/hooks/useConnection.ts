import { useEffect, useState } from 'react';

import {
  ConnectionStatus,
  ConnectionType,
  SyncEventType,
} from '../types/enums';
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
};

function deriveResult(
  status: ConnectionStatus,
  type: ConnectionType
): UseConnectionResult {
  return {
    status,
    type,
    isOnline:
      status === ConnectionStatus.CONNECTED ||
      status === ConnectionStatus.METERED,
    isMetered: status === ConnectionStatus.METERED,
  };
}

/**
 * Subscribe to live network connectivity reported by the native layer.
 *
 * Reads an initial snapshot via `getConnectionStatus()` and then mirrors
 * `CONNECTION_CHANGED` events through the `'sync-event'` channel. The hook
 * is safe to call before the native module is loaded — when unavailable it
 * returns the inert initial state and never throws on render.
 *
 * @returns a {@link UseConnectionResult} with `status`, `type`, `isOnline`,
 *   and `isMetered`.
 *
 * @example
 * const { status, isOnline } = useConnection();
 * if (!isOnline) return <Banner>Offline — your changes will sync later.</Banner>;
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
        if (!cancelled) setState(deriveResult(snapshot.status, snapshot.type));
      } catch {
        // Keep initial state on probe failure.
      }
      if (cancelled) return;
      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          if (event.type !== SyncEventType.CONNECTION_CHANGED) return;
          if (event.connectionStatus === undefined) return;
          // Re-fetch the full snapshot so we also get the updated `type`.
          getConnectionStatus()
            .then((snap) => {
              if (!cancelled) setState(deriveResult(snap.status, snap.type));
            })
            .catch(() => {
              if (!cancelled && event.connectionStatus !== undefined) {
                setState((prev) =>
                  deriveResult(event.connectionStatus!, prev.type)
                );
              }
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
