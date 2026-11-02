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
 * `config` is the native configuration as of the Provider's last read, which
 * happens on mount, when `options` or `backgroundSync` change, and on a
 * `RESUMED` event. It does not follow `configureSync` or `setConfig` calls made
 * elsewhere. It is `undefined` until the first read finishes, and also when no
 * Provider is mounted (consumers using `useSyncConfig` outside a Provider get
 * `undefined` here and the hook falls back to the singleton).
 *
 * `hasProvider` disambiguates the two `config: undefined` cases: it is `true`
 * only when a {@link SyncProvider} is actually mounted above the consumer
 * (even while its initial config round-trip is still in flight), and `false`
 * for the default context value used outside any Provider.
 */
export interface SyncProviderContextValue {
  /** Native config as of the last read, or `undefined` until the first one. */
  config: SyncOptions | undefined;
  /**
   * `true` when a {@link SyncProvider} is mounted above the consumer, `false`
   * for the default (no-Provider) context value. Lets consumers tell "no
   * Provider" apart from "Provider mounted, config still loading".
   */
  hasProvider: boolean;
}

/**
 * Context backing {@link SyncProvider}. Defaults to
 * `{ config: undefined, hasProvider: false }` so consumers can detect
 * "no Provider mounted" via `hasProvider === false` and fall back to a direct
 * facade call.
 *
 * @public
 */
export const SyncProviderContext = createContext<SyncProviderContextValue>({
  config: undefined,
  hasProvider: false,
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
        try {
          await configureSync(options);
        } finally {
          // Also on rejection: the engine keeps running on its previous config,
          // and consumers must still see it rather than wait forever.
          await refresh();
        }
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

  const value = useMemo<SyncProviderContextValue>(
    () => ({ config, hasProvider: true }),
    [config]
  );

  return (
    <SyncProviderContext.Provider value={value}>
      {children}
    </SyncProviderContext.Provider>
  );
};
