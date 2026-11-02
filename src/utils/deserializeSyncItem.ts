import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import type { SyncItem } from '../types/sync';

import { validateSyncItem } from './validateSyncItem';

/**
 * Deserialize a {@link SyncItem} from its JSON string representation.
 *
 * Validates the input shape via {@link validateSyncItem} after parsing so the
 * caller receives a fully-typed `SyncItem` or a clean
 * {@link SyncError}. Pure function — no I/O.
 *
 * The retry-bookkeeping fields {@link SyncItem.attempts} and
 * {@link SyncItem.nextRetryAt} are validated as numbers when present and
 * **default to `0`** when absent, so JSON written before those fields existed
 * still deserializes cleanly. `0` is a real value (`attempts === 0` ⇒ never
 * tried; `nextRetryAt === 0` ⇒ eligible now), never a sentinel — it is filled
 * in, not skipped or translated to `undefined`.
 *
 * @param json — a JSON string previously produced by `serializeSyncItem`.
 * @returns the parsed {@link SyncItem}.
 * @throws {SyncError} with code {@link SyncErrorCode.INVALID_PAYLOAD} when
 *   the input is not valid JSON or does not conform to {@link SyncItem}.
 *
 * @example
 * const json = await AsyncStorage.getItem('sync:01H...');
 * if (json) {
 *   const item = deserializeSyncItem(json);
 * }
 *
 * @internal
 */
export function deserializeSyncItem(json: string): SyncItem {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch (err) {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'Failed to parse SyncItem JSON.',
      err
    );
  }

  if (parsed === null || typeof parsed !== 'object') {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'Parsed SyncItem must be a non-null object.'
    );
  }

  const candidate = parsed as Partial<SyncItem>;

  if (typeof candidate.id !== 'string' || candidate.id.length === 0) {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'Deserialized SyncItem is missing a non-empty `id`.'
    );
  }
  if (typeof candidate.createdAt !== 'number') {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'Deserialized SyncItem is missing a numeric `createdAt`.'
    );
  }
  if (
    candidate.attempts !== undefined &&
    typeof candidate.attempts !== 'number'
  ) {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'Deserialized SyncItem `attempts` must be a number when present.'
    );
  }
  if (
    candidate.nextRetryAt !== undefined &&
    typeof candidate.nextRetryAt !== 'number'
  ) {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'Deserialized SyncItem `nextRetryAt` must be a number when present.'
    );
  }

  // Re-validate the input-shape subset so URL/method/body invariants hold.
  validateSyncItem(candidate as SyncItem);

  // Backward-compat: items serialized before `attempts` / `nextRetryAt` existed
  // default each to `0`, a real value (never tried / eligible now), so it is
  // filled in here — never treated as a sentinel.
  return {
    ...(candidate as SyncItem),
    attempts: candidate.attempts ?? 0,
    nextRetryAt: candidate.nextRetryAt ?? 0,
  };
}
