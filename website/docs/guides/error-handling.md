---
sidebar_position: 5
title: Error Handling
description: Discriminate errors thrown by react-native-sync-provider — SyncError class, SyncErrorCode matrix, retryable codes, and recommended handler patterns.
keywords:
  - error-handling
  - sync-error
  - sync-error-code
  - retryable-errors
  - exception-handling
  - typescript-errors
---

# Error Handling

Every public function in `react-native-sync-provider` throws `SyncError` -- a typed `Error` subclass carrying a stable `SyncErrorCode`. Consumers should branch on `error.code`, never on the message string.

Failures reach you in two places. A call that cannot run rejects. A request that fails is reported per item, on events: a failed request never rejects.

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

:::warning `QUEUE_FULL` does not reach JS yet
Each facade function maps a native rejection to one fallback code, so a full queue rejects with `INVALID_PAYLOAD` and no `validationErrors`. See [Native rejections](../api-reference/errors.md#native-rejections).
:::

## Full `SyncErrorCode` Matrix

| Code | Retryable? | Where you see it | Suggested action |
|---|---|---|---|
| `NETWORK_ERROR` | Yes | Item events. `flush()` also rejects with it when its cycle dies. | Wait for connectivity; the engine retries automatically. |
| `SERVER_ERROR` | If in `retryOnStatusCodes` | Item events | The engine retries a listed status; surface only if persistent. |
| `INVALID_PAYLOAD` | No | `enqueue`, `enqueueBatch`, and most functions when native rejects | Fix the input shape. With no `validationErrors`, native rejected the call. |
| `QUEUE_FULL` | No (until drained) | Reaches JS as `INVALID_PAYLOAD` for now | Flush or raise `maxQueueSize`. |
| `NATIVE_MODULE_UNAVAILABLE` | No | every facade method | Verify New Architecture; rebuild native app. |
| `BACKGROUND_TASK_REGISTRATION_FAILED` | No | `enableBackgroundSync`, `disableBackgroundSync`, `isBackgroundSyncEnabled` | Check the `Info.plist` identifiers, and that you are on a physical device. |
| `INVALID_URL` | No | `enqueue` | Pass an absolute http(s) URL. |
| `UNAUTHORIZED` | No | Item events | Call `configureSync` with a fresh token, and enqueue a failed item again. |
| `TIMEOUT` | Yes | Item events | Engine retries; raise `requestTimeoutMs` if requests legitimately take longer. |
| `MAX_ATTEMPTS_EXCEEDED` | No | `ITEM_FAILED` | Raise `maxAttempts` or expand `retryOnStatusCodes`. |
| `DUPLICATE_ITEM` | No | Never: reserved | None. |
| `DRAIN_ALREADY_ACTIVE` | No | `drainUntilEmpty` | Await the previous drain. |

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
  types: ['ITEM_FAILED', 'SYNC_FAILED'],
  onEvent: (e) => {
    console.warn(e.type, e.errorCode, e.itemId);
  },
});
```

`ITEM_FAILED` is emitted when an item fails permanently, with a real `errorCode`. `SYNC_FAILED` is emitted when a cycle ends with at least one permanent failure, or dies. Its `errorCode` is set only when the cycle died, so read per-item codes from `ITEM_FAILED`.

An item that fails permanently stays in the store as a `FAILED` row: see [Permanent Failures](./retry-policy.md#permanent-failures).

## Next Steps

- [`SyncErrorCode` reference](../api-reference/errors.md) -- The full enum with TSDoc.
- [Retry Policy](./retry-policy.md) -- How retryable codes are scheduled.
- [Troubleshooting](../troubleshooting.md) -- Symptom / cause / fix for common errors.
