---
sidebar_position: 3
title: iOS Native Architecture
description: Deep dive into the iOS native layer — Core Data persistence, URLSession dispatch (foreground + background), NWPathMonitor, BGTaskScheduler, RecoveryManager, and event emitter.
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
| `ios/Background/`                                  | `BackgroundSyncManager` (`BGTaskScheduler` registration + scheduling), `RecoveryManager` (app-launch recovery).|
| `ios/Events/`                                      | `SyncEventEmitter` — id-based listeners on the single `'sync-event'` channel.                                  |
| `ios/Errors/`                                      | `SyncProviderError` — typed errors mapped to `SyncErrorCode`.                                                  |
| `ios/Utils/`                                       | `ULID` generator and `SyncLogger`.                                                                             |
| `ios/Tests/`                                       | XCTest suite (driven by CocoaPods `s.test_spec 'Tests'`).                                                      |

The HybridObject implementation lives in `ios/SyncProvider.swift` (`final class HybridSyncProvider: HybridSyncProviderSpec`) and delegates to the components above — it is the JSI front door and contains no business logic.

:::note
The Swift class is intentionally named `HybridSyncProvider`, not `SyncProvider`. Nitro generates a C++ `SyncProvider::SyncProvider` class inside the `margelo::nitro::syncprovider` namespace; reusing the same name from Swift would collide with the generated bridging header (`SyncProvider-Swift.h`) and break the build. The Nitro `iosModuleName` (`SyncProvider`) and the JS lookup (`NitroModules.createHybridObject<SyncProvider>('SyncProvider')`) are unchanged — only the underlying Swift class name differs. The Kotlin implementation has no analogous collision and remains `class SyncProvider`. The autolinking mapping lives in `nitro.json → autolinking.SyncProvider.ios.implementationClassName: "HybridSyncProvider"`.
:::

## Core Data

`CoreDataStack` is a singleton that lazily builds an `NSPersistentContainer` against the `SyncProvider.xcdatamodeld` model bundled as a `.podspec` resource.

Two entities are declared with `codeGenerationType="manual/none"` (Xcode auto-codegen disabled to avoid `Invalid redeclaration` clashes with the explicit `+CoreDataClass.swift` files):

- **`SyncItemEntity`** — `id`, `method`, `url`, `headers` (Data blob), `body`, `contentType`, `priority`, `createdAt`, `attempts`, `lastAttemptAt`, `lastErrorCode`, plus a status discriminator used for the `pending → in-flight → succeeded/failed` lifecycle.
- **`SyncResultEntity`** — `id`, `startedAt`, `finishedAt`, `itemsAttempted`, `itemsSucceeded`, `itemsFailed`. Persists the flush history exposed by `getSyncHistory()`.

A separate background `NSManagedObjectContext` is used for writes to keep the JSI thread free of Core Data work.

:::tip
The model is also reconstructed programmatically in `InMemoryCoreDataStack` (test target). When you change the production model in `SyncProvider.xcdatamodeld`, mirror the change in `InMemoryCoreDataStack.makeModel()` — this is the only test seam that mirrors the schema.
:::

## SyncQueueStorage

`SyncQueueStorage` wraps the Core Data DAO and is the only path the rest of the iOS code uses to mutate the queue. It enforces:

- **Atomic batch insert** (`enqueueBatch` either persists every item or throws — no partial success).
- **`maxQueueSize` enforcement** with `QUEUE_FULL` thrown at the boundary.
- **Status transitions** (`pending → in-flight → succeeded | pending(for retry) | failed`) via a single transactional `perform` block to avoid race conditions across the dispatcher and recovery paths.
- **Sort order**: priority descending, `createdAt` ascending — the same order Android uses, kept in sync deliberately.

## SyncDispatcher (URLSession)

`SyncDispatcher` owns two `URLSession` instances:

| Session                    | Purpose                                                                                                                      |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Foreground                 | `URLSession.shared`-style ephemeral configuration. Used when the app is alive; supports any HTTP method (including PATCH/DELETE). |
| Background (OS-resumable)  | `URLSessionConfiguration.background(withIdentifier: "com.gabriel-sisjr.syncprovider.background")`. Used during BGTaskScheduler windows; the OS may suspend and resume uploads across app lifetimes. |

The dispatcher consumes claims from `SyncQueueStorage`, applies the active `RetryPolicy` (linear / exponential / fibonacci with optional full jitter), and reports per-item outcomes back as Core Data status transitions plus `SyncEvent`s on the emitter.

`BackgroundURLSessionDelegate` is the bridge that turns OS-resumed completion callbacks into the same status-transition pipeline, and forwards `application(_:handleEventsForBackgroundURLSession:completionHandler:)` invocations from the host app.

## ConnectivityMonitor (NWPathMonitor)

`ConnectivityMonitor` runs an `NWPathMonitor` on a dedicated queue and publishes a `ConnectionState` snapshot (`status`, `type`, `isInternetReachable`, `isExpensive`). It emits a `CONNECTION_CHANGED` event whenever the path status changes, and exposes the latest snapshot synchronously to `getConnectionStatus()`.

The monitor is started lazily on the first `getConnectionStatus()` / `addListener` call and kept alive for the process lifetime — there is no per-listener registration overhead.

## BackgroundSyncManager (BGTaskScheduler)

`BackgroundSyncManager` registers two task identifiers with `BGTaskScheduler.shared`:

- **`BGAppRefreshTaskRequest`** — short, network-only refresh windows (~30 seconds, OS-decided, ~15 minute soft cadence).
- **`BGProcessingTaskRequest`** — longer-running windows that may require power, used when the queue is large.

Both handlers funnel into `SyncDispatcher.flush()` and emit `BACKGROUND_SYNC_STARTED` / `BACKGROUND_SYNC_COMPLETED` events. The default identifier is `com.gabriel-sisjr.syncprovider.background`; consumers override it via `BackgroundSyncOptions.taskIdentifier`.

:::warning
Both identifiers MUST appear in the host app's `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`, and the `Background fetch` and `Background processing` capabilities MUST be enabled on the app target. Missing either causes `enableBackgroundSync` to throw `BACKGROUND_TASK_REGISTRATION_FAILED`. See [iOS Background Modes](../production/ios-background-modes.md).
:::

## RecoveryManager

`RecoveryManager` runs once at app launch (driven by the host app's app delegate). It scans Core Data for items left in `in-flight` after the previous run (e.g., the app was force-quit mid-dispatch) and atomically transitions them back to `pending` so the next flush can pick them up. It also rehydrates the background `URLSession` and re-emits `SYNC_PROGRESS` events for any in-flight uploads the OS resumed on its own.

## SyncEventEmitter

The emitter implements the Nitro listener pattern:

- A single channel string `'sync-event'` (the `SYNC_EVENT_CHANNEL` constant on the JS side).
- `addListener(event, cb)` returns a stable string id used by `removeListener(event, id)`.
- Multiple subscribers are supported per process — each receives every emitted event.
- Native validates the channel name and throws `INVALID_PAYLOAD` on anything other than `'sync-event'`.

## SyncProvider.podspec

The podspec advertises:

- `s.platforms = { :ios => "13.0" }` (BGTaskScheduler floor).
- `s.frameworks = "Foundation", "CoreData", "Network", "BackgroundTasks"`.
- `s.resources = "ios/Database/SyncProvider.xcdatamodeld"` (Core Data model bundled into the pod).
- `s.resource_bundles = { "SyncProvider_Privacy" => ["ios/PrivacyInfo.xcprivacy"] }` (deliberately a resource bundle, not a top-level resource — the RN 0.85 privacy_manifest aggregator iterates only `file_accessor.resource_bundles`, and listing the manifest as a top-level resource collides with the host app's aggregated manifest with `Multiple commands produce ...PrivacyInfo.xcprivacy`).
- `s.exclude_files = ["ios/Tests/**/*"]` so the test sources are owned exclusively by the `s.test_spec 'Tests'` block.

## Running unit tests

The iOS test target is declared on the library side via `s.test_spec 'Tests'` in `SyncProvider.podspec`. CocoaPods 1.10+ defaults `:test_type` to `:unit` and auto-prefixes the resulting scheme with `Unit-`, so the generated Xcode scheme is **`SyncProvider-Unit-Tests`** (not `SyncProvider-Tests`). We did not rename the test_spec — the segment is added by CocoaPods.

:::warning
`use_native_modules!` autolinking does **not** propagate `:testspecs` to consuming apps. The example app opts in explicitly in `example/ios/Podfile`:

```ruby
pod 'SyncProvider', :path => '../..', :testspecs => ['Tests']
```

Without this line, `pod install` will not generate the test target and `xcodebuild -scheme SyncProvider-Unit-Tests` will fail to resolve the scheme. The `post_install` block also calls `installer.pods_project.recreate_user_schemes(false)` to ensure the generated test scheme is shared (the `false` keeps user-state untouched).
:::

After `yarn nitrogen` and `bundle exec pod install --project-directory=ios` (run from `example/`), the suite can be exercised in two equivalent ways:

```bash
yarn test:ios
```

```bash
cd example/ios
xcodebuild test \
  -workspace SyncProviderExample.xcworkspace \
  -scheme SyncProvider-Unit-Tests \
  -destination 'platform=iOS Simulator,name=iPhone 16,OS=latest'
```

`yarn test:ios` shells out to `scripts/test-ios.sh`, which mirrors the CI invocation: it removes any stale `build/SyncProviderTests.xcresult` before each attempt (defends against the `"Existing file at -resultBundlePath"` failure mode) and walks a destination fallback list (`iPhone 16` → `iPhone 17` → `iPhone 16 Pro`, all `OS=latest`) so a missing simulator on a single machine does not block the run. The same loop runs in CI (`test-ios` job in `.github/workflows/ci.yml`).

## Privacy Manifest

`ios/PrivacyInfo.xcprivacy` declares exactly one Required Reason API:

- `NSPrivacyAccessedAPICategoryFileTimestamp` with reason `C617.1` — Core Data's SQLite store reads file timestamps on its own files.

The library does **not** access `UserDefaults`, does **not** track users (`NSPrivacyTracking = false`), and does **not** declare any collected data types. See [Privacy Manifest](../production/privacy-manifest.md) for what consumers must add to their own app manifest.
