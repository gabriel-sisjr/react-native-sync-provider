---
sidebar_position: 8
title: useSyncSnapshot
description: Read-only dashboard aggregator combining connection, queue, and sync-engine state into a single reactive snapshot driven by one subscription.
keywords:
  - react-native
  - sync-provider
  - hook
  - useSyncSnapshot
  - dashboard
  - snapshot
  - status
---

# `useSyncSnapshot`

Read-only dashboard aggregator that combines connection, queue, and sync-engine state into a single reactive snapshot. Unlike [`useOfflineQueue`](./useOfflineQueue.md), it exposes **no** action methods (`enqueue`, `flush`, etc.) -- it is a pure observer intended for status dashboards, badges, and "what is the sync engine doing right now?" UIs.

```ts
import { useSyncSnapshot } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS.

---

## Signature

```ts
function useSyncSnapshot(): UseSyncSnapshotResult
```

Takes no parameters and always returns the full snapshot object. Consumers that need fine-grained re-render control can memoize derived values themselves.

---

## Return Value

### State

| Property | Type | Description |
|----------|------|-------------|
| `connection` | [`UseConnectionResult`](../types.md#useconnectionresult) | Connection snapshot -- `status`, `type`, `isOnline`, `isMetered`. |
| `size` | `number` | Pending queue size, mirroring [`getQueueSize`](../functions.md#getqueuesize). |
| `items` | [`SyncItem[]`](../types.md#syncitem) | Pending items snapshot, mirroring [`getPendingItems`](../functions.md#getpendingitems). |
| `isSyncing` | `boolean` | `true` while a flush cycle is currently in progress. |
| `isPaused` | `boolean` | `true` after `pauseSync()` and before `resumeSync()`. |
| `progress` | `number \| null` | Current flush progress in `[0, 1]`, or `null` when idle. |
| `lastResult` | [`SyncResult`](../types.md#syncresult) `\| null` | Most recent flush outcome, or `null` if none yet this session. |
| `isWaitingForConnection` | `boolean` | `size > 0 && !connection.isOnline`. The "Waiting for connection..." sentinel. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last error captured by the hook, or `null` once cleared. |

### Forward fields (Block B / v0.3)

These two optional fields are part of the frozen type so consumer code can depend on their final shape, but they are **ALWAYS `undefined` in v0.2**. Real values arrive once the v0.3 network-quality classifier and dead-letter store ship.

| Property | Type | Description |
|----------|------|-------------|
| `networkQuality` | [`NetworkQuality`](../enums.md#networkquality) `\| undefined` | **Forward field (Gap #2).** Coarse network-quality classification. `undefined` in v0.2. |
| `deadLetter` | [`SyncDeadLetterItem[]`](../types.md#syncdeadletteritem) `\| undefined` | **Forward field (Gap #4).** Items moved to the dead-letter queue after exhausting their retry budget. `undefined` in v0.2. |

:::note v0.2 vs v0.3
`networkQuality` and `deadLetter` are typed-optional today purely so the snapshot shape is stable across the v0.2 → v0.3 boundary. Do not branch on them in v0.2 -- they will never be populated until the v0.3 features land.
:::

---

## Behavior

- **Single-subscription design** -- exactly **one** [`addSyncEventListener`](../listeners.md#addsynceventlistener) drives every field. Each native event triggers at most a bounded number of `setState` calls within a single callback, which React 18 auto-batches into one render -- so a burst of events never causes a per-field re-render storm.
- **Initial fetch** -- on mount, the hook calls [`getQueueSize`](../functions.md#getqueuesize), [`getPendingItems`](../functions.md#getpendingitems), [`getConnectionStatus`](../functions.md#getconnectionstatus), [`getLastSyncResult`](../functions.md#getlastsyncresult), and [`isSyncing`](../functions.md#issyncing).
- **Subscription** -- updates state on:
  - `ITEM_ENQUEUED` / `ITEM_REMOVED` / `QUEUE_CLEARED` / `SYNC_SUCCEEDED` / `SYNC_FAILED` / `ITEM_SUCCEEDED` / `ITEM_FAILED` -> queue (`size`, `items`) re-fetched
  - `SYNC_STARTED` -> `isSyncing = true`, `progress = 0`
  - `SYNC_PROGRESS` -> `progress = event.progress`
  - `SYNC_SUCCEEDED` / `SYNC_FAILED` -> `isSyncing = false`, `progress = null`, `lastResult` re-fetched
  - `PAUSED` -> `isPaused = true`
  - `RESUMED` -> `isPaused = false`
  - `CONNECTION_CHANGED` -> connection re-fetched
- **No selector** -- the hook takes no arguments and always returns the full snapshot.
- **Error capture** -- queue-refresh failures are stored in `error` instead of throwing; connection / last-result refresh failures keep the last known value.
- **Cleanup** -- guards every async `setState` with a `cancelledRef` and removes the listener on unmount.
- **Safe before native load** -- returns inert state and never throws on render.

---

## Example

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

---

## See also

- [`useOfflineQueue`](./useOfflineQueue.md) -- similar composition that also exposes `enqueue` / `flush` actions.
- [`useSyncStatus`](./useSyncStatus.md), [`useSyncQueue`](./useSyncQueue.md), [`useConnection`](./useConnection.md) -- the primitive hooks this snapshot aggregates.
- [`UseSyncSnapshotResult`](../types.md#usesyncsnapshotresult), [`NetworkQuality`](../enums.md#networkquality), [`SyncDeadLetterItem`](../types.md#syncdeadletteritem).
