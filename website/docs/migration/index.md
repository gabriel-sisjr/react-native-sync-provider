---
sidebar_position: 1
title: Migration Guides
description: Upgrade guides for breaking and behavior changes between versions of @gabriel-sisjr/react-native-sync-provider, including the v0.1 to v0.2 upgrade.
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
