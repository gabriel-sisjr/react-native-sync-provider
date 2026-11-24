---
sidebar_position: 4
title: Types & Interfaces
description: Complete TypeScript type and interface reference for @gabriel-sisjr/react-native-sync-provider — items, options, results, events, retry decider and interceptor callbacks, connection state, and hook result shapes.
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
  // Callback types
  RetryDeciderContext,
  RetryDecision,
  RetryDecider,
  HeaderResolverContext,
  HeaderResolver,
  BeforeDispatchContext,
  BeforeDispatchResult,
  BeforeDispatchHook,
  AfterDispatchContext,
  AfterDispatchHook,
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
| `headers`     | `Record<string, string>`                  | No       | Per-item headers, merged on top of `SyncOptions.defaultHeaders` and the [header resolver's](./functions.md#the-header-merge) headers, with names matching case-insensitively. Stored in plain text: put credentials in `defaultHeaders`, or supply them from the header resolver. |
| `body`        | `string`                                  | No       | Request body. Strings only, sent as UTF-8 text. Stored in plain text. Android drops it for `GET` and `DELETE`. |
| `contentType` | `string`                                  | No       | `Content-Type` header. Set it here and leave it out of `headers`: see the note below.               |
| `priority`    | [`SyncPriority`](./enums.md#syncpriority) | No       | Dispatch lane. Defaults to `NORMAL`.                                                                |
| `metadata`    | `Record<string, string>`                  | No       | Opaque tags persisted with the item and returned by `getPendingItems`. Not sent with the request and not echoed on events, but passed to the [retry decider](./functions.md#setretrydecider) and the [dispatch interceptors](./functions.md#dispatch-interceptors) in their contexts. Strings only. |

> **Note — `contentType` vs. `headers`:** The platforms resolve a conflict differently. Android uses `contentType`, and sends `application/json` for any body when it is omitted, even if `headers` sets `Content-Type`. iOS uses the value in `headers` or `defaultHeaders`, and sets `contentType` only when neither has one.

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
| `attempts`    | `number`                                  | Yes      | Dispatch attempts recorded for this item, across cycles, counted against `RetryPolicy.maxAttempts`. `0` means **no attempt recorded**, not that the item was never tried: its request may already have reached the server. iOS counts an attempt when dispatch starts and Android when its outcome is recorded, so an attempt cut short by a process kill counts only on iOS. iOS refunds an attempt whose request a [BGTask expiry](../guides/background-sync.md#when-a-window-runs-out-of-time) cancelled. A [credential hold](../guides/background-sync.md#when-a-stored-token-expires) spends no attempt, and neither does an [interceptor deferral](./functions.md#fail-closed). |
| `nextRetryAt` | `number`                                  | Yes      | [Retry cooldown](../guides/retry-policy.md#retry-cooldown-across-restarts). `0` means **eligible now**, not that the item was never attempted. A positive value is an epoch-ms deadline: a retry cooldown, a credential hold or an interceptor deferral is pending, and the item is eligible once `nextRetryAt <= now`. The value stays in place afterwards, so a timestamp in the past means the wait has elapsed. It never exceeds 8,640,000,000,000,000, the largest valid JavaScript `Date`. |
| `metadata`    | `Record<string, string>`                  | No       | Opaque tags.                                                                                                                                                                                                                                            |

> **Note — `attempts` / `nextRetryAt` are never `undefined`:** Unlike [`getLastSyncResult`](./functions.md#getlastsyncresult), which maps a native sentinel (`startedAt === 0 && finishedAt === 0`) to `undefined`, a `SyncItem`'s `attempts` and `nextRetryAt` are surfaced **verbatim**. Their `0` values carry meaning, so they are filled in, not skipped.

The two fields do not move together. Rows carried over from v0.1 can show `attempts >= 1` with `nextRetryAt: 0`, and so can an iOS row whose first attempt was cut short by a process kill. An item held after a credential rejection, or deferred by the header resolver or `beforeDispatch`, can show `attempts: 0` with `nextRetryAt > 0`. A cooldown, a hold and a deferral look the same here, because `SyncItem` carries neither the last error code nor the last attempt time: an `ITEM_RETRYING` event with `errorCode: 'UNAUTHORIZED'` marks a hold, and one with `'INTERCEPTOR_FAILED'` a deferral.

---

### `SyncDeadLetterItem`

A request that failed permanently and was moved out of the queue into the dead-letter queue, instead of being dropped. The only case where the engine itself deletes a failed item is a [retry decider's](./functions.md#setretrydecider) `DROP`. Returned by [`listDeadLetter`](./functions.md#listdeadletter) and by [`useSyncSnapshot().deadLetter`](./hooks/useSyncSnapshot.md). It mirrors `SyncItem` without the retry schedule (`nextRetryAt`), and adds the failure bookkeeping you need to inspect, replay or discard the request.

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
| `lastErrorCode`    | [`SyncErrorCode`](./errors.md#syncerrorcode) | Yes      | Code of the final failure, the one its `ITEM_FAILED` reported. Never `DEAD_LETTERED`. After a retry decider's `DEAD_LETTER` it is `RETRY_DECIDER_REJECTED`, and `lastErrorMessage` describes the failure the decider judged. An item moved by the upgrade with no usable code reports `MAX_ATTEMPTS_EXCEEDED`. |
| `lastErrorMessage` | `string`                                     | No       | Detail of the final failure: for an HTTP failure, the status line; otherwise the error message, such as `Retry budget exhausted`. At most 1024 characters. Never contains request or response headers or bodies. Absent for items moved by the upgrade, and when the platform reported no detail. |
| `deadLetteredAt`   | `number`                                     | Yes      | Unix ms when the item was moved. [`listDeadLetter`](./functions.md#listdeadletter) sorts by it, oldest first. For an item moved by the upgrade: its last attempt time, or its enqueue time when none was recorded. |

> **Note — The status line differs by platform:** iOS builds it with Foundation's reason phrase for the code, localized and capitalized, as in `HTTP 404 Not Found`. Android writes `HTTP <code> <reason>` with the reason phrase the server sent: `HTTP 404 Not Found` over HTTP/1.1, and `HTTP 404` over HTTP/2, which has no reason phrase. Branch on `lastErrorCode`, not on this text.

How an item becomes a dead letter:

- **Every permanent failure.** Attempts exhausted (`MAX_ATTEMPTS_EXCEEDED`), a response that is not retried (`SERVER_ERROR`), a 401 or 403 under a configuration set in the current process (`UNAUTHORIZED`), an attempt that failed with something other than an I/O error that the engine cannot classify (`INVALID_PAYLOAD`), and a retry decider's `DEAD_LETTER` (`RETRY_DECIDER_REJECTED`). An item whose attempts already reached `maxAttempts`, for example after `configureSync` lowered it, is dead-lettered with `MAX_ATTEMPTS_EXCEEDED` without sending a request. A [credential hold](../guides/background-sync.md#when-a-stored-token-expires) is not a failure: the held item stays queued. Neither is an [interceptor deferral](./functions.md#fail-closed), which never escalates to a dead letter.
- **Except a decider's `DROP`.** The item is deleted instead: `ITEM_FAILED` reports `RETRY_DECIDER_REJECTED`, and no dead letter or `ITEM_DEAD_LETTERED` follows.
- **One transaction.** The item leaves the queue and enters the dead-letter queue atomically, so it is never in both. `ITEM_FAILED` follows with the real code, then, right after it, `ITEM_DEAD_LETTERED` with `errorCode: 'DEAD_LETTERED'` and `metadata.lastErrorCode` set to the real code.
- **On upgrade.** Items that had failed permanently under an earlier version are moved here when the upgraded library first opens its store.
- **Same id.** A newer dead letter with the same id replaces the older one.

> **Warning — The dead-letter queue is unbounded:** Nothing prunes it, and it counts toward no capacity cap, on either platform. Each dead letter keeps its request's headers and body until [`replayDeadLetter`](./functions.md#replaydeadletter), [`discardDeadLetter`](./functions.md#discarddeadletter), [`removeItem`](./functions.md#removeitem) or [`clearQueue`](./functions.md#clearqueue) deletes it. Discard what you do not replay.

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

A [retry decider](./functions.md#setretrydecider), when one is installed, comes first: for a failed attempt this policy would retry, it can retry with the policy's delay or its own, drop the item or dead-letter it, but never retry a failure the policy would not. Once it is consulted, the delay counts from its decision instead of the end of the attempt. The policy decides alone when no decider is installed or none answers in time, and always in a process with no JS, such as a cold worker or a BGTask launch.

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
  callbackTimeoutMs?: number;
}
```

| Property           | Type                                      | Required | Description                                                                                                                                                                                                       |
| ------------------ | ----------------------------------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `strategy`         | [`SyncStrategy`](./enums.md#syncstrategy) | Yes      | What the engine schedules on its own: `AUTOMATIC`, `MANUAL`, or `OPPORTUNISTIC`. It does not control `flush()`.                                                                                                   |
| `retryPolicy`      | [`RetryPolicy`](#retrypolicy)             | Yes      | Backoff policy.                                                                                                                                                                                                   |
| `batchSize`        | `number`                                  | No       | Most items one cycle dispatches. Default `25`. Android sends them concurrently, except with `defaultHeaders` restored from disk: then the first item goes alone, and a 401 or 403 on it releases the rest unsent. iOS sends them one at a time. The JS callbacks follow: on Android up to `batchSize` of them can be waiting for an answer at once. |
| `requestTimeoutMs` | `number`                                  | No       | Per-request timeout. Default `30000`. Its expiry is reported as `TIMEOUT`, and retried, on both platforms. iOS applies it as given. On Android it bounds the whole call, raised to 1 second when lower. The connection must also open within 10 s, and a read or write may stall for at most 30 s, whatever `requestTimeoutMs` is: those fixed socket timeouts report `TIMEOUT` too. Both platforms cap it at 2147483647 ms, about 24.8 days: see [Out-of-Range Numbers](#out-of-range-numbers). |
| `maxQueueSize`     | `number`                                  | No       | Most items the queue may hold. Default `10000`. A value below `1` is treated as `1`, so the cap cannot be removed, and the largest cap is 2147483647: see [Out-of-Range Numbers](#out-of-range-numbers). Android counts pending and in-flight items toward it, iOS counts `PENDING` items only. Dead letters count on neither. `enqueue`, `enqueueBatch` and `replayDeadLetter` are rejected once it is reached: see [Native Rejections](./errors.md#native-rejections) for how that surfaces. |
| `persistQueue`     | `boolean`                                 | No       | Stored but not used yet. The queue is always persisted.                                                                                                                                                           |
| `defaultHeaders`   | `Record<string, string>`                  | No       | Headers merged into every dispatched item. The [header resolver's](./functions.md#the-header-merge) headers override them, and per-item `headers` override both, with names matching case-insensitively. Encrypted at rest. Calling `configureSync` without them deletes the stored copy. The header resolver runs only with JS, so a credential a worker or BGTask needs belongs here. See [Cold-start configuration](../guides/background-sync.md#cold-start-configuration). |
| `minNetworkQuality` | [`NetworkQuality`](./enums.md#networkquality) | No | Lowest network quality at which a flush may run. Unset, the default, means no quality gate. `POOR` blocks only `OFFLINE`, and `OFFLINE` gates nothing, like leaving it unset. Below the minimum no cycle runs and `flush()` reports `NETWORK_QUALITY_TOO_LOW` on `SYNC_FAILED`. Without `healthCheckUrl` the quality never reaches `EXCELLENT`, so `EXCELLENT` then blocks every flush. See [The quality gate](../guides/connectivity-detection.md#the-quality-gate). |
| `healthCheckUrl`   | `string`                                  | No       | Absolute `http(s)` URL the engine sends a `HEAD` to before a cycle claims its first item. A final `2xx` is healthy; anything else skips the cycle with `HEALTH_CHECK_FAILED`. Also the only target of the network-quality measurement. The probes carry no `defaultHeaders`, so point it at an endpoint that answers without authentication. Unset, the default, means no health check and no measurement. See [Health check](../guides/connectivity-detection.md#health-check). |
| `healthCheckTtlMs` | `number`                                  | No       | How long a healthy result is reused, in ms. Default `60000`. `0` checks before every cycle. Ignored without `healthCheckUrl`. |
| `healthCheckBackoffMs` | `number`                              | No       | How long cycles are skipped after a failed health check, in ms. Default `30000`. `0` means no backoff, but automatic retry wakes still wait 1000 ms after a failure. Ignored without `healthCheckUrl`. |
| `callbackTimeoutMs` | `number`                                 | No       | How long native waits for an installed JS callback to answer, in ms: the [retry decider](./functions.md#setretrydecider), the [header resolver](./functions.md#setheaderresolver), [`beforeDispatch`](./functions.md#setbeforedispatch) and [`afterDispatch`](./functions.md#setafterdispatch) share it. Default `3000`. Fractions truncate, and it is capped at 2147483647 ms when used. Without an answer in time, the decider leaves the decision to the policy, the resolver and `beforeDispatch` defer the item with `INTERCEPTOR_FAILED`, and `afterDispatch` is only logged. After an Android reload, the old runtime's installs cost this wait at each call until the new runtime first uses the library. |

`configureSync` validates the last five fields in JS, in table order and only when present: `minNetworkQuality` must be a [`NetworkQuality`](./enums.md#networkquality) value, `healthCheckUrl` an absolute `http(s)` URL, both health-check durations finite numbers of at least `0`, and `callbackTimeoutMs` a finite number of at least `1`. The first invalid field sets the code, `INVALID_URL` for `healthCheckUrl` and `INVALID_PAYLOAD` for the others, and `validationErrors` lists them all. Native checks `callbackTimeoutMs` again before it applies or stores anything. [`getSyncConfig`](./functions.md#getsyncconfig) returns these five only when they were set, as given, on both platforms: their defaults are not filled in. All five are persisted for background workers, and a stored value that cannot be used, which only a damaged record can hold, loads as unset.

#### Out-of-Range Numbers {#out-of-range-numbers}

JS does not check `batchSize`, `requestTimeoutMs`, `maxQueueSize` or the [`retryPolicy`](#retrypolicy) numbers. Both platforms convert any number, `NaN` and `Infinity` included, without failing the call, and fractions truncate toward zero:

| Field | `NaN`, `-Infinity` and low values | `Infinity` and high values |
| --- | --- | --- |
| `batchSize`, `maxQueueSize`, `retryPolicy.maxAttempts` | Below `1`, `NaN` included, reads as `1`. | 2147483647 or more reads as 2147483647. |
| `requestTimeoutMs` | `NaN` and `-Infinity` read as 1000 ms. Android raises any value below 1000 ms to 1000 ms. iOS uses any other value as given. | Above 2147483647 ms, about 24.8 days, reads as 2147483647 ms. |
| `retryPolicy.baseDelayMs` | `NaN` and negative values give no delay. | A computed delay stops at 2^53 - 1 ms. |
| `retryPolicy.maxDelayMs` | `NaN` makes every delay `0`. Android never caps below `baseDelayMs`. On iOS, `0` or less, `-Infinity` included, means no cap. | No cap below 2^53 - 1 ms. |
| Each `retryOnStatusCodes` entry | An entry that is not finite matches no status. | Matches no status. |

The configuration is saved as the call gave it, `NaN` and `Infinity` included, and converted where it is used. A cold start, a worker or a BGTask therefore uses the same values as the call, and [`getSyncConfig`](./functions.md#getsyncconfig) returns the numbers as given. A retry deadline never passes the largest valid JavaScript `Date`: see [`SyncItem.nextRetryAt`](#syncitem).

> **iOS:** A v0.2 build cannot read back a saved configuration that holds a fractional `retryPolicy` number, or a number that is not finite or is 2^63 or more, and falls back to its defaults. See [Rolling Back](../migration/v0-3-0.md#rolling-back-from-v03).

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

| Property                   | Type      | Required | Platform | Description                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| -------------------------- | --------- | -------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `minimumIntervalMs`        | `number`  | Yes      | Both     | Android: the period of the periodic work, raised to 15 minutes when lower. iOS: only the refresh task's earliest begin date, at least 60 seconds after the submit. The processing task has none. Both platforms cap it at 2^53 - 1 ms, about 285,000 years, give `NaN` and `-Infinity` the floor, and save the value as given. |
| `requiresCharging`         | `boolean` | No       | Both     | When `true`, only run while plugged in. On iOS this applies to the processing task only, not to the app-refresh task.                                                                                                                                                                                                                        |
| `requiresUnmeteredNetwork` | `boolean` | No       | Android  | When `true`, only run on unmetered networks. Android only: iOS does not apply it.                                                                                                                                                                                                                                                            |
| `requiresDeviceIdle`       | `boolean` | No       | Android  | When `true`, only run while the device is idle. The periodic runs and the Android retry kick wait for an idle device, and a run that fails as a whole is retried with WorkManager's default backoff, exponential from 30 seconds and at most 5 hours, once the device is idle again. iOS does not apply it. |
| `foregroundOnly`           | `boolean` | No       | Both     | Kill-switch for background work. When `true`, the library schedules **no** background dispatch (no `BGTaskScheduler` tasks on iOS, no `WorkManager` periodic or one-time flush jobs on Android), cancels anything it had already scheduled, and iOS stops using the background `URLSession`. The in-process retry timer keeps running, and launch/boot recovery is **not** suppressed. Defaults to `false`. Persists across relaunches. See [Background sync guide](../guides/background-sync.md#foreground-only-mode). |
| `taskIdentifier`           | `string`  | No       | Both     | Stored but not used yet. Both platforms use fixed identifiers. On iOS, `Info.plist` must declare the two fixed identifiers whatever this field says: see [iOS Setup](../getting-started/ios-setup.md).                                                                                                                                       |

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

| Property       | Type                     | Required | Platform | Description                                                                                                                                                                              |
| -------------- | ------------------------ | -------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `startedAt`    | `number`                 | Yes      | Both     | Unix ms when the run started. `0` on Android when `flush()` ran no cycle.                                                                                                                |
| `finishedAt`   | `number`                 | Yes      | Both     | Unix ms when the run finished. `0` on Android when `flush()` ran no cycle.                                                                                                               |
| `successCount` | `number`                 | Yes      | Both     | Items dispatched successfully.                                                                                                                                                           |
| `failureCount` | `number`                 | Yes      | Both     | Items that failed permanently in this cycle. Each is now in the [dead-letter queue](#syncdeadletteritem), except one that `removeItem` or `clearQueue` purged while its request was in flight, which counts here but is not dead-lettered, and one a [retry decider](./functions.md#setretrydecider) dropped, which counts here and is deleted. An item waiting for a retry, or deferred by a dispatch interceptor, counts as neither a success nor a failure. |
| `succeededIds` | `string[]`               | Yes      | Android  | ULIDs of successful items. Always empty on iOS.                                                                                                                                          |
| `failedIds`    | `string[]`               | Yes      | Android  | ULIDs of failed items, a retry decider's `DROP` and `DEAD_LETTER` included. Always empty on iOS.                                                                                         |
| `errors`       | `Record<string, string>` | Yes      | Android  | Failed-item id to `"<SyncErrorCode>: <detail>"`, so split on the first `:` to get the code, such as `RETRY_DECIDER_REJECTED` for an item the retry decider dropped or dead-lettered. Always empty on iOS: use `ITEM_FAILED` events there. |

A `flush()` that runs no cycle still resolves with a `SyncResult`. Nothing is dispatched, no item changes, no `SYNC_STARTED` is emitted and no history row is written. That happens when:

- the engine is paused, or stored `defaultHeaders` cannot be read: no event is emitted at all;
- the network quality is below [`minNetworkQuality`](#syncoptions): `SYNC_FAILED` with `errorCode: 'NETWORK_QUALITY_TOO_LOW'`;
- the health check fails, or its backoff is running: `SYNC_FAILED` with `errorCode: 'HEALTH_CHECK_FAILED'`.

Both counts are `0`, and the result does not say why. Android sets `startedAt` and `finishedAt` to `0`. iOS sets both to the time the check stopped the flush, which can be a moment after the call while the gate waits for the link or the health check's request runs, so there the result alone cannot be told apart from an empty cycle: only the missing `SYNC_STARTED` event and history row show that no cycle ran. On iOS, a `flush()` that joins a running cycle returns the last finished cycle's result, or a 0/0 one, without the gate or the health check.

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
| `errorCode`        | [`SyncErrorCode`](./errors.md#syncerrorcode)      | No       | Present on `ITEM_FAILED` and `ITEM_RETRYING`, and `DEAD_LETTERED` on `ITEM_DEAD_LETTERED`. `RETRY_DECIDER_REJECTED` on the `ITEM_FAILED` of a retry decider's `DROP` or `DEAD_LETTER`, and `INTERCEPTOR_FAILED` on the `ITEM_RETRYING` of an item a dispatch interceptor deferred. On `SYNC_FAILED` only when the cycle itself [died](../guides/error-handling.md#when-a-cycle-dies), or when the quality gate (`NETWORK_QUALITY_TOO_LOW`) or the health check (`HEALTH_CHECK_FAILED`) skipped it: a [cancelled cycle's](../guides/error-handling.md#cancelled-cycles) `SYNC_FAILED` has none. Never set on `BACKGROUND_SYNC_*` events. |
| `statusCode`       | `number`                                          | No       | HTTP status code, present on dispatch-related events when available.   |
| `attempt`          | `number`                                          | No       | 1-indexed attempt number, present on `ITEM_SUCCEEDED`, `ITEM_FAILED` and `ITEM_RETRYING`. On an `ITEM_RETRYING` with `INTERCEPTOR_FAILED`, the attempt that would have been made: no request went out. |
| `connectionStatus` | [`ConnectionStatus`](./enums.md#connectionstatus) | No       | Present on `CONNECTION_CHANGED`. Unchanged when only the network quality changed: read [`getConnectionStatus`](./functions.md#getconnectionstatus) for the new value. |
| `metadata`         | `Record<string, string>`                          | No       | Engine diagnostics. On both platforms, `ITEM_DEAD_LETTERED` sets `lastErrorCode`, the code the item failed with. iOS also sets diagnostics on cycle-level events, such as attempted and succeeded counts, and on the `BACKGROUND_SYNC_*` events (`kind`, and `ok: "false"` when the window's flush throws or the window [runs out of time](../guides/background-sync.md#when-a-window-runs-out-of-time)). This is **not** [`SyncItem.metadata`](#syncitem), which is never echoed on events. |

---

## Callback Types

The context, decision and result shapes of the [retry decider](./functions.md#setretrydecider) and the [dispatch interceptors](./functions.md#dispatch-interceptors), and the function types the `set…` functions accept. The contexts cross the native bridge, so their fields are flat and their records hold strings only. Each `metadata` is the item's own [`SyncItemInput.metadata`](#synciteminput).

### `RetryDeciderContext`

What the retry decider receives for a failed attempt.

```ts
interface RetryDeciderContext {
  itemId: string;
  attempt: number;
  errorCode: SyncErrorCode;
  statusCode?: number;
  url: string;
  method: HttpMethod;
  metadata?: Record<string, string>;
}
```

| Property     | Type                                         | Required | Description                                                                                 |
| ------------ | -------------------------------------------- | -------- | ------------------------------------------------------------------------------------------- |
| `itemId`     | `string`                                     | Yes      | ULID of the item whose attempt failed.                                                      |
| `attempt`    | `number`                                     | Yes      | The 1-based attempt that just failed, the number its `ITEM_RETRYING` or `ITEM_FAILED` carries. |
| `errorCode`  | [`SyncErrorCode`](./errors.md#syncerrorcode) | Yes      | That attempt's code, for example `SERVER_ERROR` or `TIMEOUT`.                               |
| `statusCode` | `number`                                     | No       | The HTTP status, present only when an HTTP answer came back.                                |
| `url`        | `string`                                     | Yes      | The item's URL.                                                                             |
| `method`     | [`HttpMethod`](./enums.md#httpmethod)        | Yes      | The item's HTTP method.                                                                     |
| `metadata`   | `Record<string, string>`                     | No       | The item's metadata, when it has any.                                                       |

### `RetryDecision`

What the retry decider answers.

```ts
interface RetryDecision {
  action: RetryAction;
  delayMs?: number;
}
```

| Property  | Type                                    | Required | Description |
| --------- | --------------------------------------- | -------- | ----------- |
| `action`  | [`RetryAction`](./enums.md#retryaction) | Yes      | `RETRY`, `DROP` or `DEAD_LETTER`. |
| `delayMs` | `number`                                | No       | For `RETRY` only: the delay before the next attempt, replacing the policy's. It counts from the decision and is used as given, with no jitter and no `maxDelayMs` cap, but at most 2^53 - 1 ms and never past the largest JavaScript `Date`. Fractions truncate. Missing, not finite or negative, the policy's delay applies; native logs an unusable value as a warning. |

Any other answer, such as a missing or unknown `action`, is invalid, and the policy decides.

### `RetryDecider`

The function [`setRetryDecider`](./functions.md#setretrydecider) accepts. It may be sync or async.

```ts
type RetryDecider = (
  context: RetryDeciderContext
) => RetryDecision | Promise<RetryDecision>;
```

### `HeaderResolverContext`

What the header resolver receives, right before a request is sent.

```ts
interface HeaderResolverContext {
  itemId: string;
  attempt: number;
  url: string;
  method: HttpMethod;
  metadata?: Record<string, string>;
}
```

| Property   | Type                                  | Required | Description                               |
| ---------- | ------------------------------------- | -------- | ----------------------------------------- |
| `itemId`   | `string`                              | Yes      | ULID of the item about to be sent.        |
| `attempt`  | `number`                              | Yes      | The 1-based attempt about to be made.     |
| `url`      | `string`                              | Yes      | The item's URL.                           |
| `method`   | [`HttpMethod`](./enums.md#httpmethod) | Yes      | The item's HTTP method.                   |
| `metadata` | `Record<string, string>`              | No       | The item's metadata, when it has any.     |

### `HeaderResolver`

The function [`setHeaderResolver`](./functions.md#setheaderresolver) accepts. It returns a plain object whose values are all strings, or a promise of one: see [The Header Merge](./functions.md#the-header-merge) for what is valid.

```ts
type HeaderResolver = (
  context: HeaderResolverContext
) => Record<string, string> | Promise<Record<string, string>>;
```

### `BeforeDispatchContext`

What the `beforeDispatch` hook receives: the request as it will be sent, after the header resolver. The body is not included.

```ts
interface BeforeDispatchContext {
  itemId: string;
  attempt: number;
  url: string;
  method: HttpMethod;
  headers: Record<string, string>;
  contentType?: string;
  metadata?: Record<string, string>;
}
```

| Property      | Type                                  | Required | Description |
| ------------- | ------------------------------------- | -------- | ----------- |
| `itemId`      | `string`                              | Yes      | ULID of the item about to be sent. |
| `attempt`     | `number`                              | Yes      | The 1-based attempt about to be made. |
| `url`         | `string`                              | Yes      | The item's URL. |
| `method`      | [`HttpMethod`](./enums.md#httpmethod) | Yes      | The item's HTTP method. |
| `headers`     | `Record<string, string>`              | Yes      | The merged headers: `defaultHeaders`, then the resolver's, then the item's own, names matching case-insensitively. It leaves out the `Content-Type` the engine adds when no layer sets one. On Android the body's media type replaces a layer's `Content-Type` on the wire, so this map can show one the request does not carry. |
| `contentType` | `string`                              | No       | The item's `contentType`, as stored. |
| `metadata`    | `Record<string, string>`              | No       | The item's metadata, when it has any. |

### `BeforeDispatchResult`

What the `beforeDispatch` hook answers to ask for something. Any value without an `action` key, nothing included, means `PROCEED`.

```ts
interface BeforeDispatchResult {
  action: BeforeDispatchAction;
}
```

| Property | Type                                                      | Required | Description |
| -------- | --------------------------------------------------------- | -------- | ----------- |
| `action` | [`BeforeDispatchAction`](./enums.md#beforedispatchaction) | Yes      | `PROCEED` sends the request; `SKIP` defers the item. Any other value is a failure, which defers it too. |

### `BeforeDispatchHook`

The function [`setBeforeDispatch`](./functions.md#setbeforedispatch) accepts. It may return a result, nothing, or a promise of either.

```ts
type BeforeDispatchHook = (
  context: BeforeDispatchContext
) => BeforeDispatchResult | void | Promise<BeforeDispatchResult | void>;
```

### `AfterDispatchContext`

What the `afterDispatch` hook receives for a request that went out. It never carries headers or a body.

```ts
interface AfterDispatchContext {
  itemId: string;
  attempt: number;
  outcome: DispatchOutcome;
  statusCode?: number;
  errorCode?: SyncErrorCode;
  url: string;
  method: HttpMethod;
  metadata?: Record<string, string>;
  durationMs: number;
}
```

| Property     | Type                                            | Required | Description |
| ------------ | ----------------------------------------------- | -------- | ----------- |
| `itemId`     | `string`                                        | Yes      | ULID of the item that was sent. |
| `attempt`    | `number`                                        | Yes      | The 1-based attempt that was made. |
| `outcome`    | [`DispatchOutcome`](./enums.md#dispatchoutcome) | Yes      | `SUCCEEDED`, `RETRYING` or `FAILED`. |
| `statusCode` | `number`                                        | No       | The HTTP status, present when an HTTP answer came back. |
| `errorCode`  | [`SyncErrorCode`](./errors.md#syncerrorcode)    | No       | The code the item's event carried, present unless the outcome is `SUCCEEDED`: `UNAUTHORIZED` for a credential hold, `RETRY_DECIDER_REJECTED` after a retry decider's `DROP` or `DEAD_LETTER`. |
| `url`        | `string`                                        | Yes      | The item's URL. |
| `method`     | [`HttpMethod`](./enums.md#httpmethod)           | Yes      | The item's HTTP method. |
| `metadata`   | `Record<string, string>`                        | No       | The item's metadata, when it has any. |
| `durationMs` | `number`                                        | Yes      | The request's own duration in whole milliseconds, without any callback wait. |

### `AfterDispatchHook`

The function [`setAfterDispatch`](./functions.md#setafterdispatch) accepts. It may return anything, or a promise of anything, and the result is ignored, so an expression body such as `(c) => list.push(c)` is fine.

```ts
type AfterDispatchHook = (context: AfterDispatchContext) => unknown;
```

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
| `isInternetReachable` | `boolean`                                         | No       | Whether the link reaches the internet, as the OS reports it: `NET_CAPABILITY_VALIDATED` on the default network on Android, a satisfied `NWPath` on iOS. Absent while the state is not known yet. |
| `isExpensive`         | `boolean`                                         | No       | Mirrors `NWPath.isExpensive` (iOS) / metered status (Android). |

> **Warning — `networkQuality` is required:** Code that only reads `ConnectionState` is unaffected, but a hand-built value, such as a test fixture or a mock of `getConnectionStatus`, must now set it. See the [v0.2 → v0.3 migration](../migration/v0-3-0.md).

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

## See Also

- [Enums](./enums.md) -- The enums these types use
- [Errors](./errors.md) -- `SyncError`, `SyncErrorCode` and `ValidationIssue`
- [Functions](./functions.md) -- The calls that take and return these types
- [Platform Comparison](../production/platform-comparison.md) -- Which fields each platform fills, and how their values differ
