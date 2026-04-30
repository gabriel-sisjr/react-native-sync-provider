import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import type { SyncItem } from '../types/sync';

/**
 * Serialize a {@link SyncItem} to a compact JSON string.
 *
 * Used by tooling that needs to ship items off-device (logs, debug snapshots,
 * pre-Nitro fallbacks). Pure function — no I/O.
 *
 * @param item — a fully-populated read-shape item (`id` and `createdAt`
 *   present).
 * @returns the JSON-stringified item.
 * @throws {SyncError} with code {@link SyncErrorCode.INVALID_PAYLOAD} when
 *   the item cannot be serialized (cyclic structure, BigInt values, etc.).
 *
 * @example
 * const json = serializeSyncItem(item);
 * await AsyncStorage.setItem(`sync:${item.id}`, json);
 *
 * @internal
 */
export function serializeSyncItem(item: SyncItem): string {
  try {
    return JSON.stringify(item);
  } catch (err) {
    throw new SyncError(
      SyncErrorCode.INVALID_PAYLOAD,
      'Failed to serialize SyncItem to JSON.',
      err
    );
  }
}
