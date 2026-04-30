/**
 * Discriminated error codes attached to every {@link SyncError} instance.
 *
 * Implemented as an `as const` object plus a companion type so consumers can
 * use the symbol as both a runtime value (`SyncErrorCode.NETWORK_ERROR`) and
 * a type (`code: SyncErrorCode`). Values are upper `SCREAMING_SNAKE_CASE` to
 * keep the wire format stable on both native sides.
 */
export const SyncErrorCode = {
  /**
   * Generic transport failure: DNS lookup failed, TCP connection refused,
   * TLS handshake failed, etc. Always retried (subject to `RetryPolicy`).
   *
   * Thrown by: `flush`.
   */
  NETWORK_ERROR: 'NETWORK_ERROR',
  /**
   * The server responded with a 5xx status code (or a 4xx status code in
   * `RetryPolicy.retryOnStatusCodes`). Treated as retryable.
   *
   * Thrown by: `flush`.
   */
  SERVER_ERROR: 'SERVER_ERROR',
  /**
   * The supplied {@link SyncItemInput} is malformed (empty body for a method
   * that requires one, illegal header value, etc.). Permanent — not retried.
   *
   * Thrown by: `enqueue`, `enqueueBatch`.
   */
  INVALID_PAYLOAD: 'INVALID_PAYLOAD',
  /**
   * The queue is at `SyncOptions.maxQueueSize`. Permanent — the consumer
   * must drain the queue (`flush`) or raise the cap before retrying.
   *
   * Thrown by: `enqueue`, `enqueueBatch`.
   */
  QUEUE_FULL: 'QUEUE_FULL',
  /**
   * The native HybridObject could not be resolved at runtime. Always thrown
   * by the JS facade and the `*.tsx` (non-native) fallback files.
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
   * retried.
   *
   * Thrown by: `enqueue`, `enqueueBatch`.
   */
  INVALID_URL: 'INVALID_URL',
  /**
   * The server responded with `401 Unauthorized` (or `403 Forbidden` when
   * the policy treats auth failures as permanent). Permanent — the consumer
   * is expected to refresh credentials before re-enqueueing.
   *
   * Thrown by: `flush`.
   */
  UNAUTHORIZED: 'UNAUTHORIZED',
  /**
   * The request exceeded `SyncOptions.requestTimeoutMs`. Always retried
   * (subject to `RetryPolicy`).
   *
   * Thrown by: `flush`.
   */
  TIMEOUT: 'TIMEOUT',
  /**
   * An item exhausted its `RetryPolicy.maxAttempts` budget without
   * succeeding. The item is moved to the failed bucket of the
   * `SyncResult`.
   *
   * Thrown by: `flush` (per-item; surfaced in `SyncResult.errors`).
   */
  MAX_ATTEMPTS_EXCEEDED: 'MAX_ATTEMPTS_EXCEEDED',
  /**
   * An item with the supplied id (or content fingerprint) is already in the
   * queue. Permanent — not retried.
   *
   * Thrown by: `enqueue`, `enqueueBatch`.
   */
  DUPLICATE_ITEM: 'DUPLICATE_ITEM',
} as const;
/** See {@link SyncErrorCode} value set. */
export type SyncErrorCode = (typeof SyncErrorCode)[keyof typeof SyncErrorCode];
