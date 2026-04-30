/**
 * Internal utilities barrel.
 *
 * Most of these are internal to the library — they are surfaced through this
 * barrel so the JS facade and the hooks can import them with a single line.
 * Only {@link generateId} and {@link isNativeModuleAvailable} are part of the
 * public API surface and re-exported from `src/index.tsx`.
 */

export { assertNativeModuleAvailable } from './assertNativeModuleAvailable';
export { deserializeSyncItem } from './deserializeSyncItem';
export { wrapNativeError } from './errors';
export { generateId } from './idGenerator';
export {
  getNativeSyncProvider,
  isNativeModuleAvailable,
} from './isNativeModuleAvailable';
export { computeNextDelayMs } from './retryBackoff';
export type { ComputeNextDelayArgs } from './retryBackoff';
export { serializeSyncItem } from './serializeSyncItem';
export { validateSyncItem } from './validateSyncItem';
