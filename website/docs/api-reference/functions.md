---
sidebar_position: 1
title: Functions
description: API reference for every public function in @gabriel-sisjr/react-native-sync-provider — queue, sync, configuration, history, connection, and background sync APIs.
keywords:
  - react-native
  - sync-provider
  - api
  - enqueue
  - flush
  - configureSync
  - background-sync
---

# Functions

The full list of facade functions exported from `@gabriel-sisjr/react-native-sync-provider`. Every function is async, returns a `Promise`, and throws a [`SyncError`](./errors.md) with a typed [`SyncErrorCode`](./errors.md#syncerrorcode) on failure. Calls made on web / SSR / before native linking throw `NATIVE_MODULE_UNAVAILABLE`.

```ts
import {
  // Queue
  enqueue,
  enqueueBatch,
  removeItem,
  clearQueue,
  getQueueSize,
  getPendingItems,
  // Sync
  flush,
  pauseSync,
  resumeSync,
  isSyncing,
  // Config
  configureSync,
  getSyncConfig,
  // History
  getLastSyncResult,
  getSyncHistory,
  clearSyncHistory,
  // Connection
  getConnectionStatus,
  // Background
  enableBackgroundSync,
  disableBackgroundSync,
  isBackgroundSyncEnabled,
} from '@gabriel-sisjr/react-native-sync-provider';
```

---

## Queue

### `enqueue`

Persist a single HTTP request to the native queue. Returns the assigned ULID once the row hits disk.

#### Signature

```ts
function enqueue(item: SyncItemInput): Promise<string>
```

#### Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `item` | [`SyncItemInput`](./types.md#synciteminput) | Yes | The HTTP request descriptor: `method`, `url`, optional `headers`, `body`, `contentType`, `priority`, `metadata`. |

#### Returns

`Promise<string>` -- the item's ULID, generated natively for monotonic ordering and idempotency.

#### Throws

- `INVALID_PAYLOAD` -- `method` or `url` is missing.
- `INVALID_URL` -- `url` does not parse as an absolute URL.
- `QUEUE_FULL` -- queue size has reached `SyncOptions.maxQueueSize`.
- `DUPLICATE_ITEM` -- the auto-assigned id collided with an existing row (vanishingly rare).
- `NATIVE_MODULE_UNAVAILABLE` -- the native module is not linked.

#### Example

```ts
const id = await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  contentType: 'application/json',
  body: JSON.stringify({ event: 'page_view' }),
  priority: SyncPriority.NORMAL,
  metadata: { screen: 'Home' },
});
```

---

### `enqueueBatch`

Atomically persist multiple items in a single transaction. Either every item is persisted or the call throws and none are.

#### Signature

```ts
function enqueueBatch(items: SyncItemInput[]): Promise<string[]>
```

#### Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `items` | `SyncItemInput[]` | Yes | The items to persist. Empty arrays resolve to `[]` with no native call. |

#### Returns

`Promise<string[]>` -- the ULIDs in the same order as the input.

#### Throws

Same codes as [`enqueue`](#enqueue), plus:

- `QUEUE_FULL` -- adding the batch would exceed `maxQueueSize`. No items are persisted.

#### Example

```ts
const ids = await enqueueBatch([
  { method: HttpMethod.POST, url: 'https://api.example.com/a' },
  { method: HttpMethod.POST, url: 'https://api.example.com/b' },
]);
```

---

### `removeItem`

Remove a single pending item by id.

#### Signature

```ts
function removeItem(id: string): Promise<boolean>
```

#### Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `id` | `string` | Yes | The ULID returned by `enqueue()` / `enqueueBatch()`. |

#### Returns

`Promise<boolean>` -- `true` if the row existed and was removed, `false` if the id is unknown or the item already drained.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Example

```ts
const ok = await removeItem('01HZX9V4MAYQ2XJ7C2K6Z1Q3D8');
if (!ok) console.warn('item already drained');
```

---

### `clearQueue`

Remove every pending item. Does not touch sync history.

#### Signature

```ts
function clearQueue(): Promise<void>
```

#### Returns

`Promise<void>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Example

```ts
await clearQueue();
```

---

### `getQueueSize`

Read the current pending count.

#### Signature

```ts
function getQueueSize(): Promise<number>
```

#### Returns

`Promise<number>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Example

```ts
const n = await getQueueSize();
console.log(`${n} items pending`);
```

---

### `getPendingItems`

Snapshot the pending queue, sorted by priority desc then `createdAt` asc.

#### Signature

```ts
function getPendingItems(): Promise<SyncItem[]>
```

#### Returns

`Promise<`[`SyncItem`](./types.md#syncitem)`[]>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Example

```ts
const items = await getPendingItems();
items.forEach((item) => console.log(item.id, item.url));
```

---

## Sync

### `flush`

Force an immediate sync attempt. Resolves with the [`SyncResult`](./types.md#syncresult) describing the run.

#### Signature

```ts
function flush(): Promise<SyncResult>
```

#### Returns

`Promise<SyncResult>` -- `successCount`, `failureCount`, `succeededIds`, `failedIds`, `errors` map.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`. Per-item failures are reported via `result.errors`, not thrown.

:::warning `flush()` resolves successfully even with failures
A successful `Promise` resolution does **not** mean every item synced. Always inspect `result.failureCount` and `result.errors`. See the [Error handling guide](../guides/error-handling.md).
:::

#### Example

```ts
const result = await flush();
if (result.failureCount > 0) {
  console.warn('Some items failed:', result.errors);
}
```

---

### `pauseSync`

Pause the engine without dropping the queue. Items persist and resume when `resumeSync()` is called.

#### Signature

```ts
function pauseSync(): Promise<void>
```

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

---

### `resumeSync`

Resume a previously paused engine.

#### Signature

```ts
function resumeSync(): Promise<void>
```

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

---

### `isSyncing`

Whether a flush is in flight.

#### Signature

```ts
function isSyncing(): Promise<boolean>
```

#### Returns

`Promise<boolean>`.

---

## Config

### `configureSync`

Apply a full [`SyncOptions`](./types.md#syncoptions) configuration. Replaces any prior call.

#### Signature

```ts
function configureSync(options: SyncOptions): Promise<void>
```

#### Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `options` | [`SyncOptions`](./types.md#syncoptions) | Yes | Strategy, retry policy, batch size, timeout, queue cap, persistence flag, default headers. |

#### Throws

- `INVALID_PAYLOAD` -- malformed `retryPolicy` (e.g. `maxAttempts < 1`).
- `NATIVE_MODULE_UNAVAILABLE`.

#### Example

```ts
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
  batchSize: 10,
  defaultHeaders: { 'X-Client': 'mobile' },
});
```

---

### `getSyncConfig`

Read the currently active configuration.

#### Signature

```ts
function getSyncConfig(): Promise<SyncOptions>
```

#### Returns

`Promise<SyncOptions>` -- the merged config (defaults + last `configureSync()` call).

---

## History

### `getLastSyncResult`

Return the most recent [`SyncResult`](./types.md#syncresult), or `undefined` if no flush has run yet.

#### Signature

```ts
function getLastSyncResult(): Promise<SyncResult | undefined>
```

#### Returns

`Promise<SyncResult | undefined>`.

:::note Sentinel translation
The native layer returns a result with `startedAt === 0 && finishedAt === 0` to mean "no run yet". The JS facade translates that sentinel into `undefined` so consumers can do a simple null check.
:::

#### Example

```ts
const last = await getLastSyncResult();
if (!last) {
  console.log('No sync has run yet');
} else {
  console.log(`Last run synced ${last.successCount} items`);
}
```

---

### `getSyncHistory`

Return up to `limit` recent sync results, most recent first.

#### Signature

```ts
function getSyncHistory(limit?: number): Promise<SyncResult[]>
```

#### Parameters

| Name | Type | Required | Default | Description |
|------|------|----------|---------|-------------|
| `limit` | `number` | No | `0` | Max entries to return. `0` (the JS-side default when the argument is omitted) means "all history the native layer retains". |

#### Returns

`Promise<SyncResult[]>`.

#### Example

```ts
const recent = await getSyncHistory(20);
const totalSucceeded = recent.reduce((sum, r) => sum + r.successCount, 0);
```

---

### `clearSyncHistory`

Drop all stored history.

#### Signature

```ts
function clearSyncHistory(): Promise<void>
```

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

---

## Connection

### `getConnectionStatus`

One-shot read of the current network state.

#### Signature

```ts
function getConnectionStatus(): Promise<ConnectionState>
```

#### Returns

`Promise<`[`ConnectionState`](./types.md#connectionstate)`>` -- `status`, `type`, optional `isInternetReachable`, `isExpensive`.

#### Example

```ts
const conn = await getConnectionStatus();
if (conn.status === ConnectionStatus.CONNECTED) {
  await flush();
}
```

For reactive updates, prefer the [`useConnection`](./hooks/useConnection.md) hook.

---

## Background Sync

### `enableBackgroundSync`

Register the platform background scheduler so the queue keeps draining when the app is killed.

#### Signature

```ts
function enableBackgroundSync(options: BackgroundSyncOptions): Promise<void>
```

#### Parameters

| Name | Type | Required | Description |
|------|------|----------|-------------|
| `options` | [`BackgroundSyncOptions`](./types.md#backgroundsyncoptions) | Yes | Minimum interval, network / charging / idle constraints, optional task identifier. |

#### Throws

- `BACKGROUND_TASK_REGISTRATION_FAILED` -- iOS: the `BGTaskSchedulerPermittedIdentifiers` entry is missing or the Background Modes capability is disabled. Android: WorkManager rejected the request.
- `NATIVE_MODULE_UNAVAILABLE`.

#### Example

```ts
await enableBackgroundSync({
  minimumIntervalMs: 15 * 60 * 1000, // 15 min (Android floor)
  requiresUnmeteredNetwork: false,
});
```

See the [Background sync guide](../guides/background-sync.md) for full setup.

---

### `disableBackgroundSync`

Unregister the background scheduler. Pending in-flight work is allowed to finish.

#### Signature

```ts
function disableBackgroundSync(): Promise<void>
```

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

---

### `isBackgroundSyncEnabled`

Whether background sync is currently registered.

#### Signature

```ts
function isBackgroundSyncEnabled(): Promise<boolean>
```

#### Returns

`Promise<boolean>`.

---

## See also

- [Listener helpers](./listeners.md) -- `addSyncEventListener`, `removeSyncEventListener`.
- [React hooks](./hooks/useSyncQueue.md) -- reactive wrappers around these functions.
- [Errors](./errors.md) -- `SyncError` and `SyncErrorCode`.
