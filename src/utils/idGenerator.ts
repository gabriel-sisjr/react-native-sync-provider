/**
 * Generate an RFC4122-style UUID v4 string using `Math.random()`.
 *
 * @remarks
 * Native owns ids on the production happy path: `enqueue` /
 * `enqueueBatch` return ULIDs assigned by the platform layer (Core Data on
 * iOS, Room on Android). This helper exists so consumers can:
 *
 * - Generate stable ids in the web throwing-stub fallback (no native).
 * - Pre-generate `Idempotency-Key` headers before enqueue.
 * - Produce a UUID in test harnesses without pulling in a second dependency.
 *
 * Implementation notes:
 *
 * - Returns a 36-char string of the form `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx`
 *   where `y` is one of `8`, `9`, `a`, `b` (RFC4122 variant bits).
 * - Uses only `Math.random()`. It is therefore **NOT cryptographically
 *   secure**. It is good enough for `Idempotency-Key` deduplication within a
 *   single client, but must not be used for security-sensitive identifiers
 *   (tokens, secrets, anything an adversary should not be able to guess).
 * - This is a UUID v4, so the previous v7 guarantee of timestamp-prefixed
 *   lexicographic sortability **no longer holds** — consecutive ids are not
 *   ordered by creation time.
 * - Chosen over `uuid` / `nanoid` to avoid pulling in `crypto.getRandomValues()`
 *   (unavailable on Hermes / JSC without a polyfill).
 *
 * @returns a fresh UUID v4 string (36 chars, dashed).
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
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    // RFC4122 v4 requires bitwise ops: truncation to nibble, variant mask.
    // eslint-disable-next-line no-bitwise
    const r = (Math.random() * 16) | 0;
    // eslint-disable-next-line no-bitwise
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
