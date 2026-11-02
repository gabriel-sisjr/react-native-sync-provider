---
sidebar_position: 6
title: Priority and Ordering
description: Control dispatch order with SyncPriority HIGH/NORMAL/LOW, understand queue ordering rules, and use defaultHeaders with per-item overrides.
keywords:
  - priority
  - sync-priority
  - dispatch-order
  - default-headers
  - queue-ordering
  - high-normal-low
---

# Priority and Ordering

The queue dispatches items in a deterministic order: **priority descending, `createdAt` ascending**. Within the same priority bucket, older items go first. This guide explains how to use `SyncPriority` and how `defaultHeaders` interact with per-item headers.

## `SyncPriority`

```ts
const SyncPriority = {
  HIGH: 'HIGH',
  NORMAL: 'NORMAL',
  LOW: 'LOW',
} as const;
```

| Value | Use for |
|---|---|
| `HIGH` | Time-sensitive mutations (purchase confirmations, auth refreshes). |
| `NORMAL` | Default. Most user actions belong here. |
| `LOW` | Telemetry, analytics, log uploads. Drains last to keep the bandwidth available for HIGH/NORMAL. |

If you omit `priority`, the native side defaults to `NORMAL`.

## Setting a Priority

```ts
import { enqueue, HttpMethod, SyncPriority } from '@gabriel-sisjr/react-native-sync-provider';

await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/checkout',
  body: JSON.stringify({ orderId: '123' }),
  priority: SyncPriority.HIGH,
});

await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/telemetry',
  body: JSON.stringify({ event: 'screen_view' }),
  priority: SyncPriority.LOW,
});
```

## Dispatch Order in a Single Flush Cycle

Given a queue of:

| `id` | `priority` | `createdAt` |
|---|---|---|
| A | NORMAL | 100 |
| B | HIGH | 200 |
| C | LOW | 50 |
| D | NORMAL | 150 |
| E | HIGH | 300 |

The flush dispatches in the following order:

1. **B** (HIGH, oldest of HIGH)
2. **E** (HIGH)
3. **A** (NORMAL, oldest of NORMAL)
4. **D** (NORMAL)
5. **C** (LOW)

Each cycle takes one batch of up to `batchSize` eligible items, so this order decides which items go in the next batch. iOS sends a batch one item at a time, in this order. Android sends it concurrently, so items in a batch can arrive in any order.

## Avoiding HIGH Starvation

`HIGH` is dispatched first, and each cycle takes only one batch, so `LOW` items wait until `HIGH` and `NORMAL` items no longer fill it. An item that failed does not block others: it waits out its cooldown, and the next cycle takes the next eligible items.

If you observe `LOW` items piling up indefinitely, the cause is usually the `HIGH` and `NORMAL` buckets producing items faster than cycles can dispatch them. That is a backpressure problem, not a starvation one: raise `batchSize`, flush more often, or lower the priority of the bulk traffic. `RetryPolicy` is library-wide, so there is no per-priority retry budget.

## `defaultHeaders` and Per-Item Overrides

Configure headers that apply to every dispatched request:

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
  defaultHeaders: {
    'X-App-Version': '1.4.2',
    'X-Device-Id': 'abc-123',
    'Authorization': 'Bearer ...',
  },
});
```

Per-item headers **override** `defaultHeaders` on key collision (case-insensitive on the native side):

```ts
await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/upload',
  headers: {
    'Authorization': 'Bearer per-request-token', // wins
  },
  body: '...',
});
```

The merged headers attached to the dispatched request are:

```json
{
  "X-App-Version": "1.4.2",
  "X-Device-Id": "abc-123",
  "Authorization": "Bearer per-request-token"
}
```

### `Content-Type` priority

Set the type through `SyncItemInput.contentType`, and leave it out of `headers` and `defaultHeaders`. The platforms resolve a conflict differently:

- **Android** uses `contentType`. When it is omitted, a request with a body goes out as `application/json`, even if `headers` sets `Content-Type`.
- **iOS** uses the value in `headers` or `defaultHeaders`, and applies `contentType` only when neither has one.

```ts
await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/text',
  contentType: 'text/plain',
  body: 'hello',
});
```

## Concurrency: `batchSize`

```ts
await configureSync({
  ...
  batchSize: 4,
});
```

`batchSize` caps how many items one cycle dispatches. The default is `25`. Android sends them concurrently, and iOS one at a time, so on Android it is also the concurrency limit. Raise it for upload-heavy workloads on stable networks; lower it on flaky connections to avoid swamping the retry queue.

## Per-Item Timeout

`requestTimeoutMs` is library-wide -- there is no per-item override.

## Next Steps

- [Idempotency](./idempotency.md) -- ULIDs and `Idempotency-Key`.
- [Retry Policy](./retry-policy.md) -- Backoff curves and budgets.
- [`SyncOptions` reference](../api-reference/types.md#syncoptions)
