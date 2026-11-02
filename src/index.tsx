/**
 * @gabriel-sisjr/react-native-sync-provider — public entry point.
 *
 * Offline-first HTTP sync for React Native, powered by Nitro Modules. The
 * library persists outbound HTTP requests on the native side (Core Data on
 * iOS, Room on Android), dispatches them on a flush, and can flush while the app
 * is not running (BGTaskScheduler / WorkManager).
 *
 * This module re-exports the entire public surface:
 *
 *   - 21 facade functions: 19 wrap the Nitro `SyncProvider` HybridObject, plus
 *     `drainUntilEmpty` and `cancelDrain`, which are JS-side.
 *   - 2 listener helpers (`addSyncEventListener`, `removeSyncEventListener`).
 *   - 9 React hooks (`useConnection`, `useSyncQueue`, `useSyncStatus`,
 *     `useOfflineQueue`, `useSyncEvents`, `useSyncConfig`, `useAutoSync`,
 *     `useSyncSnapshot`, `useSyncContext`).
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
import type { ValidationIssue } from './errors/ValidationIssue';
import type {
  BackgroundSyncOptions,
  SyncEvent,
  SyncItem,
  SyncItemInput,
  SyncOptions,
  SyncResult,
} from './types/sync';
import type { ConnectionState } from './types/connection';
import type { DrainOptions, DrainResult } from './types/drain';
import { assertNativeModuleAvailable } from './utils/assertNativeModuleAvailable';
import { cancelActiveDrain, runDrain } from './utils/drainController';
import { wrapNativeError } from './utils/errors';
import { getNativeSyncProvider } from './utils/isNativeModuleAvailable';
import {
  collectSyncItemIssues,
  validateSyncItem,
} from './utils/validateSyncItem';

/* -------------------------------------------------------------------------- */
/*                                  Re-exports                                */
/* -------------------------------------------------------------------------- */

export * from './types';
export { SyncError } from './errors/SyncError';
export { SyncErrorCode } from './errors/SyncErrorCode';
export type { ValidationIssue } from './errors/ValidationIssue';

export { isNativeModuleAvailable, generateId } from './utils';

export {
  useAutoSync,
  useConnection,
  useOfflineQueue,
  useSyncConfig,
  useSyncContext,
  useSyncEvents,
  useSyncQueue,
  useSyncSnapshot,
  useSyncStatus,
} from './hooks';
export type { UseAutoSyncExtraOptions } from './hooks';

export { SyncProvider, SyncProviderContext } from './contexts/SyncProvider';
export type {
  SyncProviderContextValue,
  SyncProviderProps,
} from './contexts/SyncProvider';

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
 * persisted. Enqueueing does not dispatch the item: that takes a flush, from
 * `flush()`, `useAutoSync`, or an OS background window.
 *
 * @param item — request to enqueue (no `id` / `createdAt` — assigned by native).
 * @returns the ULID assigned to the new item.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_URL} when `url` is not a
 *   valid absolute http(s) URL.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_PAYLOAD} when any other
 *   field in `item` is malformed, with `validationErrors` set. Also, with no
 *   `validationErrors`, when native rejects the call. That includes a queue at
 *   `SyncOptions.maxQueueSize`: native reports `QUEUE_FULL`, but native codes do
 *   not reach JS yet.
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
 *   malformed, including an invalid URL. Unlike {@link enqueue}, a batch always
 *   reports one umbrella code; read {@link SyncError.validationErrors} for the
 *   per-field detail. Also, with no `validationErrors`, when native rejects the
 *   call. That includes a batch that would exceed `SyncOptions.maxQueueSize`:
 *   native reports `QUEUE_FULL`, but native codes do not reach JS yet.
 *
 * @remarks
 * Validation aggregates across the whole batch: instead of aborting on the
 * first malformed item, every issue from every item is collected and surfaced
 * together via {@link SyncError.validationErrors}, each tagged with its
 * `itemIndex`. No items are enqueued when any validation issue is present.
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
  const allIssues: ValidationIssue[] = [];
  // `entries()` visits array holes, which `forEach` would silently skip.
  for (const [index, item] of items.entries()) {
    for (const issue of collectSyncItemIssues(item)) {
      allIssues.push({ ...issue, itemIndex: index });
    }
  }
  if (allIssues.length > 0) {
    const itemCount = new Set(allIssues.map((issue) => issue.itemIndex)).size;
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      `enqueueBatch validation failed: ${allIssues.length} issue(s) across ${itemCount} item(s).`,
      undefined,
      allIssues
    );
  }
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
 * Remove every item from the queue, in any status: pending, in flight, or failed
 * permanently. Does not affect the sync history.
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
 * @returns the number of items currently waiting to be dispatched: `PENDING`
 *   items, including those waiting out a retry cooldown. Items in flight and
 *   items that failed permanently are not counted.
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
 * @returns the `PENDING` items in dispatch order, including those waiting out
 *   a retry cooldown, with `id` and `createdAt` populated by the native layer.
 *   Items in flight and items that failed permanently are not returned.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @remarks
 * Each item's `attempts` and `nextRetryAt` are returned **verbatim** from the
 * native layer — the facade performs **no** sentinel translation on a
 * {@link SyncItem}. This is deliberately asymmetric with
 * {@link getLastSyncResult}, which maps the native sentinel
 * (`startedAt === 0 && finishedAt === 0`) to `undefined` via `isLastSyncSentinel`.
 * For a `SyncItem`, `attempts === 0` ("never tried") and `nextRetryAt === 0`
 * ("never attempted, eligible now") are real values — see
 * {@link SyncItem.attempts} and {@link SyncItem.nextRetryAt}.
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
 * Run one flush cycle: dispatch one batch of eligible items, up to
 * `SyncOptions.batchSize`, applying the retry policy. Items still cooling down
 * are skipped. The strategy plays no part, so this also works under `MANUAL`.
 *
 * @returns the outcome of the cycle. Per-item failures never reject: read them
 *   from the result and from `ITEM_FAILED` events.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.NETWORK_ERROR} when the cycle itself
 *   dies, for example because the store fails. It has nothing to do with the
 *   network in that case: every native rejection maps to this code.
 *
 * @remarks
 * A cycle with nothing eligible (the queue is empty, or every item is cooling
 * down) resolves with a 0/0 result and ends with `SYNC_SUCCEEDED`.
 *
 * `flush()` does not check connectivity first, so calling it offline can spend
 * retry attempts. {@link drainUntilEmpty} waits for the network instead, and
 * `useAutoSync` skips its interval and foreground flushes while `DISCONNECTED`.
 *
 * While a cycle is running, iOS returns the last result at once, and Android
 * waits and then runs its own cycle.
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
 * Flush the queue repeatedly until it is empty or a bound stops the loop.
 *
 * A JS-side loop over the existing bridge. It reports `EMPTY` only when
 * {@link getQueueSize} is `0` and {@link isSyncing} is `false`, so an item still
 * in flight in a running cycle keeps the loop waiting.
 *
 * The loop waits rather than spins, and these waits do not use up iterations:
 *
 * - While a cycle is running, it polls {@link isSyncing}.
 * - After a cycle that made no progress with items cooling down, it sleeps until
 *   the soonest `nextRetryAt`. If that moment is at or past the deadline, it
 *   stops at once with `TIMEOUT`.
 * - After a cycle that made no progress with nothing cooling (the engine is
 *   paused, for example), it sleeps for at least 250 ms.
 * - While the device is offline, it does not flush at all, so no retry attempt
 *   is spent. It waits for the network until a bound stops it.
 *
 * `timeoutMs` and {@link cancelDrain} are hard stops. Every wait ends at the
 * deadline or on cancel, including a `flush()` still in flight, and the single
 * drain lock is released on every exit. A native cycle that is already running
 * is not aborted: it keeps going after the drain returns.
 *
 * Only one drain may run per process; a concurrent call throws
 * {@link SyncErrorCode.DRAIN_ALREADY_ACTIVE}. Await the previous drain before
 * starting another. That applies after {@link cancelDrain} too, because a
 * cancelled drain still has to settle.
 *
 * @param options — optional {@link DrainOptions} bounds.
 * @returns a {@link DrainResult} describing how the loop ended.
 * @throws {SyncError} {@link SyncErrorCode.DRAIN_ALREADY_ACTIVE} when a drain
 *   is already in progress for this process.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when the
 *   native module is not loaded.
 * @throws {SyncError} any error from an underlying native call — see the
 *   rejection note below.
 *
 * @remarks
 * **Rejections:** if a `flush()` cycle or any other native call in the loop
 * rejects, the loop stops, the lock is released and the error is re-thrown, so
 * no {@link DrainResult} is returned. Items dispatched before the failure stay
 * dispatched: re-read {@link getQueueSize} / {@link getPendingItems} for the
 * remaining queue.
 *
 * @example
 * const result = await drainUntilEmpty({ maxIterations: 10 });
 * if (result.stoppedReason !== 'EMPTY') {
 *   console.warn(`stopped early: ${result.stoppedReason}, ${result.queueRemaining} left`);
 * }
 *
 * @public
 */
export async function drainUntilEmpty(
  options?: DrainOptions
): Promise<DrainResult> {
  return runDrain(
    { flush, getConnectionStatus, getPendingItems, getQueueSize, isSyncing },
    options
  );
}

/**
 * Cancel the in-progress {@link drainUntilEmpty} loop.
 *
 * Takes effect at once: any wait in the loop ends, including a `flush()` still
 * in flight, and the drain resolves with `stoppedReason: 'CANCELLED'`. A native
 * cycle that is already running is not aborted.
 *
 * The drain settles asynchronously, so a drain started in the same tick still
 * throws {@link SyncErrorCode.DRAIN_ALREADY_ACTIVE}: await the cancelled drain
 * first.
 *
 * When no drain is active this is a harmless no-op, so it is safe to call
 * unconditionally from cleanup paths (effect teardown, screen unmount, etc.). It
 * never throws and performs no native call.
 *
 * @example
 * // A remount can overlap a drain that is still settling, and StrictMode does
 * // that on every mount. Chain on the previous drain instead of racing it.
 * const previousDrain = useRef<Promise<unknown>>(Promise.resolve());
 *
 * useEffect(() => {
 *   const drain = previousDrain.current.then(() => drainUntilEmpty());
 *   previousDrain.current = drain.catch(() => undefined);
 *   drain.catch(reportError);
 *   return () => cancelDrain();
 * }, []);
 *
 * @public
 */
export function cancelDrain(): void {
  cancelActiveDrain();
}

/**
 * Pause the sync engine. While paused, `enqueue`/`enqueueBatch` continue to
 * accept items but no flush cycles run: `flush()` resolves with an empty result
 * and the retry timer is cancelled.
 *
 * The pause is persisted, so background workers stay inert while the app is
 * dead. It lasts until {@link resumeSync} or the next launch that reaches the
 * library, including a JS reload. A launch clears it without emitting `RESUMED`.
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
 * Resume the sync engine after {@link pauseSync}.
 *
 * Unless the strategy is `MANUAL`, the engine then schedules a flush for the
 * soonest pending item: items with no cooldown left are dispatched right away,
 * and cooling items when their cooldown ends. If the device is offline at that
 * moment, the flush waits until connectivity is restored.
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
 * Replace the active sync configuration. Android applies the new options from
 * the next flush cycle. iOS applies them from the next item, even inside a
 * running cycle. A request already in flight keeps the previous config.
 *
 * @param options — new library-wide configuration.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_PAYLOAD} when native rejects
 *   the call.
 *
 * @remarks
 * The options are persisted, so a background worker started without JS uses
 * them too. `defaultHeaders` are encrypted at rest. Calling `configureSync`
 * without `defaultHeaders` deletes the stored copy, which makes it the way to
 * clear credentials on logout. See {@link SyncOptions.defaultHeaders}.
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
 * @remarks Set `options.foregroundOnly` to `true` to suppress all OS-scheduled
 *   background work while preserving launch/boot data-integrity recovery — see
 *   {@link BackgroundSyncOptions.foregroundOnly}.
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
 * Check whether background sync is enabled.
 *
 * @returns `true` after {@link enableBackgroundSync} until
 *   {@link disableBackgroundSync}. This reflects the consumer's intent, not OS
 *   state: with `foregroundOnly: true` it returns `true` although no OS work is
 *   scheduled.
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
