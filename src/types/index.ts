/**
 * Public type barrel.
 *
 * Re-exports every consumer-facing type from `src/types/`. The companion
 * `as const` enum objects are re-exported as values so they can be used at
 * runtime (e.g., `if (status === ConnectionStatus.METERED)`); their type
 * counterparts are re-exported with `export type` to honor
 * `verbatimModuleSyntax: true`.
 */

/* ------------------------------- enums ----------------------------------- */
export {
  BackoffStrategy,
  ConnectionStatus,
  ConnectionType,
  HttpMethod,
  NetworkQuality,
  SyncEventType,
  SyncPriority,
  SyncStrategy,
} from './enums';

/* --------------------------- connection types ---------------------------- */
export type { ConnectionState } from './connection';

/* ------------------------------ sync types ------------------------------- */
export type {
  BackgroundSyncOptions,
  RetryPolicy,
  SyncDeadLetterItem,
  SyncEvent,
  SyncItem,
  SyncItemInput,
  SyncOptions,
  SyncResult,
} from './sync';

/* ------------------------------ hook types ------------------------------- */
export type {
  UseAutoSyncOptions,
  UseConnectionResult,
  UseOfflineQueueResult,
  UseSyncConfigResult,
  UseSyncEventsOptions,
  UseSyncQueueResult,
  UseSyncSnapshotResult,
  UseSyncStatusResult,
} from './hooks';
