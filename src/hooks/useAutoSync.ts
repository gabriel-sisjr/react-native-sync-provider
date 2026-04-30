import { useEffect, useRef } from 'react';
import { AppState } from 'react-native';
import type { AppStateStatus, NativeEventSubscription } from 'react-native';

import { ConnectionStatus, SyncEventType } from '../types/enums';
import type { UseAutoSyncOptions } from '../types/hooks';
import type { SyncEvent } from '../types/sync';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import {
  addSyncEventListener,
  flush as facadeFlush,
  isSyncing as facadeIsSyncing,
  removeSyncEventListener,
} from '../index';

const DEFAULT_INTERVAL_MS = 60_000;

/**
 * Extended {@link UseAutoSyncOptions} accepted by `useAutoSync`.
 *
 * Adds `intervalMs` (poll period) and `flushOnAppForeground` (opt-in
 * AppState integration), neither of which is part of the frozen Phase 1
 * type because they are JS-only orchestration concerns.
 */
export interface UseAutoSyncExtraOptions extends UseAutoSyncOptions {
  /**
   * Interval (in ms) at which the orchestrator polls and triggers a flush
   * when the queue is non-empty and not currently syncing. Defaults to
   * `60_000` (60s). Ignored when `enabled !== true`.
   */
  intervalMs?: number;
  /**
   * If `true`, the orchestrator listens to `AppState.change` and triggers a
   * flush when the app returns to `'active'`. Defaults to `false` to avoid
   * unintended foreground sync battery cost. Distinct from the frozen
   * `flushOnForeground` option which is reserved for a future event-based
   * implementation.
   */
  flushOnAppForeground?: boolean;
}

/**
 * Orchestrator hook that wires periodic polling, connectivity-based flushes,
 * and (opt-in) AppState foreground triggers into a single declarative API.
 *
 * Inert until `enabled === true`. When enabled, on mount:
 *
 *   1. Sets up a periodic timer (`intervalMs`, default 60s) that calls
 *      `flush()` whenever a sync is not already in flight.
 *   2. Subscribes to `CONNECTION_CHANGED` events and triggers a flush when
 *      the device transitions to `CONNECTED` (subject to `flushOnReconnect`).
 *   3. If `flushOnAppForeground === true`, registers an `AppState.change`
 *      listener and flushes on `'active'`.
 *
 * All three are torn down on unmount or when `enabled` flips to `false`.
 *
 * @param options — see {@link UseAutoSyncExtraOptions}.
 *
 * @example
 * useAutoSync({
 *   enabled: true,
 *   intervalMs: 30_000,
 *   flushOnReconnect: true,
 *   flushOnAppForeground: true,
 * });
 *
 * @public
 */
export function useAutoSync(options: UseAutoSyncExtraOptions): void {
  const {
    enabled = false,
    flushOnReconnect = true,
    flushOnMetered = false,
    flushOnAppForeground = false,
    intervalMs,
  } = options;

  const lastConnectionRef = useRef<ConnectionStatus | null>(null);

  useEffect(() => {
    if (enabled !== true) return undefined;
    if (!isNativeModuleAvailable()) return undefined;

    const period =
      typeof intervalMs === 'number' && intervalMs > 0
        ? intervalMs
        : DEFAULT_INTERVAL_MS;

    let cancelled = false;
    let subscriptionId: string | null = null;
    let appStateSubscription: NativeEventSubscription | null = null;

    const safeFlush = async () => {
      try {
        const inFlight = await facadeIsSyncing();
        if (inFlight || cancelled) return;
        await facadeFlush();
      } catch {
        // Swallow — orchestrator must never throw to consumer.
      }
    };

    const timer = setInterval(() => {
      void safeFlush();
    }, period);

    (async () => {
      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          if (!flushOnReconnect) return;
          if (event.type !== SyncEventType.CONNECTION_CHANGED) return;
          const next = event.connectionStatus;
          if (next === undefined) return;
          const prev = lastConnectionRef.current;
          lastConnectionRef.current = next;
          const becameConnected =
            prev !== ConnectionStatus.CONNECTED &&
            next === ConnectionStatus.CONNECTED;
          const becameMetered =
            flushOnMetered &&
            prev !== ConnectionStatus.METERED &&
            next === ConnectionStatus.METERED;
          if (becameConnected || becameMetered) void safeFlush();
        });
      } catch {
        // ignore
      }
    })();

    if (flushOnAppForeground) {
      appStateSubscription = AppState.addEventListener(
        'change',
        (next: AppStateStatus) => {
          if (next === 'active') void safeFlush();
        }
      );
    }

    return () => {
      cancelled = true;
      clearInterval(timer);
      if (subscriptionId !== null) {
        void removeSyncEventListener(subscriptionId).catch(() => undefined);
      }
      if (appStateSubscription !== null) {
        appStateSubscription.remove();
      }
    };
  }, [
    enabled,
    flushOnReconnect,
    flushOnMetered,
    flushOnAppForeground,
    intervalMs,
  ]);
}
