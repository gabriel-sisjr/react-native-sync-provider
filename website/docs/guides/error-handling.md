---
sidebar_position: 5
title: Error Handling
description: Discriminate errors thrown by react-native-sync-provider — SyncError class, SyncErrorCode matrix, retryable codes, skipped cycles, dead-letter flows, and recommended handler patterns.
keywords:
  - error-handling
  - sync-error
  - sync-error-code
  - retryable-errors
  - exception-handling
  - typescript-errors
  - dead-letter-queue
---

# Error Handling

This guide explains where failures reach you, and how to handle each kind: rejected calls, failed requests and dead letters.

## Overview

Every public function in `react-native-sync-provider` throws `SyncError` -- a typed `Error` subclass carrying a stable `SyncErrorCode`. Consumers should branch on `error.code`, never on the message string.

Failures reach you in three places. A call that cannot run rejects. A request that fails is reported per item, on events: a failed request never rejects. And a request that fails permanently is kept in the [dead-letter queue](#dead-letter-flows), where you can inspect, replay or discard it.

## The `SyncError` Class

```ts
class SyncError extends Error {
  readonly code: SyncErrorCode;
  readonly cause?: unknown;
  readonly validationErrors?: ValidationIssue[];
  readonly name: 'SyncError';
}
```

The constructor restores the prototype chain explicitly so `instanceof SyncError` keeps working when the error is rethrown across the Nitro bridge or serialized through React's error boundaries.

## Discrimination Pattern

```ts
import {
  enqueue,
  SyncError,
  SyncErrorCode,
  HttpMethod,
} from '@gabriel-sisjr/react-native-sync-provider';

try {
  await enqueue({ method: HttpMethod.POST, url: 'https://api.example.com/x' });
} catch (e) {
  if (!(e instanceof SyncError)) throw e;

  switch (e.code) {
    case SyncErrorCode.INVALID_URL:
      // surface to UX -- this will not succeed on retry
      break;
    case SyncErrorCode.INVALID_PAYLOAD:
      if (e.validationErrors) {
        // surface to UX -- this will not succeed on retry
      } else {
        // rejected by native, most often a full queue: back off and retry
      }
      break;
    case SyncErrorCode.NATIVE_MODULE_UNAVAILABLE:
      // running on web/SSR or before the native module loaded
      break;
    default:
      throw e;
  }
}
```

> **Warning — `QUEUE_FULL` does not reach JS yet:** Each facade function maps a native rejection to one fallback code, so a full queue rejects with `INVALID_PAYLOAD` and no `validationErrors`. See [Native Rejections](../api-reference/errors.md#native-rejections).

## Full `SyncErrorCode` Matrix

| Code | Retryable? | Where you see it | Suggested action |
|---|---|---|---|
| `NETWORK_ERROR` | Yes | Item events. `flush()` also rejects with it when its cycle dies. | Wait for connectivity; the engine retries automatically. On Android, a `NETWORK_ERROR` from anything other than an I/O failure is not retried. |
| `SERVER_ERROR` | If in `retryOnStatusCodes` | Item events | The engine retries a listed status; surface only if persistent. |
| `INVALID_PAYLOAD` | No | `enqueue`, `enqueueBatch`, `configureSync`, and most functions when native rejects | Fix the input shape. With no `validationErrors`, native rejected the call. |
| `QUEUE_FULL` | No (until drained) | `enqueue`, `enqueueBatch`, `replayDeadLetter`. Reaches JS as `INVALID_PAYLOAD` for now | Flush or raise `maxQueueSize`. A rejected replay keeps the dead letter. |
| `NATIVE_MODULE_UNAVAILABLE` | No | every facade method | Verify New Architecture; rebuild native app. |
| `BACKGROUND_TASK_REGISTRATION_FAILED` | No | `enableBackgroundSync`. `disableBackgroundSync` and `isBackgroundSyncEnabled` reject with it only as the fallback code for a native rejection | iOS: check the `Info.plist` identifiers, that you are on a physical device, and that Background App Refresh is on. Android: leave `requiresDeviceIdle` unset, and make sure WorkManager can initialize: an app that removes its default initializer must implement `Configuration.Provider`. |
| `INVALID_URL` | No | `enqueue`, `configureSync` (`healthCheckUrl`) | Pass an absolute http(s) URL. |
| `UNAUTHORIZED` | No | Item events, dead letters | Call `configureSync` with a fresh token, then replay the dead letters. |
| `TIMEOUT` | Yes | Item events | Engine retries; raise `requestTimeoutMs` if requests legitimately take longer. On Android, that limit running out is reported as `NETWORK_ERROR`, and `TIMEOUT` means a fixed socket timeout fired first: 10 s to connect, or 30 s for a stalled read or write. Raising `requestTimeoutMs` does not lengthen those. |
| `MAX_ATTEMPTS_EXCEEDED` | No | `ITEM_FAILED`, dead letters | Replay once the cause is fixed. Raise `maxAttempts` or expand `retryOnStatusCodes` if it recurs. |
| `DUPLICATE_ITEM` | No | Never: reserved | None. |
| `DRAIN_ALREADY_ACTIVE` | No | `drainUntilEmpty` | Await the previous drain. |
| `NETWORK_QUALITY_TOO_LOW` | Yes (the cycle is skipped) | `SYNC_FAILED` | Nothing for automatic wakes. Call `flush()` again later. See [Skipped cycles](#skipped-cycles). |
| `HEALTH_CHECK_FAILED` | Yes (the cycle is skipped) | `SYNC_FAILED` | Check the health endpoint. Cycles resume after the backoff. |
| `DEAD_LETTERED` | No | `ITEM_DEAD_LETTERED` | Inspect, replay or discard the dead letter. |

## Wrapping Your Own Calls

A common pattern is to wrap `flush()` in your own helper so callers see a uniform error surface. `flush()` rejects only when its cycle dies or the native module is missing, so there are few cases to handle:

```ts
import {
  flush,
  SyncError,
  SyncErrorCode,
  type SyncResult,
} from '@gabriel-sisjr/react-native-sync-provider';

export async function safeFlush(): Promise<SyncResult | null> {
  try {
    return await flush();
  } catch (e) {
    if (e instanceof SyncError) {
      switch (e.code) {
        case SyncErrorCode.NETWORK_ERROR:
          // The cycle died. Items are still queued, so swallow and flush later.
          return null;
        default:
          throw e;
      }
    }
    throw e;
  }
}
```

## Per-Item Failures via `SyncResult`

`failureCount` works on both platforms. The per-item detail does not: `failedIds` and `errors` are always empty on iOS. On Android, `errors` maps each failed item to `"<SyncErrorCode>: <detail>"`:

```ts
const result = await flush();

if (result.failureCount > 0) {
  // Android only. On iOS, use the events below.
  for (const [itemId, error] of Object.entries(result.errors)) {
    console.warn(`item ${itemId} failed: ${error}`);
  }
}
```

An item that went back to wait for a retry counts as neither a success nor a failure, so a cycle whose items all failed retriably is 0/0.

## Listening for Failures via Events

Events carry the same detail on both platforms, and work for cycles you did not start:

```tsx
import { useSyncEvents } from '@gabriel-sisjr/react-native-sync-provider';

useSyncEvents({
  types: ['ITEM_FAILED', 'ITEM_DEAD_LETTERED', 'SYNC_FAILED'],
  onEvent: (e) => {
    console.warn(e.type, e.errorCode, e.itemId, e.metadata?.lastErrorCode);
  },
});
```

- `ITEM_FAILED` is emitted when an item fails permanently, with a real `errorCode`.
- `ITEM_DEAD_LETTERED` follows it right after, once the item is in the dead-letter queue. Its `errorCode` is always `DEAD_LETTERED`, and `metadata.lastErrorCode` repeats the real code. An item removed or cleared while its request was in flight gets `ITEM_FAILED` alone: it is never dead-lettered. See [What a Purge Stops](../api-reference/functions.md#what-a-purge-stops).
- `SYNC_FAILED` is emitted when a cycle ends with at least one permanent failure, dies, or is skipped. Its `errorCode` is set only when the cycle died or was skipped, so read per-item codes from `ITEM_FAILED`.

### Skipped Cycles

With [`minNetworkQuality`](./connectivity-detection.md#the-quality-gate) or [`healthCheckUrl`](./connectivity-detection.md#health-check) set, a flush can be skipped before it starts. That `SYNC_FAILED` has no `SYNC_STARTED` before it, and its `errorCode` is `NETWORK_QUALITY_TOO_LOW` or `HEALTH_CHECK_FAILED`. No item changed, so do not treat it as a delivery failure:

```ts
useSyncEvents({
  types: ['SYNC_FAILED'],
  onEvent: (e) => {
    switch (e.errorCode) {
      case SyncErrorCode.NETWORK_QUALITY_TOO_LOW:
        showBanner('Waiting for a better connection');
        break;
      case SyncErrorCode.HEALTH_CHECK_FAILED:
        showBanner('Server unreachable, retrying shortly');
        break;
      default:
        // The cycle died, or items failed: see ITEM_FAILED.
        break;
    }
  },
});
```

## Dead-Letter Flows

An item that fails permanently moves, in one transaction, from the queue to the dead-letter queue. It keeps the original request and adds `attempts`, `lastAttemptAt`, `lastErrorCode`, an optional `lastErrorMessage` and `deadLetteredAt`: see [`SyncDeadLetterItem`](../api-reference/types.md#syncdeadletteritem). Nothing retries it, nothing prunes it, and it counts toward no capacity cap.

### Inspect

```ts
import { listDeadLetter } from '@gabriel-sisjr/react-native-sync-provider';

const deadLetters = await listDeadLetter(); // oldest first
for (const item of deadLetters) {
  report({
    id: item.id,
    url: item.url,
    code: item.lastErrorCode,
    detail: item.lastErrorMessage, // "HTTP 404 Not Found" on iOS, "HTTP 404" on Android over HTTP/2
  });
}
```

`lastErrorMessage` never contains headers or bodies, but `url` can carry a token in its query string: redact before you log it. In a component, [`useSyncSnapshot().deadLetter`](../api-reference/hooks/useSyncSnapshot.md) keeps the list current.

### Replay After Fixing the Cause

A replay puts the item back as a fresh `PENDING` item under the same id, with `attempts: 0` and `nextRetryAt: 0`, and emits `ITEM_ENQUEUED`. It does not flush by itself.

```ts
import {
  configureSync,
  flush,
  listDeadLetter,
  replayDeadLetter,
  SyncErrorCode,
} from '@gabriel-sisjr/react-native-sync-provider';

// The token expired: renew it, then retry what it got rejected.
await configureSync({ ...SYNC_OPTIONS, defaultHeaders: session.headers });

for (const item of await listDeadLetter()) {
  if (item.lastErrorCode === SyncErrorCode.UNAUTHORIZED) {
    await replayDeadLetter(item.id);
  }
}
await flush();
```

A replay respects `maxQueueSize` like an enqueue. On a full queue it rejects with `INVALID_PAYLOAD` and no `validationErrors` ([`QUEUE_FULL` does not reach JS yet](../api-reference/errors.md#native-rejections)), and the dead letter stays in place. An unknown id resolves with no change.

### Discard

```ts
import {
  discardDeadLetter,
  listDeadLetter,
  SyncErrorCode,
} from '@gabriel-sisjr/react-native-sync-provider';

// A status that is not retried, such as a 400 or a 404, usually fails again on a replay.
for (const item of await listDeadLetter()) {
  if (item.lastErrorCode === SyncErrorCode.SERVER_ERROR) {
    await discardDeadLetter(item.id);
  }
}
```

`discardDeadLetter` emits `ITEM_REMOVED`. `removeItem(id)` also deletes a dead letter, and reports whether a row was deleted.

### Purge on Logout

Dead letters keep their headers and body in plain text, like the queue. `clearQueue()` deletes the queue and the dead-letter queue together, with one `QUEUE_CLEARED`:

```ts
await clearQueue();
await configureSync({ strategy, retryPolicy }); // no defaultHeaders: deletes the stored copy
```

A plain `clearQueue()` is enough, even while a cycle runs. An item whose request has not started is not sent. A request already in flight is not cancelled and can still reach the server, but its item is never written back: not to the queue, and not to the dead-letter queue if the request fails. See [What a Purge Stops](../api-reference/functions.md#what-a-purge-stops).

Clear before you drop the credentials: in the other order, a cycle that starts in between sends the queued items without them.

## Next Steps

- [Errors](../api-reference/errors.md) -- The full `SyncErrorCode` enum with TSDoc
- [Retry Policy](./retry-policy.md) -- How retryable codes are scheduled
- [Troubleshooting](../troubleshooting.md) -- Symptom / cause / fix for common errors
