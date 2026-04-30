import { SyncError } from '../errors/SyncError';
import type { SyncErrorCode } from '../errors/SyncErrorCode';

/**
 * Re-throw an unknown error from the native layer as a typed
 * {@link SyncError}.
 *
 * Behavior:
 *   - When `err` is already a {@link SyncError}, it is rethrown unchanged
 *     (preserving the original `code` and `cause`).
 *   - Otherwise, a new {@link SyncError} is thrown with `fallbackCode`,
 *     a best-effort message, and `cause = err`.
 *
 * The function never returns — it always throws — so it is typed `: never`
 * for use at the end of a `catch` block: `} catch (err) { wrapNativeError(err, ...); }`.
 *
 * @param err — the value caught from a native Promise rejection.
 * @param fallbackCode — code to use when `err` is not already a SyncError.
 *
 * @internal
 */
export function wrapNativeError(
  err: unknown,
  fallbackCode: SyncErrorCode
): never {
  if (err instanceof SyncError) {
    throw err;
  }
  const message =
    err instanceof Error &&
    typeof err.message === 'string' &&
    err.message.length > 0
      ? err.message
      : `Native call failed with code ${fallbackCode}.`;
  throw new SyncError(fallbackCode, message, err);
}
