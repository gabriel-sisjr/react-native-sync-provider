---
sidebar_position: 5
title: useSyncEvents
description: Declarative React hook for subscribing to sync events with optional type filtering and an enabled flag.
keywords:
  - react-native
  - sync-provider
  - hook
  - useSyncEvents
  - sync-event
  - subscribe
  - listener
---

# `useSyncEvents`

Declarative subscription to [`SyncEvent`](../types.md#syncevent)s with built-in cleanup, type filtering, and an `enabled` flag for conditional subscription. The callback is captured in a ref so you do not need `useCallback` / `useMemo`.

```ts
import { useSyncEvents } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS.

---

## Signature

```ts
function useSyncEvents(options: {
  types?: SyncEventType[];
  onEvent: (event: SyncEvent) => void;
  enabled?: boolean;
}): void
```

---

## Parameters

The hook accepts a single options object.

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `onEvent` | `(event: SyncEvent) => void` | Yes | -- | Fired for every event the subscription receives. Captured in a ref -- inline callbacks are safe. |
| `types` | [`SyncEventType[]`](../enums.md#synceventtype) | No | -- | Allowlist of event types. When provided, only events whose `event.type` is in the list reach `onEvent`. Filtering happens JS-side. |
| `enabled` | `boolean` | No | `true` | When `false`, the hook unsubscribes (or does not subscribe). Useful for gating by user preference. |

---

## Return Value

`void`. The hook does not own state -- it is a pure subscription primitive.

---

## Behavior

- **Mount** -- when `enabled !== false`, registers a listener via [`addSyncEventListener`](../listeners.md#addsynceventlistener).
- **Filter** -- if `types` is provided, the hook filters events by `type` before calling `onEvent`.
- **Ref-captured callback** -- the latest `onEvent` is stored in a ref and read on each emission, so the underlying subscription does not have to tear down when callback identity changes.
- **`enabled` toggle** -- flipping `enabled` from `true` to `false` unsubscribes; flipping back resubscribes.
- **Unmount** -- always unsubscribes.

---

## Example

### Toast on every failure

```tsx
import {
  useSyncEvents,
  SyncEventType,
} from '@gabriel-sisjr/react-native-sync-provider';
import { showToast } from './toast';

function FailureToaster() {
  useSyncEvents({
    types: [SyncEventType.ITEM_FAILED, SyncEventType.SYNC_FAILED],
    onEvent: (event) => {
      showToast(`Sync error: ${event.errorCode ?? 'unknown'}`);
    },
  });
  return null;
}
```

### Conditional subscription

```tsx
import { useSyncEvents } from '@gabriel-sisjr/react-native-sync-provider';

function DebugLogger({ enabled }: { enabled: boolean }) {
  useSyncEvents({
    enabled,
    onEvent: (event) => console.log('[sync]', event),
  });
  return null;
}
```

---

## See also

- [`addSyncEventListener` / `removeSyncEventListener`](../listeners.md) -- the imperative equivalent.
- [`SyncEvent` type](../types.md#syncevent), [`SyncEventType` enum](../enums.md#synceventtype).
