import { useEffect, useRef } from 'react';

import type { UseSyncEventsOptions } from '../types/hooks';
import type { SyncEvent } from '../types/sync';
import { isNativeModuleAvailable } from '../utils/isNativeModuleAvailable';

import { addSyncEventListener, removeSyncEventListener } from '../index';

/**
 * Subscribe declaratively to native sync events.
 *
 * The supplied `onEvent` is captured into a ref so that consumers do not need
 * to memoize it — the underlying subscription is created exactly once per
 * mount and re-registered only when `enabled` toggles.
 *
 * When `types` is supplied, the callback only fires for events whose `type`
 * is in the list. When `types` is omitted, the callback fires for every
 * event on the `'sync-event'` channel.
 *
 * @param options — see {@link UseSyncEventsOptions}.
 *
 * @example
 * useSyncEvents({
 *   types: ['ITEM_FAILED', 'SYNC_FAILED'],
 *   onEvent: (e) => analytics.track('sync_failure', { type: e.type, code: e.errorCode }),
 * });
 *
 * @public
 */
export function useSyncEvents(options: UseSyncEventsOptions): void {
  const { types, onEvent, enabled = true } = options;
  const onEventRef = useRef(onEvent);
  const typesRef = useRef(types);

  // Keep the ref in sync without re-subscribing.
  useEffect(() => {
    onEventRef.current = onEvent;
    typesRef.current = types;
  }, [onEvent, types]);

  useEffect(() => {
    if (!enabled) return undefined;
    if (!isNativeModuleAvailable()) return undefined;

    let subscriptionId: string | null = null;
    let cancelled = false;

    (async () => {
      try {
        subscriptionId = await addSyncEventListener((event: SyncEvent) => {
          const filter = typesRef.current;
          if (filter !== undefined && !filter.includes(event.type)) return;
          onEventRef.current(event);
        });
      } catch {
        // non-fatal
      }
      if (cancelled && subscriptionId !== null) {
        // Component already unmounted — clean up.
        const id = subscriptionId;
        subscriptionId = null;
        void removeSyncEventListener(id).catch(() => undefined);
      }
    })();

    return () => {
      cancelled = true;
      if (subscriptionId !== null) {
        const id = subscriptionId;
        subscriptionId = null;
        void removeSyncEventListener(id).catch(() => undefined);
      }
    };
  }, [enabled]);
}
