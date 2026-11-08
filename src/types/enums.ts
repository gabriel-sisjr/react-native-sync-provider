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
   * The engine retries on its own: a failed item is dispatched again when its
   * cooldown ends, and pending items are dispatched on `resumeSync()`.
   *
   * Enqueueing does **not** trigger a native flush. To dispatch new items, mount
   * `useAutoSync` or call `flush()`.
   */
  AUTOMATIC: 'AUTOMATIC',
  /**
   * The engine schedules nothing on its own: no retry timer and no Android
   * retry kick. Items are dispatched by an explicit `flush()`, plus any OS
   * windows enabled through `enableBackgroundSync`. Useful for batch upload
   * screens.
   *
   * `useAutoSync` does not read the strategy: while it is mounted with
   * `enabled: true`, it keeps flushing under `MANUAL` too. Pass
   * `enabled: false` for a manual flow.
   */
  MANUAL: 'MANUAL',
  /**
   * Reserved for flushing only on opportunistic windows (foreground resume,
   * charging, unmetered network). Not implemented yet: it currently behaves
   * like AUTOMATIC.
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
 * Coarse network-quality classification (worst → best).
 *
 * Slated for v0.3. It is introduced early so consumer types can depend on its
 * final shape, but in v0.2 it is surfaced ONLY as the optional
 * {@link UseSyncSnapshotResult.networkQuality} field and is ALWAYS `undefined`
 * until the v0.3 classifier ships and begins emitting real measurements.
 *
 * @remarks
 * The ordering of the value set is significant (worst → best) so consumers can
 * compare or threshold on it once the v0.3 classifier populates it.
 */
export const NetworkQuality = {
  /** No usable network path — equivalent to {@link ConnectionStatus.DISCONNECTED}. */
  OFFLINE: 'OFFLINE',
  /** Connected but throughput/latency is poor — sync should be conservative. */
  POOR: 'POOR',
  /** Usable mid-tier link — acceptable for most foreground sync. */
  MODERATE: 'MODERATE',
  /** Healthy link — suitable for normal background and foreground sync. */
  GOOD: 'GOOD',
  /** Best-tier link (typically unmetered Wi-Fi/ethernet) — no constraints. */
  EXCELLENT: 'EXCELLENT',
} as const;
/** See {@link NetworkQuality} value set. */
export type NetworkQuality =
  (typeof NetworkQuality)[keyof typeof NetworkQuality];

/**
 * HTTP verb used to dispatch a {@link SyncItem}.
 *
 * Pulled out as its own alias so consumers can refer to it directly
 * (`HttpMethod.POST`) without re-spelling the union.
 */
export const HttpMethod = {
  /** HTTP GET — must not carry a body. Android drops one if supplied. */
  GET: 'GET',
  /** HTTP POST — typically carries a body. */
  POST: 'POST',
  /** HTTP PUT — full-resource replace. */
  PUT: 'PUT',
  /** HTTP PATCH — partial update. */
  PATCH: 'PATCH',
  /** HTTP DELETE — body is discouraged. Android drops one if supplied. */
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
  /** An item was removed by `removeItem()`. `itemId` is set. */
  ITEM_REMOVED: 'ITEM_REMOVED',
  /** A flush cycle has begun. */
  SYNC_STARTED: 'SYNC_STARTED',
  /**
   * Coarse progress update for the current flush. `progress` (0..1) is set.
   * iOS only: Android does not emit it.
   */
  SYNC_PROGRESS: 'SYNC_PROGRESS',
  /**
   * The current flush cycle finished with no permanent failure. This includes a
   * cycle with nothing eligible (empty queue, or every item cooling down), which
   * records a 0/0 result, and a cycle whose items all went back to wait for a
   * retry.
   */
  SYNC_SUCCEEDED: 'SYNC_SUCCEEDED',
  /**
   * The current flush cycle finished with at least one permanent failure, or
   * died. `errorCode` is set only when it died: per-item failures are on
   * `ITEM_FAILED`.
   */
  SYNC_FAILED: 'SYNC_FAILED',
  /**
   * A single item dispatched successfully. `itemId`, `statusCode` and `attempt`
   * are set.
   */
  ITEM_SUCCEEDED: 'ITEM_SUCCEEDED',
  /**
   * A single item failed permanently: a non-retryable failure, or a retriable
   * one with no attempts left. `itemId`, `errorCode` and `attempt` are set, plus
   * `statusCode` when applicable.
   */
  ITEM_FAILED: 'ITEM_FAILED',
  /**
   * A single item went back to wait for a retry.
   * `itemId`, `attempt` and `errorCode` are set.
   */
  ITEM_RETRYING: 'ITEM_RETRYING',
  /** The queue was cleared via `clearQueue()`. */
  QUEUE_CLEARED: 'QUEUE_CLEARED',
  /**
   * Network connectivity changed. `connectionStatus` is set; richer detail is
   * available via `getConnectionStatus()`.
   */
  CONNECTION_CHANGED: 'CONNECTION_CHANGED',
  /** Sync was paused via `pauseSync()`. */
  PAUSED: 'PAUSED',
  /**
   * Sync was resumed via `resumeSync()`. iOS also emits it at launch when
   * recovery put interrupted items back in the queue.
   */
  RESUMED: 'RESUMED',
  /**
   * OS-scheduled background sync window started: a BGTask on iOS, the periodic
   * worker on Android. Android's one-time retry kick emits neither background
   * event.
   */
  BACKGROUND_SYNC_STARTED: 'BACKGROUND_SYNC_STARTED',
  /**
   * The same window finished. iOS emits it whether the window's flush
   * succeeded or failed, with `metadata.ok` set to `"false"` on a failure.
   * Android emits it only after the flush returns, so a worker whose flush
   * throws emits none. `errorCode` is not set on either platform.
   */
  BACKGROUND_SYNC_COMPLETED: 'BACKGROUND_SYNC_COMPLETED',
} as const;
/** See {@link SyncEventType} value set. */
export type SyncEventType = (typeof SyncEventType)[keyof typeof SyncEventType];
