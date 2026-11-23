/**
 * Discriminated error codes attached to every {@link SyncError} instance.
 *
 * Implemented as an `as const` object plus a companion type so consumers can
 * use the symbol as both a runtime value (`SyncErrorCode.NETWORK_ERROR`) and
 * a type (`code: SyncErrorCode`). Values are upper `SCREAMING_SNAKE_CASE` to
 * keep the wire format stable on both native sides.
 *
 * Native codes do not reach JS through a rejection yet: the facade maps every
 * native rejection to one fallback code per function. Codes a per-item failure
 * reports arrive on `ITEM_FAILED` and `ITEM_RETRYING` events and are kept as a
 * dead letter's `lastErrorCode`. Codes for a cycle skipped at the start of a
 * flush, or for a cycle that died, arrive on `SYNC_FAILED`.
 */
export const SyncErrorCode = {
  /**
   * Generic transport failure: DNS lookup failed, TCP connection refused,
   * TLS handshake failed, etc. Always retried (subject to `RetryPolicy`).
   *
   * Reported on item events. Also the code of a cycle that dies of a cause
   * other than one of the library's own errors, such as a storage write that
   * fails: that cycle's `SYNC_FAILED` carries it, and on Android so does the
   * `ITEM_RETRYING` of a request the cycle had sent without classifying its
   * answer. And the code `flush` rejects with when its cycle dies, whatever
   * the cause: the facade maps every native rejection of `flush` to it.
   */
  NETWORK_ERROR: 'NETWORK_ERROR',
  /**
   * The server responded with a status code outside 2xx, other than 401/403.
   * Retried when the status is in `RetryPolicy.retryOnStatusCodes`; otherwise
   * permanent.
   *
   * Reported on item events.
   */
  SERVER_ERROR: 'SERVER_ERROR',
  /**
   * The supplied {@link SyncItemInput} or {@link SyncOptions} is malformed,
   * with the detail in `SyncError.validationErrors`. Permanent — not retried.
   * Also the code most facade functions reject with when native rejects a call,
   * with no `validationErrors`.
   *
   * Thrown by: `enqueue`, `enqueueBatch`, `configureSync` (for
   * `minNetworkQuality`, `healthCheckTtlMs` and `healthCheckBackoffMs`), and as
   * that fallback.
   *
   * Also reported on item events, for an attempt that fails on its own with an
   * error the engine cannot classify, neither an I/O failure nor an HTTP
   * answer, such as an Android request that OkHttp cannot build: on
   * `ITEM_FAILED`, as the dead letter's `lastErrorCode`, and on Android in
   * `SyncResult.errors`. Permanent: the item is dead-lettered.
   */
  INVALID_PAYLOAD: 'INVALID_PAYLOAD',
  /**
   * The queue is at `SyncOptions.maxQueueSize`. Permanent — the consumer
   * must drain the queue (`flush`) or raise the cap before retrying.
   *
   * Raised by native only, so `enqueue`, `enqueueBatch` and `replayDeadLetter`
   * currently reject with `INVALID_PAYLOAD` and no `validationErrors` instead.
   */
  QUEUE_FULL: 'QUEUE_FULL',
  /**
   * The native HybridObject could not be resolved at runtime, or the web entry
   * is in use. It only reaches you from the JS layer: a native rejection carrying
   * this code maps to the facade's fallback code like any other.
   *
   * Thrown by: every facade method when the native module is missing.
   */
  NATIVE_MODULE_UNAVAILABLE: 'NATIVE_MODULE_UNAVAILABLE',
  /**
   * The OS rejected the background task registration. On iOS,
   * `BGTaskScheduler` refused the app-refresh request, for example because the
   * task identifier is missing from `Info.plist` or Background App Refresh is
   * turned off. On Android, `WorkManager` refused the periodic work request or
   * is not initialized.
   *
   * Thrown by: `enableBackgroundSync`. Also the code every native rejection
   * of `enableBackgroundSync`, `disableBackgroundSync` and
   * `isBackgroundSyncEnabled` maps to.
   */
  BACKGROUND_TASK_REGISTRATION_FAILED: 'BACKGROUND_TASK_REGISTRATION_FAILED',
  /**
   * `SyncItemInput.url` or `SyncOptions.healthCheckUrl` is not a valid absolute
   * http(s) URL. Permanent — not retried. `enqueueBatch` reports URL problems
   * under `INVALID_PAYLOAD`, in `SyncError.validationErrors`.
   *
   * Thrown by: `enqueue`, `configureSync`.
   */
  INVALID_URL: 'INVALID_URL',
  /**
   * The server responded with `401 Unauthorized` or `403 Forbidden`. Permanent
   * — the item becomes a dead letter, and the consumer is expected to refresh
   * credentials (`configureSync`) before replaying it with `replayDeadLetter`.
   * The exception is a process running on stored `defaultHeaders`: see
   * {@link SyncOptions.defaultHeaders}.
   *
   * Reported on item events.
   */
  UNAUTHORIZED: 'UNAUTHORIZED',
  /**
   * The request exceeded `SyncOptions.requestTimeoutMs`. Always retried
   * (subject to `RetryPolicy`). On Android, the HTTP client's fixed socket
   * timeouts also report it: 10 s to connect, and 30 s for a read or a write
   * that stalls. A `requestTimeoutMs` above 2147483647 ms (about 24.8 days),
   * `Infinity` included, is capped there on both platforms.
   *
   * Reported on item events.
   */
  TIMEOUT: 'TIMEOUT',
  /**
   * A retriable failure with no attempts left in `RetryPolicy.maxAttempts`. A
   * non-retryable failure keeps its real code instead, even on the last attempt.
   * Also the code of an item whose attempts had already reached the limit when
   * a cycle took it, for example because the limit was lowered: it fails
   * without a request, and its dead letter's `lastErrorMessage` is
   * `"Retry budget exhausted"`.
   *
   * Reported on `ITEM_FAILED`. Also the `lastErrorCode` of a dead letter whose
   * stored code is empty or unknown, such as an item that failed under an
   * earlier version without recording one.
   */
  MAX_ATTEMPTS_EXCEEDED: 'MAX_ATTEMPTS_EXCEEDED',
  /**
   * Reserved. Neither platform raises it: ids are native-assigned ULIDs, and no
   * content fingerprint is computed.
   */
  DUPLICATE_ITEM: 'DUPLICATE_ITEM',
  /**
   * A `drainUntilEmpty()` loop is already running for this process. The drain
   * controller is a process-wide singleton, so only one bounded drain may run
   * at a time. Await the active drain before starting another; after
   * `cancelDrain()` it still has to settle first. Permanent — not retried.
   *
   * Thrown by: `drainUntilEmpty`.
   */
  DRAIN_ALREADY_ACTIVE: 'DRAIN_ALREADY_ACTIVE',
  /**
   * A flush cycle was skipped because the current network quality is below
   * `SyncOptions.minNetworkQuality`. No item changes and no attempt is spent.
   * A `flush()` call is not repeated.
   *
   * Reported on `SYNC_FAILED` by the skipped flush: a `flush()` call, a run of
   * the Android retry kick or periodic worker, or an iOS background task. The
   * in-process retry timer checks the gate before it flushes and holds a wake
   * the gate blocks without reporting it, until the quality reaches the
   * minimum.
   */
  NETWORK_QUALITY_TOO_LOW: 'NETWORK_QUALITY_TOO_LOW',
  /**
   * A flush cycle was skipped because the `HEAD` request to
   * `SyncOptions.healthCheckUrl` did not end with a final `2xx` status (any
   * other status, a transport error and its 2 s timeout all count), or because
   * the backoff after such a failure (`SyncOptions.healthCheckBackoffMs`) was
   * still running. No item changes and no attempt is spent.
   *
   * Reported on `SYNC_FAILED` by the skipped flush. The in-process retry timer
   * does not flush during the backoff, which for automatic retries lasts at
   * least 1 s after the failed check, and reports nothing; a flush it starts
   * afterwards reports a failed check like any other.
   */
  HEALTH_CHECK_FAILED: 'HEALTH_CHECK_FAILED',
  /**
   * The item was moved to the dead-letter queue. It is never a dead letter's
   * `lastErrorCode`: the event's `metadata.lastErrorCode` and the dead letter
   * carry the real failure code.
   *
   * Reported on `ITEM_DEAD_LETTERED`.
   */
  DEAD_LETTERED: 'DEAD_LETTERED',
  /**
   * The retry decider answered `DROP` or `DEAD_LETTER` for a failed attempt
   * that the retry policy would have retried. Permanent: the item is deleted
   * (`DROP`) or moved to the dead-letter queue (`DEAD_LETTER`).
   *
   * Reported on `ITEM_FAILED`, with the attempt's `statusCode` when an HTTP
   * answer came back. After a `DEAD_LETTER`, also the `metadata.lastErrorCode`
   * of the `ITEM_DEAD_LETTERED` that follows and the dead letter's
   * `lastErrorCode`, while its `lastErrorMessage` keeps the real failure.
   */
  RETRY_DECIDER_REJECTED: 'RETRY_DECIDER_REJECTED',
  /**
   * The header resolver or the `beforeDispatch` hook failed for an item, or
   * the hook answered `SKIP`. A failure is a throw or a rejection, an invalid
   * result, or no answer within `SyncOptions.callbackTimeoutMs`. Nothing is
   * sent and no attempt is spent: the item goes back to `PENDING` and becomes
   * due again no sooner than 60 s later.
   *
   * Reported on `ITEM_RETRYING`, without a `statusCode`.
   */
  INTERCEPTOR_FAILED: 'INTERCEPTOR_FAILED',
  // New members go at the end: nitrogen numbers the generated native enum
  // cases by position.
} as const;
/** See {@link SyncErrorCode} value set. */
export type SyncErrorCode = (typeof SyncErrorCode)[keyof typeof SyncErrorCode];
