import { SyncErrorCode } from './SyncErrorCode';

/**
 * Domain error thrown by every public SyncProvider operation.
 *
 * `SyncError` carries a stable {@link SyncErrorCode} that consumers can
 * branch on without parsing `message` strings. The optional `cause` field
 * follows the ES2022 `Error` shape and may carry the original platform-level
 * error (a Nitro-wrapped Swift/Kotlin throwable, a network error, etc.).
 *
 * The constructor restores the prototype chain explicitly so that
 * `instanceof SyncError` keeps working when the error is rethrown across the
 * Nitro bridge or serialized through React's error boundaries.
 *
 * @example
 * import { enqueue, SyncError, SyncErrorCode, HttpMethod } from '@gabriel-sisjr/react-native-sync-provider';
 *
 * try {
 *   await enqueue({ method: HttpMethod.POST, url: 'https://api.example.com/x' });
 * } catch (e) {
 *   if (e instanceof SyncError && e.code === SyncErrorCode.QUEUE_FULL) {
 *     // back off, drain the queue, and retry
 *   } else {
 *     throw e;
 *   }
 * }
 */
export class SyncError extends Error {
  /** Stable, machine-readable failure reason. */
  readonly code: SyncErrorCode;
  /** Optional underlying error captured for diagnostics. */
  override readonly cause?: unknown;

  /**
   * @param code — the {@link SyncErrorCode} value classifying this error.
   * @param message — human-readable description for logs / dev tooling.
   * @param cause — optional underlying error (forwarded native error,
   *   network exception, etc.).
   */
  constructor(code: SyncErrorCode, message: string, cause?: unknown) {
    super(message);
    this.name = 'SyncError';
    this.code = code;
    if (cause !== undefined) {
      this.cause = cause;
    }
    // Restore the prototype so `instanceof SyncError` survives transpilation
    // targets that strip `__proto__` and bridge boundaries that re-throw.
    Object.setPrototypeOf(this, SyncError.prototype);
  }
}
