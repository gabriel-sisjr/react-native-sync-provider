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

## The `SyncError` Class

```ts
class SyncError extends Error {
  readonly code: SyncErrorCode;
  readonly cause?: unknown;
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
    case SyncErrorCode.QUEUE_FULL:
      // back off and retry once the queue drains
      break;
    case SyncErrorCode.INVALID_URL:
    case SyncErrorCode.INVALID_PAYLOAD:
      // surface to UX -- this will not succeed on retry
      break;
    case SyncErrorCode.NATIVE_MODULE_UNAVAILABLE:
      // running on web/SSR or before the native module loaded
      break;
    default:
      throw e;
  }
}
```

## Full `SyncErrorCode` Matrix

| Code | Retryable? | Typically thrown by | Suggested action |
|---|---|---|---|
| `NETWORK_ERROR` | Yes | `flush` | Wait for connectivity; the engine retries automatically. |
| `SERVER_ERROR` | Yes (subject to `retryOnStatusCodes`) | `flush` | The engine retries; surface only if persistent. |
| `INVALID_PAYLOAD` | No | `enqueue`, `enqueueBatch` | Fix the input shape; the request will never succeed. |
| `QUEUE_FULL` | No (until drained) | `enqueue`, `enqueueBatch` | Drain via `flush()` or raise `maxQueueSize`. |
| `NATIVE_MODULE_UNAVAILABLE` | No | every facade method | Verify New Architecture; rebuild native app. |
| `BACKGROUND_TASK_REGISTRATION_FAILED` | No | `enableBackgroundSync`, `disableBackgroundSync`, `isBackgroundSyncEnabled` | Check Background Modes capability and Info.plist identifier. |
| `INVALID_URL` | No | `enqueue`, `enqueueBatch` | Pass an absolute http(s) URL. |
| `UNAUTHORIZED` | No | `flush` | Refresh credentials and re-enqueue. |
| `TIMEOUT` | Yes | `flush` | Engine retries; raise `requestTimeoutMs` if requests legitimately take longer. |
| `MAX_ATTEMPTS_EXCEEDED` | No | `flush` (per-item, surfaced via `SyncResult.errors`) | Raise `maxAttempts` or expand `retryOnStatusCodes`. |
| `DUPLICATE_ITEM` | No | `enqueue`, `enqueueBatch` | The item is already queued; do not re-enqueue. |

## Wrapping Your Own Calls

A common pattern is to wrap `flush()` in your own helper so callers see a uniform error surface:

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
        case SyncErrorCode.TIMEOUT:
          // Transient -- swallow; AUTOMATIC strategy will re-fire.
          return null;
        case SyncErrorCode.UNAUTHORIZED:
          await refreshAuthToken();
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

`flush()` returns a `SyncResult` whose `errors` field maps each failed item to the dominant `SyncErrorCode` value (as a string):

```ts
const result = await flush();

for (const [itemId, code] of Object.entries(result.errors)) {
  console.warn(`item ${itemId} failed: ${code}`);
}
```

This lets you process partial failures without a try/catch around each item.

## Listening for Failures via Events

```tsx
import { useSyncEvents } from '@gabriel-sisjr/react-native-sync-provider';

useSyncEvents({
  types: ['ITEM_FAILED', 'SYNC_FAILED'],
  onEvent: (e) => {
    console.warn(e.type, e.errorCode, e.itemId);
  },
});
```

`ITEM_FAILED` is emitted when an item exhausts its retry budget. `SYNC_FAILED` is emitted when the entire flush cycle aborts (e.g., a transport-level failure during the cycle).

## Next Steps

- [`SyncErrorCode` reference](../api-reference/errors.md) -- The full enum with TSDoc.
- [Retry Policy](./retry-policy.md) -- How retryable codes are scheduled.
- [Troubleshooting](../troubleshooting.md) -- Symptom / cause / fix for common errors.
