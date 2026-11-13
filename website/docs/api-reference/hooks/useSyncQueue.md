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

# useSyncQueue

Reactive view of the native pending queue, plus the write operations. Refreshes on the events that can change the queue. Dead letters are not part of `items`: read them with [`listDeadLetter`](../functions.md#listdeadletter) or [`useSyncSnapshot().deadLetter`](./useSyncSnapshot.md).

```ts
import { useSyncQueue } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS. On web it returns an empty, inert result, and its methods reject with `NATIVE_MODULE_UNAVAILABLE`.

---

## Signature

```ts
function useSyncQueue(): UseSyncQueueResult
```

---

## Return Value

### State Properties

| Property | Type | Description |
|----------|------|-------------|
| `size` | `number` | `PENDING` items, cooling ones included. Items in flight and dead letters are not counted. |
| `items` | [`SyncItem[]`](../types.md#syncitem) | The same items, in dispatch order. |
| `isLoading` | `boolean` | `true` while the initial fetch / a refresh is in flight. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last failure refreshing the queue, or `null`. The methods reject instead of setting it. |

### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `enqueue` | `(input: SyncItemInput) => Promise<string>` | Same as the top-level [`enqueue`](../functions.md#enqueue). Rejects on failure. |
| `enqueueBatch` | `(inputs: SyncItemInput[]) => Promise<string[]>` | Same as the top-level [`enqueueBatch`](../functions.md#enqueuebatch). Rejects on failure. |
| `removeItem` | `(id: string) => Promise<boolean>` | Remove a single item, or the dead letter with that id. Returns `false` if neither exists. |
| `clearQueue` | `() => Promise<void>` | Drop every item, in any status, and every dead letter. |
| `refresh` | `() => Promise<void>` | Force a re-fetch from native. Rarely needed -- the hook auto-refreshes on events. |

---

## Behavior Details

- **Initial fetch** -- on mount, the hook calls [`getQueueSize`](../functions.md#getqueuesize) and [`getPendingItems`](../functions.md#getpendingitems) in parallel.
- **Auto-refresh** -- subscribes via [`addSyncEventListener`](../listeners.md#addsynceventlistener) and re-fetches on:
  - `ITEM_ENQUEUED`, `ITEM_REMOVED`, `QUEUE_CLEARED`
  - `SYNC_SUCCEEDED`, `SYNC_FAILED`
  - `ITEM_SUCCEEDED`, `ITEM_FAILED`, `ITEM_DEAD_LETTERED`
- **Cooldown fields** -- `ITEM_RETRYING` is not on that list, so an item's `attempts` and `nextRetryAt` update when the cycle ends.
- **Errors** -- the methods pass through to the facade and reject on failure, so catch them yourself. `error` only holds a failed refresh.
- **Cleanup** -- unsubscribes on unmount.

---

## Usage

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

## When to Use

Use `useSyncQueue` when a component reads or changes the queue itself: a pending badge or list, a form that enqueues, a "clear all" action. It is the only hook that returns `enqueueBatch`, `removeItem` and `clearQueue`.

- Use [`useOfflineQueue`](./useOfflineQueue.md) when the component also needs the connection, for a "waiting for connection" state.
- Use [`useSyncSnapshot`](./useSyncSnapshot.md) to show dead letters: `items` holds pending items only.

| Feature | `useConnection` | `useSyncQueue` | `useSyncStatus` | `useOfflineQueue` | `useSyncSnapshot` |
|---------|-----------------|----------------|-----------------|-------------------|-------------------|
| Connection state | Yes | No | No | Yes (`connection`) | Yes (`connection`, `networkQuality`) |
| Queue `size` and `items` | No | Yes | No | Yes | Yes |
| `isSyncing` | No | No | Yes, from events only | Yes, read on mount, then from events | Yes, read on mount, then from events |
| `isPaused`, `progress`, `lastResult` | No | No | Yes | No | Yes |
| `isWaitingForConnection` | No | No | No | Yes | Yes |
| Dead letters | No | No | No | No | Yes (`deadLetter`) |
| Methods | None | `enqueue`, `enqueueBatch`, `removeItem`, `clearQueue`, `refresh` | `flush`, `pause`, `resume` | `enqueue`, `flush` | None |

---

## See Also

- [Functions](../functions.md) -- `enqueue`, `enqueueBatch` and `getPendingItems`, the direct facade calls
- [Offline Queue](../../guides/offline-queue.md) -- Enqueue patterns and persistence guarantees
- [useSyncStatus](./useSyncStatus.md) -- Complementary hook for sync progress
