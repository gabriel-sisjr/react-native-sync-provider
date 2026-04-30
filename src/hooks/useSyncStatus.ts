import { useCallback, useEffect, useRef, useState } from 'react';

import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import { SyncEventType } from '../types/enums';
import type { UseSyncStatusResult } from '../types/hooks';
import type { SyncEvent, SyncResult } from '../types/sync';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import {
  addSyncEventListener,
  flush as facadeFlush,
  getLastSyncResult,
  pauseSync,
  removeSyncEventListener,
  resumeSync,
} from '../index';

/**
 * Track the live sync engine state plus the most recent flush outcome.
 *
 * Initial state is fetched via `getLastSyncResult()` (with the sentinel
 * translated to `null`). The hook then mirrors `SYNC_STARTED`,
 * `SYNC_SUCCEEDED`, `SYNC_FAILED`, `SYNC_PROGRESS`, `PAUSED`, and `RESUMED`
 * events to keep `isSyncing`, `progress`, and `lastResult` in sync.
 *
 * @returns a {@link UseSyncStatusResult}.
 *
 * @example
 * const { isSyncing, lastResult, flush } = useSyncStatus();
 *
 * @public
 */
export function useSyncStatus(): UseSyncStatusResult {
  const [isSyncing, setIsSyncing] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [progress, setProgress] = useState<number | null>(null);
  const [lastResult, setLastResult] = useState<SyncResult | null>(null);
  const [error, setError] = useState<SyncError | null>(null);
  const cancelledRef = useRef(false);

  useEffect(() => {
    cancelledRef.current = false;
    if (!isNativeModuleAvailable()) return undefined;

    let subscriptionId: string | null = null;

    (async () => {
      try {
        const initial = await getLastSyncResult();
        if (!cancelledRef.current && initial !== undefined) {
          setLastResult(initial);
        }
      } catch (err) {
        if (!cancelledRef.current) {
          setError(
            err instanceof SyncError
              ? err
              : new SyncError(
                  SyncErrorCode.INVALID_PAYLOAD,
                  'Failed to read last sync result.',
                  err
                )
          );
        }
      }

      if (cancelledRef.current) return;

      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          switch (event.type) {
            case SyncEventType.SYNC_STARTED:
              setIsSyncing(true);
              setProgress(0);
              break;
            case SyncEventType.SYNC_PROGRESS:
              if (typeof event.progress === 'number') {
                setProgress(event.progress);
              }
              break;
            case SyncEventType.SYNC_SUCCEEDED:
            case SyncEventType.SYNC_FAILED:
              setIsSyncing(false);
              setProgress(null);
              getLastSyncResult()
                .then((res) => {
                  if (!cancelledRef.current && res !== undefined) {
                    setLastResult(res);
                  }
                })
                .catch(() => undefined);
              break;
            case SyncEventType.PAUSED:
              setIsPaused(true);
              break;
            case SyncEventType.RESUMED:
              setIsPaused(false);
              break;
            default:
              break;
          }
        });
      } catch {
        // Subscription failure is non-fatal.
      }
    })();

    return () => {
      cancelledRef.current = true;
      if (subscriptionId !== null) {
        void removeSyncEventListener(subscriptionId).catch(() => undefined);
      }
    };
  }, []);

  const flush = useCallback(async (): Promise<SyncResult> => {
    return facadeFlush();
  }, []);

  const pause = useCallback(async () => {
    await pauseSync();
  }, []);

  const resume = useCallback(async () => {
    await resumeSync();
  }, []);

  return {
    isSyncing,
    isPaused,
    progress,
    lastResult,
    error,
    flush,
    pause,
    resume,
  };
}
