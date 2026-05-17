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

# `useSyncStatus`

Reactive view of the sync engine. Mirrors `PAUSED`, `RESUMED`, `SYNC_STARTED`, `SYNC_PROGRESS`, `SYNC_SUCCEEDED`, and `SYNC_FAILED` events. Hydrates on mount with the last result via [`getLastSyncResult`](../functions.md#getlastsyncresult).

```ts
import { useSyncStatus } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS.

---

## Signature

```ts
function useSyncStatus(): UseSyncStatusResult
```

---

## Return Value

### State

| Property | Type | Description |
|----------|------|-------------|
| `isSyncing` | `boolean` | `true` between `SYNC_STARTED` and the next `SYNC_SUCCEEDED` / `SYNC_FAILED`. |
| `isPaused` | `boolean` | `true` when the engine has been paused via `pauseSync()` or the `PAUSED` event. |
| `progress` | `number` | Last `SyncEvent.progress` value, in `[0, 1]`. Resets to `0` on `SYNC_STARTED`. |
| `lastResult` | [`SyncResult`](../types.md#syncresult) `\| null` | The most recent completed flush, or `null` if none yet. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last error from `flush() / pause() / resume()`, or `null`. |

### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `flush` | `() => Promise<SyncResult \| null>` | Force a sync now. Returns the result, or `null` on error. |
| `pause` | `() => Promise<void>` | Pause the engine. |
| `resume` | `() => Promise<void>` | Resume after a pause. |

---

## Behavior

- **Initial fetch** -- on mount, the hook calls [`isSyncing`](../functions.md#issyncing) and [`getLastSyncResult`](../functions.md#getlastsyncresult).
- **Subscription** -- updates state on:
  - `PAUSED` -> `isPaused = true`
  - `RESUMED` -> `isPaused = false`
  - `SYNC_STARTED` -> `isSyncing = true`, `progress = 0`
  - `SYNC_PROGRESS` -> `progress = event.progress`
  - `SYNC_SUCCEEDED` / `SYNC_FAILED` -> `isSyncing = false`, `lastResult` re-fetched
- **Error capture** -- `flush()` returns `null` and stores the error instead of throwing.
- **Cleanup** -- unsubscribes on unmount.

:::note `lastResult.failureCount > 0` does not throw
A flush with partial failures still resolves to a `SyncResult`. Inspect `lastResult.errors` for per-item details. See the [Error handling guide](../../guides/error-handling.md).
:::

---

## Example

### Sync button with progress

```tsx
import { useSyncStatus } from '@gabriel-sisjr/react-native-sync-provider';
import { ActivityIndicator, Button, Text, View } from 'react-native';

function SyncButton() {
  const { isSyncing, progress, lastResult, flush } = useSyncStatus();

  return (
    <View>
      <Button title="Sync now" onPress={flush} disabled={isSyncing} />
      {isSyncing && (
        <View>
          <ActivityIndicator />
          <Text>{Math.round(progress * 100)}%</Text>
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

## See also

- [`flush`](../functions.md#flush), [`pauseSync`](../functions.md#pausesync), [`resumeSync`](../functions.md#resumesync), [`isSyncing`](../functions.md#issyncing).
- [`useSyncQueue`](./useSyncQueue.md) -- complementary hook for the queue itself.
- [`useOfflineQueue`](./useOfflineQueue.md) -- combined view (connection + queue + sync slice).
