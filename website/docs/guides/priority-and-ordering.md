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

`batchSize` limits how many items are dispatched **concurrently** within a cycle, but the global ordering is preserved across batches.

## Avoiding HIGH Starvation

`HIGH` is dispatched first within each cycle, but the cycle still drains the entire queue (constrained only by `batchSize`). LOW items are not starved -- they always run after HIGH/NORMAL drain in the same cycle.

If you observe LOW items piling up indefinitely, the cause is usually:

- The HIGH/NORMAL bucket producing items faster than the network can drain them (a backpressure problem, not a starvation one).
- A failing endpoint causing HIGH items to retry indefinitely. In that case, raise `RetryPolicy.maxAttempts` for HIGH items so they exit the queue instead of blocking it -- or accept the failure faster with a smaller budget.

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

`SyncItemInput.contentType`, when set, takes precedence over any `Content-Type` value present in `headers`. This is a convenience to avoid duplicating the field.

```ts
await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/text',
  contentType: 'text/plain', // wins over headers['Content-Type']
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

`batchSize` controls how many items dispatch in parallel within a cycle. Defaults are conservative (native side chooses `4` when omitted). Raise it for upload-heavy workloads on stable networks; lower it on flaky connections to avoid swamping the retry queue.

## Per-Item Timeout

`requestTimeoutMs` is library-wide -- there is no per-item override in v0.1. If you need different timeouts for different endpoints, partition them across separate apps or wait for the v0.2 callback API.

## Next Steps

- [Idempotency](./idempotency.md) -- ULIDs and `Idempotency-Key`.
- [Retry Policy](./retry-policy.md) -- Backoff curves and budgets.
- [`SyncOptions` reference](../api-reference/types.md#syncoptions)
