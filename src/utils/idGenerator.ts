import { v7 as uuidv7 } from 'uuid';

/**
 * Generate a UUID v7 (timestamp-prefixed, lexicographically sortable).
 *
 * @remarks
 * Native owns ids on the production happy path: `enqueue` /
 * `enqueueBatch` return ULIDs assigned by the platform layer (Core Data on
 * iOS, Room on Android). This helper exists so consumers can:
 *
 * - Generate stable ids in the web throwing-stub fallback (no native).
 * - Pre-generate `Idempotency-Key` headers before enqueue.
 * - Use a timestamp-sortable id in test harnesses without pulling in a
 *   second dependency.
 *
 * @returns a fresh UUID v7 string.
 *
 * @example
 * import { generateId } from '@gabriel-sisjr/react-native-sync-provider';
 *
 * await enqueue({
 *   method: 'POST',
 *   url: 'https://api.example.com/events',
 *   headers: { 'Idempotency-Key': generateId() },
 * });
 *
 * @public
 */
export function generateId(): string {
  return uuidv7();
}
