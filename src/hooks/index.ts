/**
 * Public hooks barrel.
 *
 * Each hook is safe to call before the native module loads — it returns its
 * documented inert state and never throws on render. Side-effecting actions
 * (e.g. `enqueue`, `flush`) returned by the hooks reject with
 * `SyncErrorCode.NATIVE_MODULE_UNAVAILABLE` when the native layer is missing.
 */

export { useAutoSync } from './useAutoSync';
export type { UseAutoSyncExtraOptions } from './useAutoSync';
export { useConnection } from './useConnection';
export { useOfflineQueue } from './useOfflineQueue';
export { useSyncConfig } from './useSyncConfig';
export { useSyncEvents } from './useSyncEvents';
export { useSyncQueue } from './useSyncQueue';
export { useSyncStatus } from './useSyncStatus';
