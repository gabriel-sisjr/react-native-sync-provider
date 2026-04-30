import { useCallback, useEffect, useRef, useState } from 'react';

import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import { SyncEventType } from '../types/enums';
import type { UseSyncQueueResult } from '../types/hooks';
import type { SyncEvent, SyncItem, SyncItemInput } from '../types/sync';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import {
  addSyncEventListener,
  clearQueue as facadeClearQueue,
  enqueue as facadeEnqueue,
  enqueueBatch as facadeEnqueueBatch,
  getPendingItems,
  getQueueSize,
  removeItem as facadeRemoveItem,
  removeSyncEventListener,
} from '../index';

const REFRESH_EVENTS: ReadonlySet<string> = new Set<string>([
  SyncEventType.ITEM_ENQUEUED,
  SyncEventType.ITEM_REMOVED,
  SyncEventType.QUEUE_CLEARED,
  SyncEventType.SYNC_SUCCEEDED,
  SyncEventType.SYNC_FAILED,
  SyncEventType.ITEM_SUCCEEDED,
  SyncEventType.ITEM_FAILED,
]);

/**
 * Manage the offline queue from a React component.
 *
 * Surfaces the live queue size + pending items, plus the canonical write
 * operations (`enqueue`, `enqueueBatch`, `removeItem`, `clearQueue`) so
 * components can drive the queue without going through the bare facade.
 *
 * The hook auto-refreshes whenever the native layer emits an event that may
 * have changed queue contents (`ITEM_ENQUEUED`, `ITEM_REMOVED`,
 * `QUEUE_CLEARED`, `SYNC_SUCCEEDED`, `SYNC_FAILED`, `ITEM_SUCCEEDED`,
 * `ITEM_FAILED`). It is safe to call before native loads — callbacks will
 * reject with {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE}.
 *
 * @returns a {@link UseSyncQueueResult}.
 *
 * @example
 * const { items, size, enqueue, clearQueue } = useSyncQueue();
 *
 * @public
 */
export function useSyncQueue(): UseSyncQueueResult {
  const [size, setSize] = useState(0);
  const [items, setItems] = useState<SyncItem[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<SyncError | null>(null);
  const cancelledRef = useRef(false);

  const refresh = useCallback(async () => {
    if (!isNativeModuleAvailable()) return;
    setIsLoading(true);
    try {
      const [nextSize, nextItems] = await Promise.all([
        getQueueSize(),
        getPendingItems(),
      ]);
      if (cancelledRef.current) return;
      setSize(nextSize);
      setItems(nextItems);
      setError(null);
    } catch (err) {
      if (cancelledRef.current) return;
      setError(
        err instanceof SyncError
          ? err
          : new SyncError(
              SyncErrorCode.INVALID_PAYLOAD,
              'Failed to refresh sync queue.',
              err
            )
      );
    } finally {
      if (!cancelledRef.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    cancelledRef.current = false;
    if (!isNativeModuleAvailable()) return undefined;

    let subscriptionId: string | null = null;

    void refresh();

    (async () => {
      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          if (REFRESH_EVENTS.has(event.type)) void refresh();
        });
      } catch {
        // Subscription failure is non-fatal — manual `refresh` still works.
      }
    })();

    return () => {
      cancelledRef.current = true;
      if (subscriptionId !== null) {
        void removeSyncEventListener(subscriptionId).catch(() => undefined);
      }
    };
  }, [refresh]);

  const enqueue = useCallback(async (input: SyncItemInput) => {
    return facadeEnqueue(input);
  }, []);

  const enqueueBatch = useCallback(async (inputs: SyncItemInput[]) => {
    return facadeEnqueueBatch(inputs);
  }, []);

  const removeItem = useCallback(async (id: string) => {
    return facadeRemoveItem(id);
  }, []);

  const clearQueue = useCallback(async () => {
    await facadeClearQueue();
  }, []);

  return {
    size,
    items,
    isLoading,
    error,
    enqueue,
    enqueueBatch,
    removeItem,
    clearQueue,
    refresh,
  };
}
