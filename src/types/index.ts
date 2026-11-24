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
  BeforeDispatchAction,
  ConnectionStatus,
  ConnectionType,
  DispatchOutcome,
  HttpMethod,
  NetworkQuality,
  RetryAction,
  SyncEventType,
  SyncPriority,
  SyncStrategy,
} from './enums';

/* ---------------------------- callback types ----------------------------- */
export type {
  AfterDispatchContext,
  AfterDispatchHook,
  BeforeDispatchContext,
  BeforeDispatchHook,
  BeforeDispatchResult,
  HeaderResolver,
  HeaderResolverContext,
  RetryDecider,
  RetryDeciderContext,
  RetryDecision,
} from './callbacks';

/* --------------------------- connection types ---------------------------- */
export type { ConnectionState } from './connection';

/* ------------------------------ drain types ------------------------------ */
export type { DrainOptions, DrainResult, DrainStoppedReason } from './drain';

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
