---
sidebar_position: 1
title: Offline Queue
description: How the native persistent queue works in react-native-sync-provider — enqueue patterns, batch operations, persistence guarantees, and observation.
keywords:
  - offline-queue
  - persistence
  - core-data
  - room
  - enqueue
  - batch
  - sync-queue
---

# Offline Queue

The offline queue is the heart of `react-native-sync-provider`. Every outbound HTTP request is persisted natively before any network attempt is made. This guide explains the persistence model, enqueue patterns, and how to observe the queue from React.

## Persistence Guarantees

| Platform | Storage | Survives |
|---|---|---|
| iOS | Core Data (SQLite-backed) | App backgrounded, force-quit, device reboot |
| Android | Room (SQLite, KSP-generated DAOs) | App backgrounded, force-quit, device reboot |

Items are written to disk **before** `enqueue()` resolves -- the function never returns until durability is confirmed. There is no in-memory-only mode.

The queue is not encrypted at rest. If your items carry secrets, set `SyncOptions.persistQueue = false` (in-memory only, lost on app death) or encrypt the request bodies on the JS side before enqueueing.

## Enqueue Patterns

### Single item

```ts
import { enqueue, HttpMethod } from '@gabriel-sisjr/react-native-sync-provider';

const id = await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  contentType: 'application/json',
  body: JSON.stringify({ event: 'page_view' }),
});
```

The returned `id` is a ULID assigned by the native layer. Store it if you need to remove the item later or correlate it with server-side records.

### Atomic batch

```ts
import { enqueueBatch, HttpMethod } from '@gabriel-sisjr/react-native-sync-provider';

const ids = await enqueueBatch([
  { method: HttpMethod.POST, url: 'https://api.example.com/a' },
  { method: HttpMethod.POST, url: 'https://api.example.com/b' },
  { method: HttpMethod.POST, url: 'https://api.example.com/c' },
]);
```

`enqueueBatch` is **atomic**: either every item is persisted (and `ids` returns all ULIDs), or the call throws and no items are added. Use this when ordering matters (e.g., a multi-step server-side transaction) or when you need all-or-nothing semantics.

### With metadata

Attach opaque tags to an item -- they are persisted alongside the item and echoed back in `SyncEvent.metadata` for item-scoped events:

```ts
await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  body: JSON.stringify({ event: 'tap' }),
  metadata: {
    screen: 'Home',
    sessionId: '7d2f...',
  },
});
```

Metadata values must be strings -- encode complex values on the JS side.

## Observing the Queue

### `useSyncQueue`

The reactive hook owns the canonical view of the queue:

```tsx
import { useSyncQueue } from '@gabriel-sisjr/react-native-sync-provider';

function PendingBadge() {
  const { size, items, isLoading, refresh, removeItem, clearQueue } = useSyncQueue();

  if (isLoading) return <Text>Loading...</Text>;

  return (
    <View>
      <Text>Pending: {size}</Text>
      <Button title="Refresh" onPress={refresh} />
      {size > 0 && <Button title="Clear all" onPress={clearQueue} />}
    </View>
  );
}
```

The hook re-fetches `items` and `size` whenever an `ITEM_ENQUEUED`, `ITEM_REMOVED`, `ITEM_SUCCEEDED`, `QUEUE_CLEARED`, or `SYNC_SUCCEEDED` event fires.

### Direct facade calls

For one-off reads, use the facade:

```ts
import { getQueueSize, getPendingItems } from '@gabriel-sisjr/react-native-sync-provider';

const count = await getQueueSize();
const items = await getPendingItems();
```

`getPendingItems()` returns items in dispatch order (priority desc, `createdAt` asc).

## Queue Size Limits

Apps that produce events at high rates should cap the queue to avoid unbounded growth:

```ts
import { configureSync, SyncStrategy, BackoffStrategy } from '@gabriel-sisjr/react-native-sync-provider';

await configureSync({
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy: {
    maxAttempts: 5,
    backoff: BackoffStrategy.EXPONENTIAL,
    baseDelayMs: 1000,
    maxDelayMs: 60_000,
    jitter: true,
    retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
  },
  maxQueueSize: 1000,
});
```

When the cap is reached, subsequent `enqueue()` calls throw `SyncErrorCode.QUEUE_FULL`. Catch and decide: drop the new item, drain via `flush()`, or surface a retry-later UX.

## Manual Removal

Remove a single item by id (returns `false` if it has already drained):

```ts
import { removeItem } from '@gabriel-sisjr/react-native-sync-provider';

const removed = await removeItem(id);
if (!removed) console.warn('item already drained or unknown');
```

Wipe everything:

```ts
import { clearQueue } from '@gabriel-sisjr/react-native-sync-provider';

await clearQueue();
```

`clearQueue()` does not touch the persisted flush history -- use [`clearSyncHistory()`](../api-reference/functions.md#clearsynchistory) for that.

## Manual Flush vs Automatic

- **`SyncStrategy.AUTOMATIC`** (default): the engine flushes the moment the queue is non-empty and connectivity allows. You rarely need to call `flush()` directly.
- **`SyncStrategy.MANUAL`**: nothing flushes until you call `flush()`. Useful for explicit upload screens.
- **`SyncStrategy.OPPORTUNISTIC`**: flushes only on opportunistic windows (foreground resume, charging, unmetered network).

See the [`useSyncStatus` hook](../api-reference/hooks/useSyncStatus.md) for live progress reporting.

## Next Steps

- [Background Sync](./background-sync.md) -- Drain the queue while the app is killed.
- [Retry Policy](./retry-policy.md) -- How failed items are retried.
- [Idempotency](./idempotency.md) -- Avoid double-sending after a crash.
