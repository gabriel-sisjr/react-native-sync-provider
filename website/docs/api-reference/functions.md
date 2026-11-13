---
sidebar_position: 1
title: Functions
description: API reference for every public function in @gabriel-sisjr/react-native-sync-provider — queue, dead letters, sync, configuration, history, connection, and background sync APIs.
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

The full list of facade functions exported from `@gabriel-sisjr/react-native-sync-provider`. Every function except [`cancelDrain`](#canceldrain) and the [utilities](#utilities) is async, returns a `Promise`, and throws a [`SyncError`](./errors.md) with a typed [`SyncErrorCode`](./errors.md#syncerrorcode) on failure. Calls made on web / SSR / before native linking throw `NATIVE_MODULE_UNAVAILABLE`. A rejection from native reaches you under one fallback code per function: see [Native Rejections](./errors.md#native-rejections).

Every function is a named export; the package has no default export.

```ts
import {
  // Queue
  enqueue,
  enqueueBatch,
  removeItem,
  clearQueue,
  getQueueSize,
  getPendingItems,
  // Dead letters
  listDeadLetter,
  replayDeadLetter,
  discardDeadLetter,
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

**Platform:** Android and iOS

#### Signature

```ts
function enqueue(item: SyncItemInput): Promise<string>;
```

#### Parameters

| Name   | Type                                        | Required | Description                                                                                                      |
| ------ | ------------------------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------- |
| `item` | [`SyncItemInput`](./types.md#synciteminput) | Yes      | The HTTP request descriptor: `method`, `url`, optional `headers`, `body`, `contentType`, `priority`, `metadata`. |

#### Return Value

`Promise<string>` -- the item's ULID, generated natively for monotonic ordering and idempotency.

#### Throws

- `INVALID_URL` -- `url` is missing, empty, or not an absolute `http(s)` URL.
- `INVALID_PAYLOAD` -- `method` is invalid, or `body`, `contentType`, `priority`, `headers` or `metadata` has the wrong type.
- `INVALID_PAYLOAD` with no `validationErrors` -- native rejected the call, most often because the queue reached `SyncOptions.maxQueueSize`. See [Native Rejections](./errors.md#native-rejections).
- `NATIVE_MODULE_UNAVAILABLE` -- the native module is not linked.

The code is that of the first violation, and `method` is checked first. [`validationErrors`](./errors.md#validationissue) lists every violation.

#### Usage

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

**Platform:** Android and iOS

#### Signature

```ts
function enqueueBatch(items: SyncItemInput[]): Promise<string[]>;
```

#### Parameters

| Name    | Type              | Required | Description                                                             |
| ------- | ----------------- | -------- | ----------------------------------------------------------------------- |
| `items` | `SyncItemInput[]` | Yes      | The items to persist. An empty array resolves to `[]`.                  |

#### Return Value

`Promise<string[]>` -- the ULIDs in the same order as the input.

#### Throws

- `INVALID_PAYLOAD` -- **one umbrella error** when any item fails validation. Unlike [`enqueue`](#enqueue) (which throws the first item's specific code), `enqueueBatch` aggregates **every** issue across **every** item into [`SyncError.validationErrors`](./errors.md#validationissue), each tagged with its `itemIndex`. No items are persisted.
- `INVALID_PAYLOAD` with no `validationErrors` -- `items` is not an array, or native rejected the call, most often because the batch would exceed `maxQueueSize`. No items are persisted. See [Native Rejections](./errors.md#native-rejections).
- `NATIVE_MODULE_UNAVAILABLE`.

Array holes count as invalid items.

> **Warning — Behavior change in v0.2:** `enqueueBatch` now throws `INVALID_PAYLOAD` with a populated `validationErrors` list instead of the first bad item's specific code (e.g. `INVALID_URL`). See [Errors → batch validation](./errors.md#batch-validation-aggregates-all-issues).

#### Usage

```ts
const ids = await enqueueBatch([
  { method: HttpMethod.POST, url: 'https://api.example.com/a' },
  { method: HttpMethod.POST, url: 'https://api.example.com/b' },
]);
```

---

### `removeItem`

Remove a single item by id, in any status: pending (including items waiting out a cooldown) or in flight. When no queued item has that id, the [dead letter](#dead-letters) with that id is removed instead, so this also deletes an item that failed permanently. Take its id from [`listDeadLetter`](#listdeadletter) or from its `ITEM_FAILED` / `ITEM_DEAD_LETTERED` event.

**Platform:** Android and iOS

#### Signature

```ts
function removeItem(id: string): Promise<boolean>;
```

#### Parameters

| Name | Type     | Required | Description                                          |
| ---- | -------- | -------- | ---------------------------------------------------- |
| `id` | `string` | Yes      | The ULID returned by `enqueue()` / `enqueueBatch()`. |

#### Return Value

`Promise<boolean>` -- `true` if a queued item or a dead letter with that id existed and was removed, `false` if neither exists: the item was already delivered, already removed or cleared, or never enqueued. `ITEM_REMOVED` is emitted only when a row was removed.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### What a Purge Stops {#what-a-purge-stops}

These rules hold for `removeItem` and for [`clearQueue`](#clearqueue), on both platforms. A purge does not wait for a running cycle: each item is checked again just before its own request starts.

- **Before its request starts.** An item removed or cleared before its own request starts is never sent, even when its cycle already picked it. No `ITEM_SUCCEEDED`, `ITEM_RETRYING`, `ITEM_FAILED` or `ITEM_DEAD_LETTERED` is emitted for it, and it counts in neither `successCount` nor `failureCount`.
- **While its request is in flight.** The purge does not cancel the request. It can still reach the server, and its outcome event is emitted, but nothing is written back: the item is not queued again, even after an `ITEM_RETRYING`, and it is never dead-lettered, so no `ITEM_DEAD_LETTERED` follows its `ITEM_FAILED`.
- **Spent retry budget.** An item whose `attempts` already reached `maxAttempts`, for example after `configureSync` lowered it, is dead-lettered without a request. Purged before that move, it gets no event.
- **No way back.** A purged item never returns, as a dead letter or as a queue row from a replay. Moves to the dead-letter queue, replays, discards, removals and clears run one at a time.

#### Usage

```ts
const ok = await removeItem('01HZX9V4MAYQ2XJ7C2K6Z1Q3D8');
if (!ok) console.warn('already delivered or unknown id');
```

---

### `clearQueue`

Remove every item, in any status (pending or in flight), and every [dead letter](#dead-letters). Emits one `QUEUE_CLEARED`. Does not touch sync history. A request already in flight is not cancelled.

Dead letters keep their request headers and body, so this is the call that purges every stored request, for example on logout. It does not wait for a running cycle, and it does not need to: an item whose request has not started is not sent, and one whose request is in flight is never written back, as a queue row or as a dead letter. See [What a Purge Stops](#what-a-purge-stops).

**Platform:** Android and iOS

#### Signature

```ts
function clearQueue(): Promise<void>;
```

#### Return Value

`Promise<void>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
await clearQueue();
```

---

### `getQueueSize`

Read the number of items waiting to be dispatched: `PENDING` items, including those waiting out a retry cooldown. Items in flight and dead letters are not counted, on either platform.

The `maxQueueSize` cap is checked separately and differs: Android counts pending and in-flight items, iOS `PENDING` items only. Dead letters count on neither.

**Platform:** Android and iOS

#### Signature

```ts
function getQueueSize(): Promise<number>;
```

#### Return Value

`Promise<number>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
const n = await getQueueSize();
console.log(`${n} items pending`);
```

---

### `getPendingItems`

Snapshot the `PENDING` items in dispatch order on both platforms: `HIGH` first, then oldest first. Cooling items are included. Items in flight and dead letters are not returned: read dead letters with [`listDeadLetter`](#listdeadletter).

Each item carries its [`attempts` and `nextRetryAt`](./types.md#syncitem).

**Platform:** Android and iOS

#### Signature

```ts
function getPendingItems(): Promise<SyncItem[]>;
```

#### Return Value

`Promise<`[`SyncItem`](./types.md#syncitem)`[]>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
const items = await getPendingItems();
items.forEach((item) => console.log(item.id, item.url));
```

---

## Dead Letters

An item that fails permanently is not dropped. In one transaction it leaves the queue and enters the dead-letter queue, then `ITEM_FAILED` reports the real code and `ITEM_DEAD_LETTERED` follows, unless the item was [removed or cleared while its request was in flight](#what-a-purge-stops). That covers attempts exhausted, a response that is not retried, and a 401 or 403 under a configuration set in the current process. Items that had failed permanently before the upgrade to v0.3 are moved there too. See [`SyncDeadLetterItem`](./types.md#syncdeadletteritem) for the fields.

> **Warning — Nothing prunes the dead-letter queue:** It is unbounded and counts toward no capacity cap. Each dead letter keeps its request's headers and body until it is replayed, discarded, removed with [`removeItem`](#removeitem) or cleared with [`clearQueue`](#clearqueue). Discard what you do not replay.

### `listDeadLetter`

Read every dead letter, with the original request and its failure bookkeeping.

**Platform:** Android and iOS

#### Signature

```ts
function listDeadLetter(): Promise<SyncDeadLetterItem[]>;
```

#### Return Value

`Promise<`[`SyncDeadLetterItem`](./types.md#syncdeadletteritem)`[]>` -- every dead letter, oldest first: ordered by `deadLetteredAt`, then by `id`.

#### Throws

- `INVALID_PAYLOAD` -- native rejected the call. See [Native Rejections](./errors.md#native-rejections).
- `NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
const deadLetters = await listDeadLetter();
for (const item of deadLetters) {
  console.warn(
    `${item.method} ${item.url} failed with ${item.lastErrorCode}`,
    item.lastErrorMessage ?? ''
  );
}
```

For a component, [`useSyncSnapshot().deadLetter`](./hooks/useSyncSnapshot.md) keeps this list up to date.

---

### `replayDeadLetter`

Put a dead letter back into the queue for another try. In one transaction the item is re-inserted under the same id, with its original `createdAt`, as a `PENDING` item with `attempts: 0`, `nextRetryAt: 0` and no last error code, and the dead letter is deleted. Emits `ITEM_ENQUEUED`.

Like [`enqueue`](#enqueue), it does not dispatch the item: a flush does, from [`flush`](#flush), [`useAutoSync`](./hooks/useAutoSync.md), or an OS background window.

**Platform:** Android and iOS

#### Signature

```ts
function replayDeadLetter(id: string): Promise<void>;
```

#### Parameters

| Name | Type     | Required | Description                                                                   |
| ---- | -------- | -------- | ----------------------------------------------------------------------------- |
| `id` | `string` | Yes      | The dead letter's id, from `listDeadLetter()` or an `ITEM_DEAD_LETTERED` event. |

#### Return Value

`Promise<void>`. An unknown id resolves with no change, so replaying the same id twice, or an id that was already discarded, is harmless.

#### Throws

- `INVALID_PAYLOAD` with no `validationErrors` -- native rejected the call, most often because the queue is at `maxQueueSize`. The dead letter stays in place. See [Native Rejections](./errors.md#native-rejections).
- `NATIVE_MODULE_UNAVAILABLE`.

The replay counts toward `maxQueueSize` the way an enqueue does on that platform: Android counts pending and in-flight items, iOS pending items only.

Replays, discards and purges of dead letters run one at a time. Two overlapping replays of the same id act as one replay and one no-op, with a single `ITEM_ENQUEUED`. A replay that races `discardDeadLetter` or `removeItem` for the same id either runs first or finds nothing, and one that races `clearQueue()` never leaves a queue row behind. On iOS, a replay over an id that is already in the queue resolves with nothing changed: the dead letter stays and no event is emitted.

#### Usage

```ts
// After a token refresh, retry what the old token got rejected.
await configureSync({
  ...SYNC_OPTIONS,
  defaultHeaders: { Authorization: `Bearer ${freshToken}` },
});

const rejected = (await listDeadLetter()).filter(
  (item) => item.lastErrorCode === SyncErrorCode.UNAUTHORIZED
);
for (const item of rejected) {
  await replayDeadLetter(item.id);
}
await flush();
```

---

### `discardDeadLetter`

Delete a dead letter for good, with its stored request. Emits `ITEM_REMOVED` when a row was deleted.

**Platform:** Android and iOS

#### Signature

```ts
function discardDeadLetter(id: string): Promise<void>;
```

#### Parameters

| Name | Type     | Required | Description                                                                   |
| ---- | -------- | -------- | ----------------------------------------------------------------------------- |
| `id` | `string` | Yes      | The dead letter's id, from `listDeadLetter()` or an `ITEM_DEAD_LETTERED` event. |

#### Return Value

`Promise<void>`. An unknown id resolves with no change, so discarding the same id twice is harmless. [`removeItem`](#removeitem) does the same for a dead letter and also reports whether a row was deleted.

#### Throws

- `INVALID_PAYLOAD` -- native rejected the call. See [Native Rejections](./errors.md#native-rejections).
- `NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
// Drop requests the server will never accept.
for (const item of await listDeadLetter()) {
  if (item.lastErrorCode === SyncErrorCode.SERVER_ERROR) {
    await discardDeadLetter(item.id);
  }
}
```

---

## Sync

### `flush`

Run one flush cycle and resolve with the [`SyncResult`](./types.md#syncresult) describing it.

A cycle dispatches one batch of eligible items, up to `batchSize`, in priority order. Items still cooling are skipped, and the [strategy](./enums.md#syncstrategy) plays no part, so `flush()` works the same under `MANUAL`. If more items remain, flush again or use [`drainUntilEmpty`](#drainuntilempty).

**Platform:** Android and iOS

#### Signature

```ts
function flush(): Promise<SyncResult>;
```

#### Return Value

`Promise<SyncResult>` -- `startedAt`, `finishedAt`, `successCount`, `failureCount`, plus `succeededIds`, `failedIds` and `errors` on Android. Those three are always empty on iOS.

An item that went back to wait for a retry counts as neither a success nor a failure. An item that failed permanently is counted in `failureCount` and is now in the [dead-letter queue](#dead-letters), except one that `removeItem` or `clearQueue` purged while its request was in flight: it counts, but is not dead-lettered. See [What a Purge Stops](#what-a-purge-stops).

#### Throws

- `NATIVE_MODULE_UNAVAILABLE`.
- `NETWORK_ERROR` -- the cycle itself died, for example because the store failed. It need not be a network problem: every native rejection from `flush` maps to this code.

Per-item failures never throw, and neither does a cycle the quality gate or the health check skips.

> **Warning — `flush()` resolves successfully even with failures:** A successful `Promise` resolution does **not** mean every item synced. Inspect `result.failureCount`, and listen to `ITEM_FAILED` for the per-item detail. See the [Error handling guide](../guides/error-handling.md).

#### Checks Before a Cycle

Before a cycle starts, `flush()` runs these checks in order:

1. **Pause.**
2. **Stored credentials.** `defaultHeaders` that were configured but cannot be read.
3. **A running cycle, iOS only.** The call returns the last result at once, without the checks below.
4. **Quality gate.** The network quality is below [`SyncOptions.minNetworkQuality`](./types.md#syncoptions). Not checked when the option is unset.
5. **Health check.** The `HEAD` to [`SyncOptions.healthCheckUrl`](./types.md#syncoptions) fails, or the backoff after a failed one is running. Not checked when the option is unset.

When the pause, the credentials, the gate or the health check stops the flush, no cycle runs: nothing is dispatched, no item changes, no `SYNC_STARTED` is emitted and no history row is written. The pause and the credentials emit no event at all. The gate emits `SYNC_FAILED` with `errorCode: 'NETWORK_QUALITY_TOO_LOW'`, and the health check `SYNC_FAILED` with `errorCode: 'HEALTH_CHECK_FAILED'`.

The result of a skipped flush is 0/0 on both platforms and does not say why. Android returns `0` for `startedAt` and `finishedAt`, the zeroed shape that [`getLastSyncResult`](#getlastsyncresult) reports as `undefined`; `flush()` returns it as is. iOS sets both to the time of the call, so the result looks like an empty cycle's: only the missing `SYNC_STARTED` event and history row tell them apart.

#### Behavior Worth Knowing

- **Nothing eligible.** With an empty queue, or every item cooling, the cycle resolves with a 0/0 result, emits `SYNC_SUCCEEDED`, and adds a row to the sync history.
- **Connectivity is checked only through the gate.** Without `minNetworkQuality`, calling `flush()` offline can spend retry attempts and send valid items to the dead-letter queue. On iOS the foreground session waits for connectivity, so the call may stay pending until the network returns instead. Any `minNetworkQuality` other than `OFFLINE` blocks an offline flush. Otherwise check [`getConnectionStatus`](#getconnectionstatus) first, or use [`drainUntilEmpty`](#drainuntilempty), which does not flush while `DISCONNECTED`.
- **While a cycle is running.** iOS returns the last result at once. Android waits, then runs its own cycle.

#### Usage

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

**Platform:** Android and iOS

#### Signature

```ts
function drainUntilEmpty(options?: DrainOptions): Promise<DrainResult>;
```

#### Parameters

| Name      | Type                                      | Required | Description                                                                                             |
| --------- | ----------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------- |
| `options` | [`DrainOptions`](./types.md#drainoptions) | No       | Bounds: `maxIterations` (default `32`), `timeoutMs` (default `60000`), optional `delayBetweenCyclesMs`. |

#### Return Value

`Promise<`[`DrainResult`](./types.md#drainresult)`>` -- `iterations`, `itemsDispatched`, `queueRemaining`, and a `stoppedReason` of `'EMPTY' | 'MAX_ITERATIONS' | 'TIMEOUT' | 'CANCELLED'`.

#### Throws

- `DRAIN_ALREADY_ACTIVE` -- another drain is running or still settling in this process. Await it before starting another. That applies after [`cancelDrain`](#canceldrain) too: do not rely on cancelling and restarting in the same tick.
- `NATIVE_MODULE_UNAVAILABLE`.
- Any error from an underlying native call. The loop stops, releases its lock and rethrows, so no `DrainResult` is returned. Items dispatched before the failure stay dispatched: re-read [`getQueueSize`](#getqueuesize) for what remains.

#### How the Loop Waits

None of these waits uses up an iteration, because `maxIterations` counts real flushes:

- **A cycle is running.** The loop polls `isSyncing()`.
- **Items are cooling.** After a cycle with no progress, it sleeps until the soonest `nextRetryAt`. If that moment is at or past the deadline, it stops at once with `TIMEOUT`.
- **The device is offline.** It does not flush while `getConnectionStatus()` is `DISCONNECTED`. It waits for the network and ends in `TIMEOUT` with items intact. `UNKNOWN` and `METERED` still flush.
- **Nothing can progress.** With nothing cooling, for example while paused, it sleeps at least 250 ms so it cannot spin.

A `flush()` that the [quality gate or the health check](#checks-before-a-cycle) skips does use up an iteration, and makes no progress. On a link below `minNetworkQuality`, or while a health-check backoff runs, the loop therefore ends with `MAX_ITERATIONS` or `TIMEOUT`.

> **Warning — `timeoutMs` and `cancelDrain()` are hard stops:** Every wait ends at the deadline or on cancel, including a `flush()` still in flight. A native cycle that is already running is not aborted: it keeps going after the drain returns, and its items may still be dispatched.

#### Usage

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

**Platform:** Android and iOS. On web it does nothing, because no drain runs there.

#### Signature

```ts
function cancelDrain(): void;
```

#### Return Value

`void`. When no drain is active this is a harmless no-op — safe to call unconditionally from cleanup paths (effect teardown, screen unmount). It never throws and performs no native call.

#### Usage

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

**Platform:** Android and iOS

#### Signature

```ts
function pauseSync(): Promise<void>;
```

#### Return Value

`Promise<void>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
await pauseSync();
// Items enqueued now are persisted, but no cycle runs until resumeSync() or the next launch.
```

---

### `resumeSync`

Resume a paused engine and emit `RESUMED`. Unless the strategy is `MANUAL`, the retry timer is armed for the soonest pending item: items with no cooldown left are dispatched right away, and cooling items when their cooldown ends. If the device is offline at that moment, the flush waits until connectivity returns.

During a [credential hold](../guides/background-sync.md#when-a-stored-token-expires), it waits for the hold to end before dispatching, on both platforms. The hold lives in memory, so a process restart drops it.

**Platform:** Android and iOS

#### Signature

```ts
function resumeSync(): Promise<void>;
```

#### Return Value

`Promise<void>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
await resumeSync(); // emits RESUMED
```

---

### `isSyncing`

Whether a cycle is running. On iOS, a flush still in its checks (the first-path wait, the health-check `HEAD`) does not count.

**Platform:** Android and iOS

#### Signature

```ts
function isSyncing(): Promise<boolean>;
```

#### Return Value

`Promise<boolean>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
// Skip the call while a cycle runs, as useAutoSync does before each flush.
if (!(await isSyncing())) {
  await flush();
}
```

---

## Config

### `configureSync`

Apply a full [`SyncOptions`](./types.md#syncoptions) configuration and persist it. Each call replaces the previous configuration entirely: it does not merge.

- **When it applies.** The platforms differ:
  - **Android** keeps the options a running cycle started with, `defaultHeaders`, `maxAttempts` and the backoff included, except `retryOnStatusCodes`, which is read again for each response, including the response to a request already in flight. `maxQueueSize` applies from the next enqueue, and `strategy` from the next retry wake, which the call re-arms at once.
  - **iOS** applies the new options as soon as `configureSync` resolves, even inside a running cycle, which keeps only its batch and the outcome of its quality gate and health check. A request already in flight keeps the headers and timeout it was sent with, but its response is classified under the new `retryPolicy`.
- **Persisted.** A worker or BGTask in a fresh process honors the options, including `maxAttempts`, `batchSize` and `requestTimeoutMs`. See [Cold-start configuration](../guides/background-sync.md#cold-start-configuration).
- **`defaultHeaders`.** Encrypted at rest. Calling `configureSync` without them deletes the stored copy, which makes it the logout path. Pass them on every other call, and call `configureSync` whenever the token changes.
- **Retry timer.** It is re-armed from the current queue, so a strategy change takes effect at once. A wake the quality gate was holding restarts its re-check backoff.
- **Holds and backoffs.** Every call clears a [credential hold](../guides/background-sync.md#when-a-stored-token-expires) and ends a running [health-check backoff](../guides/connectivity-detection.md#health-check), whether or not anything changed.
- **Network quality and health check.** `minNetworkQuality` turns on the [quality gate](../guides/connectivity-detection.md#the-quality-gate), and `healthCheckUrl` the [health check](../guides/connectivity-detection.md#health-check) and the latency measurement. Both are opt-in.

**Platform:** Android and iOS

#### Signature

```ts
function configureSync(options: SyncOptions): Promise<void>;
```

#### Parameters

| Name      | Type                                    | Required | Description                                                                                |
| --------- | --------------------------------------- | -------- | ------------------------------------------------------------------------------------------ |
| `options` | [`SyncOptions`](./types.md#syncoptions) | Yes      | Strategy, retry policy, batch size, timeout, queue cap, persistence flag, default headers, quality gate, health check. |

#### Return Value

`Promise<void>`.

#### Throws

- `INVALID_URL` -- `healthCheckUrl` is not an absolute `http(s)` URL, with `validationErrors` set. Checked before any native call.
- `INVALID_PAYLOAD` -- `minNetworkQuality` is not a [`NetworkQuality`](./enums.md#networkquality) value, `healthCheckTtlMs` or `healthCheckBackoffMs` is not a finite number of at least `0`, or `options` is not an object, with `validationErrors` set. Checked before any native call.
- `INVALID_PAYLOAD` with no `validationErrors` -- native rejected the call.
- `NATIVE_MODULE_UNAVAILABLE`.

Only those four fields are validated in JS, only when present, in the order `minNetworkQuality`, `healthCheckUrl`, `healthCheckTtlMs`, `healthCheckBackoffMs`. When several are invalid, the first one sets the code, and `validationErrors` lists them all. The other fields go to native as they are, and out-of-range numbers are clamped: Android treats a `maxAttempts` below `1` as `1`.

#### Usage

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
  // Opt-in: skip cycles below MODERATE, and probe the backend first.
  minNetworkQuality: NetworkQuality.MODERATE,
  healthCheckUrl: 'https://api.example.com/health',
});
```

---

### `getSyncConfig`

Read the currently active configuration.

**Platform:** Android and iOS

#### Signature

```ts
function getSyncConfig(): Promise<SyncOptions>;
```

#### Return Value

`Promise<SyncOptions>` -- the active config: the defaults until `configureSync` first runs, then the last options applied, restored from disk in a new process. After a `configureSync` that omitted `batchSize`, `requestTimeoutMs`, `maxQueueSize`, `persistQueue` or `defaultHeaders`, iOS returns them filled with their defaults, including `defaultHeaders: {}`. Android returns them as `undefined`.

`minNetworkQuality`, `healthCheckUrl`, `healthCheckTtlMs` and `healthCheckBackoffMs` are not filled in: both platforms return them only when they were set.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

> **Warning — Do not pass the result back without setting `defaultHeaders`:** In a process where `configureSync` has not run yet, the result is the configuration an earlier process persisted, with the stored copy of the headers. While that copy cannot be read (the Android Keystore key was invalidated, the iOS device has not been unlocked since boot, or a backup was restored onto another device), the result carries no headers: `defaultHeaders` is `undefined` on Android and `{}` on iOS. That lasts until the library reads the copy again, which it retries on each flush while not paused, or until `configureSync` runs.
>
> `configureSync` replaces the whole configuration, so passing such a result back, for example `configureSync({ ...cfg, batchSize: 5 })`, deletes the stored headers, which acts as a logout. Later requests go out without them, and a 401 or 403 fails those items permanently. See [Troubleshooting](../troubleshooting.md#symptom-items-stay-pending-and-flush-returns-00).

#### Usage

```ts
const cfg = await getSyncConfig();
// Take the credentials from the app's session, not from `cfg`.
await configureSync({ ...cfg, batchSize: 5, defaultHeaders: session.headers });
```

---

## History

### `getLastSyncResult`

Return the most recent [`SyncResult`](./types.md#syncresult), or `undefined` if no flush has run yet.

**Platform:** Android and iOS

#### Signature

```ts
function getLastSyncResult(): Promise<SyncResult | undefined>;
```

#### Return Value

`Promise<SyncResult | undefined>`. This includes 0/0 cycles, so a recent `successCount` of `0` does not mean nothing was ever sent.

> **Note — Sentinel translation:** The native layer returns a result with `startedAt === 0 && finishedAt === 0` to mean "no run yet". The JS facade translates that sentinel into `undefined` so consumers can do a simple null check. `flush()` does not translate it.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

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

**Platform:** Android and iOS

#### Signature

```ts
function getSyncHistory(limit?: number): Promise<SyncResult[]>;
```

#### Parameters

| Name    | Type     | Required | Default | Description                                                                                                                 |
| ------- | -------- | -------- | ------- | --------------------------------------------------------------------------------------------------------------------------- |
| `limit` | `number` | No       | `0`     | Max entries to return. `0` (the JS-side default when the argument is omitted) means no limit.                               |

#### Return Value

`Promise<SyncResult[]>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
const recent = await getSyncHistory(20);
const totalSucceeded = recent.reduce((sum, r) => sum + r.successCount, 0);
```

---

### `clearSyncHistory`

Drop all stored history.

**Platform:** Android and iOS

#### Signature

```ts
function clearSyncHistory(): Promise<void>;
```

#### Return Value

`Promise<void>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
// Every cycle adds a row, so trim the history now and then.
if ((await getSyncHistory()).length > 500) {
  await clearSyncHistory();
}
```

---

## Connection

### `getConnectionStatus`

One-shot read of the current network state.

**Platform:** Android and iOS

#### Signature

```ts
function getConnectionStatus(): Promise<ConnectionState>;
```

#### Return Value

`Promise<`[`ConnectionState`](./types.md#connectionstate)`>` -- `status`, `type`, `networkQuality`, optional `isInternetReachable`, `isExpensive`.

`networkQuality` is always present, and `OFFLINE` exactly when `status` is `DISCONNECTED` or `UNKNOWN`. Without `healthCheckUrl` it comes from link signals only and never reaches `EXCELLENT`. See [Network quality](../guides/connectivity-detection.md#network-quality) for how it is classified.

`CONNECTION_CHANGED` carries only `connectionStatus`, and is also emitted when only the network quality changed: call this again on that event, or use [`useConnection`](./hooks/useConnection.md), which does.

#### Usage

```ts
const conn = await getConnectionStatus();
if (conn.status === ConnectionStatus.CONNECTED) {
  await flush();
}

if (conn.networkQuality === NetworkQuality.POOR) {
  showSlowNetworkBanner();
}
```

For reactive updates, prefer the [`useConnection`](./hooks/useConnection.md) hook.

---

## Background Sync

### `enableBackgroundSync`

Register the platform background scheduler, so the queue can be flushed while the app is not running. The OS decides when a window runs. iOS does not launch an app the user force-quit, and Android stops its work after a Force Stop, until the user opens the app again.

The options persist across relaunches. With `foregroundOnly: true` nothing is scheduled, and anything already scheduled is cancelled.

**Platform:** Android and iOS

#### Signature

```ts
function enableBackgroundSync(options: BackgroundSyncOptions): Promise<void>;
```

#### Parameters

| Name      | Type                                                        | Required | Description                                                                        |
| --------- | ----------------------------------------------------------- | -------- | ---------------------------------------------------------------------------------- |
| `options` | [`BackgroundSyncOptions`](./types.md#backgroundsyncoptions) | Yes      | Minimum interval, network / charging / idle constraints, `foregroundOnly`.         |

#### Return Value

`Promise<void>`.

#### Throws

- `BACKGROUND_TASK_REGISTRATION_FAILED` -- iOS: the OS refused the request, because `com.gabriel-sisjr.syncprovider.background` is not in `BGTaskSchedulerPermittedIdentifiers`, because you are on a simulator, where `BGTaskScheduler` does not accept requests, or because the user turned off Background App Refresh. Android: WorkManager rejected the request, as it does when `requiresDeviceIdle` is `true`.
- `NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
await enableBackgroundSync({
  minimumIntervalMs: 15 * 60 * 1000, // 15 min (Android floor)
  requiresUnmeteredNetwork: false,
});
```

See the [Background sync guide](../guides/background-sync.md) for full setup.

---

### `disableBackgroundSync`

Unregister the background scheduler and cancel any pending Android retry kick. A later window that was already handed to the OS does nothing.

- **Android.** A `SyncWorker` that is running is stopped: requests already sent finish and are recorded, and claimed items not yet sent go back to `PENDING`. A cycle started in the app (`flush()`, the retry timer) is not affected.
- **iOS.** A task already running is allowed to finish.

**Platform:** Android and iOS

#### Signature

```ts
function disableBackgroundSync(): Promise<void>;
```

#### Return Value

`Promise<void>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
await disableBackgroundSync();
```

---

### `isBackgroundSyncEnabled`

Whether background sync is enabled. This reports intent, not OS state: it is `true` from `enableBackgroundSync` until `disableBackgroundSync`, even with `foregroundOnly: true`, when no OS work is scheduled. It survives relaunches.

**Platform:** Android and iOS

#### Signature

```ts
function isBackgroundSyncEnabled(): Promise<boolean>;
```

#### Return Value

`Promise<boolean>`.

#### Throws

`NATIVE_MODULE_UNAVAILABLE`.

#### Usage

```ts
if (!(await isBackgroundSyncEnabled())) {
  await enableBackgroundSync({ minimumIntervalMs: 15 * 60 * 1000 });
}
```

---

## Utilities

### `generateId`

Generate a UUID v4 string with `Math.random()`. Use it for an `Idempotency-Key` header, built before `enqueue`. See the [Idempotency guide](../guides/idempotency.md).

It is not cryptographically secure, so do not use it for anything an adversary should not be able to guess.

**Platform:** Android and iOS. It works on web too.

#### Signature

```ts
function generateId(): string;
```

#### Return Value

`string` -- a new UUID v4: 36 characters in the form `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx`.

#### Usage

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

**Platform:** Android and iOS. On web it always returns `false`.

#### Signature

```ts
function isNativeModuleAvailable(): boolean;
```

#### Return Value

`boolean` -- `true` when the native module was resolved.

#### Usage

```ts
if (isNativeModuleAvailable()) {
  await flush();
}
```

---

## See Also

- [Event Listeners](./listeners.md) -- `addSyncEventListener` and `removeSyncEventListener`
- [useSyncQueue](./hooks/useSyncQueue.md) -- Reactive wrapper over the queue functions
- [Errors](./errors.md) -- `SyncError` and `SyncErrorCode`
- [Platform Comparison](../production/platform-comparison.md) -- How dispatch, results and timings differ between Android and iOS
