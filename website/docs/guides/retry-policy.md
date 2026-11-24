---
sidebar_position: 3
title: Retry Policy
description: Configure retry behavior in react-native-sync-provider — maxAttempts, backoff curves (linear, exponential, fibonacci), jitter, retry-on-status-codes, and the JS retry decider.
keywords:
  - retry-policy
  - retry-decider
  - backoff
  - exponential-backoff
  - fibonacci
  - jitter
  - max-attempts
  - max-attempts-exceeded
---

# Retry Policy

This guide explains how failed requests are retried: the retry budget, the backoff curves, jitter, which status codes are retried, and what happens when the budget runs out.

## Overview

Every queued item carries a retry budget. When a retriable dispatch fails, the item waits out a cooldown from the configured backoff curve, then a later cycle tries again, up to `maxAttempts` attempts in total. After the budget is exhausted, the item fails permanently with `MAX_ATTEMPTS_EXCEEDED`, is reported on `ITEM_FAILED`, and moves to the [dead-letter queue](#permanent-failures).

While JS runs, an optional [retry decider](#retry-decider) can change that decision for each failed attempt: another delay, a drop or a dead letter.

## `RetryPolicy`

| Field                | Type              | Description                                                                                                            |
| -------------------- | ----------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `maxAttempts`        | `number`          | Total attempt budget. The first attempt counts as one.                                                                 |
| `backoff`            | `BackoffStrategy` | Curve used to compute the delay between attempts.                                                                      |
| `baseDelayMs`        | `number`          | Base delay (in ms) used by the curve.                                                                                  |
| `maxDelayMs`         | `number`          | Hard cap on the per-attempt delay (in ms). Use a positive value: iOS treats `0` as no cap, and Android caps at `baseDelayMs`. |
| `jitter`             | `boolean`         | Multiply the computed delay by a uniformly distributed equal-jitter factor in `[0.75, 1.25)` (±25%).                   |
| `retryOnStatusCodes` | `number[]`        | HTTP status codes that should trigger a retry. Network errors and timeouts are always retried regardless of this list. |

A computed delay never exceeds 2^53 - 1 ms, and a retry deadline never passes 8,640,000,000,000,000 ms, the largest valid JavaScript `Date`, so even an `Infinity` delay leaves the item waiting instead of due at once. For `NaN`, `Infinity` and other out-of-range values, see [Out-of-Range Numbers](../api-reference/types.md#out-of-range-numbers).

## Backoff Strategies

### `LINEAR`

`delay = baseDelayMs * attempt` (capped at `maxDelayMs`).

| Attempt | `baseDelayMs = 1000` |
| ------- | -------------------- |
| 1       | 1000 ms              |
| 2       | 2000 ms              |
| 3       | 3000 ms              |
| 4       | 4000 ms              |
| 5       | 5000 ms              |

Use for predictable, slow-growing retry windows.

### `EXPONENTIAL` (Default)

`delay = baseDelayMs * 2^(attempt - 1)` (capped at `maxDelayMs`).

| Attempt | `baseDelayMs = 1000`, `maxDelayMs = 60000` |
| ------- | ------------------------------------------ |
| 1       | 1000 ms                                    |
| 2       | 2000 ms                                    |
| 3       | 4000 ms                                    |
| 4       | 8000 ms                                    |
| 5       | 16000 ms                                   |
| 6       | 32000 ms                                   |
| 7       | 60000 ms (capped)                          |

The standard choice for transient server-side errors. Doubles the wait every attempt.

### `FIBONACCI`

`delay = baseDelayMs * fib(attempt)` (capped at `maxDelayMs`).

| Attempt | `baseDelayMs = 1000` |
| ------- | -------------------- |
| 1       | 1000 ms              |
| 2       | 1000 ms              |
| 3       | 2000 ms              |
| 4       | 3000 ms              |
| 5       | 5000 ms              |
| 6       | 8000 ms              |
| 7       | 13000 ms             |

Smoother growth than `EXPONENTIAL` -- useful when you want more retries in the first minute without ballooning to multi-minute waits.

## Jitter

When `jitter: true`, the computed delay is multiplied by a uniformly distributed equal-jitter factor in `[0.75, 1.25)` (±25%). A computed delay of `4000 ms` becomes a real delay anywhere in `[3000, 5000) ms`. The cap applies after the jitter, so a delay never exceeds `maxDelayMs`. This prevents synchronized retry storms when many devices come back online at the same time. The factor is aligned across JS, iOS, and Android.

> **Tip — Always enable jitter in production:** Without jitter, every device that lost connectivity at the same time hammers your backend on the same retry tick. Jitter is the cheapest, most effective retry-storm mitigation available.

## `retryOnStatusCodes`

Retries are triggered by:

- **Network errors** (DNS, TCP, TLS) -- always retried.
- **Timeouts** (exceeding `requestTimeoutMs`) -- always retried.
- **HTTP responses** whose status code is in `retryOnStatusCodes`.

Default value:

```ts
retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504];
```

| Code | Meaning               | Why retry                                             |
| ---- | --------------------- | ----------------------------------------------------- |
| 408  | Request Timeout       | Server-side timeout; client may succeed on retry.     |
| 425  | Too Early             | Server replayed an early-data request; safe to retry. |
| 429  | Too Many Requests     | Rate-limited; back off and retry.                     |
| 500  | Internal Server Error | Often transient.                                      |
| 502  | Bad Gateway           | Upstream proxy issue.                                 |
| 503  | Service Unavailable   | Often transient (deploys, restarts).                  |
| 504  | Gateway Timeout       | Upstream timeout.                                     |

Status codes **not** in the list are permanent failures, with the matching `SyncErrorCode`: `UNAUTHORIZED` for 401/403 and `SERVER_ERROR` for the rest. 401 and 403 stay permanent even when listed. The exception is a [process running on stored headers](./background-sync.md#when-a-stored-token-expires), where a 401 or 403 holds the item instead.

## Permanent Failures

An item fails permanently when it hits a non-retryable failure, or a retriable one with no attempts left, or when a [retry decider](#retry-decider) answers `DROP` or `DEAD_LETTER`. Listen to `ITEM_FAILED`, which works on both platforms, and to `ITEM_DEAD_LETTERED`, which follows it except after a `DROP`:

```ts
import {
  useSyncEvents,
  SyncEventType,
} from '@gabriel-sisjr/react-native-sync-provider';

useSyncEvents({
  types: [SyncEventType.ITEM_FAILED, SyncEventType.ITEM_DEAD_LETTERED],
  onEvent: (e) => console.warn(`item ${e.itemId}: ${e.type} ${e.errorCode}`),
});
```

`MAX_ATTEMPTS_EXCEEDED` applies only to a retriable failure with no attempts left. A non-retryable failure keeps its real code, including on the last attempt, so a 401 stays `UNAUTHORIZED`. For an exhausted item, the `ITEM_RETRYING` events before it carry the failures that led there.

Lowering `maxAttempts` can leave items whose budget is already spent. The next cycle dead-letters them without sending a request: `ITEM_FAILED` with `MAX_ATTEMPTS_EXCEEDED`, the stored attempt count and no `statusCode`, then `ITEM_DEAD_LETTERED`, with `lastErrorMessage` set to `Retry budget exhausted`. A `maxAttempts` below `1` reads as `1`, so a new item is always sent once.

In one transaction, the item leaves the queue and enters the dead-letter queue, with its request, its attempt count, the last error code and message, and the time it was moved. Then `ITEM_FAILED` is emitted with the real code and, right after it, `ITEM_DEAD_LETTERED` with `errorCode: 'DEAD_LETTERED'` and `metadata.lastErrorCode` set to that code. After a decider's `DEAD_LETTER`, that code is `RETRY_DECIDER_REJECTED`, and `lastErrorMessage` keeps the failure it decided on. A decider's `DROP` is the one exception to the move: the item is deleted, with `ITEM_FAILED` alone. Nothing retries a dead letter on its own:

- [`listDeadLetter`](../api-reference/functions.md#listdeadletter) reads them, oldest first.
- [`replayDeadLetter`](../api-reference/functions.md#replaydeadletter) puts one back as a fresh item, with `attempts: 0` and `nextRetryAt: 0`. It counts toward `maxQueueSize` like an enqueue.
- [`discardDeadLetter`](../api-reference/functions.md#discarddeadletter) or [`removeItem`](../api-reference/functions.md#removeitem) deletes one, and [`clearQueue`](../api-reference/functions.md#clearqueue) deletes them all with the queue.

See [Dead-letter flows](./error-handling.md#dead-letter-flows) for the usual patterns.

> **Warning — The dead-letter queue is unbounded:** Dead letters count toward no capacity cap, on either platform, and nothing prunes them. Each one keeps its headers and body. Discard what you will not replay, especially if your app runs for long periods offline or against a failing endpoint.

Before v0.3, a permanently failed item stayed in the queue store, and Android counted it toward `maxQueueSize`. The upgrade moves those rows to the dead-letter queue.

## Retry Cooldown Across Restarts

**Added in v0.2.** A retriable failure with attempts left puts the item back to `PENDING` with a persisted **`nextRetryAt`**: the clock read after the attempt finished, plus the backoff delay, capped at the largest valid JavaScript `Date`. The dispatcher skips the item until the device clock reaches that deadline, on both platforms and across process death and device reboot. Measuring from the end of the attempt means a slow request or a timeout does not eat into the cooldown. When a [retry decider](#retry-decider) is consulted, the delay counts from its decision instead: see [Delays Count From the Decision](#delays-count-from-the-decision).

[`SyncItem.nextRetryAt`](../api-reference/types.md#syncitem) tells you where an item stands:

- `0` means it is eligible now. It does not mean the item was never attempted: rows carried over from v0.1, and an iOS row whose first attempt was cut short by a process kill, can show `attempts >= 1` with `nextRetryAt: 0`.
- A positive value means a retry cooldown, a [credential hold](./background-sync.md#when-a-stored-token-expires) or an [interceptor deferral](./dispatch-interceptors.md#when-an-interceptor-fails) is pending. It is eligible once `nextRetryAt <= now`. The value stays in place afterwards, so a timestamp in the past means the wait has elapsed.

Each cycle makes at most one attempt per item, and `attempts` counts the attempts recorded across cycles, so the retry budget survives a restart. `0` means no attempt has been recorded, not that the item was never tried. Android records an attempt with its outcome, so a process kill mid-request leaves the count unchanged, while iOS counts it when the dispatch starts, and refunds it when a [BGTask expiry](./background-sync.md#when-a-window-runs-out-of-time) cancels the request. A credential hold spends no attempt, and neither does an interceptor deferral, so a held or deferred item can show `attempts: 0` with `nextRetryAt > 0`.

A cooldown, a hold and a deferral look the same on a `SyncItem`, which carries neither the last error code nor the last attempt time. An `ITEM_RETRYING` event with `errorCode: 'UNAUTHORIZED'` marks a hold, and one with `INTERCEPTOR_FAILED` marks a deferral.

> **Note — Capacity vs. eligibility:** A cooling item still counts toward `getQueueSize()` and the `maxQueueSize` cap — it is _in_ the queue, just not _dispatchable right now_. Cooldowns also survive launch recovery: resetting in-flight items back to pending does **not** clear their `nextRetryAt`.

### Who Wakes the Queue, and When

Nothing re-dispatches an item before its `nextRetryAt`. These are the triggers that can run a cycle:

| Trigger                  | Platform | When it fires                                                                                                                       | Stopped by                                                                                                       |
| ------------------------ | -------- | ----------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| **Retry timer**          | Both     | In-process and single-shot, at the soonest cooldown. Armed at the end of every cycle, on engine start after recovery, on `configureSync` and on `resumeSync`. | `pauseSync`, `strategy: MANUAL`. **Not** `foregroundOnly`, because it is in-process. |
| **Retry kick**           | Android  | A delayed one-time `WorkManager` job, armed with the timer for the same deadline, so a retry happens even if the process dies. It honors the same constraints as the periodic work. | Runs only with `enableBackgroundSync` active, `foregroundOnly` off, `strategy` other than `MANUAL` and the engine not paused. `disableBackgroundSync()` cancels it. |
| **Periodic worker**      | Android  | OS-driven, at least 15 minutes apart.                                                                                               | `foregroundOnly`, `disableBackgroundSync()`, `pauseSync`. **Not** `MANUAL`.                                      |
| **BGTask**               | iOS      | OS-driven, whenever iOS grants a window. iOS has no kick: this is its background path.                                              | `foregroundOnly`, `disableBackgroundSync()`, `pauseSync`. **Not** `MANUAL`.                                      |
| **Your own code**        | Both     | `flush()`, [`drainUntilEmpty()`](../api-reference/functions.md#drainuntilempty), [`useAutoSync`](../api-reference/hooks/useAutoSync.md). | `pauseSync`.                                                                                                     |

What the timer does and does not do:

- **It never flushes offline.** Without `minNetworkQuality`, neither dispatcher checks connectivity. An offline cycle can spend attempts on Android, until valid items reach the dead-letter queue. On iOS it waits for the network, with its first item claimed and one attempt spent, and dead-letters nothing. When the timer fires offline, it remembers the wake and flushes when connectivity returns. A manual `flush()` while offline keeps its behavior unless `minNetworkQuality` is set.
- **It holds below the quality gate, and during a health-check backoff.** With [`minNetworkQuality`](./connectivity-detection.md#the-quality-gate) set, the timer checks the gate first and holds the wake silently, with no event, until the quality reaches the minimum. It re-checks at 30, 60, 120, 240 and 480 seconds, then every 15 minutes, restarting that backoff when a wake passes, the network changes or the timer is armed again, as every `configureSync` does. See [Held wakes](./connectivity-detection.md#held-wakes).
- **It only exists in a process with JS.** A worker, a boot broadcast, or an iOS background launch where JS never reaches the library arms no timer. Android relies on the kick there.
- **It looks at items with a deadline.** Only items with `nextRetryAt > 0`, a cooldown, a credential hold or an interceptor deferral, arm it. Items with `nextRetryAt: 0` do not, so it does not replace a flush for new items, though the cycle it starts takes them too. `resumeSync` is the exception: it considers every pending item, so items with no deadline are dispatched at once. On iOS, the timer re-armed after a failed health check also considers every `PENDING` item, cooldown or not.
- **On iOS it cannot fire while the app is suspended.** It fires on resume.
- **It waits after a cycle that dies.** See [The Failure Hold](#the-failure-hold).

That owed wake is the only native flush tied to connectivity. There is no native flush on enqueue, and none on reconnect by itself: [`useAutoSync`](../api-reference/hooks/useAutoSync.md) is the mechanism.

`SyncWorker` reports `Result.success()` on any completed flush, so WorkManager's own backoff is reserved for a worker that fails as a whole, a cycle that dies included, and cannot race the cooldown. That backoff is exponential from 1 minute, or, with `requiresDeviceIdle`, WorkManager's default: exponential from 30 seconds, at most 5 hours, once the device is idle again. A worker that WorkManager stops does not report `Result.retry()`, so this backoff does not apply to it.

After a [rejected stored credential](./background-sync.md#when-a-stored-token-expires), the retry timer and the Android retry kick wait for the hold to end, even if other items are already due. The periodic worker and BGTasks do not wait for it, and a process restart drops it.

After a failed [health check](./connectivity-detection.md#health-check), the next retry wake waits for its backoff to end too, and for at least 1000 ms after the failure, even with `healthCheckBackoffMs: 0`. The next wake is therefore the latest of the item's own deadline, the credential hold, the end of the health-check backoff with that one-second floor, and a running [failure hold](#the-failure-hold).

#### The Failure Hold {#the-failure-hold}

After a [cycle that dies](./error-handling.md#when-a-cycle-dies), for example because the queue's storage keeps failing, automatic wakes do not start the next cycle at once. They wait 30 seconds from the failed cycle's end, doubling for each cycle in a row that dies, up to 15 minutes:

- **Held.** The retry timer and the Android retry kick, whatever arms them: the end of a cycle, `configureSync`, an engine start and its recovery, a health-check skip.
- **Not held.** `flush()`, [`drainUntilEmpty()`](../api-reference/functions.md#drainuntilempty), [`useAutoSync`](../api-reference/hooks/useAutoSync.md), the Android periodic worker and the iOS BGTask windows. A reconnect delivers at once a wake that fell due while offline.
- **`resumeSync()`.** It sends what is due at once, without waiting for the failure hold, and leaves the hold as it was. It still waits for a credential hold and a health-check backoff.
- **Ended.** A cycle that completes, an empty one included, ends the hold and starts the steps over. A [cancelled cycle](./error-handling.md#cancelled-cycles) neither starts nor ends it.
- **In memory.** The hold never creates a wake of its own, and a new process starts again at 30 seconds.

### `foregroundOnly` Interaction

When [`BackgroundSyncOptions.foregroundOnly`](../api-reference/types.md#backgroundsyncoptions) is `true`, the OS-scheduled wakes are suppressed: the Android retry kick, the periodic worker and BGTasks. Cooldowns are still **persisted and honored**, and acted upon while the app is alive: by the retry timer, which this flag does not stop, and by `flush()`, `useAutoSync` and [`drainUntilEmpty`](../api-reference/functions.md#drainuntilempty). Data-integrity recovery still runs.

> **Tip — Draining cooling items on demand:** [`drainUntilEmpty`](../api-reference/functions.md#drainuntilempty) sleeps until the soonest `nextRetryAt` by itself, so you do not need `delayBetweenCyclesMs` for this. If that moment is past `timeoutMs`, it stops at once with `TIMEOUT`, so raise `timeoutMs` to wait out a long backoff.

## Configuration Example

```ts
import {
  configureSync,
  SyncStrategy,
  BackoffStrategy,
} from '@gabriel-sisjr/react-native-sync-provider';

await configureSync({
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy: {
    maxAttempts: 7,
    backoff: BackoffStrategy.EXPONENTIAL,
    baseDelayMs: 500,
    maxDelayMs: 5 * 60 * 1000,
    jitter: true,
    retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
  },
});
```

## Retry Decider {#retry-decider}

**Added in v0.4.** While JS runs, a retry decider installed with [`setRetryDecider`](../api-reference/functions.md#setretrydecider) can change what the policy does with a failed attempt: retry after another delay, drop the item, or dead-letter it. It cannot retry a failure the policy would not retry, and it cannot add attempts. When it answers in time, its decision wins. The policy decides whenever it cannot: see [When the Policy Decides Instead](#when-the-policy-decides-instead).

### When It Is Consulted

Only after an attempt fails with a retryable error and attempts are left: a network error, a timeout, or a status in `retryOnStatusCodes`. It is never consulted for:

- a success;
- a failure that is not retried, such as a `404` missing from `retryOnStatusCodes`, or a 401 or 403 in a process that called `configureSync`;
- an attempt that spent the last of the budget;
- a [credential hold](./background-sync.md#when-a-stored-token-expires), the 401 or 403 to stored `defaultHeaders`;
- an item that was never sent.

It receives a `RetryDeciderContext`:

| Field | Type | Description |
| --- | --- | --- |
| `itemId` | `string` | The item's id. |
| `attempt` | `number` | The 1-based attempt that just failed, as on the `ITEM_RETRYING` or `ITEM_FAILED` that follows. |
| `errorCode` | `SyncErrorCode` | That attempt's code, such as `SERVER_ERROR`, `NETWORK_ERROR` or `TIMEOUT`. |
| `statusCode` | `number` | The HTTP status, only when a response came back. |
| `url` | `string` | The item's URL. |
| `method` | `HttpMethod` | The item's method. |
| `metadata` | `Record<string, string>` | The item's `metadata`, when it has any. |

### Actions

It answers a `RetryDecision`, `{ action, delayMs? }`, or a promise of one:

| `action` | What happens to the item | Events | Counted in |
| --- | --- | --- | --- |
| `RETRY` | Rescheduled. A valid `delayMs` replaces the policy's delay. | `ITEM_RETRYING`, with the attempt's real code | Neither total |
| `DEAD_LETTER` | Moved to the dead-letter queue with `lastErrorCode: RETRY_DECIDER_REJECTED`. Its `lastErrorMessage` keeps the real failure, such as `HTTP 503 Service Unavailable`, in each platform's wording. | `ITEM_FAILED` with `RETRY_DECIDER_REJECTED`, the attempt's `statusCode` and `attempt`, then `ITEM_DEAD_LETTERED` with `metadata.lastErrorCode: RETRY_DECIDER_REJECTED` | `failureCount` |
| `DROP` | Deleted. No dead letter is kept. | `ITEM_FAILED` with `RETRY_DECIDER_REJECTED`, the attempt's `statusCode` and `attempt`. No `ITEM_DEAD_LETTERED` and no `ITEM_REMOVED` | `failureCount`. On Android, also `failedIds` and `errors`, as `RETRY_DECIDER_REJECTED: …` |

> **Warning — `DROP` deletes the request for good:** It is the one way a failed item leaves without a dead letter, so nothing can replay it. Answer `DEAD_LETTER` for anything you may need again.

### `delayMs`

- **Read only with `RETRY`.** `DROP` and `DEAD_LETTER` ignore it.
- **Used as given.** A finite value of at least `0` replaces the policy's delay, truncated to whole milliseconds, with no jitter and no `maxDelayMs` cap. It is still capped at 2^53 - 1 ms, and the deadline at the largest valid JavaScript `Date`.
- **The policy's delay otherwise.** A missing `delayMs` means the policy's delay. So does one that is not finite or is negative, which is also logged as a warning.

### Delays Count From the Decision {#delays-count-from-the-decision}

When a decider is consulted, the delay, decided or the policy's, counts from the moment of the decision: its answer, its timeout or the fallback. Without a decider, it counts from the end of the attempt, as described in [Retry Cooldown Across Restarts](#retry-cooldown-across-restarts).

### When the Policy Decides Instead {#when-the-policy-decides-instead}

With no decider installed, or no JS running, as in a background launch, the policy decides silently: no log line and no event.

With a decider installed, the policy's own decision applies, with a native log line and no event, when:

- the decider throws, rejects, returns anything but `{ action, delayMs? }` with a known `action`, or does not answer within [`callbackTimeoutMs`](./dispatch-interceptors.md#callbacktimeoutms), 3000 ms by default;
- it was left behind by a JS runtime that is gone, or replaced by a newer runtime's installs. See [Reloads and Teardown](./dispatch-interceptors.md#reloads-and-teardown);
- a BGTask expires or the cycle is cancelled while it waits. The policy's retry is then written at once.

An item removed or cleared while the decider decides stays gone. A `DROP` or `DEAD_LETTER` for it still emits `ITEM_FAILED`, because its request was sent.

If the cycle [dies](./error-handling.md#when-a-cycle-dies) before the decision is written, the settle applies that decision without asking JS again, or the policy's when no decision was taken.

### Example

```ts
// src/sync/decideRetry.ts
import {
  RetryAction,
  SyncErrorCode,
  type RetryDecider,
} from '@gabriel-sisjr/react-native-sync-provider';

export const decideRetry: RetryDecider = ({
  attempt,
  errorCode,
  statusCode,
  metadata,
}) => {
  // Analytics is not worth a second retry.
  if (metadata?.kind === 'analytics' && attempt >= 2) {
    return { action: RetryAction.DROP };
  }
  // A maintenance window: wait five minutes instead of the backoff.
  if (statusCode === 503) {
    return { action: RetryAction.RETRY, delayMs: 5 * 60 * 1000 };
  }
  // Park requests that keep timing out, for a replay later.
  if (errorCode === SyncErrorCode.TIMEOUT && attempt >= 3) {
    return { action: RetryAction.DEAD_LETTER };
  }
  return { action: RetryAction.RETRY }; // the policy's delay
};
```

Install it with `setRetryDecider(decideRetry)`, among the first library calls at startup: see [Install Them First at Startup](./dispatch-interceptors.md#install-them-first-at-startup). `isRetryDeciderInstalled()` reports whether the calling JS runtime installed one, and `clearRetryDecider()` lets the policy decide alone again.

### Platform Notes

- **iOS** dispatches one item at a time, so a decider that is slow to answer delays each item it is asked about. **Android** asks for the items of a batch concurrently.
- **After a JS reload,** each consult falls back at once on iOS, and after waiting `callbackTimeoutMs` on Android, until the new JS first calls the library.
- **A `DROP`** shows in `SyncResult.failedIds` and `errors` on Android only. On iOS both are always empty.

## Next Steps

- [Error Handling](./error-handling.md) -- The full `SyncErrorCode` matrix
- [Dispatch Interceptors](./dispatch-interceptors.md) -- The header resolver, `beforeDispatch` and `afterDispatch`, and the timeout and lifecycle rules the retry decider shares with them
- [Background Sync](./background-sync.md) -- How retries interact with OS-scheduled fires
- [Crash Recovery](./crash-recovery.md) -- What recovery keeps of an item's attempts and cooldown
- [Battery Optimization](./battery-optimization.md) -- Every trigger that starts a flush, and the option that controls it
- [useSyncStatus](../api-reference/hooks/useSyncStatus.md) -- Live progress and last-result observation
