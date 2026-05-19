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

Every queued item carries a retry budget. When dispatch fails, the native layer waits according to the configured backoff curve and tries again -- up to `maxAttempts` times. After the budget is exhausted, the item fails permanently with `MAX_ATTEMPTS_EXCEEDED` and is surfaced via `SyncResult.errors`.

## `RetryPolicy`

| Field | Type | Description |
|---|---|---|
| `maxAttempts` | `number` | Total attempt budget. The first attempt counts as one. |
| `backoff` | `BackoffStrategy` | Curve used to compute the delay between attempts. |
| `baseDelayMs` | `number` | Base delay (in ms) used by the curve. |
| `maxDelayMs` | `number` | Hard cap on the per-attempt delay (in ms). |
| `jitter` | `boolean` | Multiply the computed delay by a uniformly distributed factor in `[0.5, 1.5]`. |
| `retryOnStatusCodes` | `number[]` | HTTP status codes that should trigger a retry. Network errors and timeouts are always retried regardless of this list. |

## Backoff Strategies

### `LINEAR`

`delay = baseDelayMs * attempt` (capped at `maxDelayMs`).

| Attempt | `baseDelayMs = 1000` |
|---|---|
| 1 | 1000 ms |
| 2 | 2000 ms |
| 3 | 3000 ms |
| 4 | 4000 ms |
| 5 | 5000 ms |

Use for predictable, slow-growing retry windows.

### `EXPONENTIAL` (default)

`delay = baseDelayMs * 2^(attempt - 1)` (capped at `maxDelayMs`).

| Attempt | `baseDelayMs = 1000`, `maxDelayMs = 60000` |
|---|---|
| 1 | 1000 ms |
| 2 | 2000 ms |
| 3 | 4000 ms |
| 4 | 8000 ms |
| 5 | 16000 ms |
| 6 | 32000 ms |
| 7 | 60000 ms (capped) |

The standard choice for transient server-side errors. Doubles the wait every attempt.

### `FIBONACCI`

`delay = baseDelayMs * fib(attempt)` (capped at `maxDelayMs`).

| Attempt | `baseDelayMs = 1000` |
|---|---|
| 1 | 1000 ms |
| 2 | 1000 ms |
| 3 | 2000 ms |
| 4 | 3000 ms |
| 5 | 5000 ms |
| 6 | 8000 ms |
| 7 | 13000 ms |

Smoother growth than `EXPONENTIAL` -- useful when you want more retries in the first minute without ballooning to multi-minute waits.

## Jitter

When `jitter: true`, the computed delay is multiplied by a uniformly distributed factor in `[0.5, 1.5]`. A computed delay of `4000 ms` becomes a real delay anywhere in `[2000, 6000] ms`. This prevents synchronized retry storms when many devices come back online at the same time.

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
retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504]
```

| Code | Meaning | Why retry |
|---|---|---|
| 408 | Request Timeout | Server-side timeout; client may succeed on retry. |
| 425 | Too Early | Server replayed an early-data request; safe to retry. |
| 429 | Too Many Requests | Rate-limited; back off and retry. |
| 500 | Internal Server Error | Often transient. |
| 502 | Bad Gateway | Upstream proxy issue. |
| 503 | Service Unavailable | Often transient (deploys, restarts). |
| 504 | Gateway Timeout | Upstream timeout. |

Status codes **not** in the list are treated as permanent failures -- the item is removed from the queue with the matching `SyncErrorCode` (`UNAUTHORIZED` for 401/403, `SERVER_ERROR` for other 5xx, etc.).

## Permanent Failures

When `maxAttempts` is exhausted, the item is dropped from the queue and surfaced in `SyncResult.errors`:

```ts
const result = await flush();

for (const [itemId, code] of Object.entries(result.errors)) {
  console.warn(`item ${itemId} failed: ${code}`);
}
```

The dominant code for an exhausted retry budget is `MAX_ATTEMPTS_EXCEEDED`. The original failure (network, server, etc.) is logged on the native side but the JS-side error code reflects the budget exhaustion.

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

## JS-Side Override (deferred to v0.2)

A per-attempt `shouldRetry(ctx)` callback is planned for v0.2:

```ts
type ShouldRetryFn = (ctx: {
  attempt: number;
  error?: SyncErrorCode;
  statusCode?: number;
  itemId: string;
}) => boolean;
```

It is intentionally **not** part of v0.1 because:

- The callback would need to be evaluated on every retry decision, requiring a JS-alive bridge round-trip.
- Background dispatch (when the app is killed) cannot consult the JS thread, so the override would behave inconsistently between foreground and background fires.

Until v0.2 ships, configure the declarative `RetryPolicy` carefully -- it covers the vast majority of cases.

## Next Steps

- [Error Handling](./error-handling.md) -- The full `SyncErrorCode` matrix.
- [Background Sync](./background-sync.md) -- How retries interact with OS-scheduled fires.
- [`useSyncStatus` hook](../api-reference/hooks/useSyncStatus.md) -- Live progress and last-result observation.
