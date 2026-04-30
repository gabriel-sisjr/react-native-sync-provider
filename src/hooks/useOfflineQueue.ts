import { useCallback, useEffect, useRef, useState } from 'react';

import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import {
  ConnectionStatus,
  ConnectionType,
  SyncEventType,
} from '../types/enums';
import type { UseOfflineQueueResult } from '../types/hooks';
import type { SyncEvent, SyncItem, SyncItemInput } from '../types/sync';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import {
  addSyncEventListener,
  enqueue as facadeEnqueue,
  flush as facadeFlush,
  getConnectionStatus,
  getPendingItems,
  getQueueSize,
  isSyncing as facadeIsSyncing,
  removeSyncEventListener,
} from '../index';

const QUEUE_REFRESH_EVENTS: ReadonlySet<string> = new Set<string>([
  SyncEventType.ITEM_ENQUEUED,
  SyncEventType.ITEM_REMOVED,
  SyncEventType.QUEUE_CLEARED,
  SyncEventType.SYNC_SUCCEEDED,
  SyncEventType.SYNC_FAILED,
  SyncEventType.ITEM_SUCCEEDED,
  SyncEventType.ITEM_FAILED,
]);

/**
 * Convenience hook combining connection state with queue + sync state for
 * offline-first UIs. Composes {@link useConnection}, {@link useSyncQueue}, and
 * a slice of {@link useSyncStatus} into a single reactive object so a single
 * subscription drives all three.
 *
 * @returns a {@link UseOfflineQueueResult}.
 *
 * @example
 * const { isWaitingForConnection, items, enqueue } = useOfflineQueue();
 *
 * @public
 */
export function useOfflineQueue(): UseOfflineQueueResult {
  const [size, setSize] = useState(0);
  const [items, setItems] = useState<SyncItem[]>([]);
  const [isSyncing, setIsSyncing] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>(
    ConnectionStatus.UNKNOWN
  );
  const [connectionType, setConnectionType] = useState<ConnectionType>(
    ConnectionType.UNKNOWN
  );
  const [error, setError] = useState<SyncError | null>(null);
  const cancelledRef = useRef(false);

  const refreshQueue = useCallback(async () => {
    if (!isNativeModuleAvailable()) return;
    try {
      const [nextSize, nextItems] = await Promise.all([
        getQueueSize(),
        getPendingItems(),
      ]);
      if (cancelledRef.current) return;
      setSize(nextSize);
      setItems(nextItems);
    } catch (err) {
      if (cancelledRef.current) return;
      setError(
        err instanceof SyncError
          ? err
          : new SyncError(
              SyncErrorCode.INVALID_PAYLOAD,
              'Failed to refresh offline queue.',
              err
            )
      );
    }
  }, []);

  const refreshConnection = useCallback(async () => {
    if (!isNativeModuleAvailable()) return;
    try {
      const snap = await getConnectionStatus();
      if (cancelledRef.current) return;
      setConnectionStatus(snap.status);
      setConnectionType(snap.type);
    } catch {
      // ignore — keep last known
    }
  }, []);

  useEffect(() => {
    cancelledRef.current = false;
    if (!isNativeModuleAvailable()) return undefined;

    let subscriptionId: string | null = null;

    void refreshQueue();
    void refreshConnection();
    facadeIsSyncing()
      .then((b) => {
        if (!cancelledRef.current) setIsSyncing(b);
      })
      .catch(() => undefined);

    (async () => {
      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          if (QUEUE_REFRESH_EVENTS.has(event.type)) void refreshQueue();
          if (event.type === SyncEventType.SYNC_STARTED) setIsSyncing(true);
          if (
            event.type === SyncEventType.SYNC_SUCCEEDED ||
            event.type === SyncEventType.SYNC_FAILED
          ) {
            setIsSyncing(false);
          }
          if (event.type === SyncEventType.CONNECTION_CHANGED) {
            void refreshConnection();
          }
        });
      } catch {
        // ignore
      }
    })();

    return () => {
      cancelledRef.current = true;
      if (subscriptionId !== null) {
        void removeSyncEventListener(subscriptionId).catch(() => undefined);
      }
    };
  }, [refreshQueue, refreshConnection]);

  const isOnline =
    connectionStatus === ConnectionStatus.CONNECTED ||
    connectionStatus === ConnectionStatus.METERED;
  const isMetered = connectionStatus === ConnectionStatus.METERED;

  const connection = {
    status: connectionStatus,
    type: connectionType,
    isOnline,
    isMetered,
  };

  const isWaitingForConnection = size > 0 && !isOnline;

  const enqueue = useCallback(async (input: SyncItemInput) => {
    return facadeEnqueue(input);
  }, []);

  const flush = useCallback(async () => {
    return facadeFlush();
  }, []);

  return {
    connection,
    size,
    items,
    isSyncing,
    isWaitingForConnection,
    error,
    enqueue,
    flush,
  };
}
