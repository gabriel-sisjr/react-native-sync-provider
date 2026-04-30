/**
 * Lazy availability probe for the native HybridObject.
 *
 * This module owns the single, memoized `NitroModules.createHybridObject` call
 * for the entire library. Every facade function and every hook reads through
 * `getNativeSyncProvider()` so that:
 *
 * - The hybrid is constructed at most once per process (zero side effect on
 *   import, since construction only happens on first read).
 * - Web / SSR / Jest environments where Nitro is not loaded never blow up at
 *   module-eval time — they degrade to `isNativeModuleAvailable() === false`.
 *
 * @see assertNativeModuleAvailable for the throwing variant.
 *
 * @internal
 */
import { NitroModules } from 'react-native-nitro-modules';

import type { SyncProvider } from '../SyncProvider.nitro';

let cachedHybrid: SyncProvider | null = null;
let resolved = false;
let available = false;

/**
 * Resolve the native HybridObject lazily.
 *
 * @returns the `SyncProvider` HybridObject reference, or `null` when the
 *   native module could not be created (web, SSR, missing autolinking, etc.).
 *
 * @internal
 */
export function getNativeSyncProvider(): SyncProvider | null {
  if (resolved) return cachedHybrid;
  resolved = true;
  try {
    cachedHybrid =
      NitroModules.createHybridObject<SyncProvider>('SyncProvider');
    available = cachedHybrid !== null && cachedHybrid !== undefined;
  } catch {
    cachedHybrid = null;
    available = false;
  }
  return cachedHybrid;
}

/**
 * Non-throwing probe for native availability.
 *
 * Use this when the caller wants to gracefully degrade (e.g. hooks returning
 * inert state on web, conditional UI badges, jest setup files, etc.). Pair
 * with {@link assertNativeModuleAvailable} for the throwing variant used by
 * the facade.
 *
 * @returns `true` when the native HybridObject was constructed successfully,
 *   `false` otherwise.
 *
 * @example
 * import { isNativeModuleAvailable } from '@gabriel-sisjr/react-native-sync-provider';
 *
 * if (isNativeModuleAvailable()) {
 *   // safe to call enqueue / flush / ...
 * }
 *
 * @public
 */
export function isNativeModuleAvailable(): boolean {
  if (!resolved) getNativeSyncProvider();
  return available;
}
