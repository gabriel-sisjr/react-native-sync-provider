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

Reactive view of the sync engine. Mirrors `PAUSED`, `RESUMED`, `SYNC_STARTED`, `SYNC_PROGRESS`, `SYNC_SUCCEEDED`, and `SYNC_FAILED` events. Hydrates on mount with the last result via [`getLastSyncResult`](../functions.md#getlastsyncresult). State that comes from events starts at its default, so a cycle or a pause already in force at mount is not reflected.

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
| `isPaused` | `boolean` | `true` after a `PAUSED` event, until `RESUMED`. A pause cleared by a launch emits no event, and hooks start with `false`. |
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

## Behavior

- **Initial fetch** -- on mount, the hook calls [`getLastSyncResult`](../functions.md#getlastsyncresult). It does not read `isSyncing()`: [`useSyncSnapshot`](./useSyncSnapshot.md) does.
- **Subscription** -- updates state on:
  - `PAUSED` -> `isPaused = true`
  - `RESUMED` -> `isPaused = false`
  - `SYNC_STARTED` -> `isSyncing = true`, `progress = 0`
  - `SYNC_PROGRESS` -> `progress = event.progress`
  - `SYNC_SUCCEEDED` / `SYNC_FAILED` -> `isSyncing = false`, `progress = null`, `lastResult` re-fetched
- **Errors** -- `flush()`, `pause()` and `resume()` reject on failure, so catch them yourself.
- **Cleanup** -- unsubscribes on unmount.

:::note `lastResult.failureCount > 0` does not throw
A flush with partial failures still resolves to a `SyncResult`. `lastResult.errors` is always empty on iOS, so use `ITEM_FAILED` events for per-item details. See the [Error handling guide](../../guides/error-handling.md).
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

## See also

- [`flush`](../functions.md#flush), [`pauseSync`](../functions.md#pausesync), [`resumeSync`](../functions.md#resumesync), [`isSyncing`](../functions.md#issyncing).
- [`useSyncQueue`](./useSyncQueue.md) -- complementary hook for the queue itself.
- [`useOfflineQueue`](./useOfflineQueue.md) -- combined view (connection + queue + sync slice).
