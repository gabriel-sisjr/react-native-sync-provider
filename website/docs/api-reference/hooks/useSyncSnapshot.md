---
sidebar_position: 8
title: useSyncSnapshot
description: Read-only dashboard aggregator combining connection, network quality, queue, dead-letter, and sync-engine state into a single reactive snapshot driven by one subscription.
keywords:
  - react-native
  - sync-provider
  - hook
  - useSyncSnapshot
  - dashboard
  - snapshot
  - status
  - dead-letter
---

# useSyncSnapshot

Read-only dashboard aggregator that combines connection, queue, dead-letter, and sync-engine state into a single reactive snapshot. Unlike [`useOfflineQueue`](./useOfflineQueue.md), it exposes **no** action methods (`enqueue`, `flush`, etc.) -- it is a pure observer intended for status dashboards, badges, and "what is the sync engine doing right now?" UIs.

```ts
import { useSyncSnapshot } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS. On web it returns an inert snapshot, with `networkQuality` and `deadLetter` always `undefined`.

---

## Signature

```ts
function useSyncSnapshot(): UseSyncSnapshotResult
```

Takes no parameters and always returns the full snapshot object. Consumers that need fine-grained re-render control can memoize derived values themselves.

---

## Return Value

### State Properties

| Property | Type | Description |
|----------|------|-------------|
| `connection` | [`UseConnectionResult`](../types.md#useconnectionresult) | Connection snapshot -- `status`, `type`, `isOnline`, `isMetered`, `networkQuality`. `connection.networkQuality` is `OFFLINE` until the first read succeeds. |
| `size` | `number` | `PENDING` items, cooling ones included, mirroring [`getQueueSize`](../functions.md#getqueuesize). |
| `items` | [`SyncItem[]`](../types.md#syncitem) | Pending items snapshot, mirroring [`getPendingItems`](../functions.md#getpendingitems). |
| `isSyncing` | `boolean` | `true` while a flush cycle is currently in progress. |
| `isPaused` | `boolean` | `true` after a `PAUSED` event, until `RESUMED`. Starts `false` and follows those events only, because JS cannot read the pause flag: a hook mounted while an earlier `pauseSync()` in the same JS session is still in force reads `false` until the next `PAUSED`. A launch or JS reload clears the pause before JS can observe it, without a `RESUMED`, so `false` is accurate in a fresh session. |
| `progress` | `number \| null` | Current flush progress in `[0, 1]`, or `null` when idle. `SYNC_PROGRESS` is iOS-only, so on Android it stays `0` during a cycle. |
| `lastResult` | [`SyncResult`](../types.md#syncresult) `\| null` | Most recent flush outcome in the persisted sync history, so it survives restarts and can come from an earlier session. Read with [`getLastSyncResult`](../functions.md#getlastsyncresult) on mount and after every `SYNC_SUCCEEDED` / `SYNC_FAILED`. `null` until a read finds a recorded flush, and not reset by `clearSyncHistory()`. A `flush()` that ran no cycle, because it was paused or the quality gate or the health check skipped it, writes no history row and does not change it. |
| `isWaitingForConnection` | `boolean` | `size > 0 && !connection.isOnline`. The "Waiting for connection..." sentinel. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last error captured by the hook, or `null` once cleared. |

### Network Quality and Dead Letters

| Property | Type | Description |
|----------|------|-------------|
| `networkQuality` | [`NetworkQuality`](../enums.md#networkquality) `\| undefined` | Coarse quality of the current link, mirroring [`ConnectionState.networkQuality`](../types.md#connectionstate). `undefined` until the first [`getConnectionStatus`](../functions.md#getconnectionstatus) read succeeds, then read again on every `CONNECTION_CHANGED`, including the ones emitted when only the quality changed. |
| `deadLetter` | `readonly` [`SyncDeadLetterItem`](../types.md#syncdeadletteritem)`[]` `\| undefined` | The dead-letter queue, as [`listDeadLetter`](../functions.md#listdeadletter) returns it: oldest first. `undefined` until the first read succeeds. |

Both are optional because they can still be loading: test for `undefined` before you use them. A failed read keeps the last value. On web, both are always `undefined`.

---

## Behavior Details

- **Single-subscription design** -- exactly **one** [`addSyncEventListener`](../listeners.md#addsynceventlistener) drives every field. Each native event triggers at most a bounded number of `setState` calls within a single callback, which React 18 auto-batches into one render -- so a burst of events never causes a per-field re-render storm.
- **Initial fetch** -- on mount, the hook calls [`getQueueSize`](../functions.md#getqueuesize), [`getPendingItems`](../functions.md#getpendingitems), [`getConnectionStatus`](../functions.md#getconnectionstatus), [`getLastSyncResult`](../functions.md#getlastsyncresult), [`listDeadLetter`](../functions.md#listdeadletter), and [`isSyncing`](../functions.md#issyncing).
- **Subscription** -- updates state on:
  - `ITEM_ENQUEUED` / `ITEM_REMOVED` / `QUEUE_CLEARED` / `SYNC_SUCCEEDED` / `SYNC_FAILED` / `ITEM_SUCCEEDED` / `ITEM_FAILED` / `ITEM_DEAD_LETTERED` -> queue (`size`, `items`) re-fetched
  - `ITEM_DEAD_LETTERED` / `ITEM_REMOVED` / `ITEM_ENQUEUED` / `QUEUE_CLEARED` -> `deadLetter` re-fetched
  - `SYNC_STARTED` -> `isSyncing = true`, `progress = 0`
  - `SYNC_PROGRESS` -> `progress = event.progress`
  - `SYNC_SUCCEEDED` / `SYNC_FAILED` -> `isSyncing = false`, `progress = null`, `lastResult` re-fetched
  - `PAUSED` -> `isPaused = true`
  - `RESUMED` -> `isPaused = false`
  - `CONNECTION_CHANGED` -> connection and `networkQuality` re-fetched
- **No selector** -- the hook takes no arguments and always returns the full snapshot.
- **No extra render for an unchanged dead-letter queue** -- a dead-letter read that finds the list already shown does not re-render, so an `ITEM_ENQUEUED` from a plain `enqueue` costs no extra render. Only the latest read may update the list.
- **Error capture** -- queue-refresh failures are stored in `error` instead of throwing; connection, last-result and dead-letter refresh failures keep the last known value.
- **Cleanup** -- guards every async `setState` with a `cancelledRef` and removes the listener on unmount.
- **Safe before native load** -- returns inert state and never throws on render.

---

## Usage

### Sync status dashboard

```tsx
import { useSyncSnapshot } from '@gabriel-sisjr/react-native-sync-provider';
import { ActivityIndicator, Text, View } from 'react-native';

function SyncStatusBadge() {
  const { size, isSyncing, progress, connection, isWaitingForConnection } =
    useSyncSnapshot();

  if (isWaitingForConnection) {
    return <Text>{size} item(s) waiting for connection</Text>;
  }

  if (isSyncing) {
    return (
      <View>
        <ActivityIndicator />
        <Text>Syncing {Math.round((progress ?? 0) * 100)}%</Text>
      </View>
    );
  }

  if (size === 0) return <Text>All synced</Text>;

  return (
    <Text>
      {size} pending on {connection.type}
    </Text>
  );
}
```

### Requests that need attention

```tsx
import {
  discardDeadLetter,
  replayDeadLetter,
  useSyncSnapshot,
} from '@gabriel-sisjr/react-native-sync-provider';
import { Button, Text, View } from 'react-native';

function DeadLetterList() {
  const { deadLetter } = useSyncSnapshot();

  if (deadLetter === undefined || deadLetter.length === 0) return null;

  return (
    <View>
      <Text>{deadLetter.length} request(s) need attention</Text>
      {deadLetter.map((item) => (
        <View key={item.id}>
          <Text>
            {item.method} {item.url}: {item.lastErrorCode}
          </Text>
          <Button
            title="Retry"
            onPress={() => replayDeadLetter(item.id).catch(console.warn)}
          />
          <Button
            title="Discard"
            onPress={() => discardDeadLetter(item.id).catch(console.warn)}
          />
        </View>
      ))}
    </View>
  );
}
```

A replay only puts the item back in the queue: a flush sends it.

---

## When to Use

Use `useSyncSnapshot` for dashboards and badges that only observe. It is the only hook that returns the dead-letter queue, and the only combined hook with `isPaused`, `progress` and `lastResult`. It returns no methods: act through the facade functions or an action hook.

- Use [`useOfflineQueue`](./useOfflineQueue.md) when the screen also enqueues or flushes.
- Use [`useConnection`](./useConnection.md), [`useSyncQueue`](./useSyncQueue.md) or [`useSyncStatus`](./useSyncStatus.md) when a component needs one slice.

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

- [useOfflineQueue](./useOfflineQueue.md) -- Similar composition that also exposes `enqueue` and `flush`
- [useSyncStatus](./useSyncStatus.md) -- The focused hook for the sync engine state
- [useSyncQueue](./useSyncQueue.md) -- The focused hook for the queue
- [useConnection](./useConnection.md) -- The focused hook for the connection
- [Types & Interfaces](../types.md) -- `UseSyncSnapshotResult` and `SyncDeadLetterItem`
- [Enums](../enums.md) -- `NetworkQuality`
