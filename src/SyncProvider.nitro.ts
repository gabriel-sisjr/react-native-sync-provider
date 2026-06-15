/**
 * SyncProvider — Nitro Module bridge spec.
 *
 * THIS FILE IS THE SOURCE OF TRUTH FOR THE JS↔NATIVE BRIDGE CONTRACT.
 *
 * Any mutation to this file is a coordinated event:
 *   1. Edit the interface below.
 *   2. Run `yarn nitrogen` to regenerate the C++/Swift/Kotlin spec base
 *      classes under `nitrogen/` (gitignored).
 *   3. Update the Swift implementation at `ios/SyncProvider.swift`.
 *   4. Update the Kotlin implementation at
 *      `android/src/main/java/com/margelo/nitro/syncprovider/SyncProvider.kt`.
 *   5. Update the JS facade in `src/index.tsx` (the entire facade lives
 *      there) and the web fallback in `src/index.web.tsx`. The platform
 *      split is at the entry level via the `"browser"` exports condition —
 *      there is NO per-method `.native.tsx` / `.tsx` split.
 *
 * Hard rules for this file:
 *   - Type-only imports (`import type ...`) — no runtime values.
 *   - No classes, no enums, no `export const`, no functions, no constants.
 *   - Only the `SyncProvider` interface and its TSDoc.
 *   - Method signatures must use Nitro-supported types: primitives, flat
 *     records, `Array<T>`, `Promise<T>`, and string-literal unions.
 *
 * @see {@link https://nitro.margelo.com} Nitro Modules documentation.
 */

import type { HybridObject } from 'react-native-nitro-modules';

import type { SyncErrorCode } from './errors';
import type {
  BackgroundSyncOptions,
  ConnectionState,
  SyncEvent,
  SyncItem,
  SyncItemInput,
  SyncOptions,
  SyncResult,
} from './types';

// `SyncErrorCode` is referenced in TSDoc `@throws` blocks below — keep the
// import for documentation-as-code linkage and to surface the symbol to
// downstream tooling that walks `import type` graphs.
export type { SyncErrorCode };

/**
 * The native HybridObject contract for the SyncProvider library.
 *
 * Implementations:
 *   - iOS / Swift   → `final class HybridSyncProvider: HybridSyncProviderSpec`
 *     (`ios/SyncProvider.swift`) — named `HybridSyncProvider` (not
 *     `SyncProvider`) to avoid a symbol collision with the C++ `SyncProvider`
 *     class Nitro generates.
 *   - Android / Kotlin → `class SyncProvider : HybridSyncProviderSpec()` (`android/`).
 */
export interface SyncProvider extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  /* ------------------------------ Queue ops ----------------------------- */

  /**
   * Enqueue a single HTTP request for asynchronous, offline-tolerant
   * dispatch. The native layer assigns a ULID and returns it immediately;
   * the request is persisted before the promise resolves.
   *
   * @param item — request to enqueue. The `id` and `createdAt` fields are
   *   intentionally omitted from the input — both are assigned by native.
   * @returns the ULID assigned to the new item.
   * @throws SyncError with code `QUEUE_FULL` when the queue is at
   *   `SyncOptions.maxQueueSize`.
   * @throws SyncError with code `INVALID_PAYLOAD` when the item shape is
   *   malformed.
   * @throws SyncError with code `INVALID_URL` when `url` is not a valid
   *   absolute http(s) URL.
   *
   * @example
   * const id = await sync.enqueue({
   *   method: 'POST',
   *   url: 'https://api.example.com/events',
   *   contentType: 'application/json',
   *   body: JSON.stringify({ kind: 'login' }),
   * });
   */
  enqueue(item: SyncItemInput): Promise<string>;

  /**
   * Enqueue a batch of HTTP requests atomically. Either every item is
   * persisted (and the corresponding ULIDs are returned in input order), or
   * the operation throws and no items are added.
   *
   * @param items — requests to enqueue, in dispatch order.
   * @returns the assigned ULIDs, parallel to `items`.
   * @throws SyncError with code `QUEUE_FULL` when accepting `items.length`
   *   would exceed `SyncOptions.maxQueueSize`.
   * @throws SyncError with code `INVALID_PAYLOAD` when any item is
   *   malformed.
   * @throws SyncError with code `INVALID_URL` when any item carries an
   *   invalid URL.
   *
   * @example
   * const ids = await sync.enqueueBatch([
   *   { method: 'POST', url: 'https://api.example.com/a' },
   *   { method: 'POST', url: 'https://api.example.com/b' },
   * ]);
   */
  enqueueBatch(items: SyncItemInput[]): Promise<string[]>;

  /**
   * Remove a single item from the queue by ULID.
   *
   * @param id — the ULID returned by `enqueue` / `enqueueBatch`.
   * @returns `true` if the item was found and removed, `false` if no item
   *   with that id exists in the queue.
   *
   * @example
   * const removed = await sync.removeItem(id);
   * if (!removed) console.warn('already drained');
   */
  removeItem(id: string): Promise<boolean>;

  /**
   * Remove every pending item from the queue.
   *
   * @example
   * await sync.clearQueue();
   */
  clearQueue(): Promise<void>;

  /**
   * Get the current pending-item count.
   *
   * @returns the number of items currently waiting to be dispatched.
   *
   * @example
   * if ((await sync.getQueueSize()) > 0) await sync.flush();
   */
  getQueueSize(): Promise<number>;

  /**
   * Read every pending item from the queue.
   *
   * @returns the pending items in dispatch order, with `id` and `createdAt`
   *   populated.
   *
   * @example
   * const pending = await sync.getPendingItems();
   * console.log(`${pending.length} item(s) pending`);
   */
  getPendingItems(): Promise<SyncItem[]>;

  /* ------------------------------ Sync ops ------------------------------ */

  /**
   * Force a flush cycle, dispatching every pending item according to the
   * configured strategy and retry policy.
   *
   * @returns the outcome of the cycle.
   * @throws SyncError with code `NETWORK_ERROR` when the cycle aborts due to
   *   a transport failure.
   * @throws SyncError with code `SERVER_ERROR` when the cycle aborts due to
   *   an unrecoverable server response.
   * @throws SyncError with code `TIMEOUT` when individual requests exceed
   *   `SyncOptions.requestTimeoutMs` and the cycle aborts.
   * @throws SyncError with code `MAX_ATTEMPTS_EXCEEDED` when items exhaust
   *   their retry budget. Per-item failures are also surfaced via
   *   {@link SyncResult.errors}.
   * @throws SyncError with code `UNAUTHORIZED` on 401/403 responses.
   *
   * @example
   * const result = await sync.flush();
   * console.log(`${result.successCount} ok / ${result.failureCount} failed`);
   */
  flush(): Promise<SyncResult>;

  /**
   * Pause the sync engine. While paused, the queue still accepts enqueues
   * but no flush cycles are scheduled (manual or otherwise).
   *
   * @example
   * await sync.pauseSync();
   */
  pauseSync(): Promise<void>;

  /**
   * Resume the sync engine after `pauseSync()`. If the strategy is
   * `AUTOMATIC` and items are pending, a flush is scheduled.
   *
   * @example
   * await sync.resumeSync();
   */
  resumeSync(): Promise<void>;

  /**
   * Check whether a flush cycle is currently in progress.
   *
   * @returns `true` while a cycle is in flight, `false` otherwise.
   *
   * @example
   * if (!(await sync.isSyncing())) await sync.flush();
   */
  isSyncing(): Promise<boolean>;

  /* ----------------------------- Config ops ----------------------------- */

  /**
   * Replace the active sync configuration. The new options take effect
   * starting with the next flush cycle (in-flight requests honor the
   * previous config).
   *
   * @param options — new library-wide configuration.
   *
   * @example
   * await sync.configureSync({
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
   */
  configureSync(options: SyncOptions): Promise<void>;

  /**
   * Read the active sync configuration.
   *
   * @returns the currently active {@link SyncOptions}.
   *
   * @example
   * const cfg = await sync.getSyncConfig();
   */
  getSyncConfig(): Promise<SyncOptions>;

  /* ----------------------------- History ops ---------------------------- */

  /**
   * Read the most recent flush outcome from native-persisted history.
   *
   * @returns the most recent {@link SyncResult}, or a sentinel result with
   *   `startedAt === 0` and `finishedAt === 0` when no flush has run yet.
   *
   * @remarks
   * Nitro codegen does not currently model `T | undefined` returns; this
   * method therefore returns a sentinel `SyncResult` instead of `undefined`.
   * The JS facade is responsible for translating the sentinel back to
   * `undefined` for consumer code.
   *
   * @example
   * const last = await sync.getLastSyncResult();
   * if (last.startedAt === 0) console.log('no flush yet');
   */
  getLastSyncResult(): Promise<SyncResult>;

  /**
   * Read the persisted flush history, most recent first.
   *
   * @param limit — maximum number of entries to return. `0` means
   *   "no limit". Defaults to `0` on the native side when not supplied.
   * @returns the persisted flush results, most recent first.
   *
   * @remarks
   * Nitro codegen does not currently model optional primitive parameters in
   * a way that round-trips cleanly across both platforms; the parameter is
   * therefore declared as a required `number` with `0` as the conventional
   * "no limit" sentinel. The JS facade exposes a true optional argument and
   * passes `0` through when omitted.
   *
   * @example
   * const recent = await sync.getSyncHistory(20);
   */
  getSyncHistory(limit: number): Promise<SyncResult[]>;

  /**
   * Clear the persisted flush history. Does not affect the pending queue.
   *
   * @example
   * await sync.clearSyncHistory();
   */
  clearSyncHistory(): Promise<void>;

  /* ----------------------------- Connection ----------------------------- */

  /**
   * Read the current connectivity snapshot.
   *
   * @returns the live {@link ConnectionState} as reported by the OS.
   *
   * @example
   * const conn = await sync.getConnectionStatus();
   * if (conn.status === 'CONNECTED') await sync.flush();
   */
  getConnectionStatus(): Promise<ConnectionState>;

  /* -------------------------- Background sync --------------------------- */

  /**
   * Register the OS-level background sync task and start honoring it.
   *
   * @param options — constraints applied to the OS-scheduled task.
   * @throws SyncError with code `BACKGROUND_TASK_REGISTRATION_FAILED` when
   *   the OS rejects the registration (missing `Info.plist` entry on iOS,
   *   missing manifest permissions on Android, etc.).
   * @throws SyncError with code `NATIVE_MODULE_UNAVAILABLE` when the
   *   underlying `BGTaskScheduler` / `WorkManager` is not available.
   *
   * @example
   * await sync.enableBackgroundSync({
   *   minimumIntervalMs: 15 * 60 * 1000,
   *   requiresUnmeteredNetwork: true,
   * });
   */
  enableBackgroundSync(options: BackgroundSyncOptions): Promise<void>;

  /**
   * Cancel the OS-level background sync task registration.
   *
   * @example
   * await sync.disableBackgroundSync();
   */
  disableBackgroundSync(): Promise<void>;

  /**
   * Check whether the OS-level background sync task is currently registered.
   *
   * @returns `true` when a registration is active, `false` otherwise.
   *
   * @example
   * const enabled = await sync.isBackgroundSyncEnabled();
   */
  isBackgroundSyncEnabled(): Promise<boolean>;

  /* ------------------------------- Events ------------------------------- */

  /**
   * Subscribe to sync events.
   *
   * For now the only supported channel name is `"sync-event"`. The parameter
   * is typed as `string` (not as a string literal) because Nitrogen rejects
   * single-literal string parameters as ambiguous between a `string` and a
   * discriminating-union enum at the C++ boundary. The native layer MUST
   * validate the channel and throw on unknown values.
   *
   * @param event — channel name. Pass `"sync-event"`. Other values throw
   *   {@link SyncErrorCode.INVALID_PAYLOAD}.
   * @param callback — invoked on the JS thread for every emitted event.
   * @returns a subscription id that must be passed to `removeListener` to
   *   unsubscribe.
   *
   * @example
   * const sub = await sync.addListener('sync-event', (e) => {
   *   if (e.type === 'ITEM_FAILED') console.warn(e.errorCode, e.itemId);
   * });
   * // ...later
   * await sync.removeListener('sync-event', sub);
   */
  addListener(
    event: string,
    callback: (event: SyncEvent) => void
  ): Promise<string>;

  /**
   * Unsubscribe a previously registered listener.
   *
   * @param event — channel name used at subscription time (e.g.
   *   `"sync-event"`). See the channel-name caveat on {@link addListener}.
   * @param subscriptionId — the id returned from `addListener`.
   *
   * @example
   * await sync.removeListener('sync-event', subscriptionId);
   */
  removeListener(event: string, subscriptionId: string): Promise<void>;
}
