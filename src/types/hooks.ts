import type { SyncError } from '../errors/SyncError';
import type {
  ConnectionStatus,
  ConnectionType,
  NetworkQuality,
  SyncEventType,
} from './enums';
import type {
  BackgroundSyncOptions,
  SyncDeadLetterItem,
  SyncEvent,
  SyncItem,
  SyncItemInput,
  SyncOptions,
  SyncResult,
} from './sync';

/**
 * Public hook return-type contracts.
 *
 * This module is types-only — no React imports, no runtime exports. The hook
 * implementations live under `src/hooks/`.
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
  /**
   * `true` between `SYNC_STARTED` and the end of the cycle. Starts `false` and
   * follows events only, so a cycle already running at mount is not reflected.
   */
  isSyncing: boolean;
  /**
   * `true` after a `PAUSED` event, until `RESUMED`. Starts `false` and follows
   * those events only, because JS cannot read the pause flag: a hook mounted
   * while a pause is already in force reads `false` until the next `PAUSED`. A
   * launch or JS reload that reaches the library clears the pause before JS can
   * observe it, without a `RESUMED`, so `false` is accurate in a fresh session.
   */
  isPaused: boolean;
  /**
   * Current flush progress in `[0, 1]`, or `null` when idle. `SYNC_PROGRESS` is
   * iOS-only, so on Android this stays `0` for the whole cycle.
   */
  progress: number | null;
  /** Most recent persisted flush outcome, or `null` if none exists. */
  lastResult: SyncResult | null;
  /** Failure of the initial read, or `null`. Actions reject instead. */
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
  /**
   * Currently active configuration, or `null` while loading. It can lack the
   * stored `defaultHeaders`: see `getSyncConfig`.
   */
  config: SyncOptions | null;
  /** `true` while the initial fetch is in flight. */
  isLoading: boolean;
  /** Last error captured by the hook, or `null` once cleared. */
  error: SyncError | null;
  /**
   * Apply a new configuration via `configureSync()`. It replaces the whole
   * configuration, so set `defaultHeaders` explicitly instead of copying them
   * from `config`.
   */
  setConfig: (next: SyncOptions) => Promise<void>;
  /** Force-refresh `config` from the native layer. */
  refresh: () => Promise<void>;
}

/* -------------------------------------------------------------------------- */
/*                              useAutoSync                                   */
/* -------------------------------------------------------------------------- */

/**
 * Options accepted by `useAutoSync` — the orchestrator hook that wires
 * periodic and connectivity-driven flushes into a single declarative API.
 */
export interface UseAutoSyncOptions {
  /**
   * If `false`, the orchestrator is inert (no flushes are triggered, no
   * subscriptions are created). Defaults to `false`: pass `true` to turn it on.
   *
   * The hook does not read `SyncOptions.strategy`, so this is what turns it
   * off: it also flushes under `MANUAL`.
   */
  enabled?: boolean;
  /**
   * If `true`, the orchestrator triggers a flush as soon as the device
   * transitions to {@link ConnectionStatus.CONNECTED} (or `METERED` when
   * `flushOnMetered` is also `true`). Defaults to `true`.
   */
  flushOnReconnect?: boolean;
  /**
   * If `true`, a transition to `METERED` also triggers a flush. Defaults to
   * `false`. It gates the reconnect trigger only: the periodic tick flushes on
   * any connection.
   */
  flushOnMetered?: boolean;
  /**
   * Reserved: `useAutoSync` ignores it. Use `flushOnAppForeground` instead.
   */
  flushOnForeground?: boolean;
  /**
   * Reserved: `useAutoSync` ignores it. Call `enableBackgroundSync`, or pass
   * `backgroundSync` to `SyncProvider`.
   */
  backgroundSync?: BackgroundSyncOptions;
}

/* -------------------------------------------------------------------------- */
/*                              useSyncSnapshot                               */
/* -------------------------------------------------------------------------- */

/**
 * Result returned by `useSyncSnapshot` — the read-only dashboard aggregator.
 *
 * Combines connection, queue, and sync-engine state into a single reactive
 * object driven by ONE native subscription. Unlike {@link UseOfflineQueueResult}
 * it exposes NO action methods (`enqueue`, `flush`, etc.) — it is a pure,
 * render-friendly snapshot intended for status dashboards and badges.
 */
export interface UseSyncSnapshotResult {
  /** Connection snapshot — same as {@link UseConnectionResult}. */
  connection: UseConnectionResult;
  /** Current queue size, mirroring `getQueueSize()`. */
  size: number;
  /** Pending items, mirroring `getPendingItems()`. */
  items: SyncItem[];
  /** `true` while a flush cycle is currently in progress. */
  isSyncing: boolean;
  /**
   * `true` after a `PAUSED` event, until `RESUMED`. Starts `false` and follows
   * those events only, because JS cannot read the pause flag: a hook mounted
   * while a pause is already in force reads `false` until the next `PAUSED`. A
   * launch or JS reload that reaches the library clears the pause before JS can
   * observe it, without a `RESUMED`, so `false` is accurate in a fresh session.
   */
  isPaused: boolean;
  /** Current flush progress in `[0, 1]`, or `null` when idle. */
  progress: number | null;
  /**
   * Most recent flush outcome in the persisted sync history, so it survives
   * restarts and can come from an earlier session. Read with
   * `getLastSyncResult()` on mount and after every `SYNC_SUCCEEDED` /
   * `SYNC_FAILED`. `null` until a read finds a recorded flush, and not reset by
   * `clearSyncHistory()`.
   */
  lastResult: SyncResult | null;
  /**
   * `true` when there are pending items but the device is offline — useful
   * for "Waiting for connection..." UI badges. Derived as
   * `size > 0 && !connection.isOnline`.
   */
  isWaitingForConnection: boolean;
  /** Last error captured by the hook, or `null` once cleared. */
  error: SyncError | null;
  /**
   * Forward field: coarse {@link NetworkQuality}
   * classification. ALWAYS `undefined` in v0.2 — populated once the v0.3
   * network-quality classifier ships.
   */
  networkQuality?: NetworkQuality;
  /**
   * Forward field: items moved to the dead-letter queue
   * after exhausting their retry budget. ALWAYS `undefined` in v0.2 —
   * populated once the v0.3 dead-letter store ships.
   */
  deadLetter?: readonly SyncDeadLetterItem[];
}
