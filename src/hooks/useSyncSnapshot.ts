import { useCallback, useEffect, useRef, useState } from 'react';

import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import {
  ConnectionStatus,
  ConnectionType,
  SyncEventType,
} from '../types/enums';
import type { UseSyncSnapshotResult } from '../types/hooks';
import type { SyncEvent, SyncItem, SyncResult } from '../types/sync';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import {
  addSyncEventListener,
  getConnectionStatus,
  getLastSyncResult,
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
 * Read-only dashboard aggregator that combines connection, queue, and
 * sync-engine state into a single reactive snapshot.
 *
 * Unlike {@link useOfflineQueue}, this hook exposes NO action methods — it is a
 * pure observer intended for status dashboards, badges, and "what is the sync
 * engine doing right now?" UIs.
 *
 * @remarks
 * **Single-subscription design.** Exactly ONE `addSyncEventListener` drives
 * every field. Each native event triggers at most a bounded number of
 * `setState` calls within a single callback, which React 18 auto-batches into
 * one render — so a burst of events never causes a per-field re-render storm.
 * The hook performs its initial fetches on mount, guards every async `setState`
 * with a `cancelledRef`, and removes the listener on unmount.
 *
 * **No selector (Q6).** The hook takes no parameters and always returns the
 * full snapshot object. Consumers that need fine-grained re-render control can
 * memoize derived values themselves.
 *
 * **Forward fields.** `networkQuality` (Block B / Gap #2) and `deadLetter`
 * (Block B / Gap #4) are typed-optional and ALWAYS `undefined` in v0.2; real
 * values arrive with the v0.3 classifier / dead-letter store.
 *
 * Safe to call before the native module loads — it returns inert state and
 * never throws on render.
 *
 * @returns {UseSyncSnapshotResult} the aggregated, read-only sync snapshot.
 *
 * @example
 * const { size, isSyncing, connection, isWaitingForConnection } =
 *   useSyncSnapshot();
 * if (isWaitingForConnection) {
 *   return <Badge>{size} item(s) waiting for connection…</Badge>;
 * }
 *
 * @public
 */
export function useSyncSnapshot(): UseSyncSnapshotResult {
  const [size, setSize] = useState(0);
  const [items, setItems] = useState<SyncItem[]>([]);
  const [isSyncing, setIsSyncing] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [progress, setProgress] = useState<number | null>(null);
  const [lastResult, setLastResult] = useState<SyncResult | null>(null);
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
              'Failed to refresh sync snapshot queue.',
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
      // Best-effort: connection is secondary state — swallow and keep
      // last-known value (queue refresh is the primary path that surfaces
      // errors into `error`).
    }
  }, []);

  const refreshLastResult = useCallback(async () => {
    if (!isNativeModuleAvailable()) return;
    try {
      const res = await getLastSyncResult();
      if (cancelledRef.current || res === undefined) return;
      setLastResult(res);
    } catch {
      // Best-effort: lastResult is secondary state — swallow and keep
      // last-known value (queue refresh is the primary path that surfaces
      // errors into `error`).
    }
  }, []);

  useEffect(() => {
    cancelledRef.current = false;
    if (!isNativeModuleAvailable()) return undefined;

    let subscriptionId: string | null = null;

    void refreshQueue();
    void refreshConnection();
    void refreshLastResult();
    facadeIsSyncing()
      .then((b) => {
        if (!cancelledRef.current) setIsSyncing(b);
      })
      .catch(() => undefined);

    (async () => {
      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          if (QUEUE_REFRESH_EVENTS.has(event.type)) void refreshQueue();
          switch (event.type) {
            case SyncEventType.SYNC_STARTED:
              setIsSyncing(true);
              setProgress(0);
              break;
            case SyncEventType.SYNC_PROGRESS:
              if (
                typeof event.progress === 'number' &&
                Number.isFinite(event.progress)
              ) {
                setProgress(event.progress);
              }
              break;
            case SyncEventType.SYNC_SUCCEEDED:
            case SyncEventType.SYNC_FAILED:
              setIsSyncing(false);
              setProgress(null);
              void refreshLastResult();
              break;
            case SyncEventType.PAUSED:
              setIsPaused(true);
              break;
            case SyncEventType.RESUMED:
              setIsPaused(false);
              break;
            case SyncEventType.CONNECTION_CHANGED:
              void refreshConnection();
              break;
            default:
              break;
          }
        });
      } catch {
        // Subscription failure is non-fatal — initial fetches still apply.
      }
    })();

    return () => {
      cancelledRef.current = true;
      if (subscriptionId !== null) {
        void removeSyncEventListener(subscriptionId).catch(() => undefined);
      }
    };
  }, [refreshQueue, refreshConnection, refreshLastResult]);

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

  return {
    connection,
    size,
    items,
    isSyncing,
    isPaused,
    progress,
    lastResult,
    isWaitingForConnection,
    error,
  };
}
