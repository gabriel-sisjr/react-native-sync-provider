---
sidebar_position: 6
title: Errors
description: SyncError class, the SyncErrorCode discriminator, and the full retryability matrix for every error code emitted by react-native-sync-provider.
keywords:
  - react-native
  - sync-provider
  - errors
  - SyncError
  - SyncErrorCode
  - error-handling
  - retryable
---

# Errors

Structured error type and discriminator emitted by every facade function and reported through `SyncEvent.errorCode` / `SyncResult.errors`.

```ts
import {
  SyncError,
  SyncErrorCode,
} from '@gabriel-sisjr/react-native-sync-provider';
```

---

## `SyncError`

A custom `Error` subclass that carries a structured [`SyncErrorCode`](#syncerrorcode) so callers can branch on the failure reason without parsing the message string.

### Class definition

```ts
class SyncError extends Error {
  name: 'SyncError';
  code: SyncErrorCode;
  cause?: unknown;

  constructor(code: SyncErrorCode, message: string, cause?: unknown);
}
```

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `name` | `'SyncError'` | Always the literal string. |
| `code` | [`SyncErrorCode`](#syncerrorcode) | Structured discriminator. |
| `message` | `string` | Human-readable description. |
| `cause` | `unknown` | Optional underlying error (native exception, fetch error). |
| `stack` | `string` | Standard stack trace, inherited from `Error`. |

The constructor restores the prototype chain (`Object.setPrototypeOf(this, SyncError.prototype)`), so `instanceof SyncError` works reliably across bundlers and runtime targets.

---

## `SyncErrorCode`

```ts
type SyncErrorCode =
  | 'NETWORK_ERROR'
  | 'SERVER_ERROR'
  | 'INVALID_PAYLOAD'
  | 'QUEUE_FULL'
  | 'NATIVE_MODULE_UNAVAILABLE'
  | 'BACKGROUND_TASK_REGISTRATION_FAILED'
  | 'INVALID_URL'
  | 'UNAUTHORIZED'
  | 'TIMEOUT'
  | 'MAX_ATTEMPTS_EXCEEDED'
  | 'DUPLICATE_ITEM';
```

### Matrix

| Code | Meaning | Retryable? | Typically thrown by | Suggested action |
|------|---------|------------|---------------------|------------------|
| `NETWORK_ERROR` | Transport failure (DNS, socket, TLS, no route). | Yes | Native dispatcher during `flush()`; surfaced on `ITEM_RETRYING` / `ITEM_FAILED` after exhaustion. | Wait for connectivity restoration; the engine will retry per `RetryPolicy`. |
| `SERVER_ERROR` | HTTP status in `RetryPolicy.retryOnStatusCodes` (typically 5xx, 408, 425, 429). | Yes | Dispatcher when the response status is in the retry list. | Retry handled automatically. Investigate server-side if it persists. |
| `INVALID_PAYLOAD` | The item is missing required fields (`method`, `url`) or `RetryPolicy` failed validation. | No | `enqueue`, `enqueueBatch`, `configureSync`. | Fix the call site; do not retry. |
| `QUEUE_FULL` | `enqueue` would exceed `SyncOptions.maxQueueSize`. | No | `enqueue`, `enqueueBatch`. | Drain via `flush()`, drop the item, or surface a "retry later" UX. |
| `NATIVE_MODULE_UNAVAILABLE` | Called on web / SSR or before native linking completed. | No | Every facade function. | Guard with `isNativeModuleAvailable()` and provide a JS fallback or no-op. |
| `BACKGROUND_TASK_REGISTRATION_FAILED` | iOS: missing `BGTaskSchedulerPermittedIdentifiers` entry or disabled Background Modes. Android: WorkManager rejected the request. | No | `enableBackgroundSync`. | See [Background sync guide](../guides/background-sync.md) and the per-platform [Production checklist](../production/production-checklist.md). |
| `INVALID_URL` | `url` does not parse as an absolute URL. | No | `enqueue`, `enqueueBatch`. | Validate URLs at the caller. |
| `UNAUTHORIZED` | HTTP 401 / 403 from the server. | No | Dispatcher; reported on `ITEM_FAILED`. | Refresh the auth token, mutate the item's `headers`, and re-enqueue. |
| `TIMEOUT` | Per-request `requestTimeoutMs` exceeded. | Yes | Dispatcher. | Retry handled automatically per policy; consider increasing `requestTimeoutMs` for large bodies. |
| `MAX_ATTEMPTS_EXCEEDED` | Item exhausted `RetryPolicy.maxAttempts`. | No | Dispatcher; reported on `ITEM_FAILED`. | Inspect the underlying `cause` (last error before exhaustion) and decide whether to manually re-enqueue. |
| `DUPLICATE_ITEM` | The native layer rejected an item whose id collided with an existing row. | No | `enqueue`, `enqueueBatch` (vanishingly rare with native ULIDs). | File a bug if reproducible. |

Use this matrix when designing your error UI -- any code marked retryable will be retried by the engine; non-retryable codes are terminal and signal that the item will not advance without code changes.

---

## Discrimination Pattern

```ts
import {
  enqueue,
  SyncError,
  SyncErrorCode,
  HttpMethod,
} from '@gabriel-sisjr/react-native-sync-provider';

try {
  await enqueue({
    method: HttpMethod.POST,
    url: 'not-a-url',
  });
} catch (err) {
  if (err instanceof SyncError) {
    switch (err.code) {
      case SyncErrorCode.INVALID_URL:
        console.error('Bad URL -- fix the caller');
        break;
      case SyncErrorCode.QUEUE_FULL:
        // Drop or warn the user
        break;
      case SyncErrorCode.NATIVE_MODULE_UNAVAILABLE:
        // Likely SSR / web; degrade gracefully
        break;
      default:
        console.error(`SyncError [${err.code}]: ${err.message}`);
    }
  } else {
    throw err;
  }
}
```

For an end-to-end pattern (try/catch + event subscriptions + result inspection), see the [Error handling guide](../guides/error-handling.md).

---

## See also

- [`SyncEvent.errorCode` field](./types.md#syncevent).
- [`SyncResult.errors` map](./types.md#syncresult).
- [Retry policy guide](../guides/retry-policy.md), [Error handling guide](../guides/error-handling.md).
