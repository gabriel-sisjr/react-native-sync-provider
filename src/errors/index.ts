/**
 * Public errors barrel.
 *
 * `SyncError` is exported as a value (the class) so consumers can
 * `instanceof`-check it. `SyncErrorCode` is exported as both a value (the
 * `as const` object) and a type so it can be used at runtime
 * (`SyncErrorCode.NETWORK_ERROR`) and as a type annotation
 * (`code: SyncErrorCode`).
 */

export { SyncError } from './SyncError';
export { SyncErrorCode } from './SyncErrorCode';
