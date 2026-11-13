---
sidebar_position: 2
title: Testing
description: How the JS, Android, and iOS test suites are structured, how to run them, and the mocking patterns used to keep them fast and deterministic.
keywords:
  - testing
  - jest
  - junit
  - xctest
  - robolectric
  - mockwebserver
  - mockk
  - nitro
---

# Testing

The library has three independent test surfaces — JS (Jest), Android (JUnit + Robolectric), and iOS (XCTest) — each runnable in isolation, all gated by CI.

---

## JavaScript (Jest)

Preset: `react-native` (intentionally aligned with the sibling lib `react-native-background-location`). Tests live under `src/__tests__/`.

### Running

```bash
yarn test                       # full suite
yarn test path/to/file          # single file
yarn test -t "name pattern"     # by test name
yarn test --coverage            # emit coverage to coverage/
```

Jest ignores `<rootDir>/example/node_modules` and `<rootDir>/lib/`. Don't put tests in those paths.

### Setup File

`src/__tests__/setup-minimal.ts` mocks the React Native + Nitro surface:

- `react-native-nitro-modules` — `NitroModules.createHybridObject` returns a stable fake stored on `globalThis.__syncMock`.
- `react-native` — `Platform`, `AppState`, `NativeEventEmitter`, `NativeModules`.
- AppState helpers — `simulateAppStateChange('active' | 'background')`.
- Full mock surface for all 24 HybridObject methods (queue/dead letters/sync/config/history/connection/background + listeners).

The facade caches `createHybridObject` on first import. To reset between tests, mutate the same singleton via `Object.assign(currentHybrid, fresh)` instead of recreating — that keeps the same reference alive across tests without `jest.resetModules()`.

### Hook Test Pattern

Hooks call `addListener` asynchronously. Two `await Promise.resolve()` ticks are required before `mock.__emit(...)` to let the subscription register:

```ts
await Promise.resolve();
await Promise.resolve();
mock.__emit({ type: 'SYNC_STARTED', timestamp: Date.now() });
```

This is documented inline in `src/__tests__/hooks/useSyncStatus.test.tsx` and is the same workaround used across every hook test.

### Coverage

The `coverageThreshold` is set to 0 % globally — same convention as the sibling lib. CI emits the report and enforces no minimum.

`src/__tests__/webParity.test.ts` also runs here: it uses the TypeScript compiler to assert that `src/index.tsx` and `src/index.web.tsx` export the same names, each with the same kind and shape.

---

## Android (JUnit + Robolectric)

Lives under `android/src/test/java/com/margelo/nitro/syncprovider/`. Driven by Gradle's `testDebugUnitTest`.

### Running

```bash
cd example/android
./gradlew :gabriel-sisjr_react-native-sync-provider:testDebugUnitTest --stacktrace
```

CI runs the same command on `ubuntu-latest` with JDK 17 (Zulu) and uploads the HTML/JaCoCo reports as artifacts.

### Suites

| Suite                                  | Cases | What it exercises                                                                       |
| -------------------------------------- | ----- | --------------------------------------------------------------------------------------- |
| `SyncProviderRuntimeTest`              | 81    | Configuration, pause, retry wake, credential holds and cold workers against a fresh runtime. |
| `database/SyncItemDaoTest`             | 16    | Room in-memory + Robolectric SDK 30. Insert/batch/count/delete, ordering, status flips. |
| `database/SyncResultDaoTest`           | 5     | History persistence, ordering DESC.                                                     |
| `database/SyncDatabaseMigrationTest`   | 6     | Room v1→v2→v3 migrations against the exported schemas, and the destructive downgrade.  |
| `storage/SyncQueueStorageTest`         | 34    | enqueue + INVALID_URL + QUEUE_FULL + claim atomicity + cooldowns + dead-letter moves + roundtrip. |
| `storage/DeadLetterReplayRaceTest`     | 3     | A replay holds one write transaction, so a purge, a discard or a second replay waits for it. |
| `http/SyncDispatcherTest`              | 57    | MockWebServer 4.12.0 — success, retry, terminal codes, credential hold, cancellation, the quality gate, the health check, dead letters. |
| `http/HeadProbeTest`                   | 14    | The `HEAD` client that the health check and the latency probe share.                   |
| `http/HealthCheckTest`                 | 6     | The healthy-result cache per URL and its clocks, and the backoff after a failure.      |
| `retry/RetryPolicyEvaluatorTest`       | 18    | Linear/exp/fib + cap + equal jitter `[0.75, 1.25)` with seeded `Random`.                |
| `retry/RetryTimerTest`                 | 25    | Single-shot arming, cancel, a wake owed to the network, wakes the quality gate or a health-check backoff holds. |
| `retry/RetryWakeTest`                  | 30    | When the timer and the kick arm, what suppresses them, the credential hold and the health-check backoff. |
| `retry/OfflineRetryTest`               | 4     | No attempt is spent while offline.                                                      |
| `config/SyncConfigStoreTest`           | 19    | Round trip of every option, damaged values, and the secret lifecycle.                   |
| `config/SyncOptionsValidatorTest`      | 5     | The checks `configureSync` runs on the network-quality and health-check options.        |
| `config/KeystoreSecretStoreTest`       | 4     | Encryption behind the `SecretStore` seam.                                               |
| `connectivity/ConnectivityMonitorTest` | 20    | Lifecycle, idempotency, the initial StateFlow value, the default network and the offline invariant. |
| `connectivity/ConnectivityMonitorQualityTest` | 23 | The latency measurement behind `networkQuality`: when it is taken, how long it is reused, what it reports. |
| `connectivity/LatencyProbeTest`        | 2     | The three back-to-back `HEAD`s of a measurement.                                        |
| `connectivity/NetworkQualityClassifierTest` | 23 | The shared threshold table, one parameterized case per scenario.                     |
| `work/BackgroundSyncManagerTest`       | 24    | Periodic work, the retry kick, `foregroundOnly`.                                        |
| `work/BootCompletedReceiverTest`       | 5     | Boot and package-replaced actions.                                                      |
| `work/SyncWorkerTest`                  | 11    | `TestListenableWorkerBuilder` + `mockkObject(SyncProviderRuntime.Companion)`. Results, inert runs, and runs the quality gate holds. |
| `work/RecoveryWorkerTest`              | 5     | Reset-in-flight + reschedule + failure → retry.                                         |

### Robolectric SDK 30 (The Sweet Spot)

Robolectric 4.13 does not ship shadows for `compileSdk 36`. SDK 30 (Android 11) is the highest fully-shadowed level that still covers `ConnectivityManager`, `Room`, and `WorkManager`. Every test that needs Robolectric annotates `@Config(sdk = [30])`.

For tests that only need pure JVM behavior (e.g. `RetryPolicyEvaluator`), `testOptions.unitTests.returnDefaultValues = true` lets Android shims return defaults without Robolectric entirely.

### Mocking

- **MockK 1.13.13** — `coEvery` / `coVerify` for suspend functions; `mockk<T>(relaxed = true)`; `mockkObject(SyncProviderRuntime.Companion)` for the `SyncWorker` runtime injection seam (`unmockkObject` in `@After`).
- **MockWebServer 4.12.0** — same major as production OkHttp. Drives realistic HTTP scenarios from the dispatcher tests.

---

## iOS (XCTest)

Lives under `ios/Tests/`. The test target is wired on the library side via `s.test_spec 'Tests'` in `SyncProvider.podspec`. CocoaPods 1.10+ defaults `:test_type` to `:unit` and auto-prefixes the resulting scheme with `Unit-`, so `pod install` generates the scheme **`SyncProvider-Unit-Tests`** (not `SyncProvider-Tests`). The library does not rename the test_spec — the segment is inserted by CocoaPods.

`use_native_modules!` autolinking does **not** propagate `:testspecs` to the consuming app. `example/ios/Podfile` opts in explicitly:

```ruby
pod 'SyncProvider', :path => '../..', :testspecs => ['Tests']
```

Without this line, CocoaPods will never generate the test target and `xcodebuild` will fail to resolve `SyncProvider-Unit-Tests`. The `post_install` block also calls `installer.pods_project.recreate_user_schemes(false)` to defensively share the generated test scheme.

### Setup Order

Always run `yarn nitrogen` before `pod install` — the podspec consumes `nitrogen/generated/ios/SyncProvider+autolinking.rb`, which is gitignored and regenerated on every spec change. From the repo root:

```bash
yarn nitrogen
cd example && bundle install && bundle exec pod install --project-directory=ios
```

### Running

```bash
cd example/ios
xcodebuild test \
  -workspace SyncProviderExample.xcworkspace \
  -scheme SyncProvider-Unit-Tests \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=latest' \
  -resultBundlePath build/SyncProviderTests.xcresult \
  -skipPackagePluginValidation \
  -skipMacroValidation
```

Remove any stale `build/SyncProviderTests.xcresult` first: `xcodebuild` refuses to overwrite one. There is no `yarn` script for this.

CI runs the same command on `macos-latest` with `XCODE_VERSION: 'latest-stable'`, the newest stable Xcode the runner image ships (see the `test-ios` job in `.github/workflows/ci.yml`), and uploads `.xcresult` artifacts. It picks the simulator automatically, preferring an iPhone 17, through `.github/scripts/pick-ios-simulator.mjs`, so the next runner image cannot leave the job without a device. A "Show available iOS simulators" diagnostic step prints the runtime list before the test step so destination drift is visible in the log.

### Test Seams

These `internal init(...)` overloads exist solely for tests (they do not appear in the public HybridObject surface):

- `CoreDataStack(container:)` — `internal init` accepting an injected `NSPersistentContainer`. Backed by `InMemoryCoreDataStack`, which programmatically rebuilds the model — when the production model changes, mirror the change in `InMemoryCoreDataStack.makeModel()`. The compiled `.momd` is bundled into the test target for the migration tests only.
- `SyncDispatcher(... foregroundSession:backgroundSession:)` — `internal init` accepting injected sessions so `URLProtocolStub` can drive every request.
- `SyncEngine(...)` — injectable stores, clock, retry timer and scheduler. `InMemorySecretStore` stands in for the Keychain, which hostless XCTest does not have, and `FakeBackgroundTasks` for `BGTaskScheduler`.
- `NetworkQualityMonitor(connectivity:probe:clock:...)` and `HealthCheck(probe:clock:)` — take the connectivity and `HEAD` probe seams. `FakeConnectivity` stands in for `NWPathMonitor`, which a unit test cannot drive, and `FakeHeadProbe` for the `HEAD` requests.

`MockSyncEventEmitter` is a subclass that captures every emitted payload for assertion.

### Suites

| Suite                            | Cases | Notes                                                                                              |
| -------------------------------- | ----- | -------------------------------------------------------------------------------------------------- |
| `SyncEngineRetryTimerTests`      | 37    | When the timer arms, offline wakes, credential holds, hybrid attach and detach.                    |
| `SyncEngineConfigTests`          | 35    | Configuration across engine restarts, pause lifetime, stale credentials.                           |
| `SyncQueueStorageTests`          | 14    | enqueue / batch / QUEUE_FULL / sort / status transitions / history.                                |
| `SyncDispatcherTests`            | 19    | URLProtocolStub-driven; 200/500/408/429/400/401 paths + transport errors + paused skip + items purged before their dispatch + response classification. |
| `SyncDispatcherCooldownTests`    | 10    | Cooldown written from the end of the attempt, and skipped items.                                   |
| `SyncRetryCooldownTests`         | 14    | Cooldowns in storage, recovery, and the predicate.                                                 |
| `RetryPolicyEvaluatorTests`      | 15    | linear/exp/fib + cap + jitter `[3000, 5000)` over 200 samples + perf measure baseline.             |
| `RetryWakeTimerTests`            | 8     | Single-shot arming and cancel.                                                                     |
| `HeldWakeTests`                  | 18    | Retry wakes the quality gate holds back: silent, re-checked with backoff, re-measured when stale.  |
| `SyncConfigStoreTests`           | 11    | Persistence, logout, orphaned secrets.                                                             |
| `NetworkHealthOptionsTests`      | 15    | The network-health options: checked before `configureSync` applies anything, kept as set, persisted, clamped. |
| `BackgroundSyncManagerTests`     | 17    | Scheduling decisions and handlers, with a fake scheduler.                                          |
| `BackgroundSyncPersistenceTests` | 7     | Options and enabled state across relaunches.                                                       |
| `BackgroundTaskRegistrarTests`   | 6     | Registration, and identifiers missing from `Info.plist`.                                           |
| `CoreDataMigrationV1ToV2Tests`   | 5     | Model v1→v2 migration.                                                                             |
| `CoreDataMigrationV3Tests`       | 8     | v1 and v2 stores opened through the production load: failed items move to the dead-letter store.  |
| `CoreDataStackTests`             | 5     | Store loading and failure.                                                                         |
| `DeadLetterStoreTests`           | 19    | A permanent failure moves the item, with its request and its failure, to the dead-letter store in one transaction. |
| `DeadLetterBridgeTests`          | 12    | Listing, replaying and discarding dead letters through the bridge.                                 |
| `BridgePromiseTests`             | 8     | Bridge calls that answer from memory return promises that are already settled.                    |
| `ConnectivityMonitorTests`       | 7     | Singleton lifecycle + replay sync + raw enum values.                                                |
| `NetworkQualityMonitorTests`     | 29    | When the monitor measures, how long a measurement is valid, and which changes it reports.          |
| `NetworkQualityClassifierTests`  | 10    | The shared threshold table.                                                                        |
| `NetworkQualityLevelTests`       | 4     | The level order, and what a gate at each minimum lets through.                                     |
| `NetworkQualityGateTests`        | 15    | The gate before every flush, the wakes it holds, and what `CONNECTION_CHANGED` and `getConnectionStatus()` report. |
| `HeadProbeTests`                 | 18    | One `HEAD` request, timed from the task metrics, redirects included.                               |
| `HealthCheckTests`               | 16    | The `HEAD` to `healthCheckUrl` before a cycle: when it is sent, its cache, and the backoff after a failure. |

`URLSessionConfiguration.background` and a real `BGTaskScheduler` launch are not exercised in unit tests: they are OS-driven and cannot be run from a test host. That coverage needs a physical device: see [iOS Setup](../getting-started/ios-setup.md#7-test-the-background-handler).

---

## Local Validation Gotcha

The maintainer's Mac currently reports `iOS 26.4 not installed` for the `Any iOS Device` placeholder, which disables every simulator destination locally. CI on `macos-latest` with `XCODE_VERSION: 'latest-stable'` is the source of truth for iOS test runs. Same story for Android: a clean Linux + JDK 17 toolchain is more reliable than the local mac for `testDebugUnitTest` end-to-end.
