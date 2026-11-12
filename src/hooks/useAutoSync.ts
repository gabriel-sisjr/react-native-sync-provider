import { useEffect } from 'react';
import { AppState } from 'react-native';
import type { AppStateStatus, NativeEventSubscription } from 'react-native';

import { ConnectionStatus, SyncEventType } from '../types/enums';
import type { UseAutoSyncOptions } from '../types/hooks';
import type { SyncEvent } from '../types/sync';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import {
  addSyncEventListener,
  flush as facadeFlush,
  getConnectionStatus as facadeGetConnectionStatus,
  isSyncing as facadeIsSyncing,
  removeSyncEventListener,
} from '../index';

const DEFAULT_INTERVAL_MS = 60_000;

/**
 * Extended {@link UseAutoSyncOptions} accepted by `useAutoSync`.
 *
 * Adds `intervalMs` (poll period) and `flushOnAppForeground` (opt-in
 * AppState integration), which are JS-only orchestration concerns.
 */
export interface UseAutoSyncExtraOptions extends UseAutoSyncOptions {
  /**
   * Interval (in ms) at which the orchestrator triggers a flush when not
   * offline and not currently syncing. Defaults to `60_000` (60s), and a value
   * that is not positive falls back to that default, so the tick cannot be
   * turned off. Ignored when `enabled !== true`.
   */
  intervalMs?: number;
  /**
   * If `true`, the orchestrator listens to `AppState.change` and triggers a
   * flush when the app returns to `'active'`, unless the device is
   * `DISCONNECTED`. Defaults to `false` to avoid unintended foreground sync
   * battery cost.
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
 *      `flush()` unless the device is `DISCONNECTED` or a sync is already in
 *      flight.
 *   2. Subscribes to `CONNECTION_CHANGED` events and triggers a flush when
 *      the device transitions to `CONNECTED` (subject to `flushOnReconnect`).
 *   3. If `flushOnAppForeground === true`, registers an `AppState.change`
 *      listener and flushes on `'active'`, unless the device is `DISCONNECTED`.
 *
 * All three are torn down on unmount or when `enabled` flips to `false`.
 *
 * The periodic tick and the foreground trigger read the connection status first
 * and do nothing while it is `DISCONNECTED`, because a flush while offline can
 * spend retry attempts and move valid items to the dead-letter queue. `UNKNOWN`
 * and `METERED` still flush, and a status read that fails skips the flush.
 * Neither trigger checks queue size, so on an empty queue a flush records a
 * 0/0 result.
 *
 * Every flush the hook starts is a `flush()` call and runs its checks. With
 * `SyncOptions.minNetworkQuality` set, each tick or trigger the gate blocks
 * emits `SYNC_FAILED` with `NETWORK_QUALITY_TOO_LOW`, and each one during a
 * health-check backoff `HEALTH_CHECK_FAILED`, an empty queue included, since
 * both checks run before the queue is read.
 *
 * The reconnect trigger reads the status once when the hook is enabled, then
 * follows `CONNECTION_CHANGED`: it flushes when an event's `connectionStatus`
 * changes to `CONNECTED`, or to `METERED` with `flushOnMetered`. An event that
 * leaves the status as it was, such as one for a change of `networkQuality`
 * alone, does not trigger it. When that first read fails, the first event only
 * sets the status the next ones are compared with.
 *
 * The hook never reads `SyncOptions.strategy`, so under `MANUAL` it keeps
 * flushing on its interval, reconnect and foreground triggers. `MANUAL` only
 * stops the native engine from scheduling flushes on its own: the in-process
 * retry timer and, on Android, the WorkManager retry kick. Background windows
 * enabled through `enableBackgroundSync` (the Android periodic worker and the
 * iOS BGTasks) still run. To stop the hook's flushes, pass `enabled: false` or
 * unmount it.
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

    // Unless `minNetworkQuality` is set, neither dispatcher checks
    // connectivity, so a flush while offline would spend retry attempts and can
    // dead-letter valid items. A status read that rejects skips the flush too.
    const flushUnlessOffline = async () => {
      try {
        const connection = await facadeGetConnectionStatus();
        if (connection.status === ConnectionStatus.DISCONNECTED) return;
        await safeFlush();
      } catch {
        // Swallow — orchestrator must never throw to consumer.
      }
    };

    const timer = setInterval(() => {
      void flushUnlessOffline();
    }, period);

    // The status the next `CONNECTION_CHANGED` is compared with, read once
    // here: native does not report the state it is already in, and it also
    // reports changes that leave the status as it was (`networkQuality`
    // alone), so only a change from a known status is a transition. `null`
    // until known.
    let lastStatus: ConnectionStatus | null = null;
    // Set while that read is pending, so an event that arrives first is
    // judged against it, in arrival order.
    let statusRead: Promise<void> | null = null;
    if (flushOnReconnect) {
      statusRead = facadeGetConnectionStatus()
        .then(
          (snapshot) => {
            lastStatus = snapshot.status;
          },
          // Unreadable: the first event becomes the status to compare with.
          () => undefined
        )
        .then(() => {
          statusRead = null;
        });
    }

    const onStatusChanged = (next: ConnectionStatus) => {
      const prev = lastStatus;
      lastStatus = next;
      if (prev === null || prev === next) return;
      const becameConnected = next === ConnectionStatus.CONNECTED;
      const becameMetered = flushOnMetered && next === ConnectionStatus.METERED;
      if (becameConnected || becameMetered) void safeFlush();
    };

    (async () => {
      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          if (!flushOnReconnect) return;
          if (event.type !== SyncEventType.CONNECTION_CHANGED) return;
          const next = event.connectionStatus;
          if (next === undefined) return;
          const pending = statusRead;
          if (pending === null) {
            onStatusChanged(next);
            return;
          }
          void pending.then(() => {
            if (!cancelled) onStatusChanged(next);
          });
        });
      } catch {
        // ignore
      }
    })();

    if (flushOnAppForeground) {
      appStateSubscription = AppState.addEventListener(
        'change',
        (next: AppStateStatus) => {
          if (next === 'active') void flushUnlessOffline();
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
