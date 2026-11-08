---
sidebar_position: 4
title: Types & Interfaces
description: Complete TypeScript type and interface reference for @gabriel-sisjr/react-native-sync-provider — items, options, results, events, connection state, and hook result shapes.
keywords:
  - react-native
  - sync-provider
  - typescript
  - types
  - SyncItem
  - SyncOptions
  - SyncResult
  - RetryPolicy
---

# Types & Interfaces

All TypeScript types exported by the library, grouped by purpose.

```ts
import type {
  SyncItemInput,
  SyncItem,
  SyncDeadLetterItem,
  RetryPolicy,
  SyncOptions,
  SyncResult,
  SyncEvent,
  BackgroundSyncOptions,
  ConnectionState,
  // Drain types
  DrainOptions,
  DrainResult,
  DrainStoppedReason,
  // Hook result types
  UseConnectionResult,
  UseSyncQueueResult,
  UseSyncStatusResult,
  UseOfflineQueueResult,
  UseSyncConfigResult,
  UseSyncSnapshotResult,
} from '@gabriel-sisjr/react-native-sync-provider';
```

---

## Queue Types

### `SyncItemInput`

The shape consumers pass to [`enqueue`](./functions.md#enqueue). The `id` and `createdAt` are added by the native layer.

```ts
interface SyncItemInput {
  method: HttpMethod;
  url: string;
  headers?: Record<string, string>;
  body?: string;
  contentType?: string;
  priority?: SyncPriority;
  metadata?: Record<string, string>;
}
```

| Property      | Type                                      | Required | Description                                                                                         |
| ------------- | ----------------------------------------- | -------- | --------------------------------------------------------------------------------------------------- |
| `method`      | [`HttpMethod`](./enums.md#httpmethod)     | Yes      | HTTP verb.                                                                                          |
| `url`         | `string`                                  | Yes      | Absolute URL.                                                                                       |
| `headers`     | `Record<string, string>`                  | No       | Per-item headers, merged on top of `SyncOptions.defaultHeaders`. Stored in plain text: put credentials in `defaultHeaders`. |
| `body`        | `string`                                  | No       | Request body. Strings only, sent as UTF-8 text. Stored in plain text. Android drops it for `GET` and `DELETE`. |
| `contentType` | `string`                                  | No       | `Content-Type` header. Set it here and leave it out of `headers`: see the note below.               |
| `priority`    | [`SyncPriority`](./enums.md#syncpriority) | No       | Dispatch lane. Defaults to `NORMAL`.                                                                |
| `metadata`    | `Record<string, string>`                  | No       | Opaque tags persisted with the item and returned by `getPendingItems`. Not sent with the request and not echoed on events. Strings only. |

:::note `contentType` vs. `headers`
The platforms resolve a conflict differently. Android uses `contentType`, and sends `application/json` for any body when it is omitted, even if `headers` sets `Content-Type`. iOS uses the value in `headers` or `defaultHeaders`, and sets `contentType` only when neither has one.
:::

---

### `SyncItem`

The shape stored natively and returned by [`getPendingItems`](./functions.md#getpendingitems).

```ts
interface SyncItem {
  id: string;
  method: HttpMethod;
  url: string;
  headers?: Record<string, string>;
  body?: string;
  contentType?: string;
  priority?: SyncPriority;
  createdAt: number;
  attempts: number;
  nextRetryAt: number;
  metadata?: Record<string, string>;
}
```

| Property      | Type                                      | Required | Description                                                                                                                                                                                                                                             |
| ------------- | ----------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `id`          | `string`                                  | Yes      | ULID assigned by the native layer at persistence time.                                                                                                                                                                                                  |
| `method`      | [`HttpMethod`](./enums.md#httpmethod)     | Yes      | HTTP verb.                                                                                                                                                                                                                                              |
| `url`         | `string`                                  | Yes      | Absolute URL.                                                                                                                                                                                                                                           |
| `headers`     | `Record<string, string>`                  | No       | Per-item headers.                                                                                                                                                                                                                                       |
| `body`        | `string`                                  | No       | Request body.                                                                                                                                                                                                                                           |
| `contentType` | `string`                                  | No       | `Content-Type` shortcut.                                                                                                                                                                                                                                |
| `priority`    | [`SyncPriority`](./enums.md#syncpriority) | No       | Dispatch lane.                                                                                                                                                                                                                                          |
| `createdAt`   | `number`                                  | Yes      | Unix milliseconds when the item was persisted.                                                                                                                                                                                                          |
| `attempts`    | `number`                                  | Yes      | Dispatch attempts recorded for this item, across cycles, counted against `RetryPolicy.maxAttempts`. `0` means **no attempt recorded**, not that the item was never tried: its request may already have reached the server. iOS counts an attempt when dispatch starts and Android when its outcome is recorded, so an attempt cut short by a process kill counts only on iOS. A [credential hold](../guides/background-sync.md#when-a-stored-token-expires) spends no attempt. |
| `nextRetryAt` | `number`                                  | Yes      | [Retry cooldown](../guides/retry-policy.md#retry-cooldown-across-restarts). `0` means **eligible now**, not that the item was never attempted. A positive value is an epoch-ms deadline: a retry cooldown or a credential hold is pending, and the item is eligible once `nextRetryAt <= now`. The value stays in place afterwards, so a timestamp in the past means the wait has elapsed. |
| `metadata`    | `Record<string, string>`                  | No       | Opaque tags.                                                                                                                                                                                                                                            |

:::note `attempts` / `nextRetryAt` are never `undefined`
Unlike [`getLastSyncResult`](./functions.md#getlastsyncresult), which maps a native sentinel (`startedAt === 0 && finishedAt === 0`) to `undefined`, a `SyncItem`'s `attempts` and `nextRetryAt` are surfaced **verbatim**. Their `0` values carry meaning, so they are filled in, not skipped.
:::

The two fields do not move together. Rows carried over from v0.1 can show `attempts >= 1` with `nextRetryAt: 0`, and so can an iOS row whose first attempt was cut short by a process kill. An item held after a credential rejection can show `attempts: 0` with `nextRetryAt > 0`. A cooldown and a hold look the same here, because `SyncItem` carries neither the last error code nor the last attempt time: an `ITEM_RETRYING` event with `errorCode: 'UNAUTHORIZED'` marks a hold.

---

### `SyncDeadLetterItem`

A request that failed permanently and was moved out of the queue into the dead-letter queue, instead of being dropped. Returned by [`listDeadLetter`](./functions.md#listdeadletter) and by [`useSyncSnapshot().deadLetter`](./hooks/useSyncSnapshot.md). It mirrors `SyncItem` without the retry schedule (`nextRetryAt`), and adds the failure bookkeeping you need to inspect, replay or discard the request.

```ts
interface SyncDeadLetterItem {
  id: string;
  method: HttpMethod;
  url: string;
  headers?: Record<string, string>;
  body?: string;
  contentType?: string;
  priority?: SyncPriority;
  createdAt: number;
  metadata?: Record<string, string>;
  attempts: number;
  lastAttemptAt: number;
  lastErrorCode: SyncErrorCode;
  lastErrorMessage?: string;
  deadLetteredAt: number;
}
```

| Property           | Type                                         | Required | Description |
| ------------------ | -------------------------------------------- | -------- | ----------- |
| `id`               | `string`                                     | Yes      | ULID assigned at enqueue time. [`replayDeadLetter`](./functions.md#replaydeadletter) puts the item back under the same id. |
| `method`           | [`HttpMethod`](./enums.md#httpmethod)        | Yes      | HTTP verb. |
| `url`              | `string`                                     | Yes      | Absolute URL. |
| `headers`          | `Record<string, string>`                     | No       | Per-item headers, as enqueued. Stored in plain text, like the queue. |
| `body`             | `string`                                     | No       | Request body, as enqueued. Stored in plain text. |
| `contentType`      | `string`                                     | No       | `Content-Type` shortcut, as enqueued. |
| `priority`         | [`SyncPriority`](./enums.md#syncpriority)    | No       | Dispatch lane. `NORMAL` if not supplied on enqueue. |
| `createdAt`        | `number`                                     | Yes      | Unix ms of the original enqueue. A replay keeps it. |
| `metadata`         | `Record<string, string>`                     | No       | Opaque tags, as enqueued. |
| `attempts`         | `number`                                     | Yes      | Dispatch attempts recorded before the item was dead-lettered. The platforms record attempts at different moments: see [`SyncItem.attempts`](#syncitem). A replay starts again from `0`. |
| `lastAttemptAt`    | `number`                                     | Yes      | Unix ms of the final, failing attempt. When none was recorded: for an item moved by the upgrade, the same value as `deadLetteredAt`, its enqueue time; for an item dead-lettered without a request, the time of the move. |
| `lastErrorCode`    | [`SyncErrorCode`](./errors.md#syncerrorcode) | Yes      | Code of the final failure, the one its `ITEM_FAILED` reported. Never `DEAD_LETTERED`. An item moved by the upgrade with no usable code reports `MAX_ATTEMPTS_EXCEEDED`. |
| `lastErrorMessage` | `string`                                     | No       | Detail of the final failure: for an HTTP failure, the status line; otherwise the error message, such as `Retry budget exhausted`. At most 1024 characters. Never contains request or response headers or bodies. Absent for items moved by the upgrade, and when the platform reported no detail. |
| `deadLetteredAt`   | `number`                                     | Yes      | Unix ms when the item was moved. [`listDeadLetter`](./functions.md#listdeadletter) sorts by it, oldest first. For an item moved by the upgrade: its last attempt time, or its enqueue time when none was recorded. |

:::note The status line differs by platform
iOS builds it with the system's reason phrase, as in `HTTP 404 Not Found`. Android uses the server's status line, whose reason phrase is empty under HTTP/2, as in `HTTP 404`. Branch on `lastErrorCode`, not on this text.
:::

How an item becomes a dead letter:

- **Every permanent failure.** Attempts exhausted (`MAX_ATTEMPTS_EXCEEDED`), a response that is not retried (`SERVER_ERROR`), and a 401 or 403 under a configuration set in the current process (`UNAUTHORIZED`). An item whose attempts already reached `maxAttempts`, for example after `configureSync` lowered it, is dead-lettered with `MAX_ATTEMPTS_EXCEEDED` without sending a request. A [credential hold](../guides/background-sync.md#when-a-stored-token-expires) is not a failure: the held item stays queued.
- **One transaction.** The item leaves the queue and enters the dead-letter queue atomically, so it is never in both. `ITEM_FAILED` follows with the real code, then, synchronously, `ITEM_DEAD_LETTERED` with `errorCode: 'DEAD_LETTERED'` and `metadata.lastErrorCode` set to the real code.
- **On upgrade.** Items that had failed permanently under an earlier version are moved here when the upgraded library first opens its store.
- **Same id.** A newer dead letter with the same id replaces the older one.

:::warning The dead-letter queue is unbounded
Nothing prunes it, and it counts toward no capacity cap, on either platform. Each dead letter keeps its request's headers and body until [`replayDeadLetter`](./functions.md#replaydeadletter), [`discardDeadLetter`](./functions.md#discarddeadletter), [`removeItem`](./functions.md#removeitem) or [`clearQueue`](./functions.md#clearqueue) deletes it. Discard what you do not replay.
:::

---

## Configuration Types

### `RetryPolicy`

Backoff strategy for failed dispatches.

```ts
interface RetryPolicy {
  maxAttempts: number;
  backoff: BackoffStrategy;
  baseDelayMs: number;
  maxDelayMs: number;
  jitter: boolean;
  retryOnStatusCodes: number[];
}
```

| Property             | Type                                            | Required | Default                               | Description                                                                                             |
| -------------------- | ----------------------------------------------- | -------- | ------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `maxAttempts`        | `number`                                        | Yes      | `5`                                   | Total attempts before the item is moved to the [dead-letter queue](#syncdeadletteritem).                |
| `backoff`            | [`BackoffStrategy`](./enums.md#backoffstrategy) | Yes      | `EXPONENTIAL`                         | Curve used to compute the next delay.                                                                   |
| `baseDelayMs`        | `number`                                        | Yes      | `1000`                                | Base delay -- input to the curve.                                                                       |
| `maxDelayMs`         | `number`                                        | Yes      | `60000`                               | Upper cap on the computed delay.                                                                        |
| `jitter`             | `boolean`                                       | Yes      | `true`                                | When `true`, the computed delay is multiplied by a uniform factor in `[0.75, 1.25)` to avoid thundering-herd retries. |
| `retryOnStatusCodes` | `number[]`                                      | Yes      | `[408, 425, 429, 500, 502, 503, 504]` | Response statuses that trigger a retry. Statuses outside the list are terminal failures, and 401/403 stay terminal even when listed. |

See [Retry policy guide](../guides/retry-policy.md) for backoff math and tuning.

---

### `SyncOptions`

The full configuration applied via [`configureSync`](./functions.md#configuresync).

```ts
interface SyncOptions {
  strategy: SyncStrategy;
  retryPolicy: RetryPolicy;
  batchSize?: number;
  requestTimeoutMs?: number;
  maxQueueSize?: number;
  persistQueue?: boolean;
  defaultHeaders?: Record<string, string>;
  minNetworkQuality?: NetworkQuality;
  healthCheckUrl?: string;
  healthCheckTtlMs?: number;
  healthCheckBackoffMs?: number;
}
```

| Property           | Type                                      | Required | Description                                                                                                                                                                                                       |
| ------------------ | ----------------------------------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `strategy`         | [`SyncStrategy`](./enums.md#syncstrategy) | Yes      | What the engine schedules on its own: `AUTOMATIC`, `MANUAL`, or `OPPORTUNISTIC`. It does not control `flush()`.                                                                                                   |
| `retryPolicy`      | [`RetryPolicy`](#retrypolicy)             | Yes      | Backoff policy.                                                                                                                                                                                                   |
| `batchSize`        | `number`                                  | No       | Most items one cycle dispatches. Default `25`. Android sends them concurrently, iOS one at a time.                                                                                                                |
| `requestTimeoutMs` | `number`                                  | No       | Per-request timeout. Default `30000`. Android enforces a floor of 1 second.                                                                                                                                       |
| `maxQueueSize`     | `number`                                  | No       | Most items the queue may hold. Default `10000`. A value below `1` is treated as `1`, so the cap cannot be removed. Android counts pending and in-flight items toward it, iOS counts `PENDING` items only. Dead letters count on neither. `enqueue`, `enqueueBatch` and `replayDeadLetter` are rejected once it is reached: see [Native rejections](./errors.md#native-rejections) for how that surfaces. |
| `persistQueue`     | `boolean`                                 | No       | Stored but not used yet. The queue is always persisted.                                                                                                                                                           |
| `defaultHeaders`   | `Record<string, string>`                  | No       | Headers merged into every dispatched item. Per-item `headers` win on conflict. Encrypted at rest. Calling `configureSync` without them deletes the stored copy. See [Cold-start configuration](../guides/background-sync.md#cold-start-configuration). |
| `minNetworkQuality` | [`NetworkQuality`](./enums.md#networkquality) | No | Lowest network quality at which a flush may run. Unset, the default, means no quality gate. `POOR` blocks only `OFFLINE`, and `OFFLINE` gates nothing, like leaving it unset. Below the minimum no cycle runs and `flush()` reports `NETWORK_QUALITY_TOO_LOW` on `SYNC_FAILED`. Without `healthCheckUrl` the quality never reaches `EXCELLENT`, so `EXCELLENT` then blocks every flush. See [The quality gate](../guides/connectivity-detection.md#the-quality-gate). |
| `healthCheckUrl`   | `string`                                  | No       | Absolute `http(s)` URL the engine sends a `HEAD` to before a cycle claims its first item. A final `2xx` is healthy; anything else skips the cycle with `HEALTH_CHECK_FAILED`. Also the only target of the network-quality measurement. The probes carry no `defaultHeaders`, so point it at an endpoint that answers without authentication. Unset, the default, means no health check and no measurement. See [Health check](../guides/connectivity-detection.md#health-check). |
| `healthCheckTtlMs` | `number`                                  | No       | How long a healthy result is reused, in ms. Default `60000`. `0` checks before every cycle. Ignored without `healthCheckUrl`. |
| `healthCheckBackoffMs` | `number`                              | No       | How long cycles are skipped after a failed health check, in ms. Default `30000`. `0` means no backoff, but automatic retry wakes still wait 1000 ms after a failure. Ignored without `healthCheckUrl`. |

`configureSync` validates the last four fields in JS, in table order and only when present: `minNetworkQuality` must be a [`NetworkQuality`](./enums.md#networkquality) value, `healthCheckUrl` an absolute `http(s)` URL, and both durations finite numbers of at least `0`. The first invalid field sets the code, `INVALID_URL` for `healthCheckUrl` and `INVALID_PAYLOAD` for the others, and `validationErrors` lists them all. [`getSyncConfig`](./functions.md#getsyncconfig) returns them only when they were set, on both platforms: their defaults are not filled in. All four are persisted for background workers, and a stored value that cannot be used, which only a damaged record can hold, loads as unset.

---

### `BackgroundSyncOptions`

Constraints for [`enableBackgroundSync`](./functions.md#enablebackgroundsync).

```ts
interface BackgroundSyncOptions {
  minimumIntervalMs: number;
  requiresCharging?: boolean;
  requiresUnmeteredNetwork?: boolean;
  requiresDeviceIdle?: boolean;
  foregroundOnly?: boolean;
  taskIdentifier?: string;
}
```

| Property                   | Type      | Required | Description                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| -------------------------- | --------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `minimumIntervalMs`        | `number`  | Yes      | Minimum milliseconds between background runs. Android floor is 15 minutes. iOS treats this as a soft hint, with a floor of 60 seconds.                                                                                                                                                                                                       |
| `requiresCharging`         | `boolean` | No       | When `true`, only run while plugged in. On iOS this applies to the processing task only, not to the app-refresh task.                                                                                                                                                                                                                        |
| `requiresUnmeteredNetwork` | `boolean` | No       | When `true`, only run on unmetered networks. Android only: iOS does not apply it.                                                                                                                                                                                                                                                            |
| `requiresDeviceIdle`       | `boolean` | No       | When `true`, only run while the device is idle. Android only.                                                                                                                                                                                                                                                                                |
| `foregroundOnly`           | `boolean` | No       | Kill-switch for background work. When `true`, the library schedules **no** background dispatch (no `BGTaskScheduler` tasks on iOS, no `WorkManager` periodic or one-time flush jobs on Android), cancels anything it had already scheduled, and iOS stops using the background `URLSession`. The in-process retry timer keeps running, and launch/boot recovery is **not** suppressed. Defaults to `false`. Persists across relaunches. See [Background sync guide](../guides/background-sync.md#foreground-only-mode). |
| `taskIdentifier`           | `string`  | No       | Stored but not used yet. Both platforms use fixed identifiers. On iOS, `Info.plist` must declare the two fixed identifiers whatever this field says: see [iOS Setup](../getting-started/ios-setup.md).                                                                                                                                       |

---

## Drain Types

Bounds and outcome for the [`drainUntilEmpty`](./functions.md#drainuntilempty) loop.

### `DrainOptions`

```ts
interface DrainOptions {
  maxIterations?: number;
  timeoutMs?: number;
  delayBetweenCyclesMs?: number;
}
```

| Property               | Type     | Required | Default | Description                                                                                                                                    |
| ---------------------- | -------- | -------- | ------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `maxIterations`        | `number` | No       | `32`    | Maximum flush cycles to start before stopping with `stoppedReason: 'MAX_ITERATIONS'`. Only real flushes count: waiting for a running cycle, a cooldown or connectivity does not use up iterations. |
| `timeoutMs`            | `number` | No       | `60000` | Hard deadline for the whole loop. When it passes, the loop stops with `stoppedReason: 'TIMEOUT'`, even with a `flush()` still in flight. A native cycle already running keeps going after the drain returns. |
| `delayBetweenCyclesMs` | `number` | No       | —       | Minimum pause between cycles. Omitted or `0` runs cycles that make progress back-to-back. It is not needed for retry cooldowns: the loop sleeps until the soonest `nextRetryAt` by itself. |

Every option has a safe default, so `drainUntilEmpty()` with no arguments always ends, even under concurrent enqueues. A non-finite value (`NaN`, `Infinity`) falls back to that option's default.

### `DrainResult`

```ts
interface DrainResult {
  iterations: number;
  itemsDispatched: number;
  queueRemaining: number;
  stoppedReason: DrainStoppedReason;
}
```

| Property          | Type                                        | Description                                                                                                                                                 |
| ----------------- | ------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `iterations`      | `number`                                    | Flush cycles started, including one interrupted by a stop.                                                                                                  |
| `itemsDispatched` | `number`                                    | Best-effort count of items that left the pending queue, summed from decreases in queue size. It is not a success count: it includes items that failed permanently and items removed by `removeItem` / `clearQueue`, and it under-counts when items are enqueued during the drain. Use `getSyncHistory` for per-cycle results. |
| `queueRemaining`  | `number`                                    | Last pending-item count the loop observed, including items still cooling. After a stop during a flush it predates that flush.                               |
| `stoppedReason`   | [`DrainStoppedReason`](#drainstoppedreason) | Why the loop ended.                                                                                                                                         |

### `DrainStoppedReason`

```ts
type DrainStoppedReason = 'EMPTY' | 'MAX_ITERATIONS' | 'TIMEOUT' | 'CANCELLED';
```

| Value            | Meaning                                                                                                                                                                                       |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `EMPTY`          | `getQueueSize()` is `0` and `isSyncing()` is `false` (the success case).                                                                                                                      |
| `MAX_ITERATIONS` | The loop started `DrainOptions.maxIterations` flush cycles and items remain.                                                                                                                  |
| `TIMEOUT`        | `DrainOptions.timeoutMs` elapsed, or the soonest retry cooldown ends at or after that deadline, so waiting for it would be pointless. Also how an offline drain ends, with items intact.       |
| `CANCELLED`      | [`cancelDrain`](./functions.md#canceldrain) was called while the loop was running.                                                                                                            |

---

## Result Types

### `SyncResult`

Returned by [`flush`](./functions.md#flush) and [`getLastSyncResult`](./functions.md#getlastsyncresult).

```ts
interface SyncResult {
  startedAt: number;
  finishedAt: number;
  successCount: number;
  failureCount: number;
  succeededIds: string[];
  failedIds: string[];
  errors: Record<string, string>;
}
```

| Property       | Type                     | Required | Description                                                                                                                                                                              |
| -------------- | ------------------------ | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `startedAt`    | `number`                 | Yes      | Unix ms when the run started. `0` on Android when `flush()` ran no cycle.                                                                                                                |
| `finishedAt`   | `number`                 | Yes      | Unix ms when the run finished. `0` on Android when `flush()` ran no cycle.                                                                                                               |
| `successCount` | `number`                 | Yes      | Items dispatched successfully.                                                                                                                                                           |
| `failureCount` | `number`                 | Yes      | Items that failed permanently in this cycle, each one now in the [dead-letter queue](#syncdeadletteritem). An item waiting for a retry counts as neither a success nor a failure.         |
| `succeededIds` | `string[]`               | Yes      | ULIDs of successful items. Always empty on iOS.                                                                                                                                          |
| `failedIds`    | `string[]`               | Yes      | ULIDs of failed items. Always empty on iOS.                                                                                                                                              |
| `errors`       | `Record<string, string>` | Yes      | Failed-item id to `"<SyncErrorCode>: <detail>"`, so split on the first `:` to get the code. Always empty on iOS: use `ITEM_FAILED` events there.                                        |

A `flush()` that runs no cycle still resolves with a `SyncResult`. Nothing is dispatched, no item changes, no `SYNC_STARTED` is emitted and no history row is written. That happens when:

- the engine is paused, or stored `defaultHeaders` cannot be read: no event is emitted at all;
- the network quality is below [`minNetworkQuality`](#syncoptions): `SYNC_FAILED` with `errorCode: 'NETWORK_QUALITY_TOO_LOW'`;
- the health check fails, or its backoff is running: `SYNC_FAILED` with `errorCode: 'HEALTH_CHECK_FAILED'`.

Both counts are `0`, and the result does not say why. Android sets `startedAt` and `finishedAt` to `0`. iOS sets both to the time of the call, so there the result alone cannot be told apart from an empty cycle: only the missing `SYNC_STARTED` event and history row show that no cycle ran.

---

## Event Types

### `SyncEvent`

Payload emitted on the [`SYNC_EVENT_CHANNEL`](./listeners.md#sync_event_channel).

```ts
interface SyncEvent {
  type: SyncEventType;
  timestamp: number;
  itemId?: string;
  progress?: number;
  errorCode?: SyncErrorCode;
  statusCode?: number;
  attempt?: number;
  connectionStatus?: ConnectionStatus;
  metadata?: Record<string, string>;
}
```

| Property           | Type                                              | Required | Description                                                            |
| ------------------ | ------------------------------------------------- | -------- | ---------------------------------------------------------------------- |
| `type`             | [`SyncEventType`](./enums.md#synceventtype)       | Yes      | What kind of event this is.                                            |
| `timestamp`        | `number`                                          | Yes      | Unix ms when the event was emitted.                                    |
| `itemId`           | `string`                                          | No       | Present on item-scoped events (`ITEM_*`).                              |
| `progress`         | `number`                                          | No       | `[0, 1]`, present on `SYNC_PROGRESS`, which only iOS emits.            |
| `errorCode`        | [`SyncErrorCode`](./errors.md#syncerrorcode)      | No       | Present on `ITEM_FAILED` and `ITEM_RETRYING`, and `DEAD_LETTERED` on `ITEM_DEAD_LETTERED`. On `SYNC_FAILED` only when the cycle itself died, or when the quality gate (`NETWORK_QUALITY_TOO_LOW`) or the health check (`HEALTH_CHECK_FAILED`) skipped it. Never set on `BACKGROUND_SYNC_*` events. |
| `statusCode`       | `number`                                          | No       | HTTP status code, present on dispatch-related events when available.   |
| `attempt`          | `number`                                          | No       | 1-indexed attempt number, present on `ITEM_SUCCEEDED`, `ITEM_FAILED` and `ITEM_RETRYING`. |
| `connectionStatus` | [`ConnectionStatus`](./enums.md#connectionstatus) | No       | Present on `CONNECTION_CHANGED`. Unchanged when only the network quality changed: read [`getConnectionStatus`](./functions.md#getconnectionstatus) for the new value. |
| `metadata`         | `Record<string, string>`                          | No       | Engine diagnostics. On both platforms, `ITEM_DEAD_LETTERED` sets `lastErrorCode`, the code the item failed with. iOS also sets diagnostics on cycle-level events, such as attempted and succeeded counts, and on the `BACKGROUND_SYNC_*` events (`kind`, and `ok: "false"` on a failed window). This is **not** [`SyncItem.metadata`](#syncitem), which is never echoed on events. |

---

## Connection Types

### `ConnectionState`

Returned by [`getConnectionStatus`](./functions.md#getconnectionstatus) and exposed via [`useConnection`](./hooks/useConnection.md).

```ts
interface ConnectionState {
  status: ConnectionStatus;
  type: ConnectionType;
  networkQuality: NetworkQuality;
  isInternetReachable?: boolean;
  isExpensive?: boolean;
}
```

| Property              | Type                                              | Required | Description                                                    |
| --------------------- | ------------------------------------------------- | -------- | -------------------------------------------------------------- |
| `status`              | [`ConnectionStatus`](./enums.md#connectionstatus) | Yes      | High-level status.                                             |
| `type`                | [`ConnectionType`](./enums.md#connectiontype)     | Yes      | Network family.                                                |
| `networkQuality`      | [`NetworkQuality`](./enums.md#networkquality)     | Yes      | The engine's quality classification, always present. `OFFLINE` exactly when `status` is `DISCONNECTED` or `UNKNOWN`. Without `healthCheckUrl` it comes from link signals only and never reaches `EXCELLENT`. |
| `isInternetReachable` | `boolean`                                         | No       | Optional reachability probe (iOS only on some setups).         |
| `isExpensive`         | `boolean`                                         | No       | Mirrors `NWPath.isExpensive` (iOS) / metered status (Android). |

:::warning `networkQuality` is required
Code that only reads `ConnectionState` is unaffected, but a hand-built value, such as a test fixture or a mock of `getConnectionStatus`, must now set it. See the [v0.2 → v0.3 migration](../migration/index.md#v0-2-to-v0-3).
:::

---

## Hook Result Types

The shapes returned by each hook. See each hook's reference page for behavior.

### `UseConnectionResult`

```ts
interface UseConnectionResult {
  status: ConnectionStatus;
  type: ConnectionType;
  isOnline: boolean;
  isMetered: boolean;
  networkQuality: NetworkQuality;
}
```

`networkQuality` is required, like on `ConnectionState`. It is `OFFLINE` whenever `isOnline` is `false`, including before the first snapshot arrives.

See [`useConnection`](./hooks/useConnection.md).

### `UseSyncQueueResult`

```ts
interface UseSyncQueueResult {
  size: number;
  items: SyncItem[];
  isLoading: boolean;
  error: SyncError | null;
  enqueue: (input: SyncItemInput) => Promise<string>;
  enqueueBatch: (inputs: SyncItemInput[]) => Promise<string[]>;
  removeItem: (id: string) => Promise<boolean>;
  clearQueue: () => Promise<void>;
  refresh: () => Promise<void>;
}
```

See [`useSyncQueue`](./hooks/useSyncQueue.md).

### `UseSyncStatusResult`

```ts
interface UseSyncStatusResult {
  isSyncing: boolean;
  isPaused: boolean;
  progress: number | null;
  lastResult: SyncResult | null;
  error: SyncError | null;
  flush: () => Promise<SyncResult>;
  pause: () => Promise<void>;
  resume: () => Promise<void>;
}
```

See [`useSyncStatus`](./hooks/useSyncStatus.md).

### `UseOfflineQueueResult`

```ts
interface UseOfflineQueueResult {
  connection: UseConnectionResult;
  size: number;
  items: SyncItem[];
  isSyncing: boolean;
  isWaitingForConnection: boolean;
  error: SyncError | null;
  enqueue: (input: SyncItemInput) => Promise<string>;
  flush: () => Promise<SyncResult>;
}
```

See [`useOfflineQueue`](./hooks/useOfflineQueue.md).

### `UseSyncConfigResult`

```ts
interface UseSyncConfigResult {
  config: SyncOptions | null;
  isLoading: boolean;
  error: SyncError | null;
  setConfig: (next: SyncOptions) => Promise<void>;
  refresh: () => Promise<void>;
}
```

See [`useSyncConfig`](./hooks/useSyncConfig.md).

### `UseSyncSnapshotResult`

```ts
interface UseSyncSnapshotResult {
  connection: UseConnectionResult;
  size: number;
  items: SyncItem[];
  isSyncing: boolean;
  isPaused: boolean;
  progress: number | null;
  lastResult: SyncResult | null;
  isWaitingForConnection: boolean;
  error: SyncError | null;
  networkQuality?: NetworkQuality;
  deadLetter?: readonly SyncDeadLetterItem[];
}
```

Read-only dashboard aggregator with no action methods. `networkQuality` mirrors [`ConnectionState.networkQuality`](#connectionstate) and `deadLetter` the [`listDeadLetter`](./functions.md#listdeadletter) result. Both stay `undefined` until their first read succeeds, and are always `undefined` on web.

See [`useSyncSnapshot`](./hooks/useSyncSnapshot.md).

---

## See also

- [Enums](./enums.md), [Errors](./errors.md).
- [Functions reference](./functions.md).
