import { useCallback, useEffect, useRef, useState } from 'react';

import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import {
  ConnectionStatus,
  ConnectionType,
  NetworkQuality,
  SyncEventType,
} from '../types/enums';
import type { UseSyncSnapshotResult } from '../types/hooks';
import type {
  SyncDeadLetterItem,
  SyncEvent,
  SyncItem,
  SyncResult,
} from '../types/sync';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import {
  addSyncEventListener,
  getConnectionStatus,
  getLastSyncResult,
  getPendingItems,
  getQueueSize,
  isSyncing as facadeIsSyncing,
  listDeadLetter,
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
  // Dead-lettered items leave the pending queue.
  SyncEventType.ITEM_DEAD_LETTERED,
]);

/** Events after which the dead-letter queue may have changed. */
const DEAD_LETTER_REFRESH_EVENTS: ReadonlySet<string> = new Set<string>([
  // A failed item moved in.
  SyncEventType.ITEM_DEAD_LETTERED,
  // `discardDeadLetter()`, or `removeItem()` reaching a dead letter.
  SyncEventType.ITEM_REMOVED,
  // `replayDeadLetter()` moved one back to the queue.
  SyncEventType.ITEM_ENQUEUED,
  // `clearQueue()` deletes dead letters too.
  SyncEventType.QUEUE_CLEARED,
]);

/**
 * Whether a dead-letter read found the rows already shown. A dead letter is
 * never updated in place, so its id, the time it was moved and its attempt
 * bookkeeping identify it.
 */
function sameDeadLetters(
  shown: readonly SyncDeadLetterItem[] | undefined,
  read: readonly SyncDeadLetterItem[]
): boolean {
  if (shown === undefined || shown.length !== read.length) return false;
  return shown.every((item, index) => {
    const other = read[index];
    return (
      other !== undefined &&
      item.id === other.id &&
      item.deadLetteredAt === other.deadLetteredAt &&
      item.attempts === other.attempts &&
      item.lastAttemptAt === other.lastAttemptAt
    );
  });
}

/**
 * Read-only dashboard aggregator that combines connection, queue, dead-letter
 * and sync-engine state into a single reactive snapshot.
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
 * **No selector.** The hook takes no parameters and always returns the
 * full snapshot object. Consumers that need fine-grained re-render control can
 * memoize derived values themselves.
 *
 * **Network quality and dead letters.** `networkQuality` comes from the same
 * `getConnectionStatus()` read as `connection`, made on mount and on every
 * `CONNECTION_CHANGED`. `deadLetter` is read with `listDeadLetter()` on mount
 * and on `ITEM_DEAD_LETTERED`, `ITEM_REMOVED`, `ITEM_ENQUEUED` and
 * `QUEUE_CLEARED`. Both are `undefined` until their first read succeeds, and a
 * failed read keeps the last value. A dead-letter read that finds the list
 * already shown does not re-render, so an `ITEM_ENQUEUED` that leaves the
 * dead-letter queue as it was costs no extra render.
 *
 * Safe to call before the native module loads — it returns inert state and
 * never throws on render.
 *
 * @returns {UseSyncSnapshotResult} the aggregated, read-only sync snapshot.
 *
 * @example
 * const { size, isSyncing, connection, isWaitingForConnection, deadLetter } =
 *   useSyncSnapshot();
 * if (isWaitingForConnection) {
 *   return <Badge>{size} item(s) waiting for connection…</Badge>;
 * }
 * if (deadLetter !== undefined && deadLetter.length > 0) {
 *   return <Badge>{deadLetter.length} request(s) need attention</Badge>;
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
  const [networkQuality, setNetworkQuality] = useState<
    NetworkQuality | undefined
  >(undefined);
  const [deadLetter, setDeadLetter] = useState<
    readonly SyncDeadLetterItem[] | undefined
  >(undefined);
  const [error, setError] = useState<SyncError | null>(null);
  const cancelledRef = useRef(false);
  /** The dead-letter list last rendered, compared before every update. */
  const deadLetterRef = useRef<readonly SyncDeadLetterItem[] | undefined>(
    undefined
  );
  /** Only the latest dead-letter read may update the list. */
  const deadLetterReadRef = useRef(0);

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
      setNetworkQuality(snap.networkQuality);
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

  const refreshDeadLetter = useCallback(async () => {
    if (!isNativeModuleAvailable()) return;
    deadLetterReadRef.current += 1;
    const read = deadLetterReadRef.current;
    try {
      const next = await listDeadLetter();
      // A later read may have started meanwhile: let it decide.
      if (cancelledRef.current || read !== deadLetterReadRef.current) return;
      if (sameDeadLetters(deadLetterRef.current, next)) return;
      deadLetterRef.current = next;
      setDeadLetter(next);
    } catch {
      // Best-effort: the dead-letter list is secondary state — swallow and
      // keep the last-known list (queue refresh is the primary path that
      // surfaces errors into `error`).
    }
  }, []);

  useEffect(() => {
    cancelledRef.current = false;
    if (!isNativeModuleAvailable()) return undefined;

    let subscriptionId: string | null = null;

    void refreshQueue();
    void refreshConnection();
    void refreshLastResult();
    void refreshDeadLetter();
    facadeIsSyncing()
      .then((b) => {
        if (!cancelledRef.current) setIsSyncing(b);
      })
      .catch(() => undefined);

    (async () => {
      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          if (QUEUE_REFRESH_EVENTS.has(event.type)) void refreshQueue();
          if (DEAD_LETTER_REFRESH_EVENTS.has(event.type)) {
            void refreshDeadLetter();
          }
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
  }, [refreshQueue, refreshConnection, refreshLastResult, refreshDeadLetter]);

  const isOnline =
    connectionStatus === ConnectionStatus.CONNECTED ||
    connectionStatus === ConnectionStatus.METERED;
  const isMetered = connectionStatus === ConnectionStatus.METERED;

  const connection = {
    status: connectionStatus,
    type: connectionType,
    isOnline,
    isMetered,
    networkQuality: networkQuality ?? NetworkQuality.OFFLINE,
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
    networkQuality,
    deadLetter,
  };
}
