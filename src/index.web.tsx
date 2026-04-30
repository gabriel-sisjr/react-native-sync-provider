/**
 * Web entry point for `@gabriel-sisjr/react-native-sync-provider`.
 *
 * Mirrors the public API surface of `src/index.tsx` but every facade
 * function throws `SyncError(NATIVE_MODULE_UNAVAILABLE)` and every hook
 * returns inert state (no throws on render). This keeps SSR and web bundles
 * importable while making it impossible to silently miss the lack of native
 * support.
 *
 * Re-exports types, enums, errors, and `generateId` (which works on web).
 *
 * @packageDocumentation
 */

import React, { createContext } from 'react';

import { SyncError } from './errors/SyncError';
import { SyncErrorCode } from './errors/SyncErrorCode';
import { ConnectionStatus, ConnectionType } from './types/enums';
import type {
  UseAutoSyncOptions,
  UseConnectionResult,
  UseOfflineQueueResult,
  UseSyncConfigResult,
  UseSyncEventsOptions,
  UseSyncQueueResult,
  UseSyncStatusResult,
} from './types/hooks';
import type {
  BackgroundSyncOptions,
  SyncEvent,
  SyncItem,
  SyncItemInput,
  SyncOptions,
  SyncResult,
} from './types/sync';
import type { ConnectionState } from './types/connection';

/* -------------------------------------------------------------------------- */
/*                                  Re-exports                                */
/* -------------------------------------------------------------------------- */

export * from './types';
export { SyncError } from './errors/SyncError';
export { SyncErrorCode } from './errors/SyncErrorCode';
export { generateId } from './utils/idGenerator';

/* -------------------------------------------------------------------------- */
/*                                  Helpers                                   */
/* -------------------------------------------------------------------------- */

function unavailable(method: string): SyncError {
  return new SyncError(
    SyncErrorCode.NATIVE_MODULE_UNAVAILABLE,
    `react-native-sync-provider.${method} is not available on web.`
  );
}

/**
 * Probe whether the native HybridObject is available. Always `false` on web.
 *
 * @returns `false`.
 *
 * @public
 */
export function isNativeModuleAvailable(): boolean {
  return false;
}

/**
 * Channel name used by every native event the library emits. Exposed on web
 * for parity with the native bundle, but no events are ever emitted here.
 *
 * @public
 */
export const SYNC_EVENT_CHANNEL = 'sync-event' as const;

/* -------------------------------------------------------------------------- */
/*                              Facade (throwing)                             */
/* -------------------------------------------------------------------------- */

/** @public */
export async function enqueue(_item: SyncItemInput): Promise<string> {
  throw unavailable('enqueue');
}
/** @public */
export async function enqueueBatch(_items: SyncItemInput[]): Promise<string[]> {
  throw unavailable('enqueueBatch');
}
/** @public */
export async function removeItem(_id: string): Promise<boolean> {
  throw unavailable('removeItem');
}
/** @public */
export async function clearQueue(): Promise<void> {
  throw unavailable('clearQueue');
}
/** @public */
export async function getQueueSize(): Promise<number> {
  throw unavailable('getQueueSize');
}
/** @public */
export async function getPendingItems(): Promise<SyncItem[]> {
  throw unavailable('getPendingItems');
}
/** @public */
export async function flush(): Promise<SyncResult> {
  throw unavailable('flush');
}
/** @public */
export async function pauseSync(): Promise<void> {
  throw unavailable('pauseSync');
}
/** @public */
export async function resumeSync(): Promise<void> {
  throw unavailable('resumeSync');
}
/** @public */
export async function isSyncing(): Promise<boolean> {
  throw unavailable('isSyncing');
}
/** @public */
export async function configureSync(_options: SyncOptions): Promise<void> {
  throw unavailable('configureSync');
}
/** @public */
export async function getSyncConfig(): Promise<SyncOptions> {
  throw unavailable('getSyncConfig');
}
/** @public */
export async function getLastSyncResult(): Promise<SyncResult | undefined> {
  throw unavailable('getLastSyncResult');
}
/** @public */
export async function getSyncHistory(_limit?: number): Promise<SyncResult[]> {
  throw unavailable('getSyncHistory');
}
/** @public */
export async function clearSyncHistory(): Promise<void> {
  throw unavailable('clearSyncHistory');
}
/** @public */
export async function getConnectionStatus(): Promise<ConnectionState> {
  throw unavailable('getConnectionStatus');
}
/** @public */
export async function enableBackgroundSync(
  _options: BackgroundSyncOptions
): Promise<void> {
  throw unavailable('enableBackgroundSync');
}
/** @public */
export async function disableBackgroundSync(): Promise<void> {
  throw unavailable('disableBackgroundSync');
}
/** @public */
export async function isBackgroundSyncEnabled(): Promise<boolean> {
  throw unavailable('isBackgroundSyncEnabled');
}

/**
 * Web no-op listener subscriber. Resolves to an empty subscription id so
 * conditional code can call it without throwing during initial bundle
 * evaluation; consumers should still gate on {@link isNativeModuleAvailable}
 * for any logic that depends on receiving events.
 *
 * @public
 */
export async function addSyncEventListener(
  _callback: (event: SyncEvent) => void
): Promise<string> {
  return '';
}

/**
 * Web no-op listener unsubscriber. Always resolves.
 *
 * @public
 */
export async function removeSyncEventListener(
  _subscriptionId: string
): Promise<void> {
  // no-op
}

/* -------------------------------------------------------------------------- */
/*                                Hooks (inert)                               */
/* -------------------------------------------------------------------------- */

const INERT_CONNECTION: UseConnectionResult = {
  status: ConnectionStatus.UNKNOWN,
  type: ConnectionType.NONE,
  isOnline: false,
  isMetered: false,
};

/** @public */
export function useConnection(): UseConnectionResult {
  return INERT_CONNECTION;
}

/** @public */
export function useSyncQueue(): UseSyncQueueResult {
  return {
    size: 0,
    items: [],
    isLoading: false,
    error: null,
    enqueue: async () => {
      throw unavailable('enqueue');
    },
    enqueueBatch: async () => {
      throw unavailable('enqueueBatch');
    },
    removeItem: async () => {
      throw unavailable('removeItem');
    },
    clearQueue: async () => {
      throw unavailable('clearQueue');
    },
    refresh: async () => {
      throw unavailable('refresh');
    },
  };
}

/** @public */
export function useSyncStatus(): UseSyncStatusResult {
  return {
    isSyncing: false,
    isPaused: false,
    progress: null,
    lastResult: null,
    error: null,
    flush: async () => {
      throw unavailable('flush');
    },
    pause: async () => {
      throw unavailable('pauseSync');
    },
    resume: async () => {
      throw unavailable('resumeSync');
    },
  };
}

/** @public */
export function useOfflineQueue(): UseOfflineQueueResult {
  return {
    connection: INERT_CONNECTION,
    size: 0,
    items: [],
    isSyncing: false,
    isWaitingForConnection: false,
    error: null,
    enqueue: async () => {
      throw unavailable('enqueue');
    },
    flush: async () => {
      throw unavailable('flush');
    },
  };
}

/** @public */
export function useSyncEvents(_options: UseSyncEventsOptions): void {
  // no-op
}

/** @public */
export function useSyncConfig(): UseSyncConfigResult {
  return {
    config: null,
    isLoading: false,
    error: null,
    setConfig: async () => {
      throw unavailable('configureSync');
    },
    refresh: async () => {
      throw unavailable('getSyncConfig');
    },
  };
}

/** Mirrors `useAutoSync` from the native bundle. No-op on web. @public */
export function useAutoSync(_options: UseAutoSyncOptions): void {
  // no-op
}

/* -------------------------------------------------------------------------- */
/*                            Context (inert on web)                          */
/* -------------------------------------------------------------------------- */

/** @public */
export interface SyncProviderContextValue {
  config: SyncOptions | undefined;
}

/** @public */
export const SyncProviderContext = createContext<SyncProviderContextValue>({
  config: undefined,
});

/** @public */
export interface SyncProviderProps {
  options: SyncOptions;
  backgroundSync?: BackgroundSyncOptions;
  children: React.ReactNode;
}

/**
 * Web no-op {@link SyncProviderContext} wrapper. Renders children with
 * `{ config: undefined }` and performs no native calls.
 *
 * @public
 */
export const SyncProvider: React.FC<SyncProviderProps> = ({ children }) => {
  return (
    <SyncProviderContext.Provider value={{ config: undefined }}>
      {children}
    </SyncProviderContext.Provider>
  );
};
