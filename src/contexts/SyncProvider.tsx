import React, {
  createContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

import { SyncEventType } from '../types/enums';
import type { SyncEvent } from '../types/sync';
import type { BackgroundSyncOptions, SyncOptions } from '../types/sync';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import {
  addSyncEventListener,
  configureSync,
  disableBackgroundSync,
  enableBackgroundSync,
  getSyncConfig,
  removeSyncEventListener,
} from '../index';

/**
 * Value exposed by {@link SyncProviderContext}.
 *
 * `config` mirrors the live native configuration — it is `undefined` until
 * the Provider has finished its initial `getSyncConfig()` round-trip, and
 * also when no Provider is mounted (consumers using `useSyncConfig` outside
 * a Provider get `undefined` here and the hook falls back to the singleton).
 */
export interface SyncProviderContextValue {
  /** Live native config, or `undefined` until first round-trip. */
  config: SyncOptions | undefined;
}

/**
 * Context backing {@link SyncProvider}. Defaults to `{ config: undefined }`
 * so that `useSyncConfig` can detect "no Provider mounted" via the
 * `undefined` config field and fall back to a direct facade call.
 *
 * @public
 */
export const SyncProviderContext = createContext<SyncProviderContextValue>({
  config: undefined,
});

/**
 * Props for the {@link SyncProvider} Context component.
 */
export interface SyncProviderProps {
  /**
   * Library-wide sync options applied via `configureSync` on mount.
   *
   * The Provider re-applies the options whenever this prop changes by
   * reference, so consumers can drive runtime reconfiguration through
   * standard React state.
   */
  options: SyncOptions;
  /**
   * Optional background sync configuration. When supplied, the Provider
   * calls `enableBackgroundSync(options)` on mount and the matching
   * `disableBackgroundSync()` on unmount.
   *
   * The Provider tracks ownership in a ref — `disableBackgroundSync()` is
   * only invoked when this Provider was the one that enabled it.
   */
  backgroundSync?: BackgroundSyncOptions;
  /** Subtree that receives the context value. */
  children: React.ReactNode;
}

/**
 * Optional context Provider for centralized sync configuration.
 *
 * The Provider is a convenience wrapper analogous to react-query's
 * `QueryClientProvider`: it owns the lifecycle of `configureSync` and (when
 * supplied) `enableBackgroundSync`/`disableBackgroundSync` calls, and exposes
 * the live config through {@link SyncProviderContext}. All hooks function
 * without a Provider — when present, `useSyncConfig` reads from context;
 * when absent, it falls back to the native singleton.
 *
 * @example
 * function App() {
 *   return (
 *     <SyncProvider
 *       options={{ strategy: 'AUTOMATIC', retryPolicy: { ... } }}
 *       backgroundSync={{ minimumIntervalMs: 15 * 60 * 1000 }}
 *     >
 *       <Root />
 *     </SyncProvider>
 *   );
 * }
 *
 * @public
 */
export const SyncProvider: React.FC<SyncProviderProps> = ({
  options,
  backgroundSync,
  children,
}) => {
  const [config, setConfig] = useState<SyncOptions | undefined>(undefined);
  const ownsBackgroundRef = useRef(false);
  const cancelledRef = useRef(false);

  useEffect(() => {
    cancelledRef.current = false;
    if (!isNativeModuleAvailable()) return undefined;

    let subscriptionId: string | null = null;

    const refresh = async () => {
      try {
        const next = await getSyncConfig();
        if (!cancelledRef.current) setConfig(next);
      } catch {
        // ignore
      }
    };

    (async () => {
      try {
        await configureSync(options);
        await refresh();
        if (backgroundSync !== undefined) {
          await enableBackgroundSync(backgroundSync);
          ownsBackgroundRef.current = true;
        }
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          if (event.type === SyncEventType.RESUMED) void refresh();
        });
      } catch {
        // ignore — Provider is best-effort; consumer can retry via setConfig.
      }
    })();

    return () => {
      cancelledRef.current = true;
      if (subscriptionId !== null) {
        void removeSyncEventListener(subscriptionId).catch(() => undefined);
      }
      if (ownsBackgroundRef.current) {
        ownsBackgroundRef.current = false;
        void disableBackgroundSync().catch(() => undefined);
      }
    };
  }, [options, backgroundSync]);

  const value = useMemo<SyncProviderContextValue>(() => ({ config }), [config]);

  return (
    <SyncProviderContext.Provider value={value}>
      {children}
    </SyncProviderContext.Provider>
  );
};
