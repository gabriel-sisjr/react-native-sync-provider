import type { SyncErrorCode } from '../errors/SyncErrorCode';
import type {
  BackoffStrategy,
  ConnectionStatus,
  HttpMethod,
  SyncEventType,
  SyncPriority,
  SyncStrategy,
} from './enums';

/* -------------------------------------------------------------------------- */
/*                                  Items                                     */
/* -------------------------------------------------------------------------- */

/**
 * Input shape accepted by `enqueue` / `enqueueBatch`.
 *
 * Both `id` and `createdAt` are intentionally omitted — they are assigned by
 * the native layer (ULID for `id`, epoch ms for `createdAt`) so the queue can
 * guarantee idempotency and monotonic ordering even when JS and native clocks
 * disagree.
 *
 * @example
 * await enqueue({
 *   method: HttpMethod.POST,
 *   url: 'https://api.example.com/events',
 *   contentType: 'application/json',
 *   body: JSON.stringify({ kind: 'page_view' }),
 *   priority: SyncPriority.NORMAL,
 * });
 */
export interface SyncItemInput {
  /** HTTP verb used to dispatch the request. */
  method: HttpMethod;
  /** Absolute URL of the request (must be a valid http(s) URL). */
  url: string;
  /**
   * Optional request headers. Values must be strings — encode complex values
   * (numbers, booleans) on the JS side before passing them in.
   */
  headers?: Record<string, string>;
  /** Optional pre-serialized request body (typically JSON / form-encoded). */
  body?: string;
  /**
   * Optional `Content-Type` header. Set it here and leave it out of `headers`,
   * because the platforms resolve a conflict differently: Android uses this
   * field, and defaults to `application/json` for any body when it is omitted.
   * iOS uses the value in `headers`.
   */
  contentType?: string;
  /**
   * Optional dispatch priority. Defaults to {@link SyncPriority.NORMAL} on
   * the native side when omitted.
   */
  priority?: SyncPriority;
  /**
   * Optional opaque metadata. Persisted with the item and returned by
   * `getPendingItems`. It is not sent with the request and not echoed on sync
   * events. Values must be strings.
   */
  metadata?: Record<string, string>;
}

/**
 * Read shape returned by `getPendingItems` and friends.
 *
 * Mirrors {@link SyncItemInput} but adds the native-assigned `id` and
 * `createdAt`.
 */
export interface SyncItem {
  /** ULID assigned by the native layer at enqueue time. */
  id: string;
  /** HTTP verb used to dispatch the request. */
  method: HttpMethod;
  /** Absolute URL of the request. */
  url: string;
  /** Optional request headers, mirroring what was enqueued. */
  headers?: Record<string, string>;
  /** Optional pre-serialized request body. */
  body?: string;
  /**
   * Optional `Content-Type` header, mirroring what was enqueued. See
   * {@link SyncItemInput.contentType}.
   */
  contentType?: string;
  /**
   * Optional dispatch priority — `NORMAL` if not supplied on enqueue.
   */
  priority?: SyncPriority;
  /**
   * Epoch-millisecond timestamp captured by the native layer at enqueue time.
   * Monotonic with respect to enqueue order.
   */
  createdAt: number;
  /**
   * Number of dispatch attempts recorded for this item, counted against
   * {@link RetryPolicy.maxAttempts}.
   *
   * `attempts === 0` means no attempt has been recorded. It does not mean the
   * item was never tried: its request may already have reached the server.
   *
   * @remarks
   * When an attempt is recorded depends on the platform:
   *
   * - Android records it with the attempt's outcome. A process kill
   *   mid-request leaves the count unchanged, so an item whose first request
   *   reached the server can still show `0`.
   * - iOS counts it when the dispatch starts, so a kill mid-request spends it.
   * - On both, a 401 or 403 sent with `defaultHeaders` restored from storage,
   *   in a process where `configureSync` has not run, holds the item without
   *   spending an attempt: the count ends at its value from before the
   *   dispatch. A held item can therefore show `attempts: 0` with
   *   `nextRetryAt > 0`.
   *
   * Surfaced **verbatim** by the facade. This is deliberately asymmetric with
   * `getLastSyncResult`, which translates a native sentinel
   * (`startedAt === 0 && finishedAt === 0`) into `undefined` via the facade's
   * `isLastSyncSentinel` helper. `SyncItem.attempts` is **never** translated:
   * the `0` here is a real value (`no attempt recorded`), not an "absent"
   * marker.
   */
  attempts: number;
  /**
   * Epoch-millisecond timestamp at which this item becomes eligible for its
   * next dispatch. An item is eligible once `nextRetryAt <= now`.
   *
   * - `0` means the item is eligible now. It does not mean the item was never
   *   attempted: see the remarks.
   * - A positive value means a retry cooldown or a credential hold is pending.
   *   The native dispatcher skips the item until the device clock reaches it,
   *   and the value stays in place afterwards, so a timestamp in the past means
   *   the wait has elapsed.
   *
   * @remarks
   * `nextRetryAt` and {@link SyncItem.attempts} do not move together. Rows
   * carried over from v0.1 can show `attempts >= 1` with `nextRetryAt: 0`, and
   * so does an iOS row whose first attempt was cut short by a process kill. An
   * item held after a credential rejection can show `attempts: 0` with
   * `nextRetryAt > 0`.
   *
   * A cooldown and a credential hold look the same here, because `SyncItem`
   * carries neither the last error code nor the last attempt time. An
   * `ITEM_RETRYING` event with `errorCode: 'UNAUTHORIZED'` marks a hold.
   *
   * Surfaced **verbatim** by the facade. Unlike `getLastSyncResult`'s sentinel
   * translation (`isLastSyncSentinel` → `undefined`), `SyncItem.nextRetryAt`'s
   * `0` is a real value (`eligible now`) and is **never** translated to
   * `undefined`.
   */
  nextRetryAt: number;
  /** Optional opaque metadata, mirroring what was enqueued. */
  metadata?: Record<string, string>;
}

/**
 * An item that exhausted its retry budget and was moved to the dead-letter
 * queue instead of being dropped.
 *
 * Slated for v0.3. It mirrors {@link SyncItem} and adds the failure bookkeeping
 * needed to inspect, re-drive, or discard a dead-lettered request.
 *
 * @remarks
 * Introduced early so consumer types can depend on its final shape. In v0.2 it
 * is never produced — {@link UseSyncSnapshotResult.deadLetter} is ALWAYS
 * `undefined` until the v0.3 dead-letter store ships.
 */
export interface SyncDeadLetterItem {
  /** ULID assigned by the native layer at enqueue time. */
  id: string;
  /** HTTP verb used to dispatch the request. */
  method: HttpMethod;
  /** Absolute URL of the request. */
  url: string;
  /** Optional request headers, mirroring what was enqueued. */
  headers?: Record<string, string>;
  /** Optional pre-serialized request body. */
  body?: string;
  /**
   * Optional `Content-Type` header, mirroring what was enqueued. See
   * {@link SyncItemInput.contentType}.
   */
  contentType?: string;
  /**
   * Optional dispatch priority — `NORMAL` if not supplied on enqueue.
   */
  priority?: SyncPriority;
  /**
   * Epoch-millisecond timestamp captured by the native layer at enqueue time.
   * Monotonic with respect to enqueue order.
   */
  createdAt: number;
  /** Optional opaque metadata, mirroring what was enqueued. */
  metadata?: Record<string, string>;
  /** Total number of dispatch attempts made before dead-lettering. */
  attempts: number;
  /** Epoch-millisecond timestamp of the final (failing) dispatch attempt. */
  lastAttemptAt: number;
  /** Failure code from the final attempt — the reason it was dead-lettered. */
  lastErrorCode: SyncErrorCode;
  /** Optional human-readable detail accompanying {@link lastErrorCode}. */
  lastErrorMessage?: string;
  /** Epoch-millisecond timestamp at which the item was moved to dead-letter. */
  deadLetteredAt: number;
}

/* -------------------------------------------------------------------------- */
/*                                Retry policy                                */
/* -------------------------------------------------------------------------- */

/**
 * Declarative retry policy applied by the native layer to each in-flight
 * item.
 *
 * @remarks
 * The policy is intentionally declarative, so it also works with no JS running.
 * A JS-side per-attempt retry override is planned for a later release. It would
 * run only while JS is alive, because every retry decision would need a bridge
 * round-trip.
 */
export interface RetryPolicy {
  /** Maximum number of dispatch attempts (initial attempt counts as one). */
  maxAttempts: number;
  /** Curve used to compute the delay between attempts. */
  backoff: BackoffStrategy;
  /** Base delay (in ms) — see {@link BackoffStrategy} for the formula. */
  baseDelayMs: number;
  /** Hard cap on the per-attempt delay (in ms). */
  maxDelayMs: number;
  /**
   * If `true`, the computed delay is multiplied by a uniformly distributed
   * equal-jitter factor in `[0.75, 1.25)` (i.e. ±25% around the base delay) to
   * avoid synchronized retry storms.
   */
  jitter: boolean;
  /**
   * HTTP status codes that should trigger a retry. Network errors and timeouts
   * are always retried regardless of this list. Non-listed 4xx/5xx responses
   * are treated as permanent failures, and 401/403 stay permanent even when
   * listed.
   */
  retryOnStatusCodes: number[];
}

/* -------------------------------------------------------------------------- */
/*                                  Options                                   */
/* -------------------------------------------------------------------------- */

/**
 * Library-wide configuration. Applied via `configureSync` and read back via
 * `getSyncConfig`.
 */
export interface SyncOptions {
  /** When the queue is drained. See {@link SyncStrategy}. */
  strategy: SyncStrategy;
  /** Retry behavior for failed items. */
  retryPolicy: RetryPolicy;
  /**
   * Maximum number of items one flush cycle dispatches. Defaults to `25`.
   * Android sends them concurrently, iOS one at a time.
   */
  batchSize?: number;
  /**
   * Per-request HTTP timeout (in ms). Defaults to `30000`. Android enforces a
   * floor of 1 second.
   */
  requestTimeoutMs?: number;
  /**
   * Maximum number of items the queue may hold. Defaults to `10000` when
   * omitted, and any value below `1` is treated as `1`, so there is no way to
   * remove the cap. New enqueues are rejected once it is reached. Android counts
   * failed items toward it, iOS does not.
   */
  maxQueueSize?: number;
  /**
   * Stored but not used yet: the queue is always persisted to disk (Core Data
   * on iOS, Room on Android), whatever this field says.
   */
  persistQueue?: boolean;
  /**
   * Headers added to every dispatched request. Per-item headers override
   * default headers on key collision.
   *
   * Encrypted at rest (Android Keystore, iOS Keychain), so a background worker
   * started without JS can dispatch with them. `configureSync` without this
   * field deletes the stored copy, which makes it the way to clear credentials
   * on logout.
   *
   * If the stored copy cannot be read, the worker leaves items pending instead
   * of dispatching without the headers. In a process that has not called
   * `configureSync`, a 401 or 403 does not fail the item, because a stored
   * token can expire and only the app can renew it: the item stays pending, no
   * attempt is spent, and a credential hold keeps every retry wake in the
   * process back for at least 60 seconds.
   *
   * The hold lives in memory, so a process restart (an OS kill, a reboot or an
   * app update) drops it. The rejected item keeps its own `nextRetryAt`, but
   * another due item can wake the queue in the new process and spend one
   * request with the rejected token, whose rejection sets a new hold. The
   * periodic worker and the iOS BGTasks are not retry wakes and do not wait for
   * the hold, so a background window that opens during one can also spend one
   * request.
   */
  defaultHeaders?: Record<string, string>;
}

/* -------------------------------------------------------------------------- */
/*                                  Results                                   */
/* -------------------------------------------------------------------------- */

/**
 * Outcome of a single flush cycle. Returned by `flush()` and persisted to the
 * sync history (see `getSyncHistory`).
 *
 * @remarks
 * A `flush()` that runs no cycle, because the engine is paused or stored
 * `defaultHeaders` cannot be read, still resolves with a `SyncResult`, but
 * nothing is dispatched, no event is emitted and no history row is written.
 * Both counts are `0`, and the result does not say why. Android sets
 * `startedAt` and `finishedAt` to `0`. iOS sets both to the time of the call,
 * so there the result alone cannot be told apart from an empty cycle: only the
 * missing `SYNC_STARTED` event and history row show that no cycle ran.
 */
export interface SyncResult {
  /**
   * Epoch ms — when the flush cycle started. `0` on Android when `flush()` ran
   * no cycle.
   */
  startedAt: number;
  /**
   * Epoch ms — when the flush cycle finished. `0` on Android when `flush()` ran
   * no cycle.
   */
  finishedAt: number;
  /** Number of items that succeeded in this cycle. */
  successCount: number;
  /** Number of items that failed permanently in this cycle. */
  failureCount: number;
  /** ULIDs of items that succeeded. Always empty on iOS. */
  succeededIds: string[];
  /** ULIDs of items that failed permanently. Always empty on iOS. */
  failedIds: string[];
  /**
   * Per-failed-item errors, keyed by item ULID, as `"<SyncErrorCode>: <detail>"`.
   * Always empty on iOS: use `ITEM_FAILED` events there.
   */
  errors: Record<string, string>;
}

/* -------------------------------------------------------------------------- */
/*                                  Events                                    */
/* -------------------------------------------------------------------------- */

/**
 * Event payload for the `'sync-event'` channel.
 *
 * This is intentionally a flat shape with optional fields rather than a
 * discriminated union — Nitro codegen and consumers both prefer the flat
 * shape, and per-`type` field semantics are documented on
 * {@link SyncEventType}.
 *
 * Field-by-`type` contract:
 * - `ITEM_ENQUEUED` / `ITEM_REMOVED` — `itemId` set.
 * - `SYNC_STARTED` / `SYNC_SUCCEEDED` — base fields only.
 * - `SYNC_PROGRESS` — `progress` (0..1) set. iOS only.
 * - `SYNC_FAILED` — `errorCode` set only when the cycle itself died.
 * - `ITEM_SUCCEEDED` — `itemId`, `statusCode`, `attempt` set.
 * - `ITEM_FAILED` — `itemId`, `errorCode`, `attempt`, `statusCode?` set.
 * - `ITEM_RETRYING` — `itemId`, `attempt`, `errorCode`, `statusCode?` set.
 * - `QUEUE_CLEARED` — base fields only.
 * - `CONNECTION_CHANGED` — `connectionStatus` set.
 * - `PAUSED` / `RESUMED` — base fields only.
 * - `BACKGROUND_SYNC_STARTED` / `BACKGROUND_SYNC_COMPLETED` — base fields
 *   only on Android. On iOS, `metadata.kind` (`"refresh"` or `"processing"`) is
 *   set on every `BACKGROUND_SYNC_COMPLETED` and on the processing task's
 *   `BACKGROUND_SYNC_STARTED`, and `metadata.ok` is `"false"` on a
 *   `BACKGROUND_SYNC_COMPLETED` whose flush failed. `errorCode` is not set on
 *   either platform: a window whose flush fails reports the code on that
 *   cycle's `SYNC_FAILED` event. See {@link SyncEventType} for when each
 *   platform emits these events.
 */
export interface SyncEvent {
  /** Discriminator — see {@link SyncEventType} for per-type field semantics. */
  type: SyncEventType;
  /** Epoch ms — when the native layer emitted this event. */
  timestamp: number;
  /** Item ULID, populated for item-scoped events. */
  itemId?: string;
  /** Coarse flush progress in `[0, 1]`. Only set for `SYNC_PROGRESS`. */
  progress?: number;
  /**
   * Failure code. Set for `ITEM_FAILED` and `ITEM_RETRYING`, and for
   * `SYNC_FAILED` only when the cycle itself died.
   */
  errorCode?: SyncErrorCode;
  /** HTTP status code, when applicable. */
  statusCode?: number;
  /**
   * 1-based attempt number. Set for `ITEM_SUCCEEDED`, `ITEM_FAILED` and
   * `ITEM_RETRYING`.
   */
  attempt?: number;
  /** Connection status snapshot. Only set for `CONNECTION_CHANGED`. */
  connectionStatus?: ConnectionStatus;
  /**
   * Engine diagnostics, on iOS only, for cycle-level events and the
   * `BACKGROUND_SYNC_*` events. This is not {@link SyncItemInput.metadata}:
   * item metadata is not echoed on events.
   */
  metadata?: Record<string, string>;
}

/* -------------------------------------------------------------------------- */
/*                              Background sync                               */
/* -------------------------------------------------------------------------- */

/**
 * Configuration accepted by `enableBackgroundSync`. Not every field applies on
 * both platforms: see each one. Where the OS-imposed minimum differs from the
 * value supplied here, the OS value wins (Android, in particular, clamps
 * `minimumIntervalMs` to >= 15 minutes for periodic work).
 */
export interface BackgroundSyncOptions {
  /**
   * Minimum interval between background sync windows, in ms.
   *
   * Android `WorkManager` clamps this to a minimum of 15 minutes for periodic
   * work. iOS `BGTaskScheduler` treats this as a soft hint with a floor of 60
   * seconds — the OS schedules the task at its discretion based on system
   * conditions.
   */
  minimumIntervalMs: number;
  /**
   * If `true`, only run when the device is plugged in. On iOS this applies to
   * the processing task only, not to the app-refresh task.
   */
  requiresCharging?: boolean;
  /**
   * If `true`, only run on an unmetered network (Wi-Fi/ethernet). Android only:
   * iOS does not apply it.
   */
  requiresUnmeteredNetwork?: boolean;
  /** If `true`, only run when the device is idle. Android only. */
  requiresDeviceIdle?: boolean;
  /**
   * Kill-switch for OS-scheduled background work.
   *
   * When `true`, the library schedules **no** OS-level background sync work:
   * neither the iOS `BGTaskScheduler` app-refresh / processing tasks nor the
   * Android `WorkManager` periodic and one-time flush jobs are registered, and
   * any work already scheduled by the library is cancelled. Sync then only
   * progresses while the app process is alive: through `flush()`,
   * `drainUntilEmpty()`, `useAutoSync`, and the in-process retry timer, which
   * this flag does not disable.
   *
   * Data-integrity launch recovery is intentionally **not** suppressed: in-app
   * relaunch recovery and the Android boot-completed recovery pass still run so
   * that items left mid-flight by an OS kill are reconciled. This flag gates
   * *scheduled background dispatch only*, never crash/kill recovery.
   *
   * Useful for QA and development builds (where background dispatch interferes
   * with logs and breakpoints) and for corporate policies that forbid
   * background networking. Honored on both iOS and Android.
   *
   * Defaults to `false` — OS background work is scheduled as normal.
   */
  foregroundOnly?: boolean;
  /**
   * Stored but not used yet: both platforms use fixed identifiers. On iOS those
   * are `com.gabriel-sisjr.syncprovider.background` and
   * `com.gabriel-sisjr.syncprovider.processing`, and `Info.plist` must declare
   * them under `BGTaskSchedulerPermittedIdentifiers` whatever this field says.
   */
  taskIdentifier?: string;
}
