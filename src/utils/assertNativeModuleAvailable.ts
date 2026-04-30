import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';

import { isNativeModuleAvailable } from './isNativeModuleAvailable';

/**
 * Throwing variant of {@link isNativeModuleAvailable}.
 *
 * Called at the top of every facade function so that consumers get a clean,
 * domain-typed error (`SyncError` with code
 * {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE}) instead of the raw
 * "could not create HybridObject 'SyncProvider'" Nitro exception.
 *
 * @throws {SyncError} with code
 *   {@link SyncErrorCode.NATIVE_MODULE_UNAVAILABLE} when the native
 *   HybridObject could not be resolved.
 *
 * @example
 * assertNativeModuleAvailable();
 * // ...safe to call into native from here
 *
 * @internal
 */
export function assertNativeModuleAvailable(): void {
  if (!isNativeModuleAvailable()) {
    throw new SyncError(
      SyncErrorCode.NATIVE_MODULE_UNAVAILABLE,
      'react-native-sync-provider native module is not available. ' +
        'Make sure the package is installed, autolinked, and the app was ' +
        'rebuilt after install. On web/SSR this is expected.'
    );
  }
}
