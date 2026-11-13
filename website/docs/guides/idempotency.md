---
sidebar_position: 7
title: Idempotency
description: Why react-native-sync-provider delivers at least once, how to send an Idempotency-Key that stays the same across retries, and the server-side dedup pattern.
keywords:
  - idempotency
  - ulid
  - at-least-once
  - duplicate-prevention
  - idempotency-key
  - sync-queue
  - retry
---

# Idempotency

This guide covers why a request can reach your server more than once, and the pattern that makes it safe.

## Overview

`react-native-sync-provider` ships **at-least-once** delivery: a request may reach your server more than once. Endpoints that receive queued requests must be idempotent.

## When a Request Can Be Sent Twice

The library can only record an outcome it received. A request is sent again when the server may have processed it but the device never learned that:

- **The process died mid-request.** [Launch recovery](./crash-recovery.md) puts the item back to `PENDING`, and the next cycle sends it again.
- **The response was lost.** A timeout or a dropped connection after the server accepted the request looks like a retriable failure.
- **An iOS background upload finished after a relaunch.** Its outcome is not recorded, so the item comes back through launch recovery with its attempt spent and no cooldown.

No retry policy can close these windows from the client. Only the server can recognize a repeat.

## The Recommended Pattern: Send an `Idempotency-Key`

Every server you POST mutations to should accept an [`Idempotency-Key` header](https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header). Generate the key **before** `enqueue` with [`generateId`](../api-reference/functions.md#generateid), and pass it in `headers`:

```tsx
import {
  enqueue,
  generateId,
  HttpMethod,
} from '@gabriel-sisjr/react-native-sync-provider';

await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/orders',
  contentType: 'application/json',
  headers: { 'Idempotency-Key': generateId() },
  body: JSON.stringify({ productId: 'sku-42', qty: 1 }),
});
```

The headers are persisted with the item, so every retry carries the same key, including retries that happen days later from a background task.

If the same user action can reach `enqueue` twice, such as a double tap, create the key once per action and reuse it, so both requests dedupe to one.

A resource id in the URL works too. `PUT /orders/{id}` with an id you generate is naturally idempotent, and the server only needs to check whether the resource already exists.

> **Note — The native ULID does not reach the server:** `enqueue` returns a ULID, which the native layer assigns when it persists the item. It identifies the item for [`removeItem`](../api-reference/functions.md#removeitem) and for sync events. It is not sent with the request, and it only exists after `enqueue` returns, so you cannot put it in that request's headers.

`generateId` returns a UUID v4 backed by `Math.random()`. That is good enough for deduplication within a client, but do not use it for anything an adversary should not be able to guess.

## Server-Side Dedup

Whatever transport you choose (header, body field, URL path), the server needs to:

1. Extract the key.
2. Look it up in a short-lived dedup table (e.g. Redis with 24h TTL, or a `processed_requests` table keyed on `(idempotency_key, endpoint)`).
3. If the key is found, **return the previously-stored response** without re-executing the side effect.
4. Otherwise, execute the request, store `(idempotency_key, status, response_body)`, and return.

Pseudocode:

```ts
// Server-side handler
async function handleOrder(req) {
  const key = req.headers['idempotency-key'];
  if (!key) return { status: 400, body: 'Missing Idempotency-Key' };

  const cached = await dedupStore.get(key);
  if (cached) return cached;

  const result = await createOrder(req.body);
  await dedupStore.set(key, result, { ttl: 86_400 });
  return result;
}
```

Keep the TTL longer than the longest time an item can stay queued. An item can wait days for connectivity.

## What NOT to Do

| Anti-pattern | Why it fails |
|---|---|
| Relying on the `SyncItem.id` | The server never sees it. |
| Enqueueing again with a new key after a failure | The server treats it as a new request. Reuse the key if you re-enqueue the same action. |
| Skipping the key for `GET` requests | Safe in theory (GETs should be idempotent), but if the server has any side effect (e.g. logging, billing-by-request), you'll double-count. |
| Trusting the response body to dedup | Two requests with the same key can race on the server. Dedup on the key, atomically. |
| Using the item's `metadata` to store an idempotency key | `metadata` is not transmitted. Use `headers`. |

## Next Steps

- [Retry Policy](./retry-policy.md) -- How interrupted requests get retried in the first place
- [Background Sync](./background-sync.md) -- The most likely place a request is dispatched twice
- [Crash Recovery](./crash-recovery.md) -- How an interrupted request goes back to the queue
- [Functions](../api-reference/functions.md#enqueue) -- The `enqueue` input shape
