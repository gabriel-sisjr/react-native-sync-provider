---
sidebar_position: 3
title: Retry Policy
description: Configure retry behavior in react-native-sync-provider — maxAttempts, backoff curves (linear, exponential, fibonacci), jitter, and retry-on-status-codes.
keywords:
  - retry-policy
  - backoff
  - exponential-backoff
  - fibonacci
  - jitter
  - max-attempts
  - max-attempts-exceeded
---

# Retry Policy

Every queued item carries a retry budget. When a retriable dispatch fails, the item waits out a cooldown from the configured backoff curve, then a later cycle tries again, up to `maxAttempts` attempts in total. After the budget is exhausted, the item fails permanently with `MAX_ATTEMPTS_EXCEEDED`, is reported on `ITEM_FAILED`, and moves to the [dead-letter queue](#permanent-failures).

## `RetryPolicy`

| Field                | Type              | Description                                                                                                            |
| -------------------- | ----------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `maxAttempts`        | `number`          | Total attempt budget. The first attempt counts as one.                                                                 |
| `backoff`            | `BackoffStrategy` | Curve used to compute the delay between attempts.                                                                      |
| `baseDelayMs`        | `number`          | Base delay (in ms) used by the curve.                                                                                  |
| `maxDelayMs`         | `number`          | Hard cap on the per-attempt delay (in ms). Use a positive value: iOS treats `0` as no cap, and Android caps at `baseDelayMs`. |
| `jitter`             | `boolean`         | Multiply the computed delay by a uniformly distributed equal-jitter factor in `[0.75, 1.25)` (±25%).                   |
| `retryOnStatusCodes` | `number[]`        | HTTP status codes that should trigger a retry. Network errors and timeouts are always retried regardless of this list. |

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

### `EXPONENTIAL` (default)

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

:::tip Always enable jitter in production
Without jitter, every device that lost connectivity at the same time hammers your backend on the same retry tick. Jitter is the cheapest, most effective retry-storm mitigation available.
:::

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

An item fails permanently when it hits a non-retryable failure, or a retriable one with no attempts left. Listen to `ITEM_FAILED`, which works on both platforms, and to `ITEM_DEAD_LETTERED`, which follows it:

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

In one transaction, the item leaves the queue and enters the dead-letter queue, with its request, its attempt count, the last error code and message, and the time it was moved. Then `ITEM_FAILED` is emitted with the real code and, synchronously, `ITEM_DEAD_LETTERED` with `errorCode: 'DEAD_LETTERED'` and `metadata.lastErrorCode` set to that code. Nothing retries a dead letter on its own:

- [`listDeadLetter`](../api-reference/functions.md#listdeadletter) reads them, oldest first.
- [`replayDeadLetter`](../api-reference/functions.md#replaydeadletter) puts one back as a fresh item, with `attempts: 0` and `nextRetryAt: 0`. It counts toward `maxQueueSize` like an enqueue.
- [`discardDeadLetter`](../api-reference/functions.md#discarddeadletter) or [`removeItem`](../api-reference/functions.md#removeitem) deletes one, and [`clearQueue`](../api-reference/functions.md#clearqueue) deletes them all with the queue.

See [Dead-letter flows](./error-handling.md#dead-letter-flows) for the usual patterns.

:::warning The dead-letter queue is unbounded
Dead letters count toward no capacity cap, on either platform, and nothing prunes them. Each one keeps its headers and body. Discard what you will not replay, especially if your app runs for long periods offline or against a failing endpoint.
:::

Before v0.3, a permanently failed item stayed in the queue store, and Android counted it toward `maxQueueSize`. The upgrade moves those rows to the dead-letter queue.

## Retry Cooldown Across Restarts

**Added in v0.2.** A retriable failure with attempts left puts the item back to `PENDING` with a persisted **`nextRetryAt`**: the clock read after the attempt finished, plus the backoff delay. The dispatcher skips the item until the device clock reaches that deadline, on both platforms and across process death and device reboot. Measuring from the end of the attempt means a slow request or a timeout does not eat into the cooldown.

[`SyncItem.nextRetryAt`](../api-reference/types.md#syncitem) tells you where an item stands:

- `0` means it is eligible now. It does not mean the item was never attempted: rows carried over from v0.1, and an iOS row whose first attempt was cut short by a process kill, can show `attempts >= 1` with `nextRetryAt: 0`.
- A positive value means a retry cooldown or a [credential hold](./background-sync.md#when-a-stored-token-expires) is pending. It is eligible once `nextRetryAt <= now`. The value stays in place afterwards, so a timestamp in the past means the wait has elapsed.

Each cycle makes at most one attempt per item, and `attempts` counts the attempts recorded across cycles, so the retry budget survives a restart. `0` means no attempt has been recorded, not that the item was never tried. Android records an attempt with its outcome, so a process kill mid-request leaves the count unchanged, while iOS counts it when the dispatch starts. A credential hold spends no attempt, so a held item can show `attempts: 0` with `nextRetryAt > 0`.

A cooldown and a hold look the same on a `SyncItem`, which carries neither the last error code nor the last attempt time. An `ITEM_RETRYING` event with `errorCode: 'UNAUTHORIZED'` marks a hold.

:::note Capacity vs. eligibility
A cooling item still counts toward `getQueueSize()` and the `maxQueueSize` cap — it is _in_ the queue, just not _dispatchable right now_. Cooldowns also survive launch recovery: resetting in-flight items back to pending does **not** clear their `nextRetryAt`.
:::

### Who wakes the queue, and when

Nothing re-dispatches an item before its `nextRetryAt`. These are the triggers that can run a cycle:

| Trigger                  | Platform | When it fires                                                                                                                       | Stopped by                                                                                                       |
| ------------------------ | -------- | ----------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| **Retry timer**          | Both     | In-process and single-shot, at the soonest cooldown. Armed at the end of every cycle, on engine start after recovery, on `configureSync` and on `resumeSync`. | `pauseSync`, `strategy: MANUAL`. **Not** `foregroundOnly`, because it is in-process. |
| **Retry kick**           | Android  | A delayed one-time `WorkManager` job, armed with the timer for the same deadline, so a retry happens even if the process dies. It honors the same constraints as the periodic work. | Runs only with `enableBackgroundSync` active, `foregroundOnly` off, `strategy` other than `MANUAL` and the engine not paused. `disableBackgroundSync()` cancels it. |
| **Periodic worker**      | Android  | OS-driven, at least 15 minutes apart.                                                                                               | `foregroundOnly`, `disableBackgroundSync()`, `pauseSync`. **Not** `MANUAL`.                                      |
| **BGTask**               | iOS      | OS-driven, whenever iOS grants a window. iOS has no kick: this is its background path.                                              | `foregroundOnly`, `disableBackgroundSync()`, `pauseSync`. **Not** `MANUAL`.                                      |
| **Your own code**        | Both     | `flush()`, [`drainUntilEmpty()`](../api-reference/functions.md#drainuntilempty), [`useAutoSync`](../api-reference/hooks/useAutoSync.md). | `pauseSync`.                                                                                                     |

What the timer does and does not do:

- **It never flushes offline.** Without `minNetworkQuality`, neither dispatcher checks connectivity, so a flush offline would spend every attempt and send valid items to the dead-letter queue. When the timer fires offline, it remembers the wake and flushes when connectivity returns. A manual `flush()` while offline keeps its behavior unless `minNetworkQuality` is set.
- **It holds below the quality gate, and during a health-check backoff.** With [`minNetworkQuality`](./connectivity-detection.md#the-quality-gate) set, the timer checks the gate first and holds the wake silently, with no event, until the quality reaches the minimum. It re-checks at 30, 60, 120, 240 and 480 seconds, then every 15 minutes, restarting that backoff when a wake passes, the network changes or the timer is armed again, as every `configureSync` does. See [Held wakes](./connectivity-detection.md#held-wakes).
- **It only exists in a process with JS.** A worker, a boot broadcast, or an iOS background launch where JS never reaches the library arms no timer. Android relies on the kick there.
- **It looks at items with a deadline.** Only items with `nextRetryAt > 0`, a cooldown or a credential hold, arm it. Items with `nextRetryAt: 0` do not, so it does not replace a flush for new items, though the cycle it starts takes them too. `resumeSync` is the exception: it considers every pending item, so items with no deadline are dispatched at once.
- **On iOS it cannot fire while the app is suspended.** It fires on resume.

That owed wake is the only native flush tied to connectivity. There is no native flush on enqueue, and none on reconnect by itself: [`useAutoSync`](../api-reference/hooks/useAutoSync.md) is the mechanism.

`SyncWorker` reports `Result.success()` on any completed flush, so WorkManager's own backoff is reserved for a worker that fails as a whole and cannot race the cooldown.

After a [rejected stored credential](./background-sync.md#when-a-stored-token-expires), the retry timer and the Android retry kick wait for the hold to end, even if other items are already due. The periodic worker and BGTasks do not wait for it, and a process restart drops it.

After a failed [health check](./connectivity-detection.md#health-check), the next retry wake waits for its backoff to end too, and for at least 1000 ms after the failure, even with `healthCheckBackoffMs: 0`. The next wake is therefore the latest of the item's own deadline, the credential hold, the end of the health-check backoff and that one-second floor.

### `foregroundOnly` interaction

When [`BackgroundSyncOptions.foregroundOnly`](../api-reference/types.md#backgroundsyncoptions) is `true`, the OS-scheduled wakes are suppressed: the Android retry kick, the periodic worker and BGTasks. Cooldowns are still **persisted and honored**, and acted upon while the app is alive: by the retry timer, which this flag does not stop, and by `flush()`, `useAutoSync` and [`drainUntilEmpty`](../api-reference/functions.md#drainuntilempty). Data-integrity recovery still runs.

:::tip Draining cooling items on demand
[`drainUntilEmpty`](../api-reference/functions.md#drainuntilempty) sleeps until the soonest `nextRetryAt` by itself, so you do not need `delayBetweenCyclesMs` for this. If that moment is past `timeoutMs`, it stops at once with `TIMEOUT`, so raise `timeoutMs` to wait out a long backoff.
:::

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

## JS-Side Override (planned)

A JS-side, per-attempt retry override is planned for a later release. Its shape is not settled yet.

It would run only while JS is alive, because every retry decision would need a bridge round-trip. Background dispatch with no JS running cannot consult it, so the declarative `RetryPolicy` would keep deciding there.

Until it ships, configure the declarative `RetryPolicy` carefully -- it covers the vast majority of cases.

## Next Steps

- [Error Handling](./error-handling.md) -- The full `SyncErrorCode` matrix.
- [Background Sync](./background-sync.md) -- How retries interact with OS-scheduled fires.
- [`useSyncStatus` hook](../api-reference/hooks/useSyncStatus.md) -- Live progress and last-result observation.
