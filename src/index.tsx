/**
 * @gabriel-sisjr/react-native-sync-provider — public entry point.
 *
 * Offline-first HTTP sync for React Native, powered by Nitro Modules. The
 * library persists outbound HTTP requests on the native side (Core Data on
 * iOS, Room on Android), drains them when connectivity allows, and keeps
 * working when the app is killed (BGTaskScheduler / WorkManager).
 *
 * This module re-exports the entire public surface:
 *
 *   - 19 facade functions that wrap the Nitro `SyncProvider` HybridObject.
 *   - 2 listener helpers (`addSyncEventListener`, `removeSyncEventListener`).
 *   - 7 React hooks (`useConnection`, `useSyncQueue`, `useSyncStatus`,
 *     `useOfflineQueue`, `useSyncEvents`, `useSyncConfig`, `useAutoSync`).
 *   - The optional {@link SyncProvider | SyncProvider Context} component.
 *   - Every public type, enum, and the `SyncError` / `SyncErrorCode` pair.
 *   - The `generateId` helper (UUID v4, Math.random-backed).
 *
 * No module-level native call is made on import; the HybridObject is
 * resolved lazily on first use via {@link isNativeModuleAvailable}.
 *
 * @packageDocumentation
 */

import { SyncError } from './errors/SyncError';
import { SyncErrorCode } from './errors/SyncErrorCode';
import type {
  BackgroundSyncOptions,
  SyncEvent,
  SyncItem,
  SyncItemInput,
  SyncOptions,
  SyncResult,
} from './types/sync';
import type { ConnectionState } from './types/connection';
import { assertNativeModuleAvailable } from './utils/assertNativeModuleAvailable';
import { wrapNativeError } from './utils/errors';
import { getNativeSyncProvider } from './utils/isNativeModuleAvailable';
import { validateSyncItem } from './utils/validateSyncItem';

/* -------------------------------------------------------------------------- */
/*                                  Re-exports                                */
/* -------------------------------------------------------------------------- */

export * from './types';
export { SyncError } from './errors/SyncError';
export { SyncErrorCode } from './errors/SyncErrorCode';

export { isNativeModuleAvailable, generateId } from './utils';

export {
  useAutoSync,
  useConnection,
  useOfflineQueue,
  useSyncConfig,
  useSyncEvents,
  useSyncQueue,
  useSyncStatus,
} from './hooks';

export { SyncProvider, SyncProviderContext } from './contexts/SyncProvider';
export type { SyncProviderContextValue } from './contexts/SyncProvider';

/* -------------------------------------------------------------------------- */
/*                                  Internal                                  */
/* -------------------------------------------------------------------------- */

/**
 * Sentinel `SyncResult` returned by native when no flush has run yet.
 *
 * The Nitro spec returns a non-nullable `SyncResult`; the JS facade translates
 * `(startedAt === 0 && finishedAt === 0)` back into `undefined`. See the
 * remarks on `SyncProvider.getLastSyncResult` in the spec for the rationale.
 */
function isLastSyncSentinel(result: SyncResult): boolean {
  return result.startedAt === 0 && result.finishedAt === 0;
}

function nativeOrThrow(): ReturnType<typeof getNativeSyncProvider> {
  assertNativeModuleAvailable();
  // We just asserted availability; `getNativeSyncProvider()` is non-null.
  return getNativeSyncProvider();
}

/* -------------------------------------------------------------------------- */
/*                                  Queue ops                                 */
/* -------------------------------------------------------------------------- */

/**
 * Enqueue a single HTTP request for asynchronous, offline-tolerant dispatch.
 *
 * The native layer assigns a ULID and returns it once the item is durably
 * persisted. The function is fire-and-forget from the JS side — the queue
 * keeps draining whether the caller awaits this promise or not.
 *
 * @param item — request to enqueue (no `id` / `createdAt` — assigned by native).
 * @returns the ULID assigned to the new item.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_URL} when `url` is not a
 *   valid absolute http(s) URL.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_PAYLOAD} when any other
 *   field in `item` is malformed.
 * @throws {SyncError} {@link SyncErrorCode.QUEUE_FULL} when the queue is at
 *   `SyncOptions.maxQueueSize`.
 *
 * @example
 * const id = await enqueue({
 *   method: 'POST',
 *   url: 'https://api.example.com/events',
 *   contentType: 'application/json',
 *   body: JSON.stringify({ kind: 'login' }),
 * });
 *
 * @public
 */
export async function enqueue(item: SyncItemInput): Promise<string> {
  validateSyncItem(item);
  const native = nativeOrThrow();
  try {
    return await native!.enqueue(item);
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Atomically enqueue a batch of HTTP requests.
 *
 * Either every item is persisted (and a ULID is returned for each, in input
 * order), or the operation throws and no items are added.
 *
 * @param items — requests to enqueue, in dispatch order.
 * @returns the assigned ULIDs, parallel to `items`.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_PAYLOAD} when any item is
 *   malformed.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_URL} when any item has an
 *   invalid URL.
 * @throws {SyncError} {@link SyncErrorCode.QUEUE_FULL} when accepting
 *   `items.length` would exceed `SyncOptions.maxQueueSize`.
 *
 * @example
 * const ids = await enqueueBatch([
 *   { method: 'POST', url: 'https://api.example.com/a' },
 *   { method: 'POST', url: 'https://api.example.com/b' },
 * ]);
 *
 * @public
 */
export async function enqueueBatch(items: SyncItemInput[]): Promise<string[]> {
  if (!Array.isArray(items)) {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'enqueueBatch(items) requires an array of SyncItemInput.'
    );
  }
  for (const item of items) validateSyncItem(item);
  const native = nativeOrThrow();
  try {
    return await native!.enqueueBatch(items);
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Remove a single pending item by ULID.
 *
 * @param id — ULID returned by {@link enqueue} / {@link enqueueBatch}.
 * @returns `true` if the item was found and removed, `false` if no item with
 *   that id exists in the queue (already drained, removed, or never present).
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * const removed = await removeItem(id);
 * if (!removed) console.warn('already drained or unknown id');
 *
 * @public
 */
export async function removeItem(id: string): Promise<boolean> {
  const native = nativeOrThrow();
  try {
    return await native!.removeItem(id);
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Remove every pending item from the queue.
 *
 * @returns resolves once the queue is empty.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * await clearQueue();
 *
 * @public
 */
export async function clearQueue(): Promise<void> {
  const native = nativeOrThrow();
  try {
    await native!.clearQueue();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Read the current pending-item count.
 *
 * @returns the number of items currently waiting to be dispatched.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * if ((await getQueueSize()) > 0) await flush();
 *
 * @public
 */
export async function getQueueSize(): Promise<number> {
  const native = nativeOrThrow();
  try {
    return await native!.getQueueSize();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Read every pending item from the queue.
 *
 * @returns the pending items in dispatch order, with `id` and `createdAt`
 *   populated by the native layer.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * const pending = await getPendingItems();
 * console.log(`${pending.length} item(s) pending`);
 *
 * @public
 */
export async function getPendingItems(): Promise<SyncItem[]> {
  const native = nativeOrThrow();
  try {
    return await native!.getPendingItems();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/* -------------------------------------------------------------------------- */
/*                                  Sync ops                                  */
/* -------------------------------------------------------------------------- */

/**
 * Force a flush cycle, dispatching every pending item according to the active
 * strategy and retry policy.
 *
 * @returns the outcome of the cycle.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.NETWORK_ERROR} on transport
 *   failures aborting the cycle.
 * @throws {SyncError} {@link SyncErrorCode.SERVER_ERROR} on unrecoverable
 *   server responses aborting the cycle.
 * @throws {SyncError} {@link SyncErrorCode.TIMEOUT} when individual requests
 *   exceed `SyncOptions.requestTimeoutMs`.
 * @throws {SyncError} {@link SyncErrorCode.UNAUTHORIZED} on 401/403 responses.
 * @throws {SyncError} {@link SyncErrorCode.MAX_ATTEMPTS_EXCEEDED} when items
 *   exhaust their retry budget. Per-item failures are surfaced via
 *   `SyncResult.errors`.
 *
 * @example
 * const result = await flush();
 * console.log(`${result.successCount} ok / ${result.failureCount} failed`);
 *
 * @public
 */
export async function flush(): Promise<SyncResult> {
  const native = nativeOrThrow();
  try {
    return await native!.flush();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.NETWORK_ERROR);
  }
}

/**
 * Pause the sync engine. While paused, `enqueue`/`enqueueBatch` continue to
 * accept items but no flush cycles are scheduled.
 *
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * await pauseSync();
 *
 * @public
 */
export async function pauseSync(): Promise<void> {
  const native = nativeOrThrow();
  try {
    await native!.pauseSync();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Resume the sync engine after {@link pauseSync}. If the strategy is
 * `AUTOMATIC` and items are pending, a flush is scheduled.
 *
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * await resumeSync();
 *
 * @public
 */
export async function resumeSync(): Promise<void> {
  const native = nativeOrThrow();
  try {
    await native!.resumeSync();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Check whether a flush cycle is currently in progress.
 *
 * @returns `true` while a cycle is in flight, `false` otherwise.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * if (!(await isSyncing())) await flush();
 *
 * @public
 */
export async function isSyncing(): Promise<boolean> {
  const native = nativeOrThrow();
  try {
    return await native!.isSyncing();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/* -------------------------------------------------------------------------- */
/*                                 Config ops                                 */
/* -------------------------------------------------------------------------- */

/**
 * Replace the active sync configuration. The new options take effect starting
 * with the next flush cycle (in-flight requests honor the previous config).
 *
 * @param options — new library-wide configuration.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_PAYLOAD} when `options`
 *   is malformed.
 *
 * @example
 * await configureSync({
 *   strategy: 'AUTOMATIC',
 *   retryPolicy: {
 *     maxAttempts: 5,
 *     backoff: 'EXPONENTIAL',
 *     baseDelayMs: 1000,
 *     maxDelayMs: 60000,
 *     jitter: true,
 *     retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
 *   },
 * });
 *
 * @public
 */
export async function configureSync(options: SyncOptions): Promise<void> {
  const native = nativeOrThrow();
  try {
    await native!.configureSync(options);
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Read the active sync configuration from the native singleton.
 *
 * @returns the currently active {@link SyncOptions}.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * const cfg = await getSyncConfig();
 *
 * @public
 */
export async function getSyncConfig(): Promise<SyncOptions> {
  const native = nativeOrThrow();
  try {
    return await native!.getSyncConfig();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/* -------------------------------------------------------------------------- */
/*                                 History ops                                */
/* -------------------------------------------------------------------------- */

/**
 * Read the most recent flush outcome from native-persisted history.
 *
 * @returns the most recent {@link SyncResult}, or `undefined` when no flush
 *   has run yet (translated from the native sentinel
 *   `startedAt === 0 && finishedAt === 0`).
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * const last = await getLastSyncResult();
 * if (!last) console.log('no flush yet');
 *
 * @public
 */
export async function getLastSyncResult(): Promise<SyncResult | undefined> {
  const native = nativeOrThrow();
  try {
    const result = await native!.getLastSyncResult();
    return isLastSyncSentinel(result) ? undefined : result;
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Read the persisted flush history, most recent first.
 *
 * @param limit — optional maximum number of entries to return. When omitted,
 *   the facade passes `0` to the native side, which means "no limit".
 * @returns the persisted flush results, most recent first.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * const recent = await getSyncHistory(20);
 *
 * @public
 */
export async function getSyncHistory(limit?: number): Promise<SyncResult[]> {
  const native = nativeOrThrow();
  try {
    return await native!.getSyncHistory(limit ?? 0);
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Clear the persisted flush history. Does not affect the pending queue.
 *
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * await clearSyncHistory();
 *
 * @public
 */
export async function clearSyncHistory(): Promise<void> {
  const native = nativeOrThrow();
  try {
    await native!.clearSyncHistory();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/* -------------------------------------------------------------------------- */
/*                                 Connection                                 */
/* -------------------------------------------------------------------------- */

/**
 * Read the current connectivity snapshot from the native layer.
 *
 * @returns the live {@link ConnectionState} as reported by the OS.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * const conn = await getConnectionStatus();
 * if (conn.status === 'CONNECTED') await flush();
 *
 * @public
 */
export async function getConnectionStatus(): Promise<ConnectionState> {
  const native = nativeOrThrow();
  try {
    return await native!.getConnectionStatus();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/* -------------------------------------------------------------------------- */
/*                              Background sync                               */
/* -------------------------------------------------------------------------- */

/**
 * Register the OS-level background sync task and start honoring it.
 *
 * @param options — constraints applied to the OS-scheduled task.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.BACKGROUND_TASK_REGISTRATION_FAILED}
 *   when the OS rejects the registration.
 *
 * @example
 * await enableBackgroundSync({
 *   minimumIntervalMs: 15 * 60 * 1000,
 *   requiresUnmeteredNetwork: true,
 * });
 *
 * @public
 */
export async function enableBackgroundSync(
  options: BackgroundSyncOptions
): Promise<void> {
  const native = nativeOrThrow();
  try {
    await native!.enableBackgroundSync(options);
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.BACKGROUND_TASK_REGISTRATION_FAILED);
  }
}

/**
 * Cancel the OS-level background sync task registration.
 *
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * await disableBackgroundSync();
 *
 * @public
 */
export async function disableBackgroundSync(): Promise<void> {
  const native = nativeOrThrow();
  try {
    await native!.disableBackgroundSync();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.BACKGROUND_TASK_REGISTRATION_FAILED);
  }
}

/**
 * Check whether the OS-level background sync task is currently registered.
 *
 * @returns `true` when a registration is active, `false` otherwise.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * const enabled = await isBackgroundSyncEnabled();
 *
 * @public
 */
export async function isBackgroundSyncEnabled(): Promise<boolean> {
  const native = nativeOrThrow();
  try {
    return await native!.isBackgroundSyncEnabled();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.BACKGROUND_TASK_REGISTRATION_FAILED);
  }
}

/* -------------------------------------------------------------------------- */
/*                                  Listeners                                 */
/* -------------------------------------------------------------------------- */

/**
 * Channel name used by every native event the library emits.
 *
 * Exposed for advanced consumers who need to interact with the underlying
 * Nitro `addListener(event, cb)` directly. Most consumers should use
 * {@link addSyncEventListener} instead.
 *
 * @public
 */
export const SYNC_EVENT_CHANNEL = 'sync-event' as const;

/**
 * Subscribe to native sync events on the `'sync-event'` channel.
 *
 * Thin wrapper around the underlying `addListener('sync-event', cb)` Nitro
 * method that hides the channel name from the consumer.
 *
 * @param callback — invoked on the JS thread for every emitted event.
 * @returns a subscription id to be passed to {@link removeSyncEventListener}.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * const sub = await addSyncEventListener((e) => {
 *   if (e.type === 'ITEM_FAILED') console.warn(e.errorCode, e.itemId);
 * });
 * // later
 * await removeSyncEventListener(sub);
 *
 * @public
 */
export async function addSyncEventListener(
  callback: (event: SyncEvent) => void
): Promise<string> {
  const native = nativeOrThrow();
  try {
    return await native!.addListener(SYNC_EVENT_CHANNEL, callback);
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Unsubscribe a listener previously registered via
 * {@link addSyncEventListener}.
 *
 * @param subscriptionId — the id returned from {@link addSyncEventListener}.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @example
 * await removeSyncEventListener(sub);
 *
 * @public
 */
export async function removeSyncEventListener(
  subscriptionId: string
): Promise<void> {
  const native = nativeOrThrow();
  try {
    await native!.removeListener(SYNC_EVENT_CHANNEL, subscriptionId);
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}
