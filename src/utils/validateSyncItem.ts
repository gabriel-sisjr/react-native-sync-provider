import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import { HttpMethod, SyncPriority } from '../types/enums';
import type { SyncItemInput } from '../types/sync';

const ALLOWED_METHODS = new Set<string>(Object.values(HttpMethod));
const ALLOWED_PRIORITIES = new Set<string>(Object.values(SyncPriority));

/**
 * Validate a {@link SyncItemInput} before it crosses the JS↔native bridge.
 *
 * Performs a tight, locally checkable subset of the contract documented in
 * {@link SyncItemInput} so that consumers fail fast with a clean
 * {@link SyncError} instead of a Nitro-level error from the native side:
 *
 * - `method` must be one of {@link HttpMethod}.
 * - `url` must be a non-empty absolute `http://` or `https://` URL.
 * - `body`, when present, must be a string.
 * - `priority`, when present, must be one of {@link SyncPriority}.
 *
 * @param input — the user-supplied request shape.
 * @throws {SyncError} with code {@link SyncErrorCode.INVALID_URL} when the
 *   URL is missing, empty, or not absolute http(s).
 * @throws {SyncError} with code {@link SyncErrorCode.INVALID_PAYLOAD} for
 *   every other contract violation.
 *
 * @internal
 */
export function validateSyncItem(input: SyncItemInput): void {
  if (input === null || typeof input !== 'object') {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput must be a non-null object.'
    );
  }

  if (typeof input.method !== 'string' || !ALLOWED_METHODS.has(input.method)) {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      `SyncItemInput.method must be one of ${[...ALLOWED_METHODS].join(', ')}.`
    );
  }

  if (typeof input.url !== 'string' || input.url.length === 0) {
    throw new SyncError(
      SyncErrorCode.INVALID_URL,
      'SyncItemInput.url must be a non-empty string.'
    );
  }
  if (!/^https?:\/\/[^\s]+$/i.test(input.url)) {
    throw new SyncError(
      SyncErrorCode.INVALID_URL,
      `SyncItemInput.url must be an absolute http(s) URL (got: "${input.url}").`
    );
  }

  if (input.body !== undefined && typeof input.body !== 'string') {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput.body must be a string when supplied. Stringify objects on the JS side first.'
    );
  }

  if (
    input.contentType !== undefined &&
    typeof input.contentType !== 'string'
  ) {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'SyncItemInput.contentType must be a string when supplied.'
    );
  }

  if (
    input.priority !== undefined &&
    !ALLOWED_PRIORITIES.has(input.priority as string)
  ) {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      `SyncItemInput.priority must be one of ${[...ALLOWED_PRIORITIES].join(', ')}.`
    );
  }

  if (input.headers !== undefined) {
    if (typeof input.headers !== 'object' || input.headers === null) {
      throw new SyncError(
        SyncErrorCode.INVALID_PAYLOAD,
        'SyncItemInput.headers must be an object of string→string entries.'
      );
    }
    for (const key of Object.keys(input.headers)) {
      const value = input.headers[key];
      if (typeof value !== 'string') {
        throw new SyncError(
          SyncErrorCode.INVALID_PAYLOAD,
          `SyncItemInput.headers["${key}"] must be a string.`
        );
      }
    }
  }

  if (input.metadata !== undefined) {
    if (typeof input.metadata !== 'object' || input.metadata === null) {
      throw new SyncError(
        SyncErrorCode.INVALID_PAYLOAD,
        'SyncItemInput.metadata must be an object of string→string entries.'
      );
    }
    for (const key of Object.keys(input.metadata)) {
      const value = input.metadata[key];
      if (typeof value !== 'string') {
        throw new SyncError(
          SyncErrorCode.INVALID_PAYLOAD,
          `SyncItemInput.metadata["${key}"] must be a string.`
        );
      }
    }
  }
}
