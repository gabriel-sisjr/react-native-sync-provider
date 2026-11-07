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
 * Coarse network-quality classification, ordered worst → best:
 * `OFFLINE < POOR < MODERATE < GOOD < EXCELLENT`.
 *
 * Reported as {@link ConnectionState.networkQuality} by `getConnectionStatus()`
 * and `useConnection`, and used as the threshold of
 * {@link SyncOptions.minNetworkQuality}. `Object.values(NetworkQuality)` lists
 * the levels in that order, so compare two levels by their index in it, never
 * as strings.
 *
 * Both platforms classify with the same fixed table:
 *
 * - **Measured**, only when {@link SyncOptions.healthCheckUrl} is set: three
 *   sequential `HEAD` requests to that URL, 2 s timeout each, a failure or a
 *   timeout counting as infinitely slow. The median picks the level: under
 *   200 ms → `EXCELLENT`, 200–499 ms → `GOOD`, 500–999 ms → `MODERATE`,
 *   1000 ms or more (including two or more failures) → `POOR`. Any HTTP status
 *   counts as a latency sample: a `5xx` is the health check's concern, not the
 *   classifier's.
 * - **Not measured**, because there is no `healthCheckUrl` or the first
 *   measurement is still pending: an unmetered link → `GOOD`, a metered one →
 *   `MODERATE`.
 * - **No link**, or a link the OS has not reported yet → `OFFLINE`.
 * - **Caps**, applied last: an Android link without
 *   `NET_CAPABILITY_VALIDATED` → at most `POOR`; a metered (Android) or
 *   expensive (iOS) link → at most `GOOD`; iOS Low Data Mode → at most
 *   `MODERATE`.
 *
 * Without `healthCheckUrl` nothing is measured, so the classification never
 * reaches `EXCELLENT` and `minNetworkQuality: EXCELLENT` never passes. With
 * it, the measurement is taken again when the network changes and a result is
 * reused for 30 s. Nothing is sent while the device is offline, and nothing at
 * all without `healthCheckUrl`.
 *
 * @example
 * const LEVELS = Object.values(NetworkQuality);
 * const isAtLeast = (quality: NetworkQuality, min: NetworkQuality) =>
 *   LEVELS.indexOf(quality) >= LEVELS.indexOf(min);
 *
 * const { networkQuality } = await getConnectionStatus();
 * if (isAtLeast(networkQuality, NetworkQuality.GOOD)) startUpload();
 */
export const NetworkQuality = {
  /**
   * No link, or a link the OS has not reported yet: the status is
   * {@link ConnectionStatus.DISCONNECTED} or {@link ConnectionStatus.UNKNOWN}.
   * The only level `minNetworkQuality: POOR` blocks.
   */
  OFFLINE: 'OFFLINE',
  /**
   * Online, but slow: a median of 1000 ms or more, two or more failed probes,
   * or an Android link without `NET_CAPABILITY_VALIDATED`.
   */
  POOR: 'POOR',
  /**
   * A median of 500–999 ms, a metered link with no measurement, or a faster
   * link held back by iOS Low Data Mode.
   */
  MODERATE: 'MODERATE',
  /**
   * A median of 200–499 ms, or an unmetered link with no measurement. The
   * highest level a metered or expensive link reaches.
   */
  GOOD: 'GOOD',
  /**
   * A median under 200 ms on a link no cap lowers. Only reported with
   * `healthCheckUrl` set.
   */
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
  /**
   * An item was accepted into the queue, by `enqueue()` / `enqueueBatch()` or
   * by `replayDeadLetter()` putting a dead letter back. `itemId` is set.
   */
  ITEM_ENQUEUED: 'ITEM_ENQUEUED',
  /**
   * A row was deleted by `removeItem()`, whether a queued item or a dead
   * letter, or by `discardDeadLetter()`. `itemId` is set. Not emitted when no
   * row had that id.
   */
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
   * The current flush cycle finished with at least one permanent failure or
   * died, or a check at the start of the flush skipped the cycle. Per-item
   * failures are on `ITEM_FAILED`, so `errorCode` is set only when:
   *
   * - the cycle died, with the code of the failure;
   * - the network-quality gate skipped it: `NETWORK_QUALITY_TOO_LOW` (see
   *   `SyncOptions.minNetworkQuality`);
   * - the health check skipped it, or its backoff was running:
   *   `HEALTH_CHECK_FAILED` (see `SyncOptions.healthCheckUrl`).
   *
   * A skipped cycle has no `SYNC_STARTED` before it and writes no history row.
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
   * `statusCode` when applicable. The item has already left the queue for the
   * dead-letter queue, and `ITEM_DEAD_LETTERED` follows right after.
   */
  ITEM_FAILED: 'ITEM_FAILED',
  /**
   * A single item went back to wait for a retry.
   * `itemId`, `attempt` and `errorCode` are set.
   */
  ITEM_RETRYING: 'ITEM_RETRYING',
  /**
   * The queue was cleared via `clearQueue()`, dead letters included. Emitted
   * once per call.
   */
  QUEUE_CLEARED: 'QUEUE_CLEARED',
  /**
   * Network connectivity or its quality changed. `connectionStatus` is set.
   * A change of `networkQuality` alone also emits it, with an unchanged
   * `connectionStatus`: read `getConnectionStatus()` for the new quality and
   * the rest of the snapshot. Not emitted when nothing changed.
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
  /**
   * A permanently failed item was moved to the dead-letter queue. Emitted
   * synchronously right after that item's `ITEM_FAILED`. `itemId` is set,
   * `errorCode` is `DEAD_LETTERED`, and `metadata.lastErrorCode` carries the
   * real failure code, the same one `ITEM_FAILED` reported. Read the dead
   * letter with `listDeadLetter()`.
   */
  ITEM_DEAD_LETTERED: 'ITEM_DEAD_LETTERED',
  // New members go at the end: nitrogen numbers the generated native enum
  // cases by position.
} as const;
/** See {@link SyncEventType} value set. */
export type SyncEventType = (typeof SyncEventType)[keyof typeof SyncEventType];
