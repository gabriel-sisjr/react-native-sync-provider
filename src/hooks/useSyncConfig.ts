import { useCallback, useContext, useEffect, useRef, useState } from 'react';

import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import { SyncEventType } from '../types/enums';
import type { UseSyncConfigResult } from '../types/hooks';
import type { SyncEvent, SyncOptions } from '../types/sync';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import { SyncProviderContext } from '../contexts/SyncProvider';
import {
  addSyncEventListener,
  configureSync,
  getSyncConfig,
  removeSyncEventListener,
} from '../index';

/**
 * Read and mutate the active {@link SyncOptions}.
 *
 * If a {@link SyncProvider} Context is mounted, the hook reads the live
 * config from context (so multiple consumers share one source of truth).
 * Otherwise it falls back to the native singleton via `getSyncConfig()` and
 * keeps `config` fresh on `RESUMED` events.
 *
 * `setConfig` always round-trips through the native layer (via
 * `configureSync`) and re-fetches the config to ensure consumers observe the
 * authoritative shape (which may differ from the input due to native
 * defaults).
 *
 * @returns a {@link UseSyncConfigResult}.
 *
 * @example
 * const { config, setConfig } = useSyncConfig();
 * await setConfig({ ...config!, batchSize: 25 });
 *
 * @public
 */
export function useSyncConfig(): UseSyncConfigResult {
  const ctx = useContext(SyncProviderContext);
  const ctxConfig = ctx?.config;
  const [config, setLocalConfig] = useState<SyncOptions | null>(
    ctxConfig ?? null
  );
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<SyncError | null>(null);
  const cancelledRef = useRef(false);

  const refresh = useCallback(async () => {
    if (!isNativeModuleAvailable()) return;
    setIsLoading(true);
    try {
      const next = await getSyncConfig();
      if (cancelledRef.current) return;
      setLocalConfig(next);
      setError(null);
    } catch (err) {
      if (cancelledRef.current) return;
      setError(
        err instanceof SyncError
          ? err
          : new SyncError(
              SyncErrorCode.INVALID_PAYLOAD,
              'Failed to read sync config.',
              err
            )
      );
    } finally {
      if (!cancelledRef.current) setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    cancelledRef.current = false;

    // If the Provider is mounted, mirror its config and skip our own probes.
    if (ctxConfig !== undefined) {
      setLocalConfig(ctxConfig);
      return undefined;
    }

    if (!isNativeModuleAvailable()) return undefined;

    let subscriptionId: string | null = null;

    void refresh();

    (async () => {
      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          if (event.type === SyncEventType.RESUMED) void refresh();
        });
      } catch {
        // non-fatal
      }
    })();

    return () => {
      cancelledRef.current = true;
      if (subscriptionId !== null) {
        void removeSyncEventListener(subscriptionId).catch(() => undefined);
      }
    };
  }, [ctxConfig, refresh]);

  const setConfig = useCallback(
    async (next: SyncOptions) => {
      await configureSync(next);
      await refresh();
    },
    [refresh]
  );

  return {
    config,
    isLoading,
    error,
    setConfig,
    refresh,
  };
}
