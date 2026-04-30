/**
 * Public enum-like value sets shared between the JS facade, the Nitro bridge
 * spec, the React hooks and consumer applications.
 *
 * Each enum is implemented as an `as const` object plus a companion type with
 * the same name. Consumers can therefore use the symbol as both a runtime
 * value (`ConnectionStatus.CONNECTED`) and a type
 * (`status: ConnectionStatus`). All string values are upper `SCREAMING_SNAKE_CASE`
 * to keep the wire format stable and unambiguous on the native side.
 */

/* -------------------------------------------------------------------------- */
/*                              Connection enums                              */
/* -------------------------------------------------------------------------- */

/**
 * High-level network connectivity status reported by the native layer.
 *
 * Reported by {@link ConnectionState.status} and surfaced through
 * `getConnectionStatus`, the `CONNECTION_CHANGED` sync event, and the
 * `useConnection` hook.
 */
export const ConnectionStatus = {
  /** Device has an active internet path (Wi-Fi, cellular, ethernet, etc.). */
  CONNECTED: 'CONNECTED',
  /** Device is offline — no usable network path is available. */
  DISCONNECTED: 'DISCONNECTED',
  /**
   * Device is online but the active link is metered (cellular data, mobile
   * hotspot, paid Wi-Fi). Background sync should be more conservative on
   * metered links.
   */
  METERED: 'METERED',
  /**
   * Connectivity could not be determined yet (initial state before the OS
   * reports a path, or transient state while reachability is being probed).
   */
  UNKNOWN: 'UNKNOWN',
} as const;
/** See {@link ConnectionStatus} value set. */
export type ConnectionStatus =
  (typeof ConnectionStatus)[keyof typeof ConnectionStatus];

/**
 * Physical/logical transport reported by the native connectivity APIs.
 *
 * iOS derives this from `NWPathMonitor`; Android derives it from
 * `ConnectivityManager` + `NetworkCapabilities`.
 */
export const ConnectionType = {
  /** Wi-Fi network (typically unmetered, but verify {@link ConnectionStatus.METERED}). */
  WIFI: 'WIFI',
  /** Cellular data (LTE, 5G, etc.). Always treat as metered unless OS says otherwise. */
  CELLULAR: 'CELLULAR',
  /** Wired ethernet — present on tablets with USB-Ethernet adapters and on tvOS. */
  ETHERNET: 'ETHERNET',
  /** Personal area network bridged over Bluetooth (PAN). */
  BLUETOOTH: 'BLUETOOTH',
  /** Active VPN tunnel — the underlying transport may be any of the above. */
  VPN: 'VPN',
  /** A transport not covered by the named values above. */
  OTHER: 'OTHER',
  /** No transport — paired with {@link ConnectionStatus.DISCONNECTED}. */
  NONE: 'NONE',
  /** Transport could not be determined yet. */
  UNKNOWN: 'UNKNOWN',
} as const;
/** See {@link ConnectionType} value set. */
export type ConnectionType =
  (typeof ConnectionType)[keyof typeof ConnectionType];

/* -------------------------------------------------------------------------- */
/*                                Sync enums                                  */
/* -------------------------------------------------------------------------- */

/**
 * Strategy that governs WHEN the queue is drained.
 *
 * Used by {@link SyncOptions.strategy}.
 */
export const SyncStrategy = {
  /**
   * The library flushes automatically as soon as the queue is non-empty and
   * connectivity allows.
   */
  AUTOMATIC: 'AUTOMATIC',
  /**
   * The queue is only drained when the consumer explicitly calls `flush()`.
   * Useful for batch upload screens.
   */
  MANUAL: 'MANUAL',
  /**
   * Like AUTOMATIC, but only flushes on opportunistic windows (foreground
   * resume, charging, unmetered network) — defers work otherwise.
   */
  OPPORTUNISTIC: 'OPPORTUNISTIC',
} as const;
/** See {@link SyncStrategy} value set. */
export type SyncStrategy = (typeof SyncStrategy)[keyof typeof SyncStrategy];

/**
 * Per-item priority — higher priority items are dispatched first within a
 * single flush cycle.
 */
export const SyncPriority = {
  /** Drain before NORMAL/LOW. Use sparingly to avoid starvation. */
  HIGH: 'HIGH',
  /** Default bucket. */
  NORMAL: 'NORMAL',
  /** Drain after HIGH/NORMAL — suitable for telemetry, analytics, etc. */
  LOW: 'LOW',
} as const;
/** See {@link SyncPriority} value set. */
export type SyncPriority = (typeof SyncPriority)[keyof typeof SyncPriority];

/**
 * Backoff curve used between retry attempts.
 *
 * Used by {@link RetryPolicy.backoff}.
 */
export const BackoffStrategy = {
  /** `delay = baseDelayMs * attempt` (capped at `maxDelayMs`). */
  LINEAR: 'LINEAR',
  /** `delay = baseDelayMs * 2^(attempt-1)` (capped at `maxDelayMs`). */
  EXPONENTIAL: 'EXPONENTIAL',
  /**
   * `delay = baseDelayMs * fib(attempt)` (capped at `maxDelayMs`).
   * Smoother growth than EXPONENTIAL while still super-linear.
   */
  FIBONACCI: 'FIBONACCI',
} as const;
/** See {@link BackoffStrategy} value set. */
export type BackoffStrategy =
  (typeof BackoffStrategy)[keyof typeof BackoffStrategy];

/**
 * HTTP verb used to dispatch a {@link SyncItem}.
 *
 * Pulled out as its own alias so consumers can refer to it directly
 * (`HttpMethod.POST`) without re-spelling the union.
 */
export const HttpMethod = {
  /** HTTP GET — must not carry a body. */
  GET: 'GET',
  /** HTTP POST — typically carries a body. */
  POST: 'POST',
  /** HTTP PUT — full-resource replace. */
  PUT: 'PUT',
  /** HTTP PATCH — partial update. */
  PATCH: 'PATCH',
  /** HTTP DELETE — body is optional and discouraged. */
  DELETE: 'DELETE',
} as const;
/** See {@link HttpMethod} value set. */
export type HttpMethod = (typeof HttpMethod)[keyof typeof HttpMethod];

/* -------------------------------------------------------------------------- */
/*                             Sync event enums                               */
/* -------------------------------------------------------------------------- */

/**
 * Event types emitted by the native layer over the `'sync-event'` channel.
 *
 * The corresponding payload shape is the flat {@link SyncEvent} object — refer
 * to its TSDoc for which optional fields are populated for which event type.
 */
export const SyncEventType = {
  /** A new item was accepted into the queue. `itemId` is set. */
  ITEM_ENQUEUED: 'ITEM_ENQUEUED',
  /** An item was removed from the queue (manual or post-success). `itemId` is set. */
  ITEM_REMOVED: 'ITEM_REMOVED',
  /** A flush cycle has begun. */
  SYNC_STARTED: 'SYNC_STARTED',
  /** Coarse progress update for the current flush. `progress` (0..1) is set. */
  SYNC_PROGRESS: 'SYNC_PROGRESS',
  /** The current flush cycle finished with no failures. */
  SYNC_SUCCEEDED: 'SYNC_SUCCEEDED',
  /**
   * The current flush cycle finished with at least one failure.
   * `errorCode` carries the dominant failure reason.
   */
  SYNC_FAILED: 'SYNC_FAILED',
  /**
   * A single item dispatched successfully. `itemId` and `statusCode` are set.
   */
  ITEM_SUCCEEDED: 'ITEM_SUCCEEDED',
  /**
   * A single item failed permanently (after exhausting its retry budget).
   * `itemId`, `errorCode` and (when applicable) `statusCode` are set.
   */
  ITEM_FAILED: 'ITEM_FAILED',
  /**
   * A single item was scheduled for retry.
   * `itemId`, `attempt` and `errorCode` are set.
   */
  ITEM_RETRYING: 'ITEM_RETRYING',
  /** The queue was cleared via `clearQueue()` or by reaching `maxQueueSize`. */
  QUEUE_CLEARED: 'QUEUE_CLEARED',
  /**
   * Network connectivity changed. `connectionStatus` is set; richer detail is
   * available via `getConnectionStatus()`.
   */
  CONNECTION_CHANGED: 'CONNECTION_CHANGED',
  /** Sync was paused via `pauseSync()`. */
  PAUSED: 'PAUSED',
  /** Sync was resumed via `resumeSync()`. */
  RESUMED: 'RESUMED',
  /** OS-scheduled background sync window started. */
  BACKGROUND_SYNC_STARTED: 'BACKGROUND_SYNC_STARTED',
  /** OS-scheduled background sync window finished (success or otherwise). */
  BACKGROUND_SYNC_COMPLETED: 'BACKGROUND_SYNC_COMPLETED',
} as const;
/** See {@link SyncEventType} value set. */
export type SyncEventType = (typeof SyncEventType)[keyof typeof SyncEventType];
