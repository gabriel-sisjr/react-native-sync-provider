# Breaking Changes

This document tracks consumer-facing breaking changes in **`@gabriel-sisjr/react-native-sync-provider`** across versions. Each entry summarizes the change and links to the full migration guide; the migration guides under [`website/docs/migration/`](https://github.com/gabriel-sisjr/react-native-sync-provider/tree/main/website/docs/migration) are the authoritative reference.

> **Pre-1.0 notice:** while the library is on `0.x`, minor version bumps (`0.x.0`) may include breaking changes per [SemVer 0.x semantics](https://semver.org/#spec-item-4).

## How to read this file

Every breaking-change entry follows the same shape so consumers can scan the file quickly:

````markdown
## [vX.Y.0] - YYYY-MM-DD - One-line summary

> **Upgrade path:** vX.(Y-1).z --> vX.Y.0
>
> **Full migration guide:** [`docs/migration/vX-Y-0`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/migration/vX-Y-0)

One-paragraph overview: what changed, why, and the blast radius (compile-time only? silent runtime regression? native code?).

### <Affected symbol or area>

#### Before

```ts
// previous API
```

#### After

```ts
// new API
```

#### Migration steps

1. ...
2. ...

#### Affected symbols

| Symbol | Change |
| ------ | ------ |
| ...    | ...    |
````

Use the table at the bottom of each entry to enumerate every renamed, removed, or reshaped public symbol so consumers can grep for what they import.

## [Unreleased]

> **Upgrade path:** v0.1.z --> v0.2.0
>
> **Full migration guide:** [`docs/migration#v0-1-to-v0-2`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/migration#v0-1-to-v0-2)

Most entries below are observable behavior changes rather than renamed APIs. Start with the first one: it is the only entry that can stop an app from launching.

### iOS: delete any `BGTaskScheduler` registration for the library's identifiers

> **Category:** native integration change. **Action required** if your `AppDelegate` registers a library identifier. Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` Added entry in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

The library now registers its BGTask handlers at process start, through `+load`, before `application(_:didFinishLaunchingWithOptions:)` runs. No `AppDelegate` code is needed for registration.

Earlier versions of the iOS setup page showed an optional block that registered `com.gabriel-sisjr.syncprovider.background` in `didFinishLaunchingWithOptions`. If your app has anything like it, delete it. iOS traps on a second `register` for the same identifier, so an app that keeps that block is expected to crash at launch. We have not reproduced that crash on a device, so treat it as expected rather than measured. The block also called `SyncProviderBackgroundTaskRunner`, a class the library never shipped.

#### Before

```swift
// In didFinishLaunchingWithOptions. Delete this.
BGTaskScheduler.shared.register(
  forTaskWithIdentifier: "com.gabriel-sisjr.syncprovider.background",
  using: nil
) { task in
  SyncProviderBackgroundTaskRunner.run(task: task as! BGProcessingTask)
}
```

#### After

```swift
import SyncProvider

// The only AppDelegate code the library needs.
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

#### Migration steps

1. Delete every `BGTaskScheduler.shared.register` call for `com.gabriel-sisjr.syncprovider.background` and `com.gabriel-sisjr.syncprovider.processing`.
2. Declare both identifiers under `BGTaskSchedulerPermittedIdentifiers` in `Info.plist`, and `fetch` and `processing` under `UIBackgroundModes`. Older instructions listed only the first identifier. A missing identifier no longer crashes: its handler is skipped, and `enableBackgroundSync` rejects when `.background` is the one missing.
3. Forward `handleEventsForBackgroundURLSession` as shown above. The library cannot inject this, and without it background uploads stall.
4. Remove any `taskIdentifier` from your `enableBackgroundSync` call, and any custom identifier from `Info.plist`. The field is ignored: only the two fixed identifiers are registered.

#### Affected symbols

| Symbol / surface                                   | Change                                                                      |
| -------------------------------------------------- | --------------------------------------------------------------------------- |
| App-side `BGTaskScheduler.shared.register`         | Must be removed for the library's two identifiers.                          |
| `BGTaskSchedulerPermittedIdentifiers`              | Must declare `.background` and `.processing`.                               |
| `HybridSyncProvider.handleBackgroundURLSessionEvents` | Must be called from the app's `AppDelegate`.                             |
| `ios/PrivacyInfo.xcprivacy`                        | Adds `NSPrivacyAccessedAPICategoryUserDefaults` / `CA92.1`. Nothing to do. |
| Public TypeScript API                              | none (no JS break)                                                          |

### iOS minimum deployment target `13.0` --> `15.0`

> **Category:** platform-requirement change (**not** a JS/TS API break). Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` Changed entry in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

The iOS minimum deployment target declared in `SyncProvider.podspec` is raised from `13.0` to `15.0`. Apps that pin an iOS deployment target below `15.0` must raise it. React Native 0.85 raises the built pod to `15.1`.

**Rationale:** the dispatcher uses the async `URLSession.data(for:)` API, which requires iOS 15+. The previous `13.0` floor was latent: the build only passed because the example app overrode `IPHONEOS_DEPLOYMENT_TARGET`. Any consumer honoring the declared `13.0` floor would have hit an availability compile error.

#### Migration steps

1. In your app's `ios/Podfile`, ensure the platform line is at least `platform :ios, '15.0'`.
2. If you set `IPHONEOS_DEPLOYMENT_TARGET` anywhere (Podfile `post_install`, Xcode build settings), make sure it is `>= 15.0`.
3. Run `cd ios && bundle exec pod install`.

No JS/TS code changes are required.

#### Affected symbols

| Symbol / surface                                              | Change                      |
| ------------------------------------------------------------- | --------------------------- |
| `SyncProvider.podspec` (`s.platforms`, `test_spec.platforms`) | iOS floor `13.0` --> `15.0` |
| Public TypeScript API                                         | none (no JS break)          |

### Required fields on `SyncItem` and `SyncProviderContextValue`

> **Category:** compile-time change. Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` Added entries in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

`SyncItem.attempts`, `SyncItem.nextRetryAt` and `SyncProviderContextValue.hasProvider` are required. Runtime code that only reads these types is unaffected. Hand-built values, usually test fixtures and test wrappers around `SyncProviderContext.Provider`, stop compiling.

#### Before

```ts
const item: SyncItem = { id, method: 'POST', url, createdAt: 0 };
const value: SyncProviderContextValue = { config };
```

#### After

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

#### Migration steps

1. Add `attempts` and `nextRetryAt` to hand-built `SyncItem` values. `0` means "never attempted".
2. Add `hasProvider` to hand-built context values.

#### Affected symbols

| Symbol                                 | Change          |
| -------------------------------------- | --------------- |
| `SyncItem.attempts`                    | New, required.  |
| `SyncItem.nextRetryAt`                 | New, required.  |
| `SyncProviderContextValue.hasProvider` | New, required.  |

### `getQueueSize()` and `getPendingItems()` cover `PENDING` items only

> **Category:** observable behavior change (**not** a JS/TS API surface break). Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` Changed entry in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

Both functions now report items waiting to be dispatched, on both platforms. Cooling items are included. `IN_FLIGHT` and `FAILED` items are not. Android's `getQueueSize()` used to count `FAILED` and `IN_FLIGHT` rows, and iOS's `getPendingItems()` used to return them. `getPendingItems()` now returns dispatch order on both.

Capacity checks did not change. On Android the cap still counts `FAILED` rows, which this function no longer shows, so the queue can be full while `getQueueSize()` reads below `maxQueueSize`. See [`maxQueueSize`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/types#syncoptions).

#### Migration steps

1. If you read permanent failures from these functions, listen to `ITEM_FAILED` instead.
2. If your UI treats `getQueueSize() === 0` as "nothing is happening", also check `isSyncing()`: an item in flight is not counted. `drainUntilEmpty()` already does.

#### Affected symbols

| Symbol / surface    | Change                                                                |
| ------------------- | --------------------------------------------------------------------- |
| `getQueueSize()`    | `PENDING` only. Android no longer counts `FAILED`.                    |
| `getPendingItems()` | `PENDING` only, in dispatch order. iOS no longer returns other rows. |
| `useSyncQueue`, `useOfflineQueue`, `useSyncSnapshot` | `size` and `items` follow the functions above. |

### iOS: a non-retryable failure keeps its real code

> **Category:** observable behavior change (**not** a JS/TS API surface break). Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` Fixed entry in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

A non-retryable failure on an item's last attempt used to end as `MAX_ATTEMPTS_EXCEEDED` on iOS. It now keeps its real code, so a 401 stays `UNAUTHORIZED`. `MAX_ATTEMPTS_EXCEEDED` applies only to a retriable failure with no attempts left. Android already behaved this way.

#### Migration steps

If you branch on `ITEM_FAILED.errorCode`, make sure `UNAUTHORIZED` and `SERVER_ERROR` are handled on iOS for that case.

#### Affected symbols

| Symbol / surface             | Change                                                |
| ---------------------------- | ----------------------------------------------------- |
| `ITEM_FAILED.errorCode` (iOS) | Non-retryable failures keep their code on the last attempt. |
| `MAX_ATTEMPTS_EXCEEDED`      | Only a retriable failure with no attempts left.       |

### `MANUAL` scope, and pause persistence

> **Category:** observable behavior change (**not** a JS/TS API surface break). Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` Changed entries in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

`strategy: MANUAL` suppresses the in-process retry timer and the Android retry kick. It does not stop periodic workers or BGTasks: if you called `enableBackgroundSync`, those windows still dispatch. Use `foregroundOnly: true` to stop background work.

`pauseSync()` is now persisted, so workers and BGTasks stay inert while the app is dead. A pause lasts until `resumeSync()` or the next launch that reaches the library, including a JS reload. A launch clears it without emitting `RESUMED`. On iOS, React Native can start during a background launch, and if JS reaches the library there, that counts as a launch.

#### Migration steps

1. If you use `MANUAL` and expect nothing to dispatch on its own, pass `foregroundOnly: true` to `enableBackgroundSync`, or do not call it.
2. If you need a pause to outlive a restart, call `pauseSync()` again at startup. Hooks cannot read the persisted flag: `useSyncStatus().isPaused` starts `false`.

#### Affected symbols

| Symbol / surface          | Change                                                                    |
| ------------------------- | ------------------------------------------------------------------------- |
| `SyncStrategy.MANUAL`     | Suppresses the timer and the kick only.                                   |
| `pauseSync()`             | Persisted. Lasts until `resumeSync()` or the next launch.                 |

### Configuration persists, and `configureSync` without `defaultHeaders` deletes them

> **Category:** observable behavior change (**not** a JS/TS API surface break). Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` Added entry in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

`configureSync` now persists its options, so a worker or BGTask in a fresh process honors them. Before, a cold worker ran on the defaults and with no default headers.

Three consequences to check:

- **Omitting `defaultHeaders` is a logout.** Each `configureSync` replaces the whole configuration, and omitting `defaultHeaders` deletes the stored copy. Pass them on every call, including through `<SyncProvider options>`.
- **A stored token can expire.** On a 401 or 403 in a process that has not called `configureSync`, the item stays `PENDING`, no attempt is spent, the next try waits at least 60 seconds and the cycle stops. You see `ITEM_RETRYING` with `UNAUTHORIZED` rather than `ITEM_FAILED`. In a process that has called `configureSync`, a 401 stays terminal.
- **The library does not refresh tokens.** Call `configureSync` when the token changes. A process with no JS cannot renew one.

If stored headers cannot be read, nothing is dispatched and items wait. See [Background sync](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/background-sync#cold-start-configuration).

#### Before

```ts
// Background workers ran without these headers, so storing a token was pointless.
await configureSync({ strategy, retryPolicy });
```

#### After

```ts
// On every launch and on every token change.
await configureSync({
  strategy,
  retryPolicy,
  defaultHeaders: { Authorization: `Bearer ${token}` },
});

// On logout: the same options with no defaultHeaders.
await configureSync({ strategy, retryPolicy });
```

#### Migration steps

1. Call `configureSync` at launch and whenever the token changes.
2. Pass `defaultHeaders` on every call that should keep them.
3. Call `configureSync` without `defaultHeaders` on logout.

#### Affected symbols

| Symbol / surface             | Change                                                                 |
| ---------------------------- | ---------------------------------------------------------------------- |
| `configureSync`              | Persists its options. Omitting `defaultHeaders` deletes the stored copy. |
| `SyncOptions.defaultHeaders` | Encrypted at rest. Used by workers and BGTasks.                        |
| 401 / 403 outcome            | Held, not failed, in a process that has not called `configureSync`.    |

### Retries are spaced out and wake on their own

> **Category:** observable behavior change (**not** a JS/TS API surface break). Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` Changed and Fixed entries in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

On iOS, failed-retriable items used to return to `PENDING` with no cooldown, so they were re-dispatched on the next cycle: a hot retry loop against a degraded server. On Android, backoff lived inside a single cycle and was lost on a restart. Both platforms now persist the cooldown as `SyncItem.nextRetryAt`, measured from the end of the attempt, and skip an item until it passes. Each cycle makes at most one attempt per item, and a timer in the running app brings the item back when its cooldown ends.

A cycle where every item is cooling now ends with `SYNC_SUCCEEDED` and a 0/0 result. An empty cycle does too on Android, as it already did on iOS, so those cycles add rows to the sync history.

#### Migration steps

1. If a test or flow relied on the next `flush()` re-dispatching a failed item, account for the cooldown: read `SyncItem.nextRetryAt` from `getPendingItems()`, or use `drainUntilEmpty()`, which sleeps until the soonest cooldown by itself.
2. If you treat `SYNC_SUCCEEDED` as "items were delivered", check the result's `successCount`.

#### Affected symbols

| Symbol / surface                            | Change                                                                  |
| ------------------------------------------- | ----------------------------------------------------------------------- |
| Retry timing (both platforms)               | Items honor a persisted `nextRetryAt` cooldown before re-dispatch.      |
| `SyncItem.attempts`, `SyncItem.nextRetryAt` | New fields exposing per-item retry bookkeeping.                         |
| Sync history on Android                     | Records a 0/0 result for an empty cycle.                                |

### `enqueueBatch` aggregates validation errors into one umbrella error

> **Category:** observable behavior change (additive field + error-code shift on a single path). Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` Changed entry in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

When a batch contains invalid items, `enqueueBatch` previously threw the **first** bad item's specific code (e.g. `INVALID_URL`). It now throws a single umbrella `SyncError(INVALID_PAYLOAD)` whose new `validationErrors: ValidationIssue[]` field carries **every** issue across **every** item, each tagged with its `itemIndex`. No items are enqueued when any issue is present. Single-item `enqueue` still throws the first violation's specific code, now with the full `validationErrors` list attached.

`ValidationIssue.value` is set only for `method`, `priority` and `contentType`. It is never set for `url`, `body`, `headers` or `metadata`, because errors end up in logs and those fields can hold credentials and personal data. `reason` does not contain the URL. The `message` of a single-item `enqueue` still can.

#### Before

```ts
try {
  await enqueueBatch([good, { method: 'POST', url: 'not-a-url' }]);
} catch (err) {
  // err.code === 'INVALID_URL'  (first bad item's specific code)
}
```

#### After

```ts
try {
  await enqueueBatch([good, { method: 'POST', url: 'not-a-url' }]);
} catch (err) {
  // err.code === 'INVALID_PAYLOAD'  (umbrella code)
  // err.validationErrors === [{ field: 'url', reason: '…', itemIndex: 1 }]
}
```

#### Migration steps

1. Where you `catch` from `enqueueBatch` and branch on `err.code`, branch on `INVALID_PAYLOAD`.
2. Read `err.validationErrors` for per-field, per-item detail (`field`, `reason`, `value?`, `itemIndex?`).
3. Do not expect `value` for `url`, `body`, `headers` or `metadata`.
4. `enqueue` (single item) needs no change.

#### Affected symbols

| Symbol                              | Change                                                                       |
| ----------------------------------- | ---------------------------------------------------------------------------- |
| `enqueueBatch` (invalid input path) | Throws `INVALID_PAYLOAD` umbrella instead of the first item's specific code. |
| `SyncError.validationErrors`        | New optional field carrying every `ValidationIssue`.                         |
| `ValidationIssue`                   | New exported type (`{ field, reason, value?, itemIndex? }`).                 |
| `ValidationIssue.value`             | Set only for `method`, `priority` and `contentType`.                         |

### Schema migrations are roll-forward only (Android downgrade wipes the queue)

> **Category:** persisted-data / migration note (**not** a JS/TS API break). Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` Changed entry in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

v0.2 ships **Room v1→v2** (Android) and **Core Data model v2** (iOS) to persist `nextRetryAt`. Both are **roll-forward only** once shipped to consumers: a forward upgrade migrates existing rows in place. There is **no supported downgrade** — recovery from a regression is a new forward migration, never a revert.

:::danger Android downgrade is destructive
Downgrading a shipped Android install from a v0.2+ schema (v2) back to a pre-v0.2 build (v1) triggers `fallbackToDestructiveMigrationOnDowngrade()` — **the entire persisted queue is wiped**. Pin or roll forward; never ship a downgrade that crosses the v0.2 schema boundary with a non-empty queue you need to keep.
:::

#### Migration steps

- **Upgrading (the normal path):** nothing to do. The v1→v2 migration runs automatically on first launch of v0.2 and preserves queued items (Android adds a `nextRetryAt` column defaulting to `0`; iOS lightweight-migrates the model).
- **If you must roll back from v0.2:** drain the queue first with `drainUntilEmpty()`, then confirm `stoppedReason === 'EMPTY'`: any other reason means items remain. Otherwise accept that the Android queue will be destructively recreated on the older build.

#### Affected symbols

| Symbol / surface      | Change                                                                                            |
| --------------------- | ------------------------------------------------------------------------------------------------- |
| Android Room schema   | `v1` → `v2` (`nextRetryAt` column + `(status, nextRetryAt)` index). Downgrade = destructive wipe. |
| iOS Core Data model   | New `v2` model version (`nextRetryAt` attribute); `v1` preserved in the bundle.                   |
| Public TypeScript API | none (no JS break)                                                                                |
