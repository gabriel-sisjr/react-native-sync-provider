---
sidebar_position: 2
title: useSyncQueue
description: React hook that mirrors the native pending queue and re-renders on every queue mutation.
keywords:
  - react-native
  - sync-provider
  - hook
  - useSyncQueue
  - offline-queue
  - enqueue
  - queue-state
---

# `useSyncQueue`

Reactive view of the native pending queue, plus the write operations. Refreshes on the events that can change the queue.

```ts
import { useSyncQueue } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS. On web / SSR returns an empty, inert result, and its methods reject with `NATIVE_MODULE_UNAVAILABLE`.

---

## Signature

```ts
function useSyncQueue(): UseSyncQueueResult
```

---

## Return Value

### State

| Property | Type | Description |
|----------|------|-------------|
| `size` | `number` | `PENDING` items, cooling ones included. Items in flight and items that failed permanently are not counted. |
| `items` | [`SyncItem[]`](../types.md#syncitem) | The same items, in dispatch order. |
| `isLoading` | `boolean` | `true` while the initial fetch / a refresh is in flight. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last failure refreshing the queue, or `null`. The methods reject instead of setting it. |

### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `enqueue` | `(input: SyncItemInput) => Promise<string>` | Same as the top-level [`enqueue`](../functions.md#enqueue). Rejects on failure. |
| `enqueueBatch` | `(inputs: SyncItemInput[]) => Promise<string[]>` | Same as the top-level [`enqueueBatch`](../functions.md#enqueuebatch). Rejects on failure. |
| `removeItem` | `(id: string) => Promise<boolean>` | Remove a single item. Returns `false` if unknown or already delivered. |
| `clearQueue` | `() => Promise<void>` | Drop every item, in any status. |
| `refresh` | `() => Promise<void>` | Force a re-fetch from native. Rarely needed -- the hook auto-refreshes on events. |

---

## Behavior

- **Initial fetch** -- on mount, the hook calls [`getQueueSize`](../functions.md#getqueuesize) and [`getPendingItems`](../functions.md#getpendingitems) in parallel.
- **Auto-refresh** -- subscribes via [`addSyncEventListener`](../listeners.md#addsynceventlistener) and re-fetches on:
  - `ITEM_ENQUEUED`, `ITEM_REMOVED`, `QUEUE_CLEARED`
  - `SYNC_SUCCEEDED`, `SYNC_FAILED`
  - `ITEM_SUCCEEDED`, `ITEM_FAILED`
- **Cooldown fields** -- `ITEM_RETRYING` is not on that list, so an item's `attempts` and `nextRetryAt` update when the cycle ends.
- **Errors** -- the methods pass through to the facade and reject on failure, so catch them yourself. `error` only holds a failed refresh.
- **Cleanup** -- unsubscribes on unmount.

---

## Example

### Pending badge

```tsx
import { useSyncQueue } from '@gabriel-sisjr/react-native-sync-provider';
import { Text, View, Button } from 'react-native';

function PendingBadge() {
  const { size, isLoading, clearQueue } = useSyncQueue();

  if (isLoading) return <Text>Loading...</Text>;
  if (size === 0) return null;

  return (
    <View>
      <Text>{size} pending</Text>
      <Button title="Clear all" onPress={clearQueue} />
    </View>
  );
}
```

### Enqueue from a form

```tsx
import { useSyncQueue, HttpMethod } from '@gabriel-sisjr/react-native-sync-provider';

function EventComposer() {
  const { enqueue, error } = useSyncQueue();

  const submit = async () => {
    try {
      const id = await enqueue({
        method: HttpMethod.POST,
        url: 'https://api.example.com/events',
        contentType: 'application/json',
        body: JSON.stringify({ event: 'tap' }),
      });
      console.log('Queued', id);
    } catch (err) {
      console.warn('Could not enqueue', err);
    }
  };

  return (
    <View>
      <Button title="Send" onPress={submit} />
      {error && <Text>Could not read the queue: {error.message}</Text>}
    </View>
  );
}
```

---

## See also

- [`enqueue`](../functions.md#enqueue), [`enqueueBatch`](../functions.md#enqueuebatch), [`getPendingItems`](../functions.md#getpendingitems) -- direct facade calls.
- [Offline queue guide](../../guides/offline-queue.md) -- enqueue patterns and persistence guarantees.
- [`useSyncStatus`](./useSyncStatus.md) -- complementary hook for sync progress.
