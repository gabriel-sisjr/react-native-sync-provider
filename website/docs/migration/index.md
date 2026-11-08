---
sidebar_position: 1
title: Migration Guides
description: Upgrade guides for breaking and behavior changes between versions of @gabriel-sisjr/react-native-sync-provider, including the v0.2 to v0.3 and v0.1 to v0.2 upgrades.
keywords:
  - migration
  - upgrade
  - breaking changes
  - versioning
  - semver
---

# Migration Guides

The library is pre-1.0, so minor versions can carry breaking changes. Each upgrade is described here, newest first.

:::note
The canonical list of breaking and behavior changes lives in [`BREAKING_CHANGES.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/BREAKING_CHANGES.md). This page turns it into an upgrade procedure.
:::

## v0.2 → v0.3 {#v0-2-to-v0-3}

No function, hook or error code was removed, and the quality gate and the health check are opt-in. Four things change for every app: `react-native-nitro-modules` must be 0.35.8 or later, permanently failed items move to a dead-letter queue, `networkQuality` is a required field, and connection events fire only on real changes. Read the whole list before you ship.

:::danger Roll-forward only
The upgrade migrates the stores in place, and there is no supported downgrade. Downgrading an Android install wipes the queue and the dead letters. See [Rolling back](#rolling-back-from-v03).
:::

### Step 1: Upgrade and rebuild

1. Upgrade the package, and `react-native-nitro-modules` to 0.35.8 or later, below 0.36: `yarn add "react-native-nitro-modules@^0.35.8"`. In 0.35.6 and 0.35.7, an iOS call could leave its JS promise pending forever. The fix was first published in 0.35.8 on npm. Nitro's GitHub has no v0.35.8 release and lists the fix in its v0.35.9 notes.
2. On iOS, run `cd ios && bundle exec pod install`, then rebuild. The pod ships a new Core Data model version.
3. On Android, rebuild. No new permission is needed.

### Step 2: What migrates automatically

- **Failed items.** Items that had failed permanently, which v0.2 kept as unlisted `FAILED` rows, move to the dead-letter queue when the upgraded library first opens its store: Room v2→v3 on Android, v1→v2→v3 included, and Core Data model v3 on iOS, with explicit mappings from v1 and v2. Read them with [`listDeadLetter`](../api-reference/functions.md#listdeadletter). A migrated item has no `lastErrorMessage` and takes its last attempt time, or else its enqueue time, as `deadLetteredAt`, and one with no usable error code reports `MAX_ATTEMPTS_EXCEEDED`.
- **Pending items.** Untouched, with their attempts and cooldowns.
- **Configuration.** The persisted options carry over. `minNetworkQuality` and the health-check options start unset, so flushes behave as in v0.2 until you opt in.

:::note iOS migration
The iOS migration runs before the store loads, on every launch path, BGTask launches included. If it fails, the original store is untouched, that launch runs without a store, so every call rejects with `NATIVE_MODULE_UNAVAILABLE`, and the next launch retries. Stores written from 0.1.1-rc on migrate. A store written by the 0.1.0-beta prerelease is not a recognized source: it fails to load the same way, and the file is kept.
:::

### Step 3: Compile-time fixes

`ConnectionState.networkQuality` and `UseConnectionResult.networkQuality` are required. Code that only reads these types compiles as before. Hand-built values, usually test fixtures and mocks of `getConnectionStatus` or `useConnection`, need the field:

```ts
const online: ConnectionState = {
  status: 'CONNECTED',
  type: 'WIFI',
  networkQuality: 'GOOD',
};

const offline: UseConnectionResult = {
  status: 'DISCONNECTED',
  type: 'NONE',
  isOnline: false,
  isMetered: false,
  networkQuality: 'OFFLINE',
};
```

Keep the invariant the library keeps: `OFFLINE` exactly when `status` is `DISCONNECTED` or `UNKNOWN`. With no `healthCheckUrl`, an online link usually reports `GOOD` when unmetered and `MODERATE` when metered; an Android network that is not validated reports `POOR`, and iOS Low Data Mode at most `MODERATE`.

`SyncEventType` gains `ITEM_DEAD_LETTERED`, and `SyncErrorCode` gains `NETWORK_QUALITY_TOO_LOW`, `HEALTH_CHECK_FAILED` and `DEAD_LETTERED`. Add them to any exhaustive `switch` that ends in a `never` check. If you mock the library in tests with an explicit factory, add `listDeadLetter`, `replayDeadLetter` and `discardDeadLetter` to it.

### Step 4: Handle dead letters

An item that fails permanently now moves, in one transaction, to the dead-letter queue: `ITEM_FAILED` reports the real code, and `ITEM_DEAD_LETTERED` follows. Nothing retries a dead letter, nothing prunes the dead-letter queue, and it counts toward no capacity cap.

```ts
import {
  discardDeadLetter,
  flush,
  listDeadLetter,
  replayDeadLetter,
  SyncErrorCode,
} from '@gabriel-sisjr/react-native-sync-provider';

// For example after a token refresh.
for (const item of await listDeadLetter()) {
  if (item.lastErrorCode === SyncErrorCode.UNAUTHORIZED) {
    await replayDeadLetter(item.id); // back to the queue, attempts 0
  } else {
    await discardDeadLetter(item.id);
  }
}
await flush(); // a replay does not flush by itself
```

- **Where to read them.** [`listDeadLetter`](../api-reference/functions.md#listdeadletter), oldest first, or [`useSyncSnapshot().deadLetter`](../api-reference/hooks/useSyncSnapshot.md) in a component.
- **Replay.** [`replayDeadLetter`](../api-reference/functions.md#replaydeadletter) puts the item back under the same id, with `attempts: 0` and `nextRetryAt: 0`. It respects `maxQueueSize`: on a full queue it rejects with `INVALID_PAYLOAD` and keeps the dead letter.
- **Discard.** [`discardDeadLetter`](../api-reference/functions.md#discarddeadletter), or `removeItem(id)`, which falls through to the dead letter with that id.
- **Logout.** `clearQueue()` deletes the dead letters too. They keep their request headers and body, so keep it in the logout path. It does not need to wait for a running cycle: an item whose request has not started is not sent, and a request already in flight is not cancelled, but its item is never written back. See [What a purge stops](../api-reference/functions.md#what-a-purge-stops).
- **Android cap.** Failed items no longer count toward `maxQueueSize`. Code that removed them on `ITEM_FAILED` only to stay under the cap can go, but the dead-letter queue is unbounded, so discard what you will not replay.

See [Dead-letter flows](../guides/error-handling.md#dead-letter-flows) for more patterns.

### Step 5: Review behavior your code may rely on

| Area | What changed | What to do |
| --- | --- | --- |
| Permanent failures | Moved to the dead-letter queue. `ITEM_FAILED` is followed by `ITEM_DEAD_LETTERED`. | Handle dead letters as in Step 4. |
| `removeItem`, `clearQueue` | Also delete dead letters. `removeItem` returns `true` when either store deleted a row. | Nothing, unless you counted on dead letters surviving a `clearQueue()`. |
| Android `maxQueueSize` | Counts pending and in-flight items. Failed items no longer count. | Drop purges that only kept the queue under the cap. |
| `CONNECTION_CHANGED` | Fires only when a reported field changes, never for a duplicate, and on Android never for its starting state. iOS starts at `UNKNOWN`, so its first path report emits one. iOS used to emit it on every path update. Also fires when only `networkQuality` changed, with the same `connectionStatus`. iOS delivers it synchronously, Android asynchronously. | Read the initial state with `getConnectionStatus()`. Compare `connectionStatus` with the previous value before acting on a transition, as `useAutoSync` does. |
| Android connectivity | Follows the default network only: losing a secondary network no longer reports `DISCONNECTED`. | Expect fewer false disconnects. |
| Android 11 (API 30) and later | A temporarily unmetered link reads as `CONNECTED` with `isExpensive: false`, not `METERED`. | Check `type === 'CELLULAR'` too if you must tell cellular apart. |
| iOS synchronous calls | `getSyncConfig`, `getConnectionStatus`, `isBackgroundSyncEnabled`, `addListener` and `removeListener` return settled promises, which fixes a promise that could stay pending forever with `react-native-nitro-modules` 0.35.6 and 0.35.7. The required 0.35.8 fixes it upstream too. | Nothing. |

### Step 6: Adopt the quality gate and the health check (optional)

Both are off until you set them, through `configureSync` or `<SyncProvider options>`:

```ts
import {
  configureSync,
  NetworkQuality,
} from '@gabriel-sisjr/react-native-sync-provider';

await configureSync({
  ...SYNC_OPTIONS,
  defaultHeaders: session.headers,
  minNetworkQuality: NetworkQuality.POOR, // skip cycles while offline
  healthCheckUrl: 'https://api.example.com/health',
  healthCheckTtlMs: 60_000, // the default
  healthCheckBackoffMs: 30_000, // the default
});
```

Before you turn them on:

- **Pick the minimum.** `POOR` is the most permissive gate: it only stops flushes while the quality is `OFFLINE`, which also protects a manual `flush()` from spending attempts offline. Without `healthCheckUrl`, `GOOD` means unmetered links in practice, and `EXCELLENT` never passes. See [The quality gate](../guides/connectivity-detection.md#the-quality-gate).
- **Pick the endpoint.** The `HEAD` carries no `defaultHeaders`, so `healthCheckUrl` must answer it with a `2xx` without authentication. It is also the only target of the latency measurement. Expect three `HEAD`s when the device moves to a network or the URL changes, and, with `minNetworkQuality` set, three more when the gate finds the measurement 30 s old or older. Without a gate, nothing measures a network again while the device stays on it. The health check adds one `HEAD` per cycle, except while a healthy result younger than `healthCheckTtlMs` exists, which a `2xx` sample from a kept measurement can provide, and while a backoff runs. After a failure, the next check goes out when the backoff ends. Automatic retry wakes also wait at least 1000 ms, so a backoff of `0` cannot make them spin, while a `flush()` call checks again at once. See [Health check](../guides/connectivity-detection.md#health-check).
- **Skipped cycles are not failures.** A skipped flush emits `SYNC_FAILED` with `NETWORK_QUALITY_TOO_LOW` or `HEALTH_CHECK_FAILED`, with no `SYNC_STARTED` and no item change. If your UI reacts to `SYNC_FAILED`, check `errorCode`. `drainUntilEmpty()` counts a skipped flush as an iteration, so on a gated link it ends with `MAX_ITERATIONS` or `TIMEOUT`.
- **Background windows.** A worker or BGTask the gate or the health check skips completes normally, and the window is spent. See [Background Sync](../guides/background-sync.md#when-the-gate-or-the-health-check-skips-a-window).
- **Validation.** `configureSync` now rejects an invalid value for these four fields before any native call. The first invalid field, in the order `minNetworkQuality`, `healthCheckUrl`, `healthCheckTtlMs`, `healthCheckBackoffMs`, sets the code: `INVALID_URL` for `healthCheckUrl`, `INVALID_PAYLOAD` for the others.

### Rolling back {#rolling-back-from-v03}

:::danger Android downgrade wipes the store
Downgrading an Android install to a v0.2 build wipes the persisted queue and the dead letters. There is no supported downgrade.
:::

A v0.2 iOS build cannot open the migrated store: the load fails as incompatible, and every call rejects with `NATIVE_MODULE_UNAVAILABLE` until the app runs v0.3 again. The store file is kept, not deleted. That iOS outcome follows from the code and has not been exercised in a test.

If you must roll back, first drain the queue with `drainUntilEmpty()` and confirm `stoppedReason === 'EMPTY'`. Then read the dead letters with `listDeadLetter()` and export anything you need to keep: the older build does not read them, on either platform.

## v0.1 → v0.2 {#v0-1-to-v0-2}

No function, hook or error code was removed. The upgrade does need native changes on iOS, and several behaviors changed, so read the whole list before you ship it.

:::danger Do this first on iOS
If your `AppDelegate` calls `BGTaskScheduler.shared.register` for a library identifier, delete that call. The library now registers its handlers at process start, and iOS traps on a second registration, so the app is expected to crash at launch. This is expected rather than reproduced on a device.
:::

### Step 1: iOS native changes

1. Delete any `BGTaskScheduler.shared.register` call for `com.gabriel-sisjr.syncprovider.background` or `com.gabriel-sisjr.syncprovider.processing`. No `AppDelegate` code is needed for registration.
2. Declare both identifiers under `BGTaskSchedulerPermittedIdentifiers`. Older instructions listed only the first one. Without `.processing`, the longer task never runs.
3. Check that `UIBackgroundModes` has `fetch` and `processing`.
4. Forward background `URLSession` events from your `AppDelegate`:

```swift
import SyncProvider

func application(
  _ application: UIApplication,
  handleEventsForBackgroundURLSession identifier: String,
  completionHandler: @escaping () -> Void
) {
  HybridSyncProvider.handleBackgroundURLSessionEvents(
    identifier: identifier,
    completionHandler: completionHandler
  )
}
```

5. Raise the deployment target to at least `15.0`, in `ios/Podfile` and anywhere you set `IPHONEOS_DEPLOYMENT_TARGET`.
6. Run `cd ios && bundle exec pod install`.

The Privacy Manifest now declares `NSPrivacyAccessedAPICategoryUserDefaults` / `CA92.1`. It is merged for you.

### Step 2: Compile-time fixes

`SyncItem.attempts`, `SyncItem.nextRetryAt` and `SyncProviderContextValue.hasProvider` are required. Code that only reads these types compiles as before. Hand-built values, usually test fixtures, need the new fields:

```ts
const item: SyncItem = {
  id,
  method: 'POST',
  url,
  createdAt: 0,
  attempts: 0,
  nextRetryAt: 0,
};
const value: SyncProviderContextValue = { config, hasProvider: true };
```

### Step 3: Configuration and credentials

`configureSync` now persists its options, so a worker or BGTask with no JS honors them. `defaultHeaders` are encrypted at rest.

- Call `configureSync` at launch and whenever the token changes. The library does not refresh tokens.
- Pass `defaultHeaders` on every call that should keep them, including through `<SyncProvider options>`. Each call replaces the whole configuration, and omitting them deletes the stored copy.
- Call `configureSync` without `defaultHeaders` on logout.
- Move credentials out of per-item `headers`, which are stored in plain text.

In a process that has not called `configureSync`, a 401 or 403 now holds the item instead of failing it. See [When a stored token expires](../guides/background-sync.md#when-a-stored-token-expires).

### Step 4: Review behavior your code may rely on

| Area | What changed | What to do |
| --- | --- | --- |
| New items | Earlier docs said `AUTOMATIC` flushes on enqueue and on reconnect. It never did. | Mount [`useAutoSync`](../api-reference/hooks/useAutoSync.md) or call `flush()`. |
| Retries | Failed items wait out a persisted `nextRetryAt`, on both platforms, and a timer brings them back while the app runs. | Do not expect the next `flush()` to re-send a failed item. Use `drainUntilEmpty()`, which sleeps through cooldowns. See [Retry cooldown](../guides/retry-policy.md#retry-cooldown-across-restarts). |
| Cycles | A cycle where every item is cooling is `SYNC_SUCCEEDED` with 0/0. An empty cycle is too, now also on Android. | Check `successCount` rather than treating `SYNC_SUCCEEDED` as delivery. |
| `getQueueSize()`, `getPendingItems()` | `PENDING` items only, on both platforms. Cooling items are included. Android stopped counting `FAILED` and in-flight rows, and iOS stopped returning them. | Listen to `ITEM_FAILED` for permanent failures, and check `isSyncing()` as well as the size. |
| `MANUAL` | It stops the retry timer and the Android kick. It does not stop workers or BGTasks. | Use `foregroundOnly: true` to stop background work. |
| `pauseSync()` | Persisted. Workers stay inert while the app is dead. Lasts until `resumeSync()` or the next launch. | Call `pauseSync()` at startup to keep a pause across launches. |
| Error codes on iOS | A non-retryable failure keeps its real code on the last attempt, so a 401 stays `UNAUTHORIZED`. `MAX_ATTEMPTS_EXCEEDED` is only for a retriable failure with no attempts left. | Handle `UNAUTHORIZED` and `SERVER_ERROR` on `ITEM_FAILED`. |
| `enqueueBatch` | Throws one `INVALID_PAYLOAD` with `validationErrors`, instead of the first item's code. Array holes are rejected. | Branch on `INVALID_PAYLOAD`, and read `err.validationErrors`. See [Errors](../api-reference/errors.md#batch-validation-aggregates-all-issues). |
| `ValidationIssue.value` | Set only for `method`, `priority` and `contentType`. | Do not expect it for `url`, `body`, `headers` or `metadata`. |

### Step 5: Remove options that do nothing

These were always accepted and never applied. They still compile, but the docs no longer present them as working:

- `SyncOptions.persistQueue`: the queue is always persisted.
- `BackgroundSyncOptions.taskIdentifier`: both platforms use fixed identifiers. Remove any custom identifier from `Info.plist`.
- `useAutoSync({ backgroundSync })` and `useAutoSync({ flushOnForeground })`: use `enableBackgroundSync` and `flushOnAppForeground`.

`useAutoSync({ intervalMs: 0 })` does not turn the interval off: it falls back to 60 seconds.

### Queue data

Room v1→v2 on Android and Core Data model v2 on iOS migrate forward automatically on first launch and preserve queued items. There is nothing to do.

### Rolling back

:::danger Android downgrade wipes the queue
Downgrading an Android install past v0.2 wipes the persisted queue. There is no supported downgrade.
:::

If you must roll back, drain the queue first with `drainUntilEmpty()` and confirm `stoppedReason === 'EMPTY'`: any other reason means items remain.

## Versioning policy

The library follows [Semantic Versioning 2.0.0](https://semver.org/):

- **Major** (`x.0.0`) — breaking changes to the public TypeScript API, native HybridObject contract, or behavior consumers depend on.
- **Minor** (`0.x.0`) — new features, additive type fields, new error codes, new hook return fields. Pre-1.0, minor bumps may also carry deliberate breaking changes, so read the changelog before upgrading.
- **Patch** (`0.0.x`) — bug fixes only. No new types, no new functions.

## How to find the right section for your upgrade

Pick the section matching the version you are upgrading **to**. Track [`BREAKING_CHANGES.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/BREAKING_CHANGES.md) on the `develop` branch to see proposed changes before they ship.
