---
sidebar_position: 4
title: useOfflineQueue
description: Convenience hook composing useConnection, useSyncQueue, and a slice of useSyncStatus — ideal for offline-aware UI.
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

Convenience composition of [`useConnection`](./useConnection.md), [`useSyncQueue`](./useSyncQueue.md), and a slice of [`useSyncStatus`](./useSyncStatus.md). Use it when you want a single hook that powers an offline-first UI -- "X items pending, waiting for connection..." badges, retry buttons, etc.

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
| `connection` | [`ConnectionState`](../types.md#connectionstate) | Mirrored from `useConnection`. Includes `status`, `type`, `isInternetReachable`, `isExpensive`. |
| `size` | `number` | Pending queue size. |
| `items` | [`SyncItem[]`](../types.md#syncitem) | Pending items snapshot. |
| `isSyncing` | `boolean` | Whether a flush is in flight. |
| `isWaitingForConnection` | `boolean` | `size > 0 && connection.status !== CONNECTED && connection.status !== METERED`. The "Waiting for connection..." sentinel. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last error from `enqueue` or `flush`. |

### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `enqueue` | `(item: SyncItemInput) => Promise<string \| null>` | Same as `useSyncQueue().enqueue`. |
| `flush` | `() => Promise<SyncResult \| null>` | Same as `useSyncStatus().flush`. |

---

## Behavior

This hook does not own any state -- it composes the three primitive hooks and projects a smaller, opinionated surface. Re-renders trigger whenever any of the underlying hooks would re-render.

For finer-grained access (priority lanes, per-item removal, sync history), reach for the primitive hooks directly.

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
      <Button title="Sync now" onPress={flush} disabled={isSyncing} />
    </View>
  );
}
```

---

## See also

- [`useConnection`](./useConnection.md), [`useSyncQueue`](./useSyncQueue.md), [`useSyncStatus`](./useSyncStatus.md) -- the three hooks composed here.
- [Offline queue guide](../../guides/offline-queue.md).
- [Connectivity detection guide](../../guides/connectivity-detection.md).
