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

# useOfflineQueue

Connection state, queue state and a slice of sync state from a single subscription, the same data [`useConnection`](./useConnection.md), [`useSyncQueue`](./useSyncQueue.md) and [`useSyncStatus`](./useSyncStatus.md) expose separately. Use it when you want a single hook that powers an offline-first UI -- "X items pending, waiting for connection..." badges, retry buttons, etc.

```ts
import { useOfflineQueue } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS. On web it returns an inert result, and `enqueue` and `flush` reject with `NATIVE_MODULE_UNAVAILABLE`.

---

## Signature

```ts
function useOfflineQueue(): UseOfflineQueueResult
```

---

## Return Value

### State Properties

| Property | Type | Description |
|----------|------|-------------|
| `connection` | [`UseConnectionResult`](../types.md#useconnectionresult) | Same shape as `useConnection`: `status`, `type`, `isOnline`, `isMetered`, `networkQuality`. |
| `size` | `number` | `PENDING` items, cooling ones included, mirroring [`getQueueSize`](../functions.md#getqueuesize). |
| `items` | [`SyncItem[]`](../types.md#syncitem) | The same items, mirroring [`getPendingItems`](../functions.md#getpendingitems). |
| `isSyncing` | `boolean` | Whether a cycle is running. |
| `isWaitingForConnection` | `boolean` | `size > 0 && !connection.isOnline`. The "Waiting for connection..." sentinel. `true` while the status is still `UNKNOWN`. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last failure refreshing the queue. `enqueue` and `flush` reject instead of setting it. |

### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `enqueue` | `(input: SyncItemInput) => Promise<string>` | Same as the top-level [`enqueue`](../functions.md#enqueue). Rejects on failure. |
| `flush` | `() => Promise<SyncResult>` | Same as the top-level [`flush`](../functions.md#flush). Rejects on failure. |

---

## Behavior Details

The hook owns its state and drives it from one [`addSyncEventListener`](../listeners.md#addsynceventlistener) subscription. On mount it reads the queue, the connection and `isSyncing()`. The queue is re-read on `ITEM_ENQUEUED`, `ITEM_REMOVED`, `QUEUE_CLEARED`, `ITEM_SUCCEEDED`, `ITEM_FAILED`, `ITEM_DEAD_LETTERED`, `SYNC_SUCCEEDED` and `SYNC_FAILED`, and the connection on every `CONNECTION_CHANGED`, including the ones emitted when only `networkQuality` changed. It unsubscribes on unmount.

For finer-grained access (per-item removal, sync history, dead letters), use [`useSyncQueue`](./useSyncQueue.md), [`useSyncStatus`](./useSyncStatus.md), [`useSyncSnapshot`](./useSyncSnapshot.md) or the facade directly.

---

## Usage

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

## When to Use

Use `useOfflineQueue` for an offline-aware screen that also acts: it combines the connection, the queue and `isSyncing` with `enqueue` and `flush`, from one subscription.

- Use [`useSyncSnapshot`](./useSyncSnapshot.md) for a read-only view that also needs `isPaused`, `progress`, `lastResult` or dead letters.
- Use [`useSyncQueue`](./useSyncQueue.md) for `removeItem` or `clearQueue`, and [`useSyncStatus`](./useSyncStatus.md) for `pause` or `resume`.

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

- [useConnection](./useConnection.md) -- The focused hook for the connection
- [useSyncQueue](./useSyncQueue.md) -- The focused hook for the queue
- [useSyncStatus](./useSyncStatus.md) -- The focused hook for the sync engine state
- [Offline Queue](../../guides/offline-queue.md) -- Enqueue patterns, persistence guarantees and observation
- [Connectivity Detection](../../guides/connectivity-detection.md) -- How the native layer detects connectivity and classifies network quality
