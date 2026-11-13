---
sidebar_position: 3
title: useSyncStatus
description: React hook that mirrors the sync engine state — isSyncing, isPaused, progress, and the last completed result.
keywords:
  - react-native
  - sync-provider
  - hook
  - useSyncStatus
  - flush
  - sync-progress
  - last-sync-result
---

# useSyncStatus

Reactive view of the sync engine. Mirrors `PAUSED`, `RESUMED`, `SYNC_STARTED`, `SYNC_PROGRESS`, `SYNC_SUCCEEDED`, and `SYNC_FAILED` events. Hydrates on mount with the last result via [`getLastSyncResult`](../functions.md#getlastsyncresult). State that comes from events starts at its default, so a cycle or a pause already in force at mount is not reflected.

```ts
import { useSyncStatus } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS. On web it returns an inert result, and its methods reject with `NATIVE_MODULE_UNAVAILABLE`.

---

## Signature

```ts
function useSyncStatus(): UseSyncStatusResult
```

---

## Return Value

### State Properties

| Property | Type | Description |
|----------|------|-------------|
| `isSyncing` | `boolean` | `true` between `SYNC_STARTED` and the next `SYNC_SUCCEEDED` / `SYNC_FAILED`. |
| `isPaused` | `boolean` | `true` after a `PAUSED` event, until `RESUMED`. Starts `false` and follows those events only, because JS cannot read the pause flag: a hook mounted while an earlier `pauseSync()` in the same JS session is still in force reads `false` until the next `PAUSED`. A launch or JS reload clears the pause before JS can observe it, without a `RESUMED`, so `false` is accurate in a fresh session. |
| `progress` | `number \| null` | Last `SyncEvent.progress` value, in `[0, 1]`. `0` on `SYNC_STARTED`, `null` when idle. `SYNC_PROGRESS` is iOS-only, so on Android it stays `0` during a cycle. |
| `lastResult` | [`SyncResult`](../types.md#syncresult) `\| null` | The most recent persisted flush, or `null` if none exists. |
| `error` | [`SyncError`](../errors.md) `\| null` | Failure of the initial read, or `null`. The methods reject instead of setting it. |

### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `flush` | `() => Promise<SyncResult>` | Same as the top-level [`flush`](../functions.md#flush). Rejects on failure. |
| `pause` | `() => Promise<void>` | Pause the engine. |
| `resume` | `() => Promise<void>` | Resume after a pause. |

---

## Behavior Details

- **Initial fetch** -- on mount, the hook calls [`getLastSyncResult`](../functions.md#getlastsyncresult). It does not read `isSyncing()`: [`useSyncSnapshot`](./useSyncSnapshot.md) does.
- **Subscription** -- updates state on:
  - `PAUSED` -> `isPaused = true`
  - `RESUMED` -> `isPaused = false`
  - `SYNC_STARTED` -> `isSyncing = true`, `progress = 0`
  - `SYNC_PROGRESS` -> `progress = event.progress`
  - `SYNC_SUCCEEDED` / `SYNC_FAILED` -> `isSyncing = false`, `progress = null`, `lastResult` re-fetched
- **Errors** -- `flush()`, `pause()` and `resume()` reject on failure, so catch them yourself.
- **Cleanup** -- unsubscribes on unmount.

> **Note — `lastResult.failureCount > 0` does not throw:** A flush with partial failures still resolves to a `SyncResult`. `lastResult.errors` is always empty on iOS, so use `ITEM_FAILED` events for per-item details. See the [Error handling guide](../../guides/error-handling.md).

---

## Usage

### Sync button with progress

```tsx
import { useSyncStatus } from '@gabriel-sisjr/react-native-sync-provider';
import { ActivityIndicator, Button, Text, View } from 'react-native';

function SyncButton() {
  const { isSyncing, progress, lastResult, flush } = useSyncStatus();

  return (
    <View>
      <Button
        title="Sync now"
        onPress={() => flush().catch(console.warn)}
        disabled={isSyncing}
      />
      {isSyncing && (
        <View>
          <ActivityIndicator />
          <Text>{Math.round((progress ?? 0) * 100)}%</Text>
        </View>
      )}
      {lastResult && (
        <Text>
          Last run: {lastResult.successCount} ok, {lastResult.failureCount} failed
        </Text>
      )}
    </View>
  );
}
```

---

## When to Use

Use `useSyncStatus` for sync controls: a "Sync now" button, a progress indicator, pause and resume, the last result.

- It starts with `isSyncing: false` and learns about a cycle only from events, so a cycle already running at mount does not show. [`useSyncSnapshot`](./useSyncSnapshot.md) and [`useOfflineQueue`](./useOfflineQueue.md) read `isSyncing()` on mount.
- Use [`useSyncQueue`](./useSyncQueue.md) for the queue itself.

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

- [Functions](../functions.md) -- `flush`, `pauseSync`, `resumeSync` and `isSyncing`, the direct facade calls
- [useSyncQueue](./useSyncQueue.md) -- Complementary hook for the queue itself
- [useOfflineQueue](./useOfflineQueue.md) -- Combined view: connection, queue and a slice of sync state
