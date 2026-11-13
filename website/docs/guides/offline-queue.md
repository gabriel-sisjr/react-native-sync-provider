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

This guide explains the persistence model, enqueue patterns, and how to observe the queue from React.

## Overview

The offline queue is the heart of `react-native-sync-provider`. Every outbound HTTP request is persisted natively before any network attempt is made.

## Persistence Guarantees

| Platform | Storage | Survives |
|---|---|---|
| iOS | Core Data (SQLite-backed) | App backgrounded, force-quit, device reboot |
| Android | Room (SQLite, KSP-generated DAOs) | App backgrounded, force-quit, device reboot |

Items are written to disk **before** `enqueue()` resolves -- the function never returns until durability is confirmed. There is no in-memory-only mode.

The library does not encrypt the queue: per-item `headers` and `body` are stored in plain text, and on iOS the store has only the system's default file protection. Put credentials in `SyncOptions.defaultHeaders`, which are [encrypted at rest](./background-sync.md#cold-start-configuration), and encrypt sensitive bodies on the JS side before enqueueing. `SyncOptions.persistQueue` is stored but not used yet, so it cannot turn persistence off.

An item whose request was in flight when the process died is not lost either: see [Crash Recovery](./crash-recovery.md).

## Enqueue Patterns

### Single Item

```ts
import { enqueue, HttpMethod } from '@gabriel-sisjr/react-native-sync-provider';

const id = await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  contentType: 'application/json',
  body: JSON.stringify({ event: 'page_view' }),
});
```

The returned `id` is a ULID assigned by the native layer. Store it if you need to remove the item later or match it against sync events. It is not sent with the request: see [Idempotency](./idempotency.md).

Enqueueing does not dispatch the item. See [When Items Are Dispatched](#when-items-are-dispatched).

### Atomic Batch

```ts
import { enqueueBatch, HttpMethod } from '@gabriel-sisjr/react-native-sync-provider';

const ids = await enqueueBatch([
  { method: HttpMethod.POST, url: 'https://api.example.com/a' },
  { method: HttpMethod.POST, url: 'https://api.example.com/b' },
  { method: HttpMethod.POST, url: 'https://api.example.com/c' },
]);
```

`enqueueBatch` is **atomic**: either every item is persisted (and `ids` returns all ULIDs), or the call throws and no items are added. It is atomic for persistence, not for delivery: each item is dispatched and retried on its own, and Android sends a batch concurrently, so do not rely on the arrival order.

### With Metadata

Attach opaque tags to an item. They are persisted with it and returned by `getPendingItems()`. They are not sent with the request and not echoed on sync events:

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

The hook re-fetches `items` and `size` whenever an `ITEM_ENQUEUED`, `ITEM_REMOVED`, `ITEM_SUCCEEDED`, `ITEM_FAILED`, `ITEM_DEAD_LETTERED`, `QUEUE_CLEARED`, `SYNC_SUCCEEDED` or `SYNC_FAILED` event fires.

### Direct Facade Calls

For one-off reads, use the facade:

```ts
import { getQueueSize, getPendingItems } from '@gabriel-sisjr/react-native-sync-provider';

const count = await getQueueSize();
const items = await getPendingItems();
```

`getQueueSize()` and `getPendingItems()` cover `PENDING` items only, on both platforms. Items waiting out a retry cooldown are included. Items in flight and dead letters are not, so `0` does not mean the engine is idle: check `isSyncing()` too. Read dead letters with [`listDeadLetter`](../api-reference/functions.md#listdeadletter).

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

The default cap is `10000`. Any value below `1` is treated as `1`, so the cap cannot be removed.

When the cap is reached, subsequent `enqueue()` calls reject. The native layer raises `QUEUE_FULL`, but it [reaches JS as `INVALID_PAYLOAD` with no `validationErrors`](../api-reference/errors.md#native-rejections) for now. Catch that and decide: drop the new item, flush, or surface a retry-later UX.

> **Note — What the cap counts:** Android counts pending and in-flight items toward the cap, and in-flight items are not in `getQueueSize()`. iOS counts `PENDING` items only. Dead letters count on neither platform: the dead-letter queue is unbounded, so discard what you will not replay. See [Permanent Failures](./retry-policy.md#permanent-failures).

## Manual Removal

Remove a single item by id, or the dead letter with that id (returns `false` if neither exists, for example because it has already drained):

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

`clearQueue()` removes every item and every dead letter, with one `QUEUE_CLEARED`. It does not touch the persisted flush history -- use [`clearSyncHistory()`](../api-reference/functions.md#clearsynchistory) for that.

## When Items Are Dispatched

Enqueueing never dispatches an item, under any strategy. A flush does, and something has to start one:

- **[`useAutoSync`](../api-reference/hooks/useAutoSync.md)**: flushes on an interval and on reconnect while the app runs. This is the usual choice.
- **[`flush()`](../api-reference/functions.md#flush)** and **[`drainUntilEmpty()`](../api-reference/functions.md#drainuntilempty)**: flush when you ask.
- **[Background sync](./background-sync.md)**: OS windows while the app is not running.

The [strategy](../api-reference/enums.md#syncstrategy) only decides what the engine schedules on its own:

- **`SyncStrategy.AUTOMATIC`** (default): the engine retries by itself. A failed item is dispatched again when its cooldown ends, and pending items are dispatched on `resumeSync()`.
- **`SyncStrategy.MANUAL`**: the engine schedules nothing by itself. Useful for explicit upload screens. Background windows still run if you called `enableBackgroundSync`, and `useAutoSync` keeps flushing while `enabled` is `true`, because it does not read the strategy.
- **`SyncStrategy.OPPORTUNISTIC`**: reserved. It currently behaves like `AUTOMATIC`.

See the [`useSyncStatus` hook](../api-reference/hooks/useSyncStatus.md) for live progress reporting.

## Best Practices

1. **Start a flush.** Enqueueing stores an item and never sends it. Mount `useAutoSync`, call `flush()`, or enable background sync. See [When Items Are Dispatched](#when-items-are-dispatched).

2. **Send an `Idempotency-Key`.** Delivery is at least once, so generate a key before `enqueue` and send it in `headers`. See the [Idempotency guide](./idempotency.md).

3. **Keep credentials out of items.** Per-item `headers` and `body` are stored in plain text. Put credentials in `defaultHeaders`, which are encrypted at rest.

4. **Use `enqueueBatch` for items that belong together.** Either every item is persisted or none is.

5. **Cap the queue for high-rate producers.** Set `maxQueueSize`, and handle the rejection a full queue returns. See [Queue Size Limits](#queue-size-limits).

6. **Drain the dead-letter queue.** It is unbounded and nothing prunes it, so replay or discard what lands there. See [Permanent Failures](./retry-policy.md#permanent-failures).

## Next Steps

- [Background Sync](./background-sync.md) -- Flush the queue while the app is not running
- [Retry Policy](./retry-policy.md) -- How failed items are retried
- [Idempotency](./idempotency.md) -- Avoid double-sending after a crash
- [Crash Recovery](./crash-recovery.md) -- What happens to an item in flight when the process dies
- [Battery Optimization](./battery-optimization.md) -- What a flush costs, and how batch size and triggers change it
