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

Structured error type and discriminator thrown by the facade functions and reported per item through `SyncEvent.errorCode`.

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
  validationErrors?: ValidationIssue[];

  constructor(
    code: SyncErrorCode,
    message: string,
    cause?: unknown,
    validationErrors?: ValidationIssue[]
  );
}
```

### Properties

| Property           | Type                                    | Description                                                                                                                                                                                                                                                        |
| ------------------ | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `name`             | `'SyncError'`                           | Always the literal string.                                                                                                                                                                                                                                         |
| `code`             | [`SyncErrorCode`](#syncerrorcode)       | Structured discriminator.                                                                                                                                                                                                                                          |
| `message`          | `string`                                | Human-readable description.                                                                                                                                                                                                                                        |
| `cause`            | `unknown`                               | Optional underlying error (the native rejection, for example). Native failures keep their detail here and in `message`.                                                                                                                                             |
| `validationErrors` | [`ValidationIssue[]`](#validationissue) | Optional. Populated only on validation failures (`INVALID_URL` / `INVALID_PAYLOAD` from `enqueue` / `enqueueBatch`). Carries **every** detected issue — not just the first — so consumers can surface all problems at once. `undefined` for non-validation errors. |
| `stack`            | `string`                                | Standard stack trace, inherited from `Error`.                                                                                                                                                                                                                      |

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
  | 'DUPLICATE_ITEM'
  | 'DRAIN_ALREADY_ACTIVE';
```

### Matrix

| Code                                  | Meaning                                                                                                                                                                                          | Retryable? | Typically thrown by                                                                               | Suggested action                                                                                                                              |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------- | ------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `NETWORK_ERROR`                       | Transport failure (DNS, socket, TLS, no route). Also what `flush()` rejects with when its cycle dies, whatever the cause.                                                                         | Yes        | Item events. `flush()` rejection.                                                                 | Wait for connectivity; the engine will retry per `RetryPolicy`.                                                                               |
| `SERVER_ERROR`                        | A response outside 2xx, other than 401/403.                                                                                                                                                       | If listed  | Item events. Retried when the status is in `RetryPolicy.retryOnStatusCodes`; otherwise terminal.  | Retry handled automatically. Investigate server-side if it persists.                                                                          |
| `INVALID_PAYLOAD`                     | The item failed validation, with detail in `validationErrors`. Also the fallback for a native rejection in most functions, with no `validationErrors`.                                          | No         | `enqueue`, `enqueueBatch`, and most functions as a fallback.                                      | Fix the call site. With no `validationErrors`, read [Native rejections](#native-rejections).                                                  |
| `QUEUE_FULL`                          | The queue is at `SyncOptions.maxQueueSize`. Raised by native only.                                                                                                                                | No         | Does not reach JS yet: see [Native rejections](#native-rejections).                               | Flush, drop the item, or surface a "retry later" UX.                                                                                          |
| `NATIVE_MODULE_UNAVAILABLE`           | Called on web / SSR or before native linking completed.                                                                                                                                           | No         | Every facade function, from the JS layer.                                                         | Guard with `isNativeModuleAvailable()` and provide a JS fallback or no-op.                                                                    |
| `BACKGROUND_TASK_REGISTRATION_FAILED` | iOS: `com.gabriel-sisjr.syncprovider.background` missing from `BGTaskSchedulerPermittedIdentifiers`, or running on a simulator. Android: WorkManager rejected the request.                       | No         | `enableBackgroundSync`. Also the fallback for `disableBackgroundSync` and `isBackgroundSyncEnabled`. | See [Background sync guide](../guides/background-sync.md) and the per-platform [Production checklist](../production/production-checklist.md). |
| `INVALID_URL`                         | `url` is missing, empty, or not an absolute `http(s)` URL.                                                                                                                                        | No         | `enqueue`. `enqueueBatch` reports URL problems under `INVALID_PAYLOAD`, in `validationErrors`.    | Validate URLs at the caller.                                                                                                                  |
| `UNAUTHORIZED`                        | HTTP 401 / 403 from the server.                                                                                                                                                                   | No         | Item events. On `ITEM_RETRYING` during a [credential hold](../guides/background-sync.md#cold-start-configuration), otherwise on `ITEM_FAILED`. | Call `configureSync` with a fresh token. A failed item does not come back: enqueue it again. |
| `TIMEOUT`                             | Per-request `requestTimeoutMs` exceeded. Android can report this expiry as `NETWORK_ERROR`, which is retried the same way.                                                                       | Yes        | Item events.                                                                                      | Retry handled automatically per policy; consider increasing `requestTimeoutMs` for large bodies.                                              |
| `MAX_ATTEMPTS_EXCEEDED`               | A retriable failure with no attempts left in `RetryPolicy.maxAttempts`. A non-retryable failure keeps its real code, even on the last attempt.                                                   | No         | `ITEM_FAILED`.                                                                                    | Decide whether to enqueue the request again. `ITEM_RETRYING` events show the failure that led here.                                           |
| `DUPLICATE_ITEM`                      | Reserved. Neither platform raises it: ids are native-assigned ULIDs.                                                                                                                              | No         | Never.                                                                                            | None.                                                                                                                                         |
| `DRAIN_ALREADY_ACTIVE`                | A [`drainUntilEmpty()`](./functions.md#drainuntilempty) loop is already running or still settling in this process. Only one bounded drain may run at a time.                                     | No         | `drainUntilEmpty`.                                                                                | Await the previous drain. Do not rely on [`cancelDrain()`](./functions.md#canceldrain) followed by an immediate restart.                      |

Use this matrix when designing your error UI -- any code marked retryable will be retried by the engine; non-retryable codes are terminal and signal that the item will not advance without code changes.

---

## Native rejections

Native codes do not reach JS through a rejection yet. Each facade function maps any rejection from native to one fallback code, and keeps the native text in `message` and the original error in `cause`:

| Functions                                                                   | Fallback code                         |
| --------------------------------------------------------------------------- | ------------------------------------- |
| `flush`                                                                     | `NETWORK_ERROR`                       |
| `enableBackgroundSync`, `disableBackgroundSync`, `isBackgroundSyncEnabled` | `BACKGROUND_TASK_REGISTRATION_FAILED` |
| Every other function                                                        | `INVALID_PAYLOAD`                     |

:::warning `QUEUE_FULL` reaches you as `INVALID_PAYLOAD`
The native layer raises `QUEUE_FULL`, but `enqueue` and `enqueueBatch` reject with `INVALID_PAYLOAD`, so `err.code === SyncErrorCode.QUEUE_FULL` never matches. A validation failure always carries `validationErrors`, and a native rejection never does, so test for that:

```ts
try {
  await enqueue(item);
} catch (err) {
  if (
    err instanceof SyncError &&
    err.code === SyncErrorCode.INVALID_PAYLOAD &&
    err.validationErrors === undefined
  ) {
    // Rejected by native, most often a full queue. The detail is in err.message.
    console.warn(err.message);
  }
}
```

:::

Codes describing a dispatched request arrive on events instead, on `ITEM_FAILED` and `ITEM_RETRYING`. They are not rejections, because per-item failures never reject.

---

## `ValidationIssue`

A single, machine-readable validation violation, attached to [`SyncError.validationErrors`](#syncerror) on `INVALID_URL` / `INVALID_PAYLOAD` failures. Instead of parsing one opaque `message`, consumers can react to **every** problem with a payload.

```ts
import type { ValidationIssue } from '@gabriel-sisjr/react-native-sync-provider';

type ValidationIssue = {
  field: string;
  reason: string;
  value?: unknown;
  itemIndex?: number;
};
```

| Property    | Type      | Description                                                                                                                           |
| ----------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `field`     | `string`  | Dotted path of the offending field, e.g. `"url"`, `"method"`, `"headers.Authorization"`, or `"input"` when the whole item is invalid. |
| `reason`    | `string`  | Human-readable explanation of why `field` failed.                                                                                     |
| `value`     | `unknown` | The offending value. Set only for `method`, `priority` and `contentType`. Never set for `url`, `body`, `headers`, `metadata` or a whole-item `input` failure, because errors end up in logs and those fields can hold credentials and personal data. |
| `itemIndex` | `number`  | Zero-based index of the originating item in a batch. Omitted for single-item validation.                                              |

### Batch validation aggregates all issues

`enqueue` and `enqueueBatch` differ in how they report validation failures:

- **`enqueue`** throws on the **first** violation, preserving the specific code (`INVALID_URL` or `INVALID_PAYLOAD`) and message. The full list is still attached on `validationErrors`. `reason` never contains the URL, but this `message` can, so treat it with care in logs.
- **`enqueueBatch`** collects issues across **every** item and throws **one** umbrella `SyncError(INVALID_PAYLOAD)` whose `validationErrors` carries all of them, each tagged with its `itemIndex`. **No items are enqueued** when any issue is present.

:::warning Behavior change in v0.2
Before v0.2, `enqueueBatch` threw the first bad item's specific code (e.g. `INVALID_URL`). It now always throws `INVALID_PAYLOAD` and exposes per-field, per-item detail via `validationErrors`. If you pattern-match on `err.code` from a batch call, branch on `INVALID_PAYLOAD` and read `err.validationErrors`. See [`BREAKING_CHANGES.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/BREAKING_CHANGES.md).
:::

```ts
import {
  enqueueBatch,
  SyncError,
  SyncErrorCode,
  HttpMethod,
} from '@gabriel-sisjr/react-native-sync-provider';

try {
  await enqueueBatch([
    { method: HttpMethod.POST, url: 'https://api.example.com/a' },
    { method: HttpMethod.POST, url: 'not-a-url' }, // bad
  ]);
} catch (err) {
  if (err instanceof SyncError && err.code === SyncErrorCode.INVALID_PAYLOAD) {
    for (const issue of err.validationErrors ?? []) {
      console.warn(`item ${issue.itemIndex} → ${issue.field}: ${issue.reason}`);
      // → item 1 → url: SyncItemInput.url must be an absolute http(s) URL.
    }
  }
}
```

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
      case SyncErrorCode.INVALID_PAYLOAD:
        // With no validationErrors, native rejected the call: see Native rejections
        console.warn(err.validationErrors ?? err.message);
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
- [`SyncResult.errors` map](./types.md#syncresult), Android only.
- [Retry policy guide](../guides/retry-policy.md), [Error handling guide](../guides/error-handling.md).
