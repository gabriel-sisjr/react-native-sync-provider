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
   * Optional `Content-Type` header. When supplied, takes precedence over any
   * `Content-Type` present in `headers`.
   */
  contentType?: string;
  /**
   * Optional dispatch priority. Defaults to {@link SyncPriority.NORMAL} on
   * the native side when omitted.
   */
  priority?: SyncPriority;
  /**
   * Optional opaque metadata. Persisted alongside the item and echoed back in
   * `SyncEvent.metadata` for the events that involve this item. Values must
   * be strings.
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
   * Optional `Content-Type` header. When set, native will prefer it over any
   * value already present in `headers`.
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
}

/* -------------------------------------------------------------------------- */
/*                                Retry policy                                */
/* -------------------------------------------------------------------------- */

/**
 * Declarative retry policy applied by the native layer to each in-flight
 * item.
 *
 * @remarks
 * v0.1 of the library is intentionally declarative — the per-attempt
 * `shouldRetry(error)` callback is deferred to v0.2, since it requires a
 * JS-alive bridge round-trip on every retry decision.
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
   * are treated as permanent failures.
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
   * Maximum number of items dispatched concurrently in a single flush cycle.
   * Native default applies when omitted.
   */
  batchSize?: number;
  /**
   * Per-request HTTP timeout (in ms). Native default applies when omitted.
   */
  requestTimeoutMs?: number;
  /**
   * Maximum number of items the queue may hold. New enqueues throw
   * `SyncErrorCode.QUEUE_FULL` once the cap is reached. `0` or omitted means
   * "no cap".
   */
  maxQueueSize?: number;
  /**
   * If `true` (default), the queue is persisted to disk (Core Data on iOS,
   * Room on Android) so it survives process death.
   */
  persistQueue?: boolean;
  /**
   * Headers added to every dispatched request. Per-item headers override
   * default headers on key collision.
   */
  defaultHeaders?: Record<string, string>;
}

/* -------------------------------------------------------------------------- */
/*                                  Results                                   */
/* -------------------------------------------------------------------------- */

/**
 * Outcome of a single flush cycle. Returned by `flush()` and persisted to the
 * sync history (see `getSyncHistory`).
 */
export interface SyncResult {
  /** Epoch ms — when the flush cycle started. */
  startedAt: number;
  /** Epoch ms — when the flush cycle finished. */
  finishedAt: number;
  /** Number of items that succeeded in this cycle. */
  successCount: number;
  /** Number of items that failed permanently in this cycle. */
  failureCount: number;
  /** ULIDs of items that succeeded (parallel to `successCount`). */
  succeededIds: string[];
  /** ULIDs of items that failed (parallel to `failureCount`). */
  failedIds: string[];
  /**
   * Per-failed-item error codes, keyed by item ULID. Values are the string
   * representation of {@link SyncErrorCode}.
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
 * - `SYNC_PROGRESS` — `progress` (0..1) set.
 * - `SYNC_FAILED` — `errorCode` set.
 * - `ITEM_SUCCEEDED` — `itemId`, `statusCode` set.
 * - `ITEM_FAILED` — `itemId`, `errorCode`, `statusCode?` set.
 * - `ITEM_RETRYING` — `itemId`, `attempt`, `errorCode` set.
 * - `QUEUE_CLEARED` — base fields only.
 * - `CONNECTION_CHANGED` — `connectionStatus` set.
 * - `PAUSED` / `RESUMED` — base fields only.
 * - `BACKGROUND_SYNC_STARTED` / `BACKGROUND_SYNC_COMPLETED` — base fields only.
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
  /** Failure code. Set for `SYNC_FAILED`, `ITEM_FAILED`, `ITEM_RETRYING`. */
  errorCode?: SyncErrorCode;
  /** HTTP status code, when applicable. */
  statusCode?: number;
  /** 1-based retry attempt number. Only set for `ITEM_RETRYING`. */
  attempt?: number;
  /** Connection status snapshot. Only set for `CONNECTION_CHANGED`. */
  connectionStatus?: ConnectionStatus;
  /**
   * Echoed item metadata for item-scoped events. May be absent even when the
   * item was enqueued with metadata (e.g., for `ITEM_REMOVED` after restart).
   */
  metadata?: Record<string, string>;
}

/* -------------------------------------------------------------------------- */
/*                              Background sync                               */
/* -------------------------------------------------------------------------- */

/**
 * Configuration accepted by `enableBackgroundSync`.
 *
 * Each field maps to a constraint on `BGTaskScheduler` (iOS) and
 * `WorkManager` (Android). Where the OS-imposed minimum differs from the
 * value supplied here, the OS value wins (Android, in particular, clamps
 * `minimumIntervalMs` to >= 15 minutes for periodic work).
 */
export interface BackgroundSyncOptions {
  /**
   * Minimum interval between background sync windows, in ms.
   *
   * Android `WorkManager` clamps this to a minimum of 15 minutes for periodic
   * work. iOS `BGTaskScheduler` treats this as a soft hint — the OS schedules
   * the task at its discretion based on system conditions.
   */
  minimumIntervalMs: number;
  /** If `true`, only run when the device is plugged in. */
  requiresCharging?: boolean;
  /** If `true`, only run on an unmetered network (Wi-Fi/ethernet). */
  requiresUnmeteredNetwork?: boolean;
  /** If `true`, only run when the device is idle. */
  requiresDeviceIdle?: boolean;
  /**
   * Override for the OS-level task identifier.
   *
   * - iOS: registered with `BGTaskScheduler.shared.register(forTaskWithIdentifier:)`.
   *   Must also be present in `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`.
   * - Android: used as the unique work name passed to `WorkManager.enqueueUniquePeriodicWork`.
   *
   * When omitted, the library uses a stable default derived from the package
   * name.
   */
  taskIdentifier?: string;
}
