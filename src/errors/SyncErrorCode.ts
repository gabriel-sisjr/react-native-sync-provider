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
 * reports arrive on `ITEM_FAILED` and `ITEM_RETRYING` events.
 */
export const SyncErrorCode = {
  /**
   * Generic transport failure: DNS lookup failed, TCP connection refused,
   * TLS handshake failed, etc. Always retried (subject to `RetryPolicy`).
   *
   * Reported on item events. Also the code `flush` rejects with when its cycle
   * dies, whatever the cause.
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
   * The supplied {@link SyncItemInput} is malformed, with the detail in
   * `SyncError.validationErrors`. Permanent — not retried. Also the code most
   * facade functions reject with when native rejects a call, with no
   * `validationErrors`.
   *
   * Thrown by: `enqueue`, `enqueueBatch`, and as that fallback.
   */
  INVALID_PAYLOAD: 'INVALID_PAYLOAD',
  /**
   * The queue is at `SyncOptions.maxQueueSize`. Permanent — the consumer
   * must drain the queue (`flush`) or raise the cap before retrying.
   *
   * Raised by native only, so `enqueue` and `enqueueBatch` currently reject with
   * `INVALID_PAYLOAD` and no `validationErrors` instead.
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
   * The OS rejected the background task registration — typically because the
   * iOS `Info.plist` is missing the task identifier or the Android manifest
   * is missing required permissions.
   *
   * Thrown by: `enableBackgroundSync`.
   */
  BACKGROUND_TASK_REGISTRATION_FAILED: 'BACKGROUND_TASK_REGISTRATION_FAILED',
  /**
   * `SyncItemInput.url` is not a valid absolute http(s) URL. Permanent — not
   * retried. `enqueueBatch` reports URL problems under `INVALID_PAYLOAD`, in
   * `SyncError.validationErrors`.
   *
   * Thrown by: `enqueue`.
   */
  INVALID_URL: 'INVALID_URL',
  /**
   * The server responded with `401 Unauthorized` or `403 Forbidden`. Permanent
   * — the consumer is expected to refresh credentials before re-enqueueing. The
   * exception is a process running on stored `defaultHeaders`: see
   * {@link SyncOptions.defaultHeaders}.
   *
   * Reported on item events.
   */
  UNAUTHORIZED: 'UNAUTHORIZED',
  /**
   * The request exceeded `SyncOptions.requestTimeoutMs`. Always retried
   * (subject to `RetryPolicy`). Android can report this expiry as
   * `NETWORK_ERROR`, which is retried the same way.
   *
   * Reported on item events.
   */
  TIMEOUT: 'TIMEOUT',
  /**
   * A retriable failure with no attempts left in `RetryPolicy.maxAttempts`. A
   * non-retryable failure keeps its real code instead, even on the last attempt.
   *
   * Reported on `ITEM_FAILED`.
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
} as const;
/** See {@link SyncErrorCode} value set. */
export type SyncErrorCode = (typeof SyncErrorCode)[keyof typeof SyncErrorCode];
