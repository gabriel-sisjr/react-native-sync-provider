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

Structured error type and discriminator thrown by the facade functions, reported per item and per skipped cycle through `SyncEvent.errorCode`, and kept on each dead letter as `lastErrorCode`.

```ts
import {
  SyncError,
  SyncErrorCode,
} from '@gabriel-sisjr/react-native-sync-provider';
```

---

## `SyncError`

A custom `Error` subclass that carries a structured [`SyncErrorCode`](#syncerrorcode) so callers can branch on the failure reason without parsing the message string.

### Class Definition

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
| `validationErrors` | [`ValidationIssue[]`](#validationissue) | Optional. Populated only on validation failures (`INVALID_URL` / `INVALID_PAYLOAD` from `enqueue`, `enqueueBatch` and `configureSync`, and `INVALID_PAYLOAD` from the four `set…` callback functions when their argument is not a function). Carries **every** detected issue — not just the first — so consumers can surface all problems at once. `undefined` for non-validation errors. |
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
  | 'DRAIN_ALREADY_ACTIVE'
  | 'NETWORK_QUALITY_TOO_LOW'
  | 'HEALTH_CHECK_FAILED'
  | 'DEAD_LETTERED'
  | 'RETRY_DECIDER_REJECTED'
  | 'INTERCEPTOR_FAILED';
```

`RETRY_DECIDER_REJECTED` and `INTERCEPTOR_FAILED` were added in v0.4, and the three before them in v0.3. A `switch` over `SyncErrorCode` that ends in a `never` exhaustiveness check needs a case for each: see the [v0.3 → v0.4 migration](../migration/v0-4-0.md).

### Matrix

| Code                                  | Meaning                                                                                                                                                                                          | Retryable? | Typically thrown by                                                                               | Suggested action                                                                                                                              |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------- | ------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `NETWORK_ERROR`                       | Transport failure (DNS, socket, TLS, no route). Also the code on the `SYNC_FAILED` of a [cycle that died](../guides/error-handling.md#when-a-cycle-dies) of something other than one of the library's own errors, such as a failed storage write, and what `flush()` rejects with when its cycle dies, whatever the cause. On Android, also the code of an item whose request went out when its cycle died before the answer was read. | Yes        | Item events. The `SYNC_FAILED` of a cycle that died. `flush()` rejection.                         | Wait for connectivity; the engine will retry per `RetryPolicy`. On a `SYNC_FAILED`, see [When a cycle dies](../guides/error-handling.md#when-a-cycle-dies). |
| `SERVER_ERROR`                        | A response outside 2xx, other than 401/403.                                                                                                                                                       | If listed  | Item events. Retried when the status is in `RetryPolicy.retryOnStatusCodes`; otherwise terminal.  | Retry handled automatically. Investigate server-side if it persists.                                                                          |
| `INVALID_PAYLOAD`                     | The item or the options failed validation, with detail in `validationErrors`. Also the fallback for a native rejection in most functions, with no `validationErrors`. On item events, an attempt that failed with something other than an I/O error, which the engine cannot classify, such as a request that cannot be built: a permanent failure, on `ITEM_FAILED`, as the dead letter's `lastErrorCode`, and in Android's `SyncResult.errors`. | No         | `enqueue`, `enqueueBatch`, `configureSync` (for `minNetworkQuality`, `healthCheckTtlMs`, `healthCheckBackoffMs`, `callbackTimeoutMs`), the four `set…` callback functions when their argument is not a function, and most functions as a fallback. Item events. | Fix the call site. With no `validationErrors`, read [Native Rejections](#native-rejections). On an item, read the dead letter's `lastErrorMessage`, then discard it, or replay it once the cause is fixed. |
| `QUEUE_FULL`                          | The queue is at `SyncOptions.maxQueueSize`. Raised by native only, by `enqueue`, `enqueueBatch` and `replayDeadLetter`.                                                                          | No         | Does not reach JS yet: see [Native Rejections](#native-rejections).                               | Flush, drop the item, or surface a "retry later" UX. A rejected replay keeps the dead letter.                                                 |
| `NATIVE_MODULE_UNAVAILABLE`           | Called on web / SSR or before native linking completed. Also what a `set…` callback function rejects with when native rejects it, for example because a newer JS runtime has attached since the calling one. | No         | Every facade function, from the JS layer, except the web no-ops. The fallback for the four `set…` callback functions. | Guard with `isNativeModuleAvailable()` and provide a JS fallback or no-op. From a `set…`, see [Install Order and Lifetime](./functions.md#install-order-and-lifetime). |
| `BACKGROUND_TASK_REGISTRATION_FAILED` | iOS: `com.gabriel-sisjr.syncprovider.background` missing from `BGTaskSchedulerPermittedIdentifiers`, running on a simulator, or Background App Refresh turned off. Android: WorkManager refused the request, or could not initialize, as when the app removes WorkManager's default initializer without implementing `Configuration.Provider`. | No         | `enableBackgroundSync`. Also the fallback for `disableBackgroundSync` and `isBackgroundSyncEnabled`. | See [Background sync guide](../guides/background-sync.md) and the per-platform [Production checklist](../production/production-checklist.md). |
| `INVALID_URL`                         | `url` or `SyncOptions.healthCheckUrl` is missing, empty, or not an absolute `http(s)` URL.                                                                                                       | No         | `enqueue`, `configureSync`. `enqueueBatch` reports URL problems under `INVALID_PAYLOAD`, in `validationErrors`. | Validate URLs at the caller.                                                                                                    |
| `UNAUTHORIZED`                        | HTTP 401 / 403 from the server. Headers from the [header resolver](./functions.md#setheaderresolver) do not change how it is handled, and the retry decider is never consulted for it.        | No         | Item events. On `ITEM_RETRYING` during a [credential hold](../guides/background-sync.md#cold-start-configuration), otherwise on `ITEM_FAILED`, and as the dead letter's `lastErrorCode`. | Call `configureSync` with a fresh token, or have the header resolver return one, then [`replayDeadLetter`](./functions.md#replaydeadletter) the rejected items. |
| `TIMEOUT`                             | The request exceeded `requestTimeoutMs`, on both platforms. On Android, also a fixed socket timeout of the HTTP client: connecting took over 10 s, or a read or write stalled 30 s. | Yes        | Item events.                                                                                      | Retry handled automatically per policy. For large bodies, consider increasing `requestTimeoutMs`. On Android, raising it does not lengthen the fixed socket timeouts. |
| `MAX_ATTEMPTS_EXCEEDED`               | A retriable failure with no attempts left in `RetryPolicy.maxAttempts`, or an item whose attempts already reached a lowered `maxAttempts`, which is dead-lettered without a request. A non-retryable failure keeps its real code, even on the last attempt. Also the `lastErrorCode` of a dead letter moved by the upgrade with no usable code. | No | `ITEM_FAILED`, dead letters.                                                                    | Replay the dead letter once the cause is fixed, or discard it. `ITEM_RETRYING` events show the failure that led here.                       |
| `DUPLICATE_ITEM`                      | Reserved. Neither platform raises it: ids are native-assigned ULIDs.                                                                                                                              | No         | Never.                                                                                            | None.                                                                                                                                         |
| `DRAIN_ALREADY_ACTIVE`                | A [`drainUntilEmpty()`](./functions.md#drainuntilempty) loop is already running or still settling in this process. Only one bounded drain may run at a time.                                     | No         | `drainUntilEmpty`.                                                                                | Await the previous drain. Do not rely on [`cancelDrain()`](./functions.md#canceldrain) followed by an immediate restart.                      |
| `NETWORK_QUALITY_TOO_LOW`             | A flush cycle was skipped because the network quality is below `SyncOptions.minNetworkQuality`. No item changes and no attempt is spent.                                                          | Yes        | `SYNC_FAILED`, with no `SYNC_STARTED` before it.                                                  | Nothing for automatic wakes: a blocked wake runs once the quality reaches the minimum. A `flush()` call is not repeated, so call it again later. See [The quality gate](../guides/connectivity-detection.md#the-quality-gate). |
| `HEALTH_CHECK_FAILED`                 | A flush cycle was skipped because the `HEAD` to `SyncOptions.healthCheckUrl` did not end with a `2xx`, or because the backoff after such a failure (`healthCheckBackoffMs`) was still running. No item changes and no attempt is spent. | Yes | `SYNC_FAILED`, with no `SYNC_STARTED` before it. | Check the endpoint. The next retry wake waits for the backoff to end. See [Health check](../guides/connectivity-detection.md#health-check). |
| `DEAD_LETTERED`                       | The item was moved to the dead-letter queue. Never a dead letter's `lastErrorCode`: the event's `metadata.lastErrorCode` and the dead letter carry the real code.                                | No         | `ITEM_DEAD_LETTERED`.                                                                             | Inspect with [`listDeadLetter`](./functions.md#listdeadletter), then replay or discard.                                                      |
| `RETRY_DECIDER_REJECTED`              | The [retry decider](./functions.md#setretrydecider) answered `DROP` or `DEAD_LETTER` for an attempt the policy would have retried. `DROP` deletes the item; `DEAD_LETTER` moves it to the dead-letter queue, whose `lastErrorMessage` keeps the real failure. Both count in `failureCount`. | No | `ITEM_FAILED`, with the attempt's `statusCode`. After a `DEAD_LETTER`, also the `metadata.lastErrorCode` of `ITEM_DEAD_LETTERED` and the dead letter's `lastErrorCode`. On Android, in `SyncResult.errors`. | Your decider chose this. Replay a dead letter once the cause is fixed, or discard it. A dropped item is gone. |
| `INTERCEPTOR_FAILED`                  | The [header resolver](./functions.md#setheaderresolver) or [`beforeDispatch`](./functions.md#setbeforedispatch) failed for an item, or `beforeDispatch` answered `SKIP`. Nothing was sent and no attempt was spent: the item is due again after the longer of the policy's delay and 60 s. See [Fail Closed](./functions.md#fail-closed). | Yes | `ITEM_RETRYING`, with no `statusCode`, and `attempt` set to the attempt that would have been made. | A `SKIP` is your hook's choice. For a failure, read the native log line for the reason and fix the callback: deferrals never escalate, so a broken resolver holds its items indefinitely. |

Use this matrix when designing your error UI. A retryable item code is retried by the engine, and a retryable skip code (`NETWORK_QUALITY_TOO_LOW`, `HEALTH_CHECK_FAILED`) leaves every item queued for a later cycle. `INTERCEPTOR_FAILED` leaves its item queued too, without spending an attempt. A non-retryable item code is terminal: the item moves to the [dead-letter queue](./types.md#syncdeadletteritem), and only a replay brings it back. The only case where the engine itself deletes a failed item is a retry decider's `DROP`.

---

## Native Rejections

Native codes do not reach JS through a rejection yet. Each facade function maps any rejection from native to one fallback code, and keeps the native text in `message` and the original error in `cause`:

| Functions                                                                   | Fallback code                         |
| --------------------------------------------------------------------------- | ------------------------------------- |
| `flush`                                                                     | `NETWORK_ERROR`                       |
| `enableBackgroundSync`, `disableBackgroundSync`, `isBackgroundSyncEnabled` | `BACKGROUND_TASK_REGISTRATION_FAILED` |
| `setRetryDecider`, `setHeaderResolver`, `setBeforeDispatch`, `setAfterDispatch` | `NATIVE_MODULE_UNAVAILABLE`       |
| Every other function                                                        | `INVALID_PAYLOAD`                     |

Native rejects a `set…` callback function when the calling JS runtime no longer owns the installs, for example after a reload: see [Install Order and Lifetime](./functions.md#install-order-and-lifetime).

> **Warning — `QUEUE_FULL` reaches you as `INVALID_PAYLOAD`:** The native layer raises `QUEUE_FULL`, but `enqueue`, `enqueueBatch` and `replayDeadLetter` reject with `INVALID_PAYLOAD`, so `err.code === SyncErrorCode.QUEUE_FULL` never matches. A validation failure always carries `validationErrors`, and a native rejection never does, so test for that:
>
> ```ts
> try {
>   await enqueue(item);
> } catch (err) {
>   if (
>     err instanceof SyncError &&
>     err.code === SyncErrorCode.INVALID_PAYLOAD &&
>     err.validationErrors === undefined
>   ) {
>     // Rejected by native, most often a full queue. The detail is in err.message.
>     console.warn(err.message);
>   }
> }
> ```

Codes describing a dispatched request arrive on events instead, on `ITEM_FAILED` and `ITEM_RETRYING`, and stay on the dead letter as `lastErrorCode`. `INTERCEPTOR_FAILED`, for an item a dispatch interceptor deferred unsent, arrives on `ITEM_RETRYING` too. Codes for a cycle skipped at the start of a flush arrive on `SYNC_FAILED`. None of them are rejections, because per-item failures and skipped cycles never reject. A [cycle that dies](../guides/error-handling.md#when-a-cycle-dies) reports its code on `SYNC_FAILED` too, and its `flush()` rejects with `NETWORK_ERROR`.

---

## `ValidationIssue`

A single, machine-readable validation violation, attached to [`SyncError.validationErrors`](#syncerror) on `INVALID_URL` / `INVALID_PAYLOAD` failures from `enqueue`, `enqueueBatch` and `configureSync`, and on the `INVALID_PAYLOAD` a `set…` callback function throws when its argument is not a function. Instead of parsing one opaque `message`, consumers can react to **every** problem with a payload.

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
| `field`     | `string`  | Dotted path of the offending field, e.g. `"url"`, `"method"`, `"headers.Authorization"`, or `"input"` when the whole item is invalid. For `configureSync`, the `SyncOptions` field name, e.g. `"healthCheckUrl"`, or `"options"` when the argument is not an object. For a `set…` callback function, the parameter name: `"decider"`, `"resolver"` or `"hook"`. |
| `reason`    | `string`  | Human-readable explanation of why `field` failed.                                                                                     |
| `value`     | `unknown` | The offending value. Set only for `method`, `priority` and `contentType`, and for the `SyncOptions` fields `minNetworkQuality`, `healthCheckTtlMs`, `healthCheckBackoffMs` and `callbackTimeoutMs`. Never set for `url`, `body`, `headers`, `metadata`, a whole-item `input` failure or `healthCheckUrl`, because errors end up in logs and those fields can hold credentials and personal data. Not set for a `set…` argument either: `reason` names the type received. |
| `itemIndex` | `number`  | Zero-based index of the originating item in a batch. Omitted for single-item validation, for `configureSync` and for the `set…` callback functions. |

### Batch Validation Aggregates All Issues

`enqueue` and `enqueueBatch` differ in how they report validation failures:

- **`enqueue`** throws on the **first** violation, preserving the specific code (`INVALID_URL` or `INVALID_PAYLOAD`) and message. The full list is still attached on `validationErrors`. `reason` never contains the URL, but this `message` can, so treat it with care in logs.
- **`enqueueBatch`** collects issues across **every** item and throws **one** umbrella `SyncError(INVALID_PAYLOAD)` whose `validationErrors` carries all of them, each tagged with its `itemIndex`. **No items are enqueued** when any issue is present.

> **Warning — Behavior change in v0.2:** Before v0.2, `enqueueBatch` threw the first bad item's specific code (e.g. `INVALID_URL`). It now always throws `INVALID_PAYLOAD` and exposes per-field, per-item detail via `validationErrors`. If you pattern-match on `err.code` from a batch call, branch on `INVALID_PAYLOAD` and read `err.validationErrors`. See [`BREAKING_CHANGES.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/BREAKING_CHANGES.md).

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

## Error Handling Patterns

### Branching on error code

Narrow with `instanceof SyncError`, then switch on `code`:

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
        // With no validationErrors, native rejected the call: see Native Rejections
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

### Failures that do not throw

A per-item failure never rejects, and neither does a cycle the quality gate or the health check skips. Item codes arrive on `ITEM_FAILED` and `ITEM_RETRYING`, skipped-cycle codes on `SYNC_FAILED`, and `flush()` resolves with a [`SyncResult`](./types.md#syncresult):

```ts
import {
  addSyncEventListener,
  flush,
  SyncEventType,
} from '@gabriel-sisjr/react-native-sync-provider';

// Subscribe once, at startup for example. Pass the id to removeSyncEventListener on cleanup.
const subscriptionId = await addSyncEventListener((event) => {
  if (event.type === SyncEventType.ITEM_FAILED) {
    console.warn(`${event.itemId} failed with ${event.errorCode}`);
  }
});

const result = await flush(); // resolves even when items fail
if (result.failureCount > 0) {
  console.warn(`${result.failureCount} item(s) failed permanently`);
}
```

### Guarding against a missing native module

Every async function on the [Functions](./functions.md) page throws `NATIVE_MODULE_UNAVAILABLE` when the native module is missing, except the four `clear…` callback functions on web, which resolve without doing anything. Check first where that can happen:

```ts
import {
  enqueue,
  HttpMethod,
  isNativeModuleAvailable,
} from '@gabriel-sisjr/react-native-sync-provider';

if (isNativeModuleAvailable()) {
  await enqueue({ method: HttpMethod.POST, url: 'https://api.example.com/events' });
} else {
  // Web, or an app not rebuilt after installing the library: fall back, or skip.
}
```

For an end-to-end pattern (try/catch + event subscriptions + result inspection), see the [Error handling guide](../guides/error-handling.md).

---

## See Also

- [Types & Interfaces](./types.md) -- `SyncEvent.errorCode`, and the `SyncResult.errors` map, which only Android fills
- [Retry Policy](../guides/retry-policy.md) -- Which responses are retried, and how the delay grows
- [Error Handling](../guides/error-handling.md) -- Handler patterns end to end: try/catch, events, results and dead letters
