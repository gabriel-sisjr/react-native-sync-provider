---
sidebar_position: 4
title: iOS Native Architecture
description: Deep dive into the iOS native layer — SyncEngine, Core Data persistence, URLSession dispatch (foreground + background), the JS callback gateway, NWPathMonitor, BGTaskScheduler, RecoveryManager, persisted configuration, and event emitter.
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
| `ios/Database/`                                    | `CoreDataStack` singleton, `SyncProvider.xcdatamodeld` (manual codegen), `SyncItemDao`, `CoreDataMigration` (the explicit migration to the current model, with the mapping built in code). |
| `ios/Storage/`                                     | `SyncQueueStorage` — high-level wrapper around the DAOs (filtering, ordering, status transitions).             |
| `ios/HTTP/`                                        | `SyncDispatcher` (foreground + background `URLSession`) and `BackgroundURLSessionDelegate`; `HeaderMerge` (the three header layers of one request); `HeadProbe` (one `HEAD` on a session of its own) and `HealthCheck` (the check before a cycle, its cache and its backoff). |
| `ios/Callbacks/`                                   | `CallbackGateway` (calls JS and waits for its answer), `CallbackRegistry` (the four callback slots, and which hybrid owns them), `CallbackPayloads` (the request and answer shapes, and the header rules a resolver's answer must meet). |
| `ios/Connectivity/`                                | `ConnectivityMonitor` over `NWPathMonitor`; `NetworkQualityMonitor`, `NetworkQualityClassifier` (the shared threshold table) and `NetworkQualityLevel`. |
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

It also owns the callback registry and the gateway, so the callbacks belong to no hybrid. Creating a hybrid attaches it as the newest owner, which evicts every older install and ends its waits, then re-arms the retry timer. A hybrid's `deinit` retires its installs: they stay in place as orphans that count as installed, and every request for one ends at once without calling JS, until a newer hybrid attaches. See [JS Callbacks](#js-callbacks).

> **Note:** The Swift class is named `HybridSyncProvider`, not `SyncProvider`, as `nitro.json` maps it (`autolinking.SyncProvider.ios.implementationClassName: "HybridSyncProvider"`). The name was chosen in v0.1.1 to avoid a symbol clash with the code Nitro generated then. The Nitro `iosModuleName` (`SyncProvider`) and the JS lookup (`NitroModules.createHybridObject<SyncProvider>('SyncProvider')`) are unchanged — only the underlying Swift class name differs. The Kotlin implementation remains `class SyncProvider`.

## Core Data

`CoreDataStack` is a singleton that lazily builds an `NSPersistentContainer` against the `SyncProvider.xcdatamodeld` model bundled as a `.podspec` resource.

The entities are declared with no `codeGenerationType` attribute, which Xcode shows as **Manual/None**. No class is generated, so nothing clashes with the explicit `+CoreDataClass.swift` files (`Invalid redeclaration`). `manual/none` is not a valid value for the attribute: `momc` rejects it.

- **`SyncItemEntity`** — `id`, `method`, `url`, `headersJSON`, `body`, `contentType`, `priority`, `createdAt`, `attempts`, `lastAttemptAt`, `nextRetryAt`, `lastErrorCode`, `metadataJSON`, plus a `status` discriminator (`PENDING`, `IN_FLIGHT`). A delivered item is deleted, and an item that fails permanently moves to `SyncDeadLetterEntity`.
- **`SyncDeadLetterEntity`** — `id` (unique), `method`, `url`, `headersJSON`, `body`, `contentType`, `priority`, `createdAt`, `metadataJSON`, `attempts`, `lastAttemptAt`, `lastErrorCode`, `lastErrorMessage`, and `deadLetteredAt`, which is indexed. No `nextRetryAt`. Backs `listDeadLetter()`. An unknown stored code reaches JS as `MAX_ATTEMPTS_EXCEEDED`.
- **`SyncResultEntity`** — `id`, `startedAt`, `finishedAt`, `itemsAttempted`, `itemsSucceeded`, `itemsFailed`, `errorMessage`. Persists the flush history exposed by `getSyncHistory()`. It stores no per-item detail, which is why `SyncResult.succeededIds`, `failedIds` and `errors` are always empty on iOS.

The model has three versions: `SyncProvider`, `SyncProvider 2` and `SyncProvider 3`, the current one. Version 2 adds `nextRetryAt` and migrates lightweight. Version 3 adds `SyncDeadLetterEntity`. Earlier versions stay in the bundle. The store is at `Library/Application Support/SyncProvider.sqlite`.

`CoreDataStack` migrates a v1 store (written from 0.1.1-rc on) or a v2 store (v0.2.x) to v3 before it loads the store, on every load path, BGTask launches included. The mapping is explicit and built in code, never inferred, and `NSMigrationManager` writes the result to a side file that `replacePersistentStore` swaps in, keeping the WAL and SHM files consistent. Pending and in-flight rows are copied (v1 rows get `nextRetryAt: 0`), `FAILED` rows become dead letters with the legacy fallbacks, and history rows are copied.

If the migration fails, the original store is untouched and the next launch tries again. Until then, every call that reads or writes the queue, the dead letters or the history rejects, with the facade's fallback code (`INVALID_PAYLOAD`, or `NETWORK_ERROR` for `flush`) and the store error in `message`: see [Native Rejections](../api-reference/errors.md#native-rejections). `configureSync`, `getSyncConfig` and `getConnectionStatus` still work. A store the code does not recognize as a source, such as one written by the 0.1.0-beta prerelease, fails to load as incompatible the same way. So does a v3 store opened by a downgraded v0.2 build: there, the calls that use the store reject with the same fallback codes. The file is never deleted. That last case follows from the code and has not been exercised in a test.

Every store operation runs on a fresh background `NSManagedObjectContext`, which keeps the JSI thread free of Core Data work. Only the persistent container is shared.

> **Tip:** The model is also reconstructed programmatically in `InMemoryCoreDataStack` (test target). When you change the production model in `SyncProvider.xcdatamodeld`, mirror the change in `InMemoryCoreDataStack.makeModel()` — this is the only test seam that mirrors the schema.

## SyncQueueStorage

`SyncQueueStorage` wraps the Core Data DAO and is the only path the rest of the iOS code uses to mutate the queue. It enforces:

- **Atomic batch insert** (`enqueueBatch` either persists every item or throws — no partial success).
- **`maxQueueSize` enforcement** with `QUEUE_FULL` thrown at the boundary, for enqueues and dead-letter replays. The cap counts `PENDING` rows only, cooling ones included. Dead letters do not count.
- **Status transitions** (`PENDING → IN_FLIGHT → deleted | PENDING (for retry) | moved to the dead-letter entity`) via a single transactional `perform` block to avoid race conditions across the dispatcher and recovery paths. An attempt is counted when the row goes `IN_FLIGHT`, so a kill mid-request spends it. A request the cycle's cancellation cuts off gets its attempt refunded.
- **Sort order**: priority first, `HIGH` to `LOW`, then `createdAt` ascending — the same order Android uses, kept in sync deliberately. `getSize` and `getQueued` cover `PENDING` rows only. The fetch for a cycle also requires `nextRetryAt <= now`.

## SyncDispatcher (URLSession)

`SyncDispatcher` is an actor that owns two `URLSession` instances. A cycle takes one batch of eligible items and dispatches them one at a time:

| Session                    | Purpose                                                                                                                      |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Foreground                 | `URLSessionConfiguration.default`, with `waitsForConnectivity`. Used when the app is active, and always under `foregroundOnly`. |
| Background (OS-resumable)  | `URLSessionConfiguration.background(withIdentifier: "com.gabriel-sisjr.syncprovider.background-upload")`. Used when the app is not active, unless `foregroundOnly` is set. The body goes through a temporary file, and the OS may finish the upload while the app is suspended. |

The dispatcher reads eligible rows from `SyncQueueStorage`, runs the installed [JS callbacks](#js-callbacks) around each request, applies the active `RetryPolicy` (linear / exponential / fibonacci with optional equal jitter), then the retry decider when one is installed and the failure qualifies, and reports per-item outcomes back as Core Data status transitions plus `SyncEvent`s on the emitter. A retriable failure writes `nextRetryAt` from a clock read after the attempt, or after the decision when a retry decider was consulted. A non-retryable failure keeps its real code, and `MAX_ATTEMPTS_EXCEEDED` applies only to a retriable failure with no attempts left. `configureSync` applies as soon as the call resolves, even inside a running cycle, which keeps only its batch and the outcome of its quality gate and health check. A request already in flight keeps the headers and timeout it was sent with, but its response is classified under the new `retryPolicy`.

Every request that goes out ends with a classified outcome or a cancellation. When the write of an outcome fails, the cycle dies, and first the row is settled in its own write with that outcome: a `2xx` removes it as delivered, a retryable failure cools it, or dead-letters it with no attempts left, a permanent failure dead-letters it, and a 401 or 403 to restored `defaultHeaders` holds it. A row whose request never went out comes back exactly as before its claim. Only a row still `IN_FLIGHT` is settled, so one purged since stays as it is. The cycle then ends with `SYNC_FAILED`, carrying a library error's own code or `NETWORK_ERROR` for any other cause, such as a Core Data error, and `flush()` rejects. When the settle's write fails too, the row waits `IN_FLIGHT` for launch recovery. See [When a Cycle Dies](../guides/error-handling.md#when-a-cycle-dies).

A cancellation of the cycle's own task, as at a [BGTask expiry](../guides/background-sync.md#when-a-window-runs-out-of-time), claims no further item and cancels the request in flight, in either session. Its item goes back to `PENDING` with the attempt refunded and the cooldown untouched, and the cycle ends with `SYNC_FAILED` without `errorCode` or `metadata`, with no history row.

With a retry decider installed, a settle applies the decision recorded for that attempt, or the policy's when none was recorded, without asking JS. Its retry delay counts from the settle's own write, while Android counts it from when the outcome was decided, a difference of one failed write that predates v0.4.

`flush()` during a running cycle returns the last result at once, so a callback that awaits it gets that result and sends nothing. When paused, or with default headers that cannot be read, it returns a zero-count result with real timestamps and no cycle runs.

`BackgroundURLSessionDelegate` routes a completion back to the dispatcher while the call that started the upload is still waiting, and receives the `application(_:handleEventsForBackgroundURLSession:completionHandler:)` call the host app forwards through `HybridSyncProvider.handleBackgroundURLSessionEvents`.

> **Warning — A completion delivered after a relaunch is dropped:** The row stays `IN_FLIGHT` until launch recovery puts it back to `PENDING`, with no cooldown and its attempt spent. The server may have accepted the request, so it can be sent twice. See [Idempotency](../guides/idempotency.md).

The header resolver and `beforeDispatch` run while the request's headers are assembled, before the upload task is created, so the upload carries the headers resolved then. A completion delivered after a relaunch gets no `afterDispatch` call, because no JS installed one in that process.

## JS Callbacks

`CallbackGateway` and `CallbackRegistry` belong to `SyncEngine`. See the [Dispatch Interceptors guide](../guides/dispatch-interceptors.md) for the behavior apps see.

- **The gateway.** It calls the installed JS function with a fresh request id, and the facade answers through the internal `answerCallback`. Each wait is a continuation resumed exactly once: by the answer, a failed answer, its timeout, a cancellation, or its owner going away. The timeout is a one-shot timer on uptime, freed as soon as the request ends, so it fires even when the JS promise never settles. A late, unknown or repeated answer does nothing.
- **Claim timing.** The header resolver and `beforeDispatch` run after the spent-budget guard and before the claim, so the row stays `PENDING` during their waits, and `getPendingItems()` shows it. The claim is the final check that the row is still queued. A deferral writes `lastErrorCode: INTERCEPTOR_FAILED` and the new `nextRetryAt` in a write that applies only while the row is `PENDING`, and leaves `attempts` and `lastAttemptAt` as they were.
- **One configuration per item.** The dispatcher takes one configuration snapshot right after the resolver returns. It feeds the merge, `beforeDispatch`, the request and its timeout, and the credential judgment. A `configureSync` during the `beforeDispatch` wait therefore applies from the next item. The retry decider's timeout comes from the same snapshot.
- **One item at a time.** Each awaited callback adds its wait to its item, so with JS hung each item costs up to `callbackTimeoutMs`.
- **The merge.** `HeaderMerge` layers `defaultHeaders`, the resolver's headers and the item's, compares names case-insensitively, and keeps the winning layer's spelling. Names that differ only by case inside one layer resolve deterministically here, unlike earlier builds, but Android makes no such promise, so the guides call that winner unspecified. A `Content-Type` from `contentType` is not part of the merge: the request adds it when no layer sets one.
- **`afterDispatch`.** It is called on the main queue, behind the item event the emitter posted, for a request that went out. Nothing waits for it.
- **Logs.** Category `callbacks`. Each request without a usable answer is logged once, at most 256 characters of any JS text, logged as private. Headers, bodies and contexts are never logged. An unusable `delayMs` is logged as a warning.

## Retry Wake

`RetryWakeTimer` is one single-shot wall-clock timer per engine. `SyncEngine` arms it for the soonest `nextRetryAt` when a cycle ends, when a hybrid attaches and on `configureSync`. On `resumeSync` and after a failed health check, it re-arms it for every `PENDING` item, cooldown or not. It runs only with a JS-facing hybrid attached, and not while paused or under `MANUAL`. If it fires offline, the wake waits for connectivity. After a cycle that dies, every automatic arm except the one `resumeSync` makes waits for the failure hold: 30 seconds from that cycle's end, doubling for each dead cycle in a row, at most 15 minutes, in memory only. See [The Failure Hold](../guides/retry-policy.md#the-failure-hold). After a rejected stored credential, no wake comes before the hold ends: the hold lives in memory, and `configureSync` clears it (see [When a stored token expires](../guides/background-sync.md#when-a-stored-token-expires)). See [Who wakes the queue](../guides/retry-policy.md#who-wakes-the-queue-and-when).

## Persisted Configuration

`SyncConfigStore` keeps the configuration available to a process the app never spoke to. See [Cold-start configuration](../guides/background-sync.md#cold-start-configuration).

| Store                                                 | Contents                                                                                                                      |
| ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| UserDefaults suite `com.gabriel-sisjr.syncprovider`   | `config.v1` (every option except headers, plus `hasDefaultHeaders`), `paused`, `background.options.v1`, `background.enabled`. No secrets. |
| Keychain, service `com.gabriel-sisjr.syncprovider`, account `defaultHeaders` | `defaultHeaders`, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, not synchronized.                |

Numbers are saved as the call gave them, with `NaN` and `±Infinity` written as strings, and converted where they are used, so a cold start or a BGTask uses the same values as the call. See [Out-of-Range Numbers](../api-reference/types.md#out-of-range-numbers). A v0.2 build cannot read back such a record: see [Rolling Back](../migration/v0-3-0.md#rolling-back-from-v03).

Keychain items survive an uninstall. If the engine finds an item with no UserDefaults record, it deletes the orphan. That needs a successful read, so it waits for the first unlock.

## ConnectivityMonitor (NWPathMonitor)

`ConnectivityMonitor` runs an `NWPathMonitor` on a dedicated queue and publishes a `ConnectionState` snapshot (`status`, `type`, `networkQuality`, `isInternetReachable`, `isExpensive`). When a reported field changes, `SyncEngine`, through `NetworkQualityMonitor`, emits `CONNECTION_CHANGED` from inside the path update, measurement or `configureSync` call that caused it, after the snapshot that `getConnectionStatus()` returns is updated. Listeners run later: the emitter queues them on the main queue, and Nitro queues each JS call on the JS thread. Snapshots reach each subscriber one at a time and in order, never an older one after a newer one, so the events keep the order of the updates. The snapshot starts at `UNKNOWN`, and the quality monitor subscribes before the path monitor starts, so the first path report always emits one. Before v0.3, one was emitted on every path update.

The quality follows the [shared threshold table](../guides/connectivity-detection.md#how-it-is-classified). An expensive path is at most `GOOD` and a constrained one (Low Data Mode) at most `MODERATE`. With `healthCheckUrl` set, the latency is measured with `HEAD` requests on an ephemeral `URLSession` of its own, timed with `URLSessionTaskMetrics`. A gated flush that finds no path update yet waits up to 1.5 s for the first one, then grades the link `OFFLINE`.

`NWPath` has no network identifier, so a measurement is keyed by the interface type, the expensive and constrained flags, the gateway addresses and the URL. The gateways are a best-effort discriminator: two networks behind the same gateway address are taken for one. A measurement under 30 s old when the path changes is kept until a later path update, or with `minNetworkQuality` a gate evaluation, finds it 30 s old or older and measures again. Cache ages use the wall clock, not a Required Reason API, and a negative age counts as stale.

The health check sends its `HEAD` on the same probe session. A healthy result is cached per URL, so a new URL is checked afresh, and overlapping checks share one request. A `2xx` sample from a kept quality measurement refreshes that cache but does not end a backoff. A failure starts the backoff when the failed `HEAD` finishes, and a negative cache age also ends it. Automatic retry wakes wait for the backoff, and at least 1000 ms after the failure, so a backoff of `0` cannot make them spin. An explicit `flush()` keeps to the backoff alone.

`SyncEngine` starts the monitor when it is built and keeps it alive for the process lifetime — there is no per-listener registration overhead. `.unknown` does not count as online, so the retry timer waits on it.

## BackgroundSyncManager (BGTaskScheduler)

`BackgroundTaskRegistrar` registers the handlers at process start: `SyncProviderBackgroundBootstrap.m` calls it from `+load`, the only hook that needs no change to the app's `AppDelegate` and still runs before `didFinishLaunchingWithOptions` returns. It registers only the identifiers declared in `BGTaskSchedulerPermittedIdentifiers`, because registering any other traps, and it resolves the engine only when a task fires.

`BackgroundSyncManager` decides what a fired task does. Two fixed identifiers:

- **`BGAppRefreshTaskRequest`** (`com.gabriel-sisjr.syncprovider.background`) — short refresh window. `enableBackgroundSync` submits it. Its handler re-submits it and submits the processing request.
- **`BGProcessingTaskRequest`** (`com.gabriel-sisjr.syncprovider.processing`) — longer window. `requiresCharging` maps to `requiresExternalPower`, and network connectivity is always required. Its handler re-submits it.

Both handlers wait for launch recovery, call `SyncDispatcher.flush()` once, and emit `BACKGROUND_SYNC_STARTED` / `BACKGROUND_SYNC_COMPLETED`. When the window's time runs out, the task is completed at once with `success: false`, exactly once, and its flush is cancelled; `BACKGROUND_SYNC_COMPLETED` then carries `metadata.ok: "false"`. See [When a Window Runs Out of Time](../guides/background-sync.md#when-a-window-runs-out-of-time). The refresh request begins no earlier than `minimumIntervalMs` after the submit: at least 60 seconds, `NaN` and `-Infinity` included, and at most 2^53 - 1 ms. `BackgroundSyncOptionsStore` persists the options as given, including `foregroundOnly`, with `NaN` and `±Infinity` written as strings, so a handler honors them with no JS loaded. Under `foregroundOnly`, or after `disableBackgroundSync`, a task that fires completes inert. `requiresUnmeteredNetwork` and `requiresDeviceIdle` are not mapped, and `taskIdentifier` is not used.

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

## Running Unit Tests

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

## Next Steps

- [Android Native Architecture](./android-native.md) -- The Android counterpart: Room, OkHttp, WorkManager and boot recovery
- [Data Flow](./data-flow.md) -- Step-by-step traces of enqueue, flush, connectivity and recovery
- [Architecture Overview](./overview.md) -- The three layers, the key design decisions and the technology stack
- [iOS Background Modes](../production/ios-background-modes.md) -- The capabilities and task identifiers the host app declares
