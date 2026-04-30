import type { SyncError } from '../errors/SyncError';
import type { ConnectionStatus, ConnectionType, SyncEventType } from './enums';
import type {
  BackgroundSyncOptions,
  SyncEvent,
  SyncItem,
  SyncItemInput,
  SyncOptions,
  SyncResult,
} from './sync';

/**
 * Public hook return-type contracts.
 *
 * This module is types-only — no React imports, no runtime exports. The
 * actual hook implementations land in Phase 2 under `src/hooks/`. These
 * shapes are frozen here so consumer code (and the documentation site) can
 * depend on them without waiting for the implementations.
 */

/* -------------------------------------------------------------------------- */
/*                              useConnection                                 */
/* -------------------------------------------------------------------------- */

/**
 * Result returned by `useConnection`.
 *
 * Provides a flattened, render-friendly view of {@link ConnectionState} so
 * that components don't need to destructure or compare nested fields on
 * every render.
 */
export interface UseConnectionResult {
  /** Coarse connectivity status (mirrors {@link ConnectionState.status}). */
  status: ConnectionStatus;
  /** Physical/logical transport (mirrors {@link ConnectionState.type}). */
  type: ConnectionType;
  /**
   * Convenience flag — `true` when {@link ConnectionStatus.CONNECTED} or
   * {@link ConnectionStatus.METERED}. `false` for `DISCONNECTED`/`UNKNOWN`.
   */
  isOnline: boolean;
  /** Convenience flag — `true` when {@link ConnectionStatus.METERED}. */
  isMetered: boolean;
}

/* -------------------------------------------------------------------------- */
/*                              useSyncQueue                                  */
/* -------------------------------------------------------------------------- */

/**
 * Result returned by `useSyncQueue`.
 *
 * Surfaces the queue's current contents plus the canonical write operations,
 * so components can manage the queue without going through the bare facade.
 */
export interface UseSyncQueueResult {
  /** Current queue size, mirroring `getQueueSize()`. */
  size: number;
  /** Pending items, mirroring `getPendingItems()`. */
  items: SyncItem[];
  /** `true` while the initial fetch (or a `refresh()`) is in flight. */
  isLoading: boolean;
  /** Last error captured by the hook, or `null` once cleared. */
  error: SyncError | null;
  /** Equivalent to the top-level `enqueue(input)`. */
  enqueue: (input: SyncItemInput) => Promise<string>;
  /** Equivalent to the top-level `enqueueBatch(inputs)`. */
  enqueueBatch: (inputs: SyncItemInput[]) => Promise<string[]>;
  /** Equivalent to the top-level `removeItem(id)`. */
  removeItem: (id: string) => Promise<boolean>;
  /** Equivalent to the top-level `clearQueue()`. */
  clearQueue: () => Promise<void>;
  /** Force-refresh `size` and `items` from the native layer. */
  refresh: () => Promise<void>;
}

/* -------------------------------------------------------------------------- */
/*                              useSyncStatus                                 */
/* -------------------------------------------------------------------------- */

/**
 * Result returned by `useSyncStatus`.
 *
 * Tracks the live sync engine state plus the most recent flush outcome.
 */
export interface UseSyncStatusResult {
  /** `true` while a flush cycle is currently in progress. */
  isSyncing: boolean;
  /** `true` after `pauseSync()` and before `resumeSync()`. */
  isPaused: boolean;
  /** Current flush progress in `[0, 1]`, or `null` when idle. */
  progress: number | null;
  /** Most recent flush outcome, or `null` if no flush has run yet this session. */
  lastResult: SyncResult | null;
  /** Last error captured by the hook, or `null` once cleared. */
  error: SyncError | null;
  /** Equivalent to the top-level `flush()`. */
  flush: () => Promise<SyncResult>;
  /** Equivalent to the top-level `pauseSync()`. */
  pause: () => Promise<void>;
  /** Equivalent to the top-level `resumeSync()`. */
  resume: () => Promise<void>;
}

/* -------------------------------------------------------------------------- */
/*                              useOfflineQueue                               */
/* -------------------------------------------------------------------------- */

/**
 * Result returned by `useOfflineQueue` — the convenience hook that combines
 * connection state with queue + sync state for "offline-first" UIs.
 */
export interface UseOfflineQueueResult {
  /** Connection snapshot — same as {@link UseConnectionResult}. */
  connection: UseConnectionResult;
  /** Current queue size. */
  size: number;
  /** Pending items, mirroring `getPendingItems()`. */
  items: SyncItem[];
  /** `true` while a flush cycle is currently in progress. */
  isSyncing: boolean;
  /**
   * `true` when there are pending items but the device is offline — useful
   * for "Waiting for connection..." UI badges.
   */
  isWaitingForConnection: boolean;
  /** Last error captured by the hook, or `null` once cleared. */
  error: SyncError | null;
  /** Equivalent to the top-level `enqueue(input)`. */
  enqueue: (input: SyncItemInput) => Promise<string>;
  /** Force a flush attempt regardless of strategy. */
  flush: () => Promise<SyncResult>;
}

/* -------------------------------------------------------------------------- */
/*                              useSyncEvents                                 */
/* -------------------------------------------------------------------------- */

/**
 * Options accepted by `useSyncEvents`.
 *
 * Subscribers can either listen to every event (default) or filter to a
 * specific subset.
 */
export interface UseSyncEventsOptions {
  /**
   * Optional filter — when supplied, the callback is only invoked for
   * events whose `type` is in this list. Pass `undefined` (or omit) to
   * receive all events.
   */
  types?: SyncEventType[];
  /**
   * Callback invoked for each matching event. The callback is invoked on the
   * JS thread, after the native layer has dispatched the event over the
   * `'sync-event'` channel.
   */
  onEvent: (event: SyncEvent) => void;
  /**
   * If `false`, the underlying subscription is not created — useful for
   * conditionally subscribing without unmounting the component.
   * Defaults to `true`.
   */
  enabled?: boolean;
}

/* -------------------------------------------------------------------------- */
/*                              useSyncConfig                                 */
/* -------------------------------------------------------------------------- */

/**
 * Result returned by `useSyncConfig`.
 *
 * Surfaces the live {@link SyncOptions} plus a setter that round-trips
 * through `configureSync()` and refetches.
 */
export interface UseSyncConfigResult {
  /** Currently active configuration, or `null` while loading. */
  config: SyncOptions | null;
  /** `true` while the initial fetch is in flight. */
  isLoading: boolean;
  /** Last error captured by the hook, or `null` once cleared. */
  error: SyncError | null;
  /** Apply a new configuration via `configureSync()`. */
  setConfig: (next: SyncOptions) => Promise<void>;
  /** Force-refresh `config` from the native layer. */
  refresh: () => Promise<void>;
}

/* -------------------------------------------------------------------------- */
/*                              useAutoSync                                   */
/* -------------------------------------------------------------------------- */

/**
 * Options accepted by `useAutoSync` — the orchestrator hook that wires
 * connectivity, foreground state, and (optionally) background sync into a
 * single declarative API.
 */
export interface UseAutoSyncOptions {
  /**
   * If `false`, the orchestrator is inert (no flushes are triggered, no
   * subscriptions are created). Defaults to `true`.
   */
  enabled?: boolean;
  /**
   * If `true`, the orchestrator triggers a flush as soon as the device
   * transitions to {@link ConnectionStatus.CONNECTED} (or `METERED` when
   * `flushOnMetered` is also `true`). Defaults to `true`.
   */
  flushOnReconnect?: boolean;
  /**
   * If `true`, the orchestrator may flush over metered links. Defaults to
   * `false` to avoid unintended cellular usage.
   */
  flushOnMetered?: boolean;
  /**
   * If `true`, the orchestrator triggers a flush when the app returns to the
   * foreground. Defaults to `true`.
   */
  flushOnForeground?: boolean;
  /**
   * Optional background sync configuration. When supplied, the orchestrator
   * calls `enableBackgroundSync(options)` on mount and
   * `disableBackgroundSync()` on unmount.
   */
  backgroundSync?: BackgroundSyncOptions;
}
