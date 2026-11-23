---
sidebar_position: 3.5
title: Dispatch Interceptors
description: Supply headers per request with a JS header resolver, observe or defer each request with beforeDispatch, and trace outcomes with afterDispatch. Covers the fail-closed rule, callbackTimeoutMs, the install order and the lifecycle they share with the retry decider.
keywords:
  - dispatch-interceptors
  - header-resolver
  - before-dispatch
  - after-dispatch
  - token-refresh
  - callback-timeout
  - interceptor-failed
---

# Dispatch Interceptors

This guide explains the three JS callbacks that run around each request: the header resolver, `beforeDispatch` and `afterDispatch`. It also covers the rules they share with the [retry decider](./retry-policy.md#retry-decider): when they run, how long native waits for them, and what happens when one fails.

## Overview

**Added in v0.4.** Each callback is a function you install from JS. It may be sync or async.

| Function | Called | Your function can | When it fails or does not answer in time |
| --- | --- | --- | --- |
| [`setHeaderResolver`](../api-reference/functions.md#setheaderresolver) | Right before each request, retries included | Add headers, such as a fresh access token | The item is [deferred](#when-an-interceptor-fails), unsent |
| [`setBeforeDispatch`](../api-reference/functions.md#setbeforedispatch) | After the resolver, with the merged headers | Let the request go, or defer the item with `SKIP` | The item is deferred, unsent |
| [`setAfterDispatch`](../api-reference/functions.md#setafterdispatch) | After each request's outcome is stored and its `ITEM_*` event emitted | Observe the outcome | Only logged |
| [`setRetryDecider`](../api-reference/functions.md#setretrydecider) | After a failed attempt that the `RetryPolicy` would retry | Change the delay, drop the item or dead-letter it | The `RetryPolicy` decides |

- A `set…` call replaces the function installed before it. Each has a `clear…` twin: `clearHeaderResolver`, `clearBeforeDispatch`, `clearAfterDispatch` and `clearRetryDecider`.
- Only the retry decider has an installed check, `isRetryDeciderInstalled()`. The interceptors have none.
- A `set…` call whose argument is not a function rejects with `INVALID_PAYLOAD` and `validationErrors`, before any native call.
- On web, the `set…` functions and `isRetryDeciderInstalled` throw `NATIVE_MODULE_UNAVAILABLE`, and the `clear…` functions do nothing.

> **Warning — Callbacks need a running JS runtime:** They run only in a process where JS is alive and installed them. A background launch with no JS sends the stored `defaultHeaders` alone, and the `RetryPolicy` decides its retries. Keep the credentials your backend needs in `defaultHeaders` too. See [When No JS Runs](#when-no-js-runs).

## Install Them First at Startup {#install-them-first-at-startup}

The first library call in a process creates the native module, and that arms the retry wake. Every `configureSync` arms it too. An item that is already due can then be dispatched before your `set…` call lands, with the static headers alone. So make the `set…` calls the first library calls at startup: before `configureSync`, and before `<SyncProvider>` or any hook mounts.

`<SyncProvider>` calls `configureSync` from an effect, and React runs that effect before the effects of the component that renders the Provider. An effect in your `App` is therefore too late. Install the callbacks at module scope of your entry file, before `AppRegistry.registerComponent`:

```ts
// index.js
import { AppRegistry } from 'react-native';
import App from './src/App';
import { name as appName } from './app.json';
import { installSyncCallbacks } from './src/sync/installSyncCallbacks';

// Before anything else calls the library.
installSyncCallbacks().catch((e) =>
  console.warn('Sync callbacks not installed', e)
);

AppRegistry.registerComponent(appName, () => App);
```

```ts
// src/sync/installSyncCallbacks.ts
import {
  setAfterDispatch,
  setHeaderResolver,
} from '@gabriel-sisjr/react-native-sync-provider';
import { resolveHeaders } from './resolveHeaders'; // see Token Refresh below
import { recordDispatch } from './recordDispatch'; // see afterDispatch below

export function installSyncCallbacks(): Promise<void[]> {
  // Start every call before awaiting any of them.
  return Promise.all([
    setHeaderResolver(resolveHeaders),
    setAfterDispatch(recordDispatch),
  ]);
}
```

Each `set…` function creates the native module when needed and reaches its native call before its first `await`, so calls started back to back all land in one turn of JS. That keeps the window between the module's creation and the installs as short as it can be. It does not close it: an item due at that moment can still go out with the static headers alone. The modules `index.js` imports load before its body runs, so none of them may call the library while it loads.

Install unconditionally. A `set…` call replaces what this JS runtime installed, and a fresh runtime starts with nothing installed, so a guard such as `if (!(await isRetryDeciderInstalled()))` can only keep an older function in place.

## Header Resolver {#header-resolver}

Install one with [`setHeaderResolver`](../api-reference/functions.md#setheaderresolver). It is called right before each request, retries included, with a `HeaderResolverContext`:

| Field | Type | Description |
| --- | --- | --- |
| `itemId` | `string` | The item's id. |
| `attempt` | `number` | The 1-based attempt about to be made. |
| `url` | `string` | The item's URL. |
| `method` | `HttpMethod` | The item's method. |
| `metadata` | `Record<string, string>` | The item's `metadata`, when it has any. |

It returns the headers to add, or a promise of them:

- **A plain object whose values are all strings.** An object literal, or one without a prototype. A class instance, a `Map`, an array, `undefined` or `null` is an invalid result.
- **Sendable names and values.** A name must be non-empty and use only the characters `!` to `~`, so no space. A value may hold only tab and the characters from space to `~`, so no non-ASCII text. One header that breaks a rule makes the whole answer invalid.
- **Merged under the item's own headers.** The order is `defaultHeaders`, then the resolver's headers, then the item's `headers`, a later layer winning. Names match case-insensitively, and the winning layer's spelling is kept. See [`defaultHeaders` and Per-Item Overrides](./priority-and-ordering.md#defaultheaders-and-per-item-overrides).

An invalid result fails the resolver, and the item is [deferred](#when-an-interceptor-fails).

### Token Refresh

On Android, a batch dispatches concurrently, so up to `batchSize` resolver calls can be in flight at once. Share one refresh between them:

```ts
// src/sync/resolveHeaders.ts
import type { HeaderResolver } from '@gabriel-sisjr/react-native-sync-provider';
import { session } from '../auth/session'; // your app's auth module

let refreshing: Promise<string> | null = null;

// One refresh at a time, however many requests ask for a token.
function freshAccessToken(): Promise<string> {
  if (!session.expiresWithin(60_000)) {
    return Promise.resolve(session.accessToken);
  }
  if (!refreshing) {
    refreshing = session.refresh().finally(() => {
      refreshing = null;
    });
  }
  return refreshing;
}

export const resolveHeaders: HeaderResolver = async () => ({
  Authorization: `Bearer ${await freshAccessToken()}`,
});
```

- **Refresh before the token expires.** A resolver's token does not change the credential rules. In a process that called `configureSync`, a 401 or 403 is terminal: the item is dead-lettered with `UNAUTHORIZED`, and the retry decider is not consulted. In a process running on stored `defaultHeaders`, it is a [credential hold](./background-sync.md#when-a-stored-token-expires).
- **Keep `defaultHeaders` current too.** A run with no JS calls no resolver: it sends the stored `defaultHeaders`. Call `configureSync` with the current token whenever it changes. With both set, the resolver's `Authorization` wins while JS runs.
- **Mind the timeout.** A refresh slower than `callbackTimeoutMs` defers every item waiting on it, for at least 60 seconds. Raise [`callbackTimeoutMs`](#callbacktimeoutms) if your refresh is slow.
- **Pass an arrow function or a bound method.** The library calls your function without a `this`, so `setHeaderResolver(auth.getHeaders)` throws on every call and defers every item. Use `setHeaderResolver(() => auth.getHeaders())`.
- **Keep secrets out of error messages.** An `Error`'s message reaches the native log. See [Messages and Logs](#messages-and-logs).

## `beforeDispatch` {#beforedispatch}

Install one with [`setBeforeDispatch`](../api-reference/functions.md#setbeforedispatch). It runs after the header resolver, right before each request, with a `BeforeDispatchContext`. It never receives the body.

| Field | Type | Description |
| --- | --- | --- |
| `itemId` | `string` | The item's id. |
| `attempt` | `number` | The 1-based attempt about to be made. |
| `url` | `string` | The item's URL. |
| `method` | `HttpMethod` | The item's method. |
| `headers` | `Record<string, string>` | The merged headers: `defaultHeaders`, then the resolver's, then the item's. |
| `contentType` | `string` | The item's `contentType`, as stored, when it has one. |
| `metadata` | `Record<string, string>` | The item's `metadata`, when it has any. |

`headers` holds a `Content-Type` only when a header layer sets one. The type the request takes from `contentType` when no layer sets one, or Android's `application/json` default, is not in the map: read `contentType` instead. On Android, the body's media type also replaces a layer's `Content-Type` on the wire, so `headers` can show a `Content-Type` that Android does not send. See [`Content-Type` Priority](./priority-and-ordering.md#content-type-priority).

What it returns decides what happens:

| It returns | What happens |
| --- | --- |
| `{ action: 'PROCEED' }` | The request goes out. |
| Nothing, `null`, a primitive, an array, or an object without an `action` key | The request goes out. |
| `{ action: 'SKIP' }` | The item is [deferred](#when-an-interceptor-fails), unsent. |
| An object whose `action` is any other value | A failure: the item is deferred. |
| A throw, a rejection, or no answer within `callbackTimeoutMs` | A failure: the item is deferred. |

So an observer such as `(c) => track(c)` proceeds, unless `track` returns an object with an `action` key. That `action` then decides, and any value other than `PROCEED` or `SKIP` is a failure that defers the item. To observe only, use a block body that returns nothing: `(c) => { track(c); }`. A bare `'SKIP'` string is a primitive, and proceeds: return `{ action: BeforeDispatchAction.SKIP }`.

```ts
// src/sync/beforeDispatch.ts
import {
  BeforeDispatchAction,
  type BeforeDispatchHook,
} from '@gabriel-sisjr/react-native-sync-provider';
import { settings } from '../settings'; // your app's settings store

export const beforeDispatch: BeforeDispatchHook = ({ url, attempt }) => {
  // Observe: log the path only, since a query string can carry a token.
  console.log(`Sending ${url.split('?')[0]}, attempt ${attempt}`);

  // Defer media uploads while the user has paused them.
  if (settings.uploadsPaused && url.includes('/uploads/')) {
    return { action: BeforeDispatchAction.SKIP };
  }
  return undefined; // proceed
};
```

> **Warning — `SKIP` defers, it does not discard:** A skipped item comes back no sooner than 60 seconds later, and is offered to the hook again. To give up on an item, call `removeItem`.

## `afterDispatch` {#afterdispatch}

Install one with [`setAfterDispatch`](../api-reference/functions.md#setafterdispatch). It is called once for each request that went out, after its outcome is stored and its `ITEM_*` event emitted. It is never called for an item that was not sent: deferred, purged, or out of attempts. A request whose outcome is settled after its [cycle dies](./error-handling.md#when-a-cycle-dies) gets no call either.

- **Not awaited.** Dispatch never waits for it, and `flush()` may resolve before it runs.
- **Best-effort order.** It is queued behind its item's event, but that order is not guaranteed.
- **Its result is ignored.** It may return anything. A throw, a rejection or a timeout is only logged.
- **iOS background uploads.** It is not called for an upload that completes after a relaunch: no JS runs then.

It receives an `AfterDispatchContext`, which never carries headers or a body:

| Field | Type | Description |
| --- | --- | --- |
| `itemId` | `string` | The item's id. |
| `attempt` | `number` | The 1-based attempt that was made. |
| `outcome` | `DispatchOutcome` | `SUCCEEDED`, `RETRYING` or `FAILED`. See below. |
| `statusCode` | `number` | The HTTP status, when a response came back. |
| `errorCode` | `SyncErrorCode` | The failure code, when the request failed. |
| `url` | `string` | The item's URL. |
| `method` | `HttpMethod` | The item's method. |
| `metadata` | `Record<string, string>` | The item's `metadata`, when it has any. |
| `durationMs` | `number` | The request's own duration, in whole milliseconds. It leaves out every callback wait. |

| `outcome` | When | `errorCode` |
| --- | --- | --- |
| `SUCCEEDED` | A `2xx` | None |
| `RETRYING` | The item was rescheduled, or held after its stored credential was rejected | The attempt's code. A hold reports `UNAUTHORIZED` |
| `FAILED` | A permanent failure, the last attempt spent, or a retry decider's `DROP` or `DEAD_LETTER` | The real code, `MAX_ATTEMPTS_EXCEEDED`, or `RETRY_DECIDER_REJECTED` |

```ts
// src/sync/recordDispatch.ts
import type { AfterDispatchHook } from '@gabriel-sisjr/react-native-sync-provider';
import { tracer } from '../telemetry'; // your tracing client

export const recordDispatch: AfterDispatchHook = (c) => {
  tracer.record({
    name: `${c.method} ${c.url.split('?')[0]}`,
    outcome: c.outcome,
    statusCode: c.statusCode,
    errorCode: c.errorCode,
    durationMs: c.durationMs,
    attempt: c.attempt,
  });
};
```

## Order for One Item {#order}

For each item, a cycle runs these steps in order:

1. The purge check: an item removed or cleared is not sent.
2. The header resolver.
3. The merge of `defaultHeaders`, the resolver's headers and the item's own.
4. `beforeDispatch`.
5. A last check that the item is still queued. On iOS, claiming the item is that check.
6. The request.
7. The outcome, with the [retry decider](./retry-policy.md#retry-decider) when it applies.
8. The item's event.
9. `afterDispatch`, not awaited.

When the resolver fails, `beforeDispatch` is not called. An item that cannot be sent, because its retry budget is spent or its URL is invalid, reaches no interceptor. The health check and the network-quality probes never call a callback.

## When an Interceptor Fails {#when-an-interceptor-fails}

A header resolver or a `beforeDispatch` hook fails when it throws, rejects, returns an invalid result or does not answer within `callbackTimeoutMs`, and when it was left behind by a JS runtime that is gone (see [Reloads and Teardown](#reloads-and-teardown)). A failure and a `SKIP` defer the item the same way:

- **Nothing is sent, and no attempt is spent.** `attempts` and `lastAttemptAt` stay as they were.
- **The item waits.** It goes back to `PENDING`, due again after the policy's delay for the attempt it would have made, and no sooner than 60 seconds.
- **`ITEM_RETRYING` reports it,** with `errorCode: 'INTERCEPTOR_FAILED'`, no `statusCode`, and `attempt` set to the attempt it would have made.
- **Only that item waits.** It counts in neither `successCount` nor `failureCount`, and the cycle goes on with the next item.

A deferral never escalates. A broken resolver holds its items `PENDING`, never dead-letters them, and every cycle ends with `SYNC_SUCCEEDED` and 0/0. Watch for the code:

```tsx
import {
  SyncErrorCode,
  SyncEventType,
  useSyncEvents,
} from '@gabriel-sisjr/react-native-sync-provider';

useSyncEvents({
  types: [SyncEventType.ITEM_RETRYING],
  onEvent: (e) => {
    if (e.errorCode === SyncErrorCode.INTERCEPTOR_FAILED) {
      console.warn(`Item ${e.itemId} deferred before attempt ${e.attempt}`);
    }
  },
});
```

The event is the only place the code shows: a `SyncItem` carries no error code, and `getPendingItems()` shows a deferred item only through its later `nextRetryAt`.

A cycle stopped during a wait, by a BGTask expiry or a cancelled worker, is not a failure: nothing is sent, the item stays as it was, and no event is emitted. An item removed or cleared during a wait is not sent and gets no event.

## `callbackTimeoutMs` {#callbacktimeoutms}

| Option | Default | Meaning |
| --- | --- | --- |
| [`callbackTimeoutMs`](../api-reference/types.md#syncoptions) | `3000` | How long native waits for an installed callback to answer, in milliseconds. All four callbacks share it. |

```ts
await configureSync({ ...SYNC_OPTIONS, callbackTimeoutMs: 5000 });
```

- **Validated before anything changes.** `configureSync` rejects a value that is not finite or is below `1` with `INVALID_PAYLOAD` and `validationErrors`, before it applies or stores anything. Fractions truncate, and a value above 2147483647 is capped there when used.
- **Echoed only when set.** `getSyncConfig()` returns it only when it was set, as given. It is persisted with the rest of the configuration, and a damaged stored value loads as unset.
- **A hard bound.** The wait ends on time even if your promise never settles.
- **No wait for a failure.** A throw, a rejection or an invalid result answers at once, so native does not wait out the timeout.

Native cannot tell a slow answer from a JS thread that is busy, hung or gone after a reload, so each of those costs the full wait. Size it for your slowest legitimate answer, such as a token refresh, and no longer. See [Concurrency](#concurrency) for what the wait costs a cycle.

## Reloads and Teardown {#reloads-and-teardown}

An install belongs to the JS runtime that made it:

- **The newest runtime wins.** The first library call of a new JS runtime evicts every install an older one made, and ends their pending waits at once. Nothing is installed again until the new runtime calls `set…`, so until then items go out with the static headers. Installing first keeps that gap short.
- **A stale runtime cannot install.** A `set…` call from a runtime that a newer one replaced, or that was torn down, rejects with `NATIVE_MODULE_UNAVAILABLE`.
- **Each runtime sees its own installs.** `clear…` and `isRetryDeciderInstalled()` act on the calling runtime's installs only.
- **One runtime at a time.** Two live JS runtimes in one process evict each other's installs: the one that started using the library last owns them, and the other one's `set…` calls are refused.

Between a reload and the new JS's first library call, the old installs behave differently on each platform:

| Platform | What happens to the old installs | Until the new JS first calls the library |
| --- | --- | --- |
| iOS | The old runtime's teardown retires them. They stay in place, and are never called. | Each consult ends at once, without calling JS: a resolver or `beforeDispatch` defers the item, a decider leaves the decision to the policy, and an `afterDispatch` call is only logged. |
| Android | A reload runs no teardown, so they stay installed, tied to a runtime that is gone. If the old module is disposed, its installs are retired as on iOS. | Each consult waits `callbackTimeoutMs` for an answer that never comes, then the same deferral or fallback applies. |

On both platforms, until the new JS first calls the library, a resolver whose runtime is gone holds its items instead of letting them go out without its headers:

- **Android.** An item whose retry falls due before the new JS attaches waits out the old install's timeout, and is deferred for about 60 seconds. It then goes out with the new runtime's headers, once that runtime has installed its resolver.
- **iOS.** The retry timer is disarmed while no hybrid is attached, so a retry that falls due during the reload waits for the new runtime instead of going out with the static headers.

## When No JS Runs {#when-no-js-runs}

A worker or a BGTask in a process where JS never started has nothing installed, and neither has any cycle that runs before the first `set…` call:

- The request goes out with the stored `defaultHeaders`. No resolver or hook runs.
- The declarative `RetryPolicy` decides every retry.

A credential needed without JS must therefore live in `defaultHeaders`. With no stored `defaultHeaders`, a 401 in a run with no JS is terminal: the item is dead-lettered with `UNAUTHORIZED`. See [Tokens and Logout](./background-sync.md#tokens-and-logout).

## Concurrency {#concurrency}

- **Android** dispatches a batch concurrently, so up to `batchSize` callback calls can be in flight at once, all on the one JS thread. A resolver must dedupe its own token refresh, as in [Token Refresh](#token-refresh). With JS hung, the items' waits overlap, so they cost the batch about one `callbackTimeoutMs`.
- **iOS** dispatches one item at a time, so each awaited callback adds its wait to that item. With JS hung, every item costs up to `callbackTimeoutMs`.
- **Android, on stored `defaultHeaders`.** A batch first sends one item alone, to check the stored credential. When that item is deferred, the next one goes alone instead. With JS hung, such a cycle can take about `batchSize` × `callbackTimeoutMs`.

## Never Await `flush()` in a Callback {#never-await-flush-in-a-callback}

A callback runs inside the cycle that asked it. Do not call `flush()` or `drainUntilEmpty()` from it:

- **Android.** The cycle waits for your callback, and `flush()` waits for the cycle. Your callback's wait ends at `callbackTimeoutMs`, then the fallback or the deferral applies, and only then does your `flush()` run.
- **iOS.** `flush()` during a running cycle returns the last result at once, so it sends nothing new.

## Messages and Logs {#messages-and-logs}

- When your function throws or rejects with an `Error`, its message goes to the native log, cut to 256 characters. An empty message is replaced by the error's name. Keep tokens and response bodies out of the messages your callbacks throw.
- A thrown value that is not an `Error` is described by its type only, such as "a thrown string", never by its content.
- Headers, bodies and contexts are never logged.
- iOS logs that text as private, under the `callbacks` category. Android writes it to logcat, under the `SyncCallbacks` tag, where other apps cannot read it. See [Debugging](../development/debugging.md).

## Platform Differences {#platform-differences}

| Behavior | Android | iOS |
| --- | --- | --- |
| Callback calls in flight in one cycle | Up to `batchSize` | One |
| After a JS reload | Each consult times out, until the new JS first calls the library | Each consult fails at once, until the new JS first calls the library |
| `getPendingItems()` during an interceptor wait | Leaves the item out: it is `IN_FLIGHT` | Includes the item: it stays `PENDING` until it is claimed |
| `configureSync` during an interceptor wait | Applies from the next cycle | Applies from the next item |
| `Content-Type` in `beforeDispatch` `headers` | Can show a layer's `Content-Type` that the body's media type replaces on the wire | The layer's `Content-Type` is the one sent |
| A retry decider's `DROP` in `SyncResult` | Listed in `failedIds` and `errors` | Not listed: both stay empty on iOS |
| `flush()` awaited in a callback | Stalls the cycle until `callbackTimeoutMs` | Returns the last result at once |
| Logged JS text | logcat, tag `SyncCallbacks` | `os.log`, private, category `callbacks` |
| Background uploads | Not applicable | The upload carries the headers resolved when it was created, and gets no `afterDispatch` if it completes after a relaunch |

See [Platform Comparison](../production/platform-comparison.md#js-callbacks) for the rest of the library.

## Next Steps

- [Retry Policy](./retry-policy.md#retry-decider) -- The retry decider, its actions and its fallback
- [Error Handling](./error-handling.md) -- `INTERCEPTOR_FAILED` and `RETRY_DECIDER_REJECTED` in the code matrix
- [Background Sync](./background-sync.md#tokens-and-logout) -- Tokens, logout and the runs with no JS
- [Priority and Ordering](./priority-and-ordering.md#defaultheaders-and-per-item-overrides) -- How the header layers merge
- [Troubleshooting](../troubleshooting.md#symptom-items-stay-pending-with-interceptor_failed) -- Deferred items, timeouts and reloads
