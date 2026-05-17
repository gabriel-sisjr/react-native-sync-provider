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

Reactive view of the native pending queue. Auto-refreshes on every queue mutation: `ITEM_ENQUEUED`, `ITEM_REMOVED`, `QUEUE_CLEARED`, every `SYNC_*` event, and every per-item `ITEM_*` event.

```ts
import { useSyncQueue } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS. On web / SSR returns the `UNKNOWN` defaults with `isLoading: false`.

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
| `size` | `number` | Number of pending items. |
| `items` | [`SyncItem[]`](../types.md#syncitem) | Snapshot of pending items, ordered by priority desc + `createdAt` asc. |
| `isLoading` | `boolean` | `true` while the initial fetch / a refresh is in flight. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last error from a hook-issued mutation, or `null`. |

### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `enqueue` | `(item: SyncItemInput) => Promise<string \| null>` | Enqueue a single item. Returns the ULID, or `null` on failure (error captured in state). |
| `enqueueBatch` | `(items: SyncItemInput[]) => Promise<string[] \| null>` | Atomic batch. Returns ULIDs, or `null` on failure. |
| `removeItem` | `(id: string) => Promise<boolean>` | Remove a single item. Returns `false` if unknown / drained. |
| `clearQueue` | `() => Promise<void>` | Drop every pending item. |
| `refresh` | `() => Promise<void>` | Force a re-fetch from native. Rarely needed -- the hook auto-refreshes on events. |

---

## Behavior

- **Initial fetch** -- on mount, the hook calls [`getQueueSize`](../functions.md#getqueuesize) and [`getPendingItems`](../functions.md#getpendingitems) in parallel.
- **Auto-refresh** -- subscribes via [`addSyncEventListener`](../listeners.md#addsynceventlistener) and re-fetches on:
  - `ITEM_ENQUEUED`, `ITEM_REMOVED`, `QUEUE_CLEARED`
  - `SYNC_STARTED`, `SYNC_PROGRESS`, `SYNC_SUCCEEDED`, `SYNC_FAILED`
  - `ITEM_SUCCEEDED`, `ITEM_FAILED`, `ITEM_RETRYING`
- **Error capture** -- mutations (`enqueue`, `enqueueBatch`, `removeItem`, `clearQueue`) catch and surface errors in the `error` state. They also `console.warn` so the failure is visible in dev tools.
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
    const id = await enqueue({
      method: HttpMethod.POST,
      url: 'https://api.example.com/events',
      contentType: 'application/json',
      body: JSON.stringify({ event: 'tap' }),
    });
    if (id) console.log('Queued', id);
  };

  return (
    <View>
      <Button title="Send" onPress={submit} />
      {error && <Text>Error: {error.message}</Text>}
    </View>
  );
}
```

---

## See also

- [`enqueue`](../functions.md#enqueue), [`enqueueBatch`](../functions.md#enqueuebatch), [`getPendingItems`](../functions.md#getpendingitems) -- direct facade calls.
- [Offline queue guide](../../guides/offline-queue.md) -- enqueue patterns and persistence guarantees.
- [`useSyncStatus`](./useSyncStatus.md) -- complementary hook for sync progress.
