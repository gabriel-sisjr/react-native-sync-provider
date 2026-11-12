---
sidebar_position: 4
title: useOfflineQueue
description: Convenience hook combining connection, queue, and a slice of sync state from one subscription — ideal for offline-aware UI.
keywords:
  - react-native
  - sync-provider
  - hook
  - useOfflineQueue
  - offline-first
  - connection
  - waiting-for-connection
---

# `useOfflineQueue`

Connection state, queue state and a slice of sync state from a single subscription, the same data [`useConnection`](./useConnection.md), [`useSyncQueue`](./useSyncQueue.md) and [`useSyncStatus`](./useSyncStatus.md) expose separately. Use it when you want a single hook that powers an offline-first UI -- "X items pending, waiting for connection..." badges, retry buttons, etc.

```ts
import { useOfflineQueue } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS.

---

## Signature

```ts
function useOfflineQueue(): UseOfflineQueueResult
```

---

## Return Value

### State

| Property | Type | Description |
|----------|------|-------------|
| `connection` | [`UseConnectionResult`](../types.md#useconnectionresult) | Same shape as `useConnection`: `status`, `type`, `isOnline`, `isMetered`, `networkQuality`. |
| `size` | `number` | `PENDING` items, cooling ones included, mirroring [`getQueueSize`](../functions.md#getqueuesize). |
| `items` | [`SyncItem[]`](../types.md#syncitem) | The same items, mirroring [`getPendingItems`](../functions.md#getpendingitems). |
| `isSyncing` | `boolean` | Whether a flush is in flight. |
| `isWaitingForConnection` | `boolean` | `size > 0 && !connection.isOnline`. The "Waiting for connection..." sentinel. `true` while the status is still `UNKNOWN`. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last failure refreshing the queue. `enqueue` and `flush` reject instead of setting it. |

### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `enqueue` | `(input: SyncItemInput) => Promise<string>` | Same as the top-level [`enqueue`](../functions.md#enqueue). Rejects on failure. |
| `flush` | `() => Promise<SyncResult>` | Same as the top-level [`flush`](../functions.md#flush). Rejects on failure. |

---

## Behavior

The hook owns its state and drives it from one [`addSyncEventListener`](../listeners.md#addsynceventlistener) subscription. On mount it reads the queue, the connection and `isSyncing()`. The queue is re-read on `ITEM_ENQUEUED`, `ITEM_REMOVED`, `QUEUE_CLEARED`, `ITEM_SUCCEEDED`, `ITEM_FAILED`, `ITEM_DEAD_LETTERED`, `SYNC_SUCCEEDED` and `SYNC_FAILED`, and the connection on every `CONNECTION_CHANGED`, including the ones emitted when only `networkQuality` changed. It unsubscribes on unmount.

For finer-grained access (per-item removal, sync history, dead letters), use [`useSyncQueue`](./useSyncQueue.md), [`useSyncStatus`](./useSyncStatus.md), [`useSyncSnapshot`](./useSyncSnapshot.md) or the facade directly.

---

## Example

### Offline-aware status bar

```tsx
import { useOfflineQueue } from '@gabriel-sisjr/react-native-sync-provider';
import { Text, View, Button } from 'react-native';

function OfflineStatusBar() {
  const { size, isSyncing, isWaitingForConnection, connection, flush } =
    useOfflineQueue();

  if (isWaitingForConnection) {
    return (
      <View>
        <Text>{size} items pending -- waiting for connection</Text>
      </View>
    );
  }

  if (size === 0) return <Text>All synced</Text>;

  return (
    <View>
      <Text>
        {size} pending on {connection.type}
        {isSyncing ? ' (syncing...)' : ''}
      </Text>
      <Button
        title="Sync now"
        onPress={() => flush().catch(console.warn)}
        disabled={isSyncing}
      />
    </View>
  );
}
```

---

## See also

- [`useConnection`](./useConnection.md), [`useSyncQueue`](./useSyncQueue.md), [`useSyncStatus`](./useSyncStatus.md) -- the focused hooks for the same data.
- [Offline queue guide](../../guides/offline-queue.md).
- [Connectivity detection guide](../../guides/connectivity-detection.md).
