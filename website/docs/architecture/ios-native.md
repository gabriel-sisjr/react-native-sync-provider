---
sidebar_position: 3
title: iOS Native Architecture
description: Deep dive into the iOS native layer — SyncEngine, Core Data persistence, URLSession dispatch (foreground + background), NWPathMonitor, BGTaskScheduler, RecoveryManager, persisted configuration, and event emitter.
keywords:
  - ios
  - swift
  - core data
  - urlsession
  - bgtaskscheduler
  - nwpathmonitor
  - background sync
  - privacy manifest
---

# iOS Native Architecture

All iOS native code lives under `ios/`. The implementation uses Swift 5.9+, Core Data for the persistent queue, `URLSession` for HTTP dispatch (with a separate `URLSessionConfiguration.background` session for OS-resumable uploads), `NWPathMonitor` for connectivity, and `BGTaskScheduler` for background sync windows.

## Directory Map

| Directory                                          | Purpose                                                                                                        |
| -------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `ios/Database/`                                    | `CoreDataStack` singleton, `SyncProvider.xcdatamodeld` (manual codegen), `SyncItemDao`.                        |
| `ios/Storage/`                                     | `SyncQueueStorage` — high-level wrapper around the DAOs (filtering, ordering, status transitions).             |
| `ios/HTTP/`                                        | `SyncDispatcher` (foreground + background `URLSession`) and `BackgroundURLSessionDelegate`.                    |
| `ios/Connectivity/`                                | `ConnectivityMonitor` over `NWPathMonitor`.                                                                    |
| `ios/Engine/`                                      | `SyncEngine` (the process-wide runtime, independent of JS) and `RetryWakeTimer`.                               |
| `ios/Config/`                                      | `SyncConfigStore`, `SecretStore` (Keychain), `SyncOptionsSnapshot`.                                            |
| `ios/Background/`                                  | `BackgroundTaskRegistrar` and `SyncProviderBackgroundBootstrap.m` (handler registration at `+load`), `BackgroundSyncManager` (what a fired task does), `BackgroundSyncOptionsStore`, `RecoveryManager`. |
| `ios/Events/`                                      | `SyncEventEmitter` — id-based listeners on the single `'sync-event'` channel.                                  |
| `ios/Errors/`                                      | `SyncProviderError` — typed errors mapped to `SyncErrorCode`.                                                  |
| `ios/Retry/`                                       | `RetryPolicyEvaluator` — the backoff math.                                                                     |
| `ios/Utils/`                                       | `ULID` generator, `SyncLogger`, `SyncClock` (the injectable clock behind cooldowns), `SyncDefaults` (the library's UserDefaults suite). |
| `ios/Tests/`                                       | XCTest suite (driven by CocoaPods `s.test_spec 'Tests'`).                                                      |

The HybridObject implementation lives in `ios/SyncProvider.swift` (`final class HybridSyncProvider: HybridSyncProviderSpec`) and delegates to the `SyncEngine` — it is the JSI front door and contains no business logic.

## SyncEngine

`SyncEngine.shared` owns the queue, the dispatcher, the retry timer and the configuration. It exists independently of JS, because a BGTask launch needs all of them with no bridge loaded. It is built on first use, from whichever side gets there first: JS creating the hybrid, or a fired BGTask.

On start it loads the persisted configuration, starts `ConnectivityMonitor`, and runs launch recovery. It counts JS-facing hybrids: the retry timer runs only while there is one, and creating a hybrid clears a persisted pause, because JS cannot read the flag and starts out unpaused.

> **Note:** The Swift class is intentionally named `HybridSyncProvider`, not `SyncProvider`. Nitro generates a C++ `SyncProvider::SyncProvider` class inside the `margelo::nitro::syncprovider` namespace; reusing the same name from Swift would collide with the generated bridging header (`SyncProvider-Swift.h`) and break the build. The Nitro `iosModuleName` (`SyncProvider`) and the JS lookup (`NitroModules.createHybridObject<SyncProvider>('SyncProvider')`) are unchanged — only the underlying Swift class name differs. The Kotlin implementation has no analogous collision and remains `class SyncProvider`. The autolinking mapping lives in `nitro.json → autolinking.SyncProvider.ios.implementationClassName: "HybridSyncProvider"`.

## Core Data

`CoreDataStack` is a singleton that lazily builds an `NSPersistentContainer` against the `SyncProvider.xcdatamodeld` model bundled as a `.podspec` resource.

The entities are declared with no `codeGenerationType` attribute, which Xcode shows as **Manual/None**. No class is generated, so nothing clashes with the explicit `+CoreDataClass.swift` files (`Invalid redeclaration`). `manual/none` is not a valid value for the attribute: `momc` rejects it.

- **`SyncItemEntity`** — `id`, `method`, `url`, `headersJSON`, `body`, `contentType`, `priority`, `createdAt`, `attempts`, `lastAttemptAt`, `nextRetryAt`, `lastErrorCode`, `metadataJSON`, plus a `status` discriminator (`PENDING`, `IN_FLIGHT`). A delivered item is deleted, and an item that fails permanently moves to `SyncDeadLetterEntity`.
- **`SyncDeadLetterEntity`** — `id` (unique), `method`, `url`, `headersJSON`, `body`, `contentType`, `priority`, `createdAt`, `metadataJSON`, `attempts`, `lastAttemptAt`, `lastErrorCode`, `lastErrorMessage`, and `deadLetteredAt`, which is indexed. No `nextRetryAt`. Backs `listDeadLetter()`. An unknown stored code reaches JS as `MAX_ATTEMPTS_EXCEEDED`.
- **`SyncResultEntity`** — `id`, `startedAt`, `finishedAt`, `itemsAttempted`, `itemsSucceeded`, `itemsFailed`, `errorMessage`. Persists the flush history exposed by `getSyncHistory()`. It stores no per-item detail, which is why `SyncResult.succeededIds`, `failedIds` and `errors` are always empty on iOS.

The model has three versions: `SyncProvider`, `SyncProvider 2` and `SyncProvider 3`, the current one. Version 2 adds `nextRetryAt` and migrates lightweight. Version 3 adds `SyncDeadLetterEntity`. Earlier versions stay in the bundle. The store is at `Library/Application Support/SyncProvider.sqlite`.

`CoreDataStack` migrates a v1 store (written from 0.1.1-rc on) or a v2 store (v0.2.x) to v3 before it loads the store, on every load path, BGTask launches included. The mapping is explicit and built in code, never inferred, and `NSMigrationManager` writes the result to a side file that `replacePersistentStore` swaps in, keeping the WAL and SHM files consistent. Pending and in-flight rows are copied (v1 rows get `nextRetryAt: 0`), `FAILED` rows become dead letters with the legacy fallbacks, and history rows are copied.

If the migration fails, the original store is untouched, that launch runs without a store, so every call rejects with `NATIVE_MODULE_UNAVAILABLE`, and the next launch retries. A store the code does not recognize as a source, such as one written by the 0.1.0-beta prerelease or a v3 store opened by a downgraded v0.2 build, fails to load as incompatible the same way, and the file is never deleted. That last case follows from the code and has not been exercised in a test.

A separate background `NSManagedObjectContext` is used for writes to keep the JSI thread free of Core Data work.

> **Tip:** The model is also reconstructed programmatically in `InMemoryCoreDataStack` (test target). When you change the production model in `SyncProvider.xcdatamodeld`, mirror the change in `InMemoryCoreDataStack.makeModel()` — this is the only test seam that mirrors the schema.

## SyncQueueStorage

`SyncQueueStorage` wraps the Core Data DAO and is the only path the rest of the iOS code uses to mutate the queue. It enforces:

- **Atomic batch insert** (`enqueueBatch` either persists every item or throws — no partial success).
- **`maxQueueSize` enforcement** with `QUEUE_FULL` thrown at the boundary, for enqueues and dead-letter replays. The cap counts `PENDING` rows only, cooling ones included. Dead letters do not count.
- **Status transitions** (`PENDING → IN_FLIGHT → deleted | PENDING (for retry) | moved to the dead-letter entity`) via a single transactional `perform` block to avoid race conditions across the dispatcher and recovery paths. An attempt is counted when the row goes `IN_FLIGHT`, so a kill mid-request spends it.
- **Sort order**: priority first, `HIGH` to `LOW`, then `createdAt` ascending — the same order Android uses, kept in sync deliberately. `getSize` and `getQueued` cover `PENDING` rows only. The fetch for a cycle also requires `nextRetryAt <= now`.

## SyncDispatcher (URLSession)

`SyncDispatcher` is an actor that owns two `URLSession` instances. A cycle takes one batch of eligible items and dispatches them one at a time:

| Session                    | Purpose                                                                                                                      |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Foreground                 | `URLSessionConfiguration.default`, with `waitsForConnectivity`. Used when the app is active, and always under `foregroundOnly`. |
| Background (OS-resumable)  | `URLSessionConfiguration.background(withIdentifier: "com.gabriel-sisjr.syncprovider.background-upload")`. Used when the app is not active, unless `foregroundOnly` is set. The body goes through a temporary file, and the OS may finish the upload while the app is suspended. |

The dispatcher reads eligible rows from `SyncQueueStorage`, applies the active `RetryPolicy` (linear / exponential / fibonacci with optional equal jitter), and reports per-item outcomes back as Core Data status transitions plus `SyncEvent`s on the emitter. A retriable failure writes `nextRetryAt` from a clock read after the attempt. A non-retryable failure keeps its real code, and `MAX_ATTEMPTS_EXCEEDED` applies only to a retriable failure with no attempts left. It reads `config` per item, so `configureSync` applies from the next item, even inside a running cycle.

`flush()` during a running cycle returns the last result at once. When paused, or with default headers that cannot be read, it returns a zero-count result with real timestamps and no cycle runs.

`BackgroundURLSessionDelegate` routes a completion back to the dispatcher while the call that started the upload is still waiting, and receives the `application(_:handleEventsForBackgroundURLSession:completionHandler:)` call the host app forwards through `HybridSyncProvider.handleBackgroundURLSessionEvents`.

> **Warning — A completion delivered after a relaunch is dropped:** The row stays `IN_FLIGHT` until launch recovery puts it back to `PENDING`, with no cooldown and its attempt spent. The server may have accepted the request, so it can be sent twice. See [Idempotency](../guides/idempotency.md).

## Retry Wake

`RetryWakeTimer` is one single-shot wall-clock timer per engine. `SyncEngine` arms it for the soonest `nextRetryAt` when a cycle ends, when a hybrid attaches, on `configureSync` and on `resumeSync`. It runs only with a JS-facing hybrid attached, and not while paused or under `MANUAL`. If it fires offline, the wake waits for connectivity. After a rejected stored credential, no wake comes before the hold ends: the hold lives in memory, and `configureSync` clears it (see [When a stored token expires](../guides/background-sync.md#when-a-stored-token-expires)). See [Who wakes the queue](../guides/retry-policy.md#who-wakes-the-queue-and-when).

## Persisted Configuration

`SyncConfigStore` keeps the configuration available to a process the app never spoke to. See [Cold-start configuration](../guides/background-sync.md#cold-start-configuration).

| Store                                                 | Contents                                                                                                                      |
| ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| UserDefaults suite `com.gabriel-sisjr.syncprovider`   | `config.v1` (every option except headers, plus `hasDefaultHeaders`), `paused`, `background.options.v1`, `background.enabled`. No secrets. |
| Keychain, service `com.gabriel-sisjr.syncprovider`, account `defaultHeaders` | `defaultHeaders`, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, not synchronized.                |

Keychain items survive an uninstall. If the engine finds an item with no UserDefaults record, it deletes the orphan. That needs a successful read, so it waits for the first unlock.

## ConnectivityMonitor (NWPathMonitor)

`ConnectivityMonitor` runs an `NWPathMonitor` on a dedicated queue and publishes a `ConnectionState` snapshot (`status`, `type`, `networkQuality`, `isInternetReachable`, `isExpensive`). It emits a `CONNECTION_CHANGED` event, synchronously, only when a reported field changes, and exposes the latest snapshot synchronously to `getConnectionStatus()`. The snapshot starts at `UNKNOWN`, so the first path report emits one. Before v0.3 it emitted one on every path update.

The quality follows the [shared threshold table](../guides/connectivity-detection.md#how-it-is-classified). An expensive path is at most `GOOD` and a constrained one (Low Data Mode) at most `MODERATE`. With `healthCheckUrl` set, the latency is measured with `HEAD` requests on an ephemeral `URLSession` of its own, timed with `URLSessionTaskMetrics`. A gated flush that finds no path update yet waits up to 1.5 s for the first one, then grades the link `OFFLINE`.

`NWPath` has no network identifier, so a measurement is keyed by the interface type, the expensive and constrained flags, the gateway addresses and the URL. The gateways are a best-effort discriminator: two networks with the same gateway address can share a measurement for up to 30 s. Cache ages use the wall clock, not a Required Reason API, and a negative age counts as stale.

The health check sends its `HEAD` on the same probe session. A healthy result is cached per URL, so a new URL is checked afresh, and overlapping checks share one request. A `2xx` sample from a kept quality measurement refreshes that cache but does not end a backoff. A failure starts the backoff when the failed `HEAD` finishes, and a negative cache age also ends it. Automatic retry wakes wait for the backoff, and at least 1000 ms after the failure, so a backoff of `0` cannot make them spin. An explicit `flush()` keeps to the backoff alone.

`SyncEngine` starts the monitor when it is built and keeps it alive for the process lifetime — there is no per-listener registration overhead. `.unknown` does not count as online, so the retry timer waits on it.

## BackgroundSyncManager (BGTaskScheduler)

`BackgroundTaskRegistrar` registers the handlers at process start: `SyncProviderBackgroundBootstrap.m` calls it from `+load`, the only hook that needs no change to the app's `AppDelegate` and still runs before `didFinishLaunchingWithOptions` returns. It registers only the identifiers declared in `BGTaskSchedulerPermittedIdentifiers`, because registering any other traps, and it resolves the engine only when a task fires.

`BackgroundSyncManager` decides what a fired task does. Two fixed identifiers:

- **`BGAppRefreshTaskRequest`** (`com.gabriel-sisjr.syncprovider.background`) — short refresh window. `enableBackgroundSync` submits it. Its handler re-submits it and submits the processing request.
- **`BGProcessingTaskRequest`** (`com.gabriel-sisjr.syncprovider.processing`) — longer window. `requiresCharging` maps to `requiresExternalPower`, and network connectivity is always required. Its handler re-submits it.

Both handlers wait for launch recovery, call `SyncDispatcher.flush()` once, and emit `BACKGROUND_SYNC_STARTED` / `BACKGROUND_SYNC_COMPLETED`. `BackgroundSyncOptionsStore` persists the options, including `foregroundOnly`, so a handler honors them with no JS loaded. Under `foregroundOnly`, or after `disableBackgroundSync`, a task that fires completes inert. `requiresUnmeteredNetwork` and `requiresDeviceIdle` are not mapped, and `taskIdentifier` is not used.

> **Warning:** Both identifiers MUST appear in the host app's `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`, and the `Background fetch` and `Background processing` capabilities MUST be enabled on the app target. A missing identifier skips its handler, and `enableBackgroundSync` throws `BACKGROUND_TASK_REGISTRATION_FAILED` when `.background` is the one missing. See [iOS Background Modes](../production/ios-background-modes.md).

## RecoveryManager

`RecoveryManager` runs once per engine start, driven by `SyncEngine`, including on a BGTask launch with no JS. It atomically transitions items left `IN_FLIGHT` after the previous run (e.g., the app was force-quit mid-dispatch) back to `PENDING`, keeping `attempts` and `nextRetryAt`, so the next flush can pick them up. If it reset any item, it emits `RESUMED` with `metadata.recovered`.

## SyncEventEmitter

The emitter implements the Nitro listener pattern:

- A single channel string `'sync-event'` (the `SYNC_EVENT_CHANNEL` constant on the JS side).
- `addListener(event, cb)` returns a stable string id used by `removeListener(event, id)`.
- Multiple subscribers are supported per process — each receives every emitted event.
- Native validates the channel name and throws `INVALID_PAYLOAD` on anything other than `'sync-event'`.

## SyncProvider.podspec

The podspec advertises:

- `s.platforms = { :ios => "15.0" }` (the async `URLSession.data(for:)` API). React Native 0.85 raises the built pod to 15.1.
- `s.frameworks = "Foundation", "CoreData", "Network", "BackgroundTasks", "Security"`.
- `s.resources = "ios/Database/SyncProvider.xcdatamodeld"` (Core Data model bundled into the pod).
- `s.resource_bundles = { "SyncProvider_Privacy" => ["ios/PrivacyInfo.xcprivacy"] }` (deliberately a resource bundle, not a top-level resource — the RN 0.85 privacy_manifest aggregator iterates only `file_accessor.resource_bundles`, and listing the manifest as a top-level resource collides with the host app's aggregated manifest with `Multiple commands produce ...PrivacyInfo.xcprivacy`).
- `s.exclude_files = ["ios/Tests/**/*"]` so the test sources are owned exclusively by the `s.test_spec 'Tests'` block.

## Running unit tests

The iOS test target is declared on the library side via `s.test_spec 'Tests'` in `SyncProvider.podspec`. CocoaPods 1.10+ defaults `:test_type` to `:unit` and auto-prefixes the resulting scheme with `Unit-`, so the generated Xcode scheme is **`SyncProvider-Unit-Tests`** (not `SyncProvider-Tests`). We did not rename the test_spec — the segment is added by CocoaPods.

> **Warning:** `use_native_modules!` autolinking does **not** propagate `:testspecs` to consuming apps. The example app opts in explicitly in `example/ios/Podfile`:
>
> ```ruby
> pod 'SyncProvider', :path => '../..', :testspecs => ['Tests']
> ```
>
> Without this line, `pod install` will not generate the test target and `xcodebuild -scheme SyncProvider-Unit-Tests` will fail to resolve the scheme. The `post_install` block also calls `installer.pods_project.recreate_user_schemes(false)` to ensure the generated test scheme is shared (the `false` keeps user-state untouched).

After `yarn nitrogen` and `bundle exec pod install --project-directory=ios` (run from `example/`), run the suite with:

```bash
cd example/ios
xcodebuild test \
  -workspace SyncProviderExample.xcworkspace \
  -scheme SyncProvider-Unit-Tests \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=latest'
```

CI runs the same command in the `test-ios` job of `.github/workflows/ci.yml`. It prefers an iPhone 17 and picks the simulator automatically from what the runner ships, through `.github/scripts/pick-ios-simulator.mjs`.

## Privacy Manifest

`ios/PrivacyInfo.xcprivacy` declares two Required Reason APIs:

- `NSPrivacyAccessedAPICategoryFileTimestamp` with reason `C617.1` — Core Data's SQLite store reads file timestamps on its own files.
- `NSPrivacyAccessedAPICategoryUserDefaults` with reason `CA92.1` — the library keeps its non-secret configuration in its own UserDefaults suite.

The library does **not** track users (`NSPrivacyTracking = false`), and does **not** declare any collected data types. See [Privacy Manifest](../production/privacy-manifest.md) for what consumers must add to their own app manifest.
