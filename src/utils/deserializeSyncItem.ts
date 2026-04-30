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

  // Re-validate the input-shape subset so URL/method/body invariants hold.
  validateSyncItem(candidate as SyncItem);

  return candidate as SyncItem;
}
