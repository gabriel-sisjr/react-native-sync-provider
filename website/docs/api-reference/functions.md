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

The full list of facade functions exported from `@gabriel-sisjr/react-native-sync-provider`. Every function except [`cancelDrain`](#canceldrain) and the [utilities](#utilities) is async, returns a `Promise`, and throws a [`SyncError`](./errors.md) with a typed [`SyncErrorCode`](./errors.md#syncerrorcode) on failure. Calls made on web / SSR / before native linking throw `NATIVE_MODULE_UNAVAILABLE`. A rejection from native reaches you under one fallback code per function: see [Native rejections](./errors.md#native-rejections).

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
  drainUntilEmpty,
  cancelDrain,
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
  // Utilities
  generateId,
  isNativeModuleAvailable,
} from '@gabriel-sisjr/react-native-sync-provider';
```

---

## Queue

### `enqueue`

Persist a single HTTP request to the native queue. Returns the assigned ULID once the row hits disk. Enqueueing does not dispatch the item: a flush does, from [`flush`](#flush), [`useAutoSync`](./hooks/useAutoSync.md), or an OS background window.

#### Signature

```ts
function enqueue(item: SyncItemInput): Promise<string>;
```

#### Parameters

| Name   | Type                                        | Required | Description                                                                                                      |
| ------ | ------------------------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------- |
| `item` | [`SyncItemInput`](./types.md#synciteminput) | Yes      | The HTTP request descriptor: `method`, `url`, optional `headers`, `body`, `contentType`, `priority`, `metadata`. |

#### Returns

`Promise<string>` -- the item's ULID, generated natively for monotonic ordering and idempotency.

#### Throws

- `INVALID_URL` -- `url` is missing, empty, or not an absolute `http(s)` URL.
- `INVALID_PAYLOAD` -- `method` is invalid, or `body`, `contentType`, `priority`, `headers` or `metadata` has the wrong type.
- `INVALID_PAYLOAD` with no `validationErrors` -- native rejected the call, most often because the queue reached `SyncOptions.maxQueueSize`. See [Native rejections](./errors.md#native-rejections).
- `NATIVE_MODULE_UNAVAILABLE` -- the native module is not linked.

The code is that of the first violation, and `method` is checked first. [`validationErrors`](./errors.md#validationissue) lists every violation.

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
function enqueueBatch(items: SyncItemInput[]): Promise<string[]>;
```

#### Parameters

| Name    | Type              | Required | Description                                                             |
| ------- | ----------------- | -------- | ----------------------------------------------------------------------- |
| `items` | `SyncItemInput[]` | Yes      | The items to persist. An empty array resolves to `[]`.                  |

#### Returns

`Promise<string[]>` -- the ULIDs in the same order as the input.

#### Throws

- `INVALID_PAYLOAD` -- **one umbrella error** when any item fails validation. Unlike [`enqueue`](#enqueue) (which throws the first item's specific code), `enqueueBatch` aggregates **every** issue across **every** item into [`SyncError.validationErrors`](./errors.md#validationissue), each tagged with its `itemIndex`. No items are persisted.
- `INVALID_PAYLOAD` with no `validationErrors` -- `items` is not an array, or native rejected the call, most often because the batch would exceed `maxQueueSize`. No items are persisted. See [Native rejections](./errors.md#native-rejections).
- `NATIVE_MODULE_UNAVAILABLE`.

Array holes count as invalid items.

:::warning Behavior change in v0.2
`enqueueBatch` now throws `INVALID_PAYLOAD` with a populated `validationErrors` list instead of the first bad item's specific code (e.g. `INVALID_URL`). See [Errors → batch validation](./errors.md#batch-validation-aggregates-all-issues).
:::

#### Example

```ts
const ids = await enqueueBatch([
  { method: HttpMethod.POST, url: 'https://api.example.com/a' },
  { method: HttpMethod.POST, url: 'https://api.example.com/b' },
]);
```

---

### `removeItem`

Remove a single item by id, in any status: pending (including items waiting out a cooldown), in flight, or failed permanently. This is the way to delete one item that failed permanently: [`getPendingItems`](#getpendingitems) does not return failed items, so take its id from an `ITEM_FAILED` event.

#### Signature

```ts
function removeItem(id: string): Promise<boolean>;
```

#### Parameters

| Name | Type     | Required | Description                                          |
| ---- | -------- | -------- | ---------------------------------------------------- |
| `id` | `string` | Yes      | The ULID returned by `enqueue()` / `enqueueBatch()`. |

#### Returns

`Promise<boolean>` -- `true` if a row with that id existed and was removed, `false` if none exists: the item was already delivered, already removed or cleared, or never enqueued.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

:::warning Removal does not cancel a request
An item that a running cycle has already taken into its batch can still be sent and reach the server. Its outcome event (`ITEM_SUCCEEDED`, `ITEM_RETRYING` or `ITEM_FAILED`) is still emitted, but nothing is written back to the queue. `ITEM_REMOVED` is emitted only when a row was removed.
:::

#### Example

```ts
const ok = await removeItem('01HZX9V4MAYQ2XJ7C2K6Z1Q3D8');
if (!ok) console.warn('already delivered or unknown id');
```

---

### `clearQueue`

Remove every item, in any status: pending, in flight, or failed permanently. Does not touch sync history. A request already in flight is not cancelled.

#### Signature

```ts
function clearQueue(): Promise<void>;
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

Read the number of items waiting to be dispatched: `PENDING` items, including those waiting out a retry cooldown. Items in flight and items that failed permanently are not counted, on either platform.

The `maxQueueSize` cap is checked separately and differs: Android counts every row, including `FAILED` and in-flight ones. iOS counts `PENDING` rows only.

#### Signature

```ts
function getQueueSize(): Promise<number>;
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

Snapshot the `PENDING` items in dispatch order on both platforms: `HIGH` first, then oldest first. Cooling items are included. Items in flight and items that failed permanently are not returned.

Each item carries its [`attempts` and `nextRetryAt`](./types.md#syncitem).

#### Signature

```ts
function getPendingItems(): Promise<SyncItem[]>;
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

Run one flush cycle and resolve with the [`SyncResult`](./types.md#syncresult) describing it.

A cycle dispatches one batch of eligible items, up to `batchSize`, in priority order. Items still cooling are skipped, and the [strategy](./enums.md#syncstrategy) plays no part, so `flush()` works the same under `MANUAL`. If more items remain, flush again or use [`drainUntilEmpty`](#drainuntilempty).

#### Signature

```ts
function flush(): Promise<SyncResult>;
```

#### Returns

`Promise<SyncResult>` -- `startedAt`, `finishedAt`, `successCount`, `failureCount`, plus `succeededIds`, `failedIds` and `errors` on Android. Those three are always empty on iOS.

An item that went back to wait for a retry counts as neither a success nor a failure.

#### Throws

- `NATIVE_MODULE_UNAVAILABLE`.
- `NETWORK_ERROR` -- the cycle itself died, for example because the store failed. It need not be a network problem: every native rejection from `flush` maps to this code.

Per-item failures never throw.

:::warning `flush()` resolves successfully even with failures
A successful `Promise` resolution does **not** mean every item synced. Inspect `result.failureCount`, and listen to `ITEM_FAILED` for the per-item detail. See the [Error handling guide](../guides/error-handling.md).
:::

#### Behavior worth knowing

- **Nothing eligible.** With an empty queue, or every item cooling, the cycle resolves with a 0/0 result, emits `SYNC_SUCCEEDED`, and adds a row to the sync history.
- **Connectivity is not checked.** Calling `flush()` offline can spend retry attempts and mark valid items `FAILED`. On iOS the foreground session waits for connectivity, so the call may stay pending until the network returns instead. Check [`getConnectionStatus`](#getconnectionstatus) first, or use [`drainUntilEmpty`](#drainuntilempty), which does not flush while `DISCONNECTED`.
- **While a cycle is running.** iOS returns the last result at once. Android waits, then runs its own cycle.
- **Paused, or stored headers unreadable.** No cycle runs: nothing is dispatched, no event is emitted and no history row is written. The counts are 0/0 on both platforms and do not say why. Android returns `0` for `startedAt` and `finishedAt`, the zeroed shape that [`getLastSyncResult`](#getlastsyncresult) reports as `undefined`; `flush()` returns it as is. iOS sets both to the time of the call, so the result looks like an empty cycle's: only the missing `SYNC_STARTED` event and history row tell them apart.

#### Example

```ts
const result = await flush();
if (result.failureCount > 0) {
  console.warn('Some items failed:', result.errors);
}
```

---

### `drainUntilEmpty`

Repeatedly [`flush`](#flush) the queue until it is empty or a bound stops it. A JS-side loop over the existing bridge, useful for "force-sync before logout" flows.

`EMPTY` means `getQueueSize() === 0` and `isSyncing() === false`, so an item still in flight keeps the loop waiting.

#### Signature

```ts
function drainUntilEmpty(options?: DrainOptions): Promise<DrainResult>;
```

#### Parameters

| Name      | Type                                      | Required | Description                                                                                             |
| --------- | ----------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------- |
| `options` | [`DrainOptions`](./types.md#drainoptions) | No       | Bounds: `maxIterations` (default `32`), `timeoutMs` (default `60000`), optional `delayBetweenCyclesMs`. |

#### Returns

`Promise<`[`DrainResult`](./types.md#drainresult)`>` -- `iterations`, `itemsDispatched`, `queueRemaining`, and a `stoppedReason` of `'EMPTY' | 'MAX_ITERATIONS' | 'TIMEOUT' | 'CANCELLED'`.

#### Throws

- `DRAIN_ALREADY_ACTIVE` -- another drain is running or still settling in this process. Await it before starting another. That applies after [`cancelDrain`](#canceldrain) too: do not rely on cancelling and restarting in the same tick.
- `NATIVE_MODULE_UNAVAILABLE`.
- Any error from an underlying native call. The loop stops, releases its lock and rethrows, so no `DrainResult` is returned. Items dispatched before the failure stay dispatched: re-read [`getQueueSize`](#getqueuesize) for what remains.

#### How the loop waits

None of these waits uses up an iteration, because `maxIterations` counts real flushes:

- **A cycle is running.** The loop polls `isSyncing()`.
- **Items are cooling.** After a cycle with no progress, it sleeps until the soonest `nextRetryAt`. If that moment is at or past the deadline, it stops at once with `TIMEOUT`.
- **The device is offline.** It does not flush while `getConnectionStatus()` is `DISCONNECTED`. It waits for the network and ends in `TIMEOUT` with items intact. `UNKNOWN` and `METERED` still flush.
- **Nothing can progress.** With nothing cooling, for example while paused, it sleeps at least 250 ms so it cannot spin.

:::warning `timeoutMs` and `cancelDrain()` are hard stops
Every wait ends at the deadline or on cancel, including a `flush()` still in flight. A native cycle that is already running is not aborted: it keeps going after the drain returns, and its items may still be dispatched.
:::

#### Example

```ts
const result = await drainUntilEmpty({ maxIterations: 10, timeoutMs: 30_000 });
if (result.stoppedReason !== 'EMPTY') {
  console.warn(
    `stopped early: ${result.stoppedReason}, ${result.queueRemaining} left`
  );
}
```

---

### `cancelDrain`

Cancel the in-progress [`drainUntilEmpty`](#drainuntilempty) loop. It takes effect at once: any wait in the loop ends, including a `flush()` still in flight, and the drain resolves with `stoppedReason: 'CANCELLED'`. A native cycle already running is not aborted.

The drain settles asynchronously, so a drain started in the same tick still throws `DRAIN_ALREADY_ACTIVE`. Await the cancelled drain first.

#### Signature

```ts
function cancelDrain(): void;
```

#### Returns

`void`. When no drain is active this is a harmless no-op — safe to call unconditionally from cleanup paths (effect teardown, screen unmount). It never throws and performs no native call.

#### Example

A remount can overlap a drain that is still settling, and StrictMode does that on every mount. Chain on the previous drain instead of racing it:

```tsx
const previousDrain = useRef<Promise<unknown>>(Promise.resolve());

useEffect(() => {
  const drain = previousDrain.current.then(() => drainUntilEmpty());
  previousDrain.current = drain.catch(() => undefined);
  drain.catch(console.warn);
  return () => cancelDrain();
}, []);
```

---

### `pauseSync`

Pause the engine without dropping the queue. `enqueue` keeps accepting items, but no cycle runs: `flush()` resolves with a 0/0 result and emits no event (see [`flush`](#flush) for the timestamps each platform returns), the retry timer is cancelled, and the Android retry kick is cancelled.

The pause is persisted, so workers and BGTasks stay inert while the app is dead. It lasts until `resumeSync()` or the next launch that reaches the library, including a JS reload. A launch clears it without emitting `RESUMED`, so `isPaused: false` in a fresh session is correct. JS cannot read the pause flag, though: a hook mounted while an earlier `pauseSync()` is still in force reads `false` too, until the next `PAUSED`. On iOS, React Native can start during a background launch, and if JS reaches the library there, that counts as a launch. To keep a pause across launches, call `pauseSync()` again at startup.

#### Signature

```ts
function pauseSync(): Promise<void>;
```

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

---

### `resumeSync`

Resume a paused engine and emit `RESUMED`. Unless the strategy is `MANUAL`, the retry timer is armed for the soonest pending item: items with no cooldown left are dispatched right away, and cooling items when their cooldown ends. If the device is offline at that moment, the flush waits until connectivity returns.

During a [credential hold](../guides/background-sync.md#when-a-stored-token-expires), it waits for the hold to end before dispatching, on both platforms. The hold lives in memory, so a process restart drops it.

#### Signature

```ts
function resumeSync(): Promise<void>;
```

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

---

### `isSyncing`

Whether a flush is in flight.

#### Signature

```ts
function isSyncing(): Promise<boolean>;
```

#### Returns

`Promise<boolean>`.

---

## Config

### `configureSync`

Apply a full [`SyncOptions`](./types.md#syncoptions) configuration and persist it. Each call replaces the previous configuration entirely: it does not merge.

- **When it applies.** Android uses the new options from the next flush cycle. iOS uses them from the next item, even inside a running cycle. A request already in flight keeps the previous config.
- **Persisted.** A worker or BGTask in a fresh process honors the options, including `maxAttempts`, `batchSize` and `requestTimeoutMs`. See [Cold-start configuration](../guides/background-sync.md#cold-start-configuration).
- **`defaultHeaders`.** Encrypted at rest. Calling `configureSync` without them deletes the stored copy, which makes it the logout path. Pass them on every other call, and call `configureSync` whenever the token changes.
- **Retry timer.** It is re-armed from the current queue, so a strategy change takes effect at once.

#### Signature

```ts
function configureSync(options: SyncOptions): Promise<void>;
```

#### Parameters

| Name      | Type                                    | Required | Description                                                                                |
| --------- | --------------------------------------- | -------- | ------------------------------------------------------------------------------------------ |
| `options` | [`SyncOptions`](./types.md#syncoptions) | Yes      | Strategy, retry policy, batch size, timeout, queue cap, persistence flag, default headers. |

#### Throws

- `INVALID_PAYLOAD` -- native rejected the call. The options are not validated, and out-of-range numbers are clamped: Android treats a `maxAttempts` below `1` as `1`.
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
  defaultHeaders: { Authorization: `Bearer ${token}` },
});
```

---

### `getSyncConfig`

Read the currently active configuration.

#### Signature

```ts
function getSyncConfig(): Promise<SyncOptions>;
```

#### Returns

`Promise<SyncOptions>` -- the active config: the defaults until `configureSync` first runs, then the last options applied, restored from disk in a new process. After a `configureSync` that omitted optional fields, iOS returns them filled with their defaults, including `defaultHeaders: {}`. Android returns them as `undefined`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

:::warning Do not pass the result back without setting `defaultHeaders`
In a process where `configureSync` has not run yet, the result is the configuration an earlier process persisted, with the stored copy of the headers. While that copy cannot be read (the Android Keystore key was invalidated, the iOS device has not been unlocked since boot, or a backup was restored onto another device), the result carries no headers: `defaultHeaders` is `undefined` on Android and `{}` on iOS. That lasts until the library reads the copy again, which it retries on each flush while not paused, or until `configureSync` runs.

`configureSync` replaces the whole configuration, so passing such a result back, for example `configureSync({ ...cfg, batchSize: 5 })`, deletes the stored headers, which acts as a logout. Later requests go out without them, and a 401 or 403 fails those items permanently. See [Troubleshooting](../troubleshooting.md#symptom-items-stay-pending-and-flush-returns-00).
:::

#### Example

```ts
const cfg = await getSyncConfig();
// Take the credentials from the app's session, not from `cfg`.
await configureSync({ ...cfg, batchSize: 5, defaultHeaders: session.headers });
```

---

## History

### `getLastSyncResult`

Return the most recent [`SyncResult`](./types.md#syncresult), or `undefined` if no flush has run yet.

#### Signature

```ts
function getLastSyncResult(): Promise<SyncResult | undefined>;
```

#### Returns

`Promise<SyncResult | undefined>`. This includes 0/0 cycles, so a recent `successCount` of `0` does not mean nothing was ever sent.

:::note Sentinel translation
The native layer returns a result with `startedAt === 0 && finishedAt === 0` to mean "no run yet". The JS facade translates that sentinel into `undefined` so consumers can do a simple null check. `flush()` does not translate it.
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

Return up to `limit` recent sync results, most recent first. History is not trimmed automatically, and every cycle adds a row, including 0/0 cycles. Call [`clearSyncHistory`](#clearsynchistory) from time to time if you flush on a timer.

#### Signature

```ts
function getSyncHistory(limit?: number): Promise<SyncResult[]>;
```

#### Parameters

| Name    | Type     | Required | Default | Description                                                                                                                 |
| ------- | -------- | -------- | ------- | --------------------------------------------------------------------------------------------------------------------------- |
| `limit` | `number` | No       | `0`     | Max entries to return. `0` (the JS-side default when the argument is omitted) means no limit.                               |

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
function clearSyncHistory(): Promise<void>;
```

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

---

## Connection

### `getConnectionStatus`

One-shot read of the current network state.

#### Signature

```ts
function getConnectionStatus(): Promise<ConnectionState>;
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

Register the platform background scheduler, so the queue can be flushed while the app is not running. The OS decides when a window runs. iOS does not launch an app the user force-quit, and Android stops its work after a Force Stop, until the user opens the app again.

The options persist across relaunches. With `foregroundOnly: true` nothing is scheduled, and anything already scheduled is cancelled.

#### Signature

```ts
function enableBackgroundSync(options: BackgroundSyncOptions): Promise<void>;
```

#### Parameters

| Name      | Type                                                        | Required | Description                                                                        |
| --------- | ----------------------------------------------------------- | -------- | ---------------------------------------------------------------------------------- |
| `options` | [`BackgroundSyncOptions`](./types.md#backgroundsyncoptions) | Yes      | Minimum interval, network / charging / idle constraints, `foregroundOnly`.         |

#### Throws

- `BACKGROUND_TASK_REGISTRATION_FAILED` -- iOS: the OS refused the request, because `com.gabriel-sisjr.syncprovider.background` is not in `BGTaskSchedulerPermittedIdentifiers`, or because you are on a simulator, where `BGTaskScheduler` does not accept requests. Android: WorkManager rejected the request.
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

Unregister the background scheduler and cancel any pending Android retry kick. Work already running is allowed to finish. A later window that was already handed to the OS does nothing.

#### Signature

```ts
function disableBackgroundSync(): Promise<void>;
```

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

---

### `isBackgroundSyncEnabled`

Whether background sync is enabled. This reports intent, not OS state: it is `true` from `enableBackgroundSync` until `disableBackgroundSync`, even with `foregroundOnly: true`, when no OS work is scheduled. It survives relaunches.

#### Signature

```ts
function isBackgroundSyncEnabled(): Promise<boolean>;
```

#### Returns

`Promise<boolean>`.

---

## Utilities

### `generateId`

Generate a UUID v4 string with `Math.random()`. Use it for an `Idempotency-Key` header, built before `enqueue`. See the [Idempotency guide](../guides/idempotency.md).

It is not cryptographically secure, so do not use it for anything an adversary should not be able to guess.

#### Signature

```ts
function generateId(): string;
```

#### Example

```ts
await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/orders',
  headers: { 'Idempotency-Key': generateId() },
});
```

---

### `isNativeModuleAvailable`

Whether the native module can be resolved. It returns `false` on web and when the app was not rebuilt after installing the library. It never throws.

#### Signature

```ts
function isNativeModuleAvailable(): boolean;
```

---

## See also

- [Listener helpers](./listeners.md) -- `addSyncEventListener`, `removeSyncEventListener`.
- [React hooks](./hooks/useSyncQueue.md) -- reactive wrappers around these functions.
- [Errors](./errors.md) -- `SyncError` and `SyncErrorCode`.
