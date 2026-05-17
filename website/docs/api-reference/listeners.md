---
sidebar_position: 2
title: Event Listeners
description: Low-level subscription helpers for sync events — addSyncEventListener, removeSyncEventListener, and the SYNC_EVENT_CHANNEL constant.
keywords:
  - react-native
  - sync-provider
  - events
  - listeners
  - addSyncEventListener
  - removeSyncEventListener
  - SYNC_EVENT_CHANNEL
---

# Event Listeners

The native layer streams [`SyncEvent`](./types.md#syncevent)s -- enqueues, sync starts, item failures, connection changes, background sync ticks. The two facade functions below are the imperative API; for declarative React subscriptions, prefer [`useSyncEvents`](./hooks/useSyncEvents.md).

```ts
import {
  addSyncEventListener,
  removeSyncEventListener,
  SYNC_EVENT_CHANNEL,
  SyncEventType,
} from '@gabriel-sisjr/react-native-sync-provider';
```

---

## `SYNC_EVENT_CHANNEL`

The constant string identifier the native layer broadcasts events on. Exported as a `const` for callers that bridge sync events into another event bus.

```ts
const SYNC_EVENT_CHANNEL = 'sync-event' as const;
```

You rarely need this directly -- `addSyncEventListener` already wires it up.

---

## `addSyncEventListener`

Subscribe to every [`SyncEvent`](./types.md#syncevent) emitted by the native layer. Returns a subscription id that you must pass back to `removeSyncEventListener` to unsubscribe.

### Signature

```ts
function addSyncEventListener(
  callback: (event: SyncEvent) => void
): Promise<string>
```

### Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `callback` | `(event: SyncEvent) => void` | Yes | Invoked on the JS thread for every emitted event. Filter inside the callback if you only care about certain `event.type` values. |

### Returns

`Promise<string>` -- the subscription id.

### Throws

`NATIVE_MODULE_UNAVAILABLE` -- if called on web / SSR or before native linking.

### Implementation notes

This is a thin wrapper around the native module's `addListener('sync-event', cb)`. The promise resolves once the native side has registered the callback.

### Example

```ts
const subscriptionId = await addSyncEventListener((event) => {
  console.log(event.type, event.itemId);
});

// later, on cleanup
await removeSyncEventListener(subscriptionId);
```

---

## `removeSyncEventListener`

Unsubscribe a previously-registered listener.

### Signature

```ts
function removeSyncEventListener(id: string): Promise<void>
```

### Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `string` | Yes | The subscription id returned by `addSyncEventListener`. |

### Returns

`Promise<void>`.

### Throws

`NATIVE_MODULE_UNAVAILABLE`. Unknown ids are silently ignored on the native side.

### Example

```ts
await removeSyncEventListener(subscriptionId);
```

---

## Filter by Type

Filtering is callback-side. The native layer emits all event types on the same channel.

```ts
const id = await addSyncEventListener((event) => {
  switch (event.type) {
    case SyncEventType.ITEM_FAILED:
      console.warn(`Item ${event.itemId} failed:`, event.errorCode);
      break;
    case SyncEventType.SYNC_SUCCEEDED:
      console.log('Flush completed');
      break;
    case SyncEventType.CONNECTION_CHANGED:
      console.log('Connection now:', event.connectionStatus);
      break;
    default:
      // ignore
  }
});
```

For a declarative version that ref-captures the callback (no `useCallback` / `useMemo` needed) and supports a `types` allowlist, see [`useSyncEvents`](./hooks/useSyncEvents.md).

---

## Lifecycle

- Listeners outlive React component mounts. Always unsubscribe on cleanup.
- Multiple listeners can be active simultaneously; each receives every event.
- Listeners do not survive a JS context reload (Metro fast refresh, app restart). The native event bus is recreated; you must re-subscribe.

---

## See also

- [`SyncEvent` type](./types.md#syncevent) -- the full payload shape.
- [`SyncEventType` enum](./enums.md#synceventtype) -- every emitted type.
- [`useSyncEvents` hook](./hooks/useSyncEvents.md) -- React-friendly wrapper.
