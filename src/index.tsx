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
 *   - 24 facade functions: 22 wrap the Nitro `SyncProvider` HybridObject, plus
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
  SyncDeadLetterItem,
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
import { validateSyncOptions } from './utils/validateSyncOptions';

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
 * Remove a single item by ULID, in any status: pending (including items
 * waiting out a cooldown) or in flight. When no queued item has that id, the
 * dead letter with that id is removed instead, so this also works on an item
 * that failed permanently: take its id from {@link listDeadLetter} or from its
 * `ITEM_FAILED` / `ITEM_DEAD_LETTERED` event.
 *
 * @param id — ULID returned by {@link enqueue} / {@link enqueueBatch}.
 * @returns `true` if a queued item or a dead letter with that id existed and
 *   was removed, `false` if neither exists (already delivered, already removed
 *   or cleared, or never enqueued).
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @remarks
 * Removal does not cancel a request already sent. For an item that a running
 * cycle has taken into its batch, it depends on when the removal lands:
 *
 * - Before the item's request is sent, including an item whose attempts had
 *   already run out, which a cycle ends without a request: the item is never
 *   sent and gets no outcome event (`ITEM_SUCCEEDED`, `ITEM_RETRYING`,
 *   `ITEM_FAILED` or `ITEM_DEAD_LETTERED`).
 * - While its request is in flight: the request can still reach the server,
 *   and its outcome event (`ITEM_SUCCEEDED`, `ITEM_RETRYING` or `ITEM_FAILED`)
 *   is still emitted for it, but nothing is written back. The item does not
 *   return to the queue and is never dead-lettered, so no `ITEM_DEAD_LETTERED`
 *   follows its `ITEM_FAILED`.
 *
 * A removed item never comes back, as a dead letter or as a replayed queue
 * row, with or without its headers and body. `ITEM_REMOVED` is emitted only
 * when a row was removed.
 *
 * @example
 * const removed = await removeItem(id);
 * if (!removed) console.warn('already delivered or unknown id');
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
 * Remove every item from the queue, in any status (pending or in flight), and
 * every dead letter. Emits one `QUEUE_CLEARED`. Does not affect the sync
 * history.
 *
 * Dead letters keep their request headers and body, so this is the call that
 * purges every stored request, for example on logout. Clearing does not cancel
 * a request already sent by a running cycle, and every cleared item follows the
 * same rules as with {@link removeItem}: one whose request was not sent yet is
 * never sent and gets no outcome event; one whose request is in flight still
 * gets its outcome event for that request, but returns neither to the queue
 * nor, after a permanent failure, to the dead letters, and no
 * `ITEM_DEAD_LETTERED` follows. A cleared item never comes back, as a dead
 * letter or as a replayed queue row, with or without its headers and body.
 *
 * @returns resolves once the queue and the dead-letter queue are empty.
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
 *   dead letters are not counted.
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
 *   Items in flight and dead letters are not returned: read dead letters with
 *   {@link listDeadLetter}.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @remarks
 * Each item's `attempts` and `nextRetryAt` are returned **verbatim** from the
 * native layer — the facade performs **no** sentinel translation on a
 * {@link SyncItem}. This is deliberately asymmetric with
 * {@link getLastSyncResult}, which maps the native sentinel
 * (`startedAt === 0 && finishedAt === 0`) to `undefined` via `isLastSyncSentinel`.
 * For a `SyncItem`, `attempts === 0` ("no attempt recorded") and
 * `nextRetryAt === 0` ("eligible now") are real values, and neither one means
 * the item was never tried — see {@link SyncItem.attempts} and
 * {@link SyncItem.nextRetryAt}.
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
/*                                Dead letters                                */
/* -------------------------------------------------------------------------- */

/**
 * Read every dead letter: the items that failed permanently and were moved out
 * of the queue, with the original request and its failure bookkeeping.
 *
 * An item becomes a dead letter whenever it fails permanently. The move is
 * atomic and is followed by `ITEM_FAILED`, then `ITEM_DEAD_LETTERED`. An item
 * removed with {@link removeItem} or {@link clearQueue} is never
 * dead-lettered: if its request was in flight, only its `ITEM_FAILED` is
 * emitted. Items that failed permanently before the upgrade to this version
 * are moved here too. See {@link SyncDeadLetterItem}.
 *
 * @returns every dead letter, oldest first: ordered by `deadLetteredAt`, then
 *   by `id`. There is at most one per id: a newer dead letter replaces an older
 *   one with the same id.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_PAYLOAD} when native rejects
 *   the call.
 *
 * @remarks
 * The dead-letter queue is unbounded: nothing prunes it, and it counts toward
 * no capacity cap, so {@link discardDeadLetter} what you do not
 * {@link replayDeadLetter}. Each dead letter keeps its request's headers and
 * body until it is replayed, discarded, removed with {@link removeItem} or
 * cleared with {@link clearQueue}.
 *
 * `useSyncSnapshot().deadLetter` keeps this list up to date for a component.
 *
 * @example
 * const deadLetters = await listDeadLetter();
 * for (const item of deadLetters) {
 *   console.warn(`${item.method} ${item.url} failed: ${item.lastErrorCode}`);
 * }
 *
 * @public
 */
export async function listDeadLetter(): Promise<SyncDeadLetterItem[]> {
  const native = nativeOrThrow();
  try {
    return await native!.listDeadLetter();
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Put a dead letter back into the queue for another try.
 *
 * In one transaction, the item is re-inserted under the same id, with its
 * original `createdAt`, as a pending item with `attempts: 0`, `nextRetryAt: 0`
 * and no last error code, and the dead letter is deleted. Emits
 * `ITEM_ENQUEUED`. Like {@link enqueue}, it does not dispatch the item: that
 * takes a flush, from {@link flush}, `useAutoSync`, or an OS background
 * window.
 *
 * An unknown id resolves with no change, so replaying the same id twice, or an
 * id that was already discarded, is harmless. If the queue already holds an
 * item with that id, which normal use does not produce, nothing changes and the
 * dead letter stays: iOS resolves, Android rejects.
 *
 * Calls on the same id take effect one at a time. Two overlapping replays act
 * as one replay and one no-op, so `ITEM_ENQUEUED` is emitted once, and a dead
 * letter that an overlapping {@link clearQueue} or {@link discardDeadLetter}
 * deletes is never put back.
 *
 * The replayed item counts toward `SyncOptions.maxQueueSize` the way an
 * enqueue does on that platform: iOS counts the pending items, Android the
 * pending and in-flight ones.
 *
 * @param id — the dead letter's id, from {@link listDeadLetter} or an
 *   `ITEM_DEAD_LETTERED` event.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_PAYLOAD} when native rejects
 *   the call, and the dead letter stays in place. That includes a queue at
 *   `SyncOptions.maxQueueSize`: native reports `QUEUE_FULL`, but native codes
 *   do not reach JS yet.
 *
 * @example
 * await replayDeadLetter(deadLetter.id);
 * await flush();
 *
 * @public
 */
export async function replayDeadLetter(id: string): Promise<void> {
  const native = nativeOrThrow();
  try {
    await native!.replayDeadLetter(id);
  } catch (err) {
    wrapNativeError(err, SyncErrorCode.INVALID_PAYLOAD);
  }
}

/**
 * Delete a dead letter for good, with its stored request.
 *
 * Emits `ITEM_REMOVED` when a row was deleted. An unknown id resolves with no
 * change, so discarding the same id twice is harmless. {@link removeItem} does
 * the same for a dead letter and also reports whether a row was deleted. A
 * {@link replayDeadLetter} of the same id that overlaps it never puts the
 * discarded dead letter back.
 *
 * @param id — the dead letter's id, from {@link listDeadLetter} or an
 *   `ITEM_DEAD_LETTERED` event.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_PAYLOAD} when native rejects
 *   the call.
 *
 * @example
 * await discardDeadLetter(deadLetter.id);
 *
 * @public
 */
export async function discardDeadLetter(id: string): Promise<void> {
  const native = nativeOrThrow();
  try {
    await native!.discardDeadLetter(id);
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
 * down) resolves with a 0/0 result, is recorded in the sync history, and ends
 * with `SYNC_SUCCEEDED`.
 *
 * Before a cycle starts, the flush checks, in this order, the pause, the stored
 * credentials, on iOS whether a cycle is already running (see the last
 * paragraph), the network-quality gate and the health check. When one of them
 * fails, no cycle runs: nothing is dispatched, no item changes, no
 * `SYNC_STARTED` is emitted and no history row is written.
 *
 * - Paused, or with stored `defaultHeaders` that cannot be read: no event at
 *   all.
 * - Network quality below `SyncOptions.minNetworkQuality`: `SYNC_FAILED` with
 *   `errorCode: NETWORK_QUALITY_TOO_LOW`. On iOS, when the system has not
 *   reported the link yet, the gate first waits up to 1.5 s for it, and grades
 *   a link still unknown after that `OFFLINE`.
 * - Health check failed, or its backoff still running (see
 *   `SyncOptions.healthCheckUrl`): `SYNC_FAILED` with
 *   `errorCode: HEALTH_CHECK_FAILED`.
 *
 * The result counts are then 0/0 on both platforms and do not say why. Android
 * returns `0` for `startedAt` and `finishedAt`, the zeroed shape that
 * {@link getLastSyncResult} reports as `undefined`; `flush()` returns it as is.
 * iOS sets both to the time the check stopped the flush, so the result looks
 * like an empty cycle's.
 *
 * Unless `SyncOptions.minNetworkQuality` is set, `flush()` does not check
 * connectivity first, so calling it offline can spend retry attempts and move
 * valid items to the dead-letter queue. Any `minNetworkQuality` other than
 * `OFFLINE` blocks an offline flush instead. {@link drainUntilEmpty} waits for
 * the network, and `useAutoSync` skips its interval and foreground flushes
 * while `DISCONNECTED`.
 *
 * While a cycle is running, iOS returns the last result at once, so the gate
 * and the health check do not run and report nothing. Android waits, then runs
 * its own cycle with every check.
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
 * **Skipped cycles:** a `flush()` that the network-quality gate or the health
 * check skips (see {@link flush}) still uses up an iteration and makes no
 * progress. On a link below `SyncOptions.minNetworkQuality`, or while a
 * health-check backoff runs, the loop therefore ends with `MAX_ITERATIONS` or
 * `TIMEOUT`.
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
 * accept items but no flush cycle runs: `flush()` resolves with a 0/0 result
 * and emits no event (see {@link flush} for the timestamps each platform
 * returns), and the retry timer is cancelled.
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
 * moment, the flush waits until connectivity is restored. With
 * `SyncOptions.minNetworkQuality` set, it also waits, without any event, until
 * the network quality reaches that minimum (see there for how often it checks
 * again).
 *
 * During a credential hold, set after a 401 or 403 on a request sent with
 * headers restored from storage, that flush waits for the hold to end before
 * dispatching, on both platforms, even when items are already due. The hold
 * lives in memory, so a process restart drops it. See
 * {@link SyncOptions.defaultHeaders}. It also waits for a running health-check
 * backoff to end (see {@link SyncOptions.healthCheckBackoffMs}).
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
 * @throws {SyncError} {@link SyncErrorCode.INVALID_URL} when `healthCheckUrl`
 *   is not an absolute http(s) URL, with `validationErrors` set. Checked
 *   before any native call.
 * @throws {SyncError} {@link SyncErrorCode.INVALID_PAYLOAD} when
 *   `minNetworkQuality` is not a {@link NetworkQuality} value,
 *   `healthCheckTtlMs` or `healthCheckBackoffMs` is not a finite number of at
 *   least `0`, or `options` is not an object, with `validationErrors` set.
 *   Checked before any native call. Also, with no `validationErrors`, when
 *   native rejects the call.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @remarks
 * The options are persisted, so a background worker started without JS uses
 * them too. `defaultHeaders` are encrypted at rest. Calling `configureSync`
 * without `defaultHeaders` deletes the stored copy, which makes it the way to
 * clear credentials on logout. See {@link SyncOptions.defaultHeaders}.
 *
 * Every call also clears a credential hold, whether or not the headers
 * changed, so the retry wake stops waiting for it. From then on the app owns
 * the token in this process, so a 401 or 403 is terminal, as under any
 * configuration set in the process: the item fails with `UNAUTHORIZED`. Every
 * call also ends a running health-check backoff (see
 * {@link SyncOptions.healthCheckBackoffMs}), and arms the retry wake again,
 * which starts the network-quality gate's re-check schedule over (see
 * {@link SyncOptions.minNetworkQuality}).
 *
 * Only `minNetworkQuality`, `healthCheckUrl`, `healthCheckTtlMs` and
 * `healthCheckBackoffMs` are validated in JS, in that order and only when
 * present; the other fields go to native as they are. When several are
 * invalid, the first one sets the code, and `validationErrors` lists them all.
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
  validateSyncOptions(options);
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
 * @returns the currently active {@link SyncOptions}. `minNetworkQuality`,
 *   `healthCheckUrl`, `healthCheckTtlMs` and `healthCheckBackoffMs` are present
 *   only when they were set: their defaults are not filled in.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @remarks
 * Do not pass the result back to {@link configureSync} without setting
 * `defaultHeaders` yourself.
 *
 * In a process where `configureSync` has not run yet, the result is the
 * configuration an earlier process persisted, if any, with the stored copy of
 * the headers. While that copy cannot be read (the Android Keystore key was
 * invalidated, the iOS device has not been unlocked since boot, or a backup
 * was restored onto another device), the result carries no headers:
 * `defaultHeaders` is `undefined` on Android and `{}` on iOS. That lasts until
 * the library reads the copy again, which it retries on each flush while not
 * paused, or until `configureSync` runs.
 *
 * A stored `minNetworkQuality` that cannot be read, a stored `healthCheckUrl`
 * the engine cannot use, and a stored `healthCheckTtlMs` or
 * `healthCheckBackoffMs` that is negative or not finite load as unset, so the
 * result leaves them out. Only a damaged record can hold such values, since
 * `configureSync` rejects them.
 *
 * `configureSync` replaces the whole configuration, so passing such a result
 * back, for example `configureSync({ ...cfg, batchSize: 5 })`, deletes the
 * stored headers, which acts as a logout. Later requests then go out without
 * them, and a 401 or 403 fails those items permanently.
 *
 * @example
 * const cfg = await getSyncConfig();
 * // Take the credentials from the app's session, not from `cfg`.
 * await configureSync({ ...cfg, batchSize: 5, defaultHeaders: session.headers });
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
 * @returns the live {@link ConnectionState} as reported by the OS, including
 *   the engine's {@link ConnectionState.networkQuality}, which is always
 *   present and is `OFFLINE` exactly when `status` is `DISCONNECTED` or
 *   `UNKNOWN`. See {@link NetworkQuality} for how it is classified.
 * @throws {SyncError} {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when
 *   the native module is not loaded.
 *
 * @remarks
 * `CONNECTION_CHANGED` carries only `connectionStatus`, and is also emitted
 * when only the network quality changed: call this again on that event, or use
 * `useConnection`, which does. It is not emitted for the state the engine
 * starts with, so call this once at start too.
 *
 * @example
 * const conn = await getConnectionStatus();
 * if (conn.status === 'CONNECTED') await flush();
 *
 * @example
 * const { networkQuality } = await getConnectionStatus();
 * if (networkQuality === NetworkQuality.POOR) showSlowNetworkBanner();
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
