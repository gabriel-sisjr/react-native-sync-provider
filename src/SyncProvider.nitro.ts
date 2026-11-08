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
  SyncDeadLetterItem,
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
   * Remove a single item by ULID. When no queued item has that id, the dead
   * letter with that id is removed instead. Emits `ITEM_REMOVED` only when a
   * row was removed.
   *
   * A request already in flight is not cancelled and its outcome event is
   * still emitted, but the removed item never comes back: not to the queue,
   * and not as a dead letter after a permanent failure, so no
   * `ITEM_DEAD_LETTERED` follows its `ITEM_FAILED`.
   *
   * @param id — the ULID returned by `enqueue` / `enqueueBatch`.
   * @returns `true` if a queued item or a dead letter with that id was found
   *   and removed, `false` if neither exists.
   *
   * @example
   * const removed = await sync.removeItem(id);
   * if (!removed) console.warn('already drained');
   */
  removeItem(id: string): Promise<boolean>;

  /**
   * Remove every item from the queue, in any status (pending or in flight),
   * and every dead letter. Emits one `QUEUE_CLEARED`.
   *
   * Requests already in flight are not cancelled and their outcome events are
   * still emitted, but no cleared item comes back: not to the queue, and not
   * as a dead letter after a permanent failure, so no `ITEM_DEAD_LETTERED`
   * follows an `ITEM_FAILED` for one.
   *
   * @example
   * await sync.clearQueue();
   */
  clearQueue(): Promise<void>;

  /**
   * Get the current pending-item count.
   *
   * @returns the number of items currently waiting to be dispatched: `PENDING`
   *   items, including those waiting out a retry cooldown. Items in flight and
   *   dead letters are not counted.
   *
   * @example
   * if ((await sync.getQueueSize()) > 0) await sync.flush();
   */
  getQueueSize(): Promise<number>;

  /**
   * Read every pending item from the queue.
   *
   * @returns the `PENDING` items in dispatch order, including those waiting
   *   out a retry cooldown, with `id` and `createdAt` populated. Items in
   *   flight and dead letters are not returned: read dead letters with
   *   `listDeadLetter`.
   *
   * @example
   * const pending = await sync.getPendingItems();
   * console.log(`${pending.length} item(s) pending`);
   */
  getPendingItems(): Promise<SyncItem[]>;

  /* ---------------------------- Dead letters ---------------------------- */

  /**
   * Read every dead letter: the items that failed permanently and were moved
   * out of the queue, with the request and its failure bookkeeping.
   *
   * @returns every dead letter, ordered by `deadLetteredAt` ascending, then by
   *   `id` ascending, at most one per id: a newer dead letter replaces an older
   *   one with the same id. The list is unbounded: nothing prunes it, and dead
   *   letters count toward no capacity cap.
   *
   * @example
   * const dead = await sync.listDeadLetter();
   * for (const item of dead) console.warn(item.id, item.lastErrorCode);
   */
  listDeadLetter(): Promise<SyncDeadLetterItem[]>;

  /**
   * Put a dead letter back into the queue. In one transaction, the item is
   * re-inserted under the same id, with its original `createdAt`, as `PENDING`
   * with `attempts: 0`, `nextRetryAt: 0` and no last error code, and the dead
   * letter is deleted. Emits `ITEM_ENQUEUED`. Like `enqueue`, it does not
   * start a flush.
   *
   * An unknown id resolves with no change, so a repeated call is harmless. If
   * the queue already holds an item with that id, which normal use does not
   * produce, nothing changes and the dead letter stays: iOS resolves, Android
   * rejects.
   *
   * @param id — the dead letter's id, from `listDeadLetter` or an
   *   `ITEM_DEAD_LETTERED` event.
   * @throws SyncError with code `QUEUE_FULL` when the queue is at
   *   `SyncOptions.maxQueueSize`, counted as for an enqueue on that platform
   *   (iOS: pending items; Android: pending and in-flight items). The dead
   *   letter stays in place.
   *
   * @example
   * await sync.replayDeadLetter(deadLetter.id);
   * await sync.flush();
   */
  replayDeadLetter(id: string): Promise<void>;

  /**
   * Delete a dead letter. Emits `ITEM_REMOVED` when a row was deleted. An
   * unknown id resolves with no change, so a repeated call is harmless.
   *
   * @param id — the dead letter's id, from `listDeadLetter` or an
   *   `ITEM_DEAD_LETTERED` event.
   *
   * @example
   * await sync.discardDeadLetter(deadLetter.id);
   */
  discardDeadLetter(id: string): Promise<void>;

  /* ------------------------------ Sync ops ------------------------------ */

  /**
   * Run one flush cycle: dispatch one batch of eligible items, up to
   * `SyncOptions.batchSize`, applying the retry policy. Items still cooling down
   * are skipped. The strategy plays no part.
   *
   * The checks at the start of a flush run in this order: pause, stored
   * credentials, on iOS a cycle already running, network-quality gate
   * (`SyncOptions.minNetworkQuality`), health check
   * (`SyncOptions.healthCheckUrl`). On iOS, a flush that finds a cycle running
   * returns the last result at once, without the gate or the health check;
   * Android waits for the running cycle, then runs every check. When a check
   * fails, no cycle runs: the result is 0/0, no item changes, no `SYNC_STARTED`
   * is emitted and no history row is written. Paused or with unreadable
   * `defaultHeaders`, no event is emitted at all. The gate and the health check
   * emit `SYNC_FAILED` with `NETWORK_QUALITY_TOO_LOW` or `HEALTH_CHECK_FAILED`.
   * Android then returns `0` for `startedAt` and `finishedAt`, and iOS the time
   * the check stopped the flush for both.
   *
   * @returns the outcome of the cycle. Per-item failures never reject: they are
   *   counted here and reported through item events.
   * @throws when the cycle itself dies, for example because the store fails.
   *
   * @example
   * const result = await sync.flush();
   * console.log(`${result.successCount} ok / ${result.failureCount} failed`);
   */
  flush(): Promise<SyncResult>;

  /**
   * Pause the sync engine. While paused, the queue still accepts enqueues
   * but no flush cycles run (manual or otherwise). The pause is persisted, so
   * background workers stay inert while the app is dead. It lasts until
   * `resumeSync()` or the next launch that reaches the library, including a JS
   * reload. A launch clears it without emitting `RESUMED`.
   *
   * @example
   * await sync.pauseSync();
   */
  pauseSync(): Promise<void>;

  /**
   * Resume the sync engine after `pauseSync()`. Unless the strategy is
   * `MANUAL`, a flush is scheduled for the soonest pending item: items with no
   * cooldown left are dispatched right away, and cooling items when their
   * cooldown ends. If the device is offline at that moment, the flush waits
   * until connectivity is restored, and with `SyncOptions.minNetworkQuality`
   * set, until the quality reaches it, without any event. During a credential
   * hold or a health-check backoff, the flush waits for it to end, on both
   * platforms. The hold lives in memory, so a process restart drops it.
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
   * Replace the active sync configuration. Android applies the new options
   * from the next flush cycle. iOS applies them from the next item, even inside
   * a running cycle. A request already in flight keeps the previous config.
   *
   * The options are persisted for background workers. `defaultHeaders` are
   * encrypted at rest, and omitting them deletes the stored copy. Every call
   * also ends a running health-check backoff, and arms the retry wake again,
   * which starts the network-quality gate's re-check schedule over.
   *
   * @param options — new library-wide configuration.
   * @throws SyncError with code `INVALID_URL` when `healthCheckUrl` is not a
   *   valid absolute http(s) URL.
   * @throws SyncError with code `INVALID_PAYLOAD` when `healthCheckTtlMs` or
   *   `healthCheckBackoffMs` is not a finite number of at least `0`, or
   *   `minNetworkQuality` is not a `NetworkQuality` value.
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
   * @returns the currently active {@link SyncOptions}. `minNetworkQuality`,
   *   `healthCheckUrl`, `healthCheckTtlMs` and `healthCheckBackoffMs` are
   *   present only when they were set: their defaults are not filled in. A
   *   stored value of theirs that the engine cannot use, which only a damaged
   *   record can hold, loads as unset and is left out too.
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
   * @returns the live {@link ConnectionState} as reported by the OS, with the
   *   engine's `networkQuality` classification, always present: `OFFLINE`
   *   exactly when `status` is `DISCONNECTED` or `UNKNOWN`.
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
   * Check whether background sync is enabled.
   *
   * @returns `true` after `enableBackgroundSync` until
   *   `disableBackgroundSync`. This reflects the consumer's intent, not OS
   *   state: with `foregroundOnly: true` it returns `true` although no OS work
   *   is scheduled.
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
