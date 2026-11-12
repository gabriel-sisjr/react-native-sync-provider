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

### Setup file

`src/__tests__/setup-minimal.ts` mocks the React Native + Nitro surface:

- `react-native-nitro-modules` — `NitroModules.createHybridObject` returns a stable fake stored on `globalThis.__syncMock`.
- `react-native` — `Platform`, `AppState`, `NativeEventEmitter`, `NativeModules`.
- AppState helpers — `simulateAppStateChange('active' | 'background')`.
- Full mock surface for all 24 HybridObject methods (queue/dead letters/sync/config/history/connection/background + listeners).

The facade caches `createHybridObject` on first import. To reset between tests, mutate the same singleton via `Object.assign(currentHybrid, fresh)` instead of recreating — that keeps the same reference alive across tests without `jest.resetModules()`.

### Hook test pattern

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
| `SyncProviderRuntimeTest`              | 54    | Configuration, pause, retry wake and cold workers against a fresh runtime.              |
| `database/SyncItemDaoTest`             | 16    | Room in-memory + Robolectric SDK 30. Insert/batch/count/delete, ordering, status flips. |
| `database/SyncResultDaoTest`           | 5     | History persistence, ordering DESC.                                                     |
| `database/SyncDatabaseMigrationTest`   | 4     | Room v1→v2 migration.                                                                   |
| `storage/SyncQueueStorageTest`         | 28    | enqueue + INVALID_URL + QUEUE_FULL + claim atomicity + roundtrip.                       |
| `http/SyncDispatcherTest`              | 30    | MockWebServer 4.12.0 — success, retry, terminal codes, credential hold, cancellation.   |
| `retry/RetryPolicyEvaluatorTest`       | 18    | Linear/exp/fib + cap + equal jitter `[0.75, 1.25)` with seeded `Random`.                |
| `retry/RetryTimerTest`                 | 13    | Single-shot arming, cancel, a wake owed to the network.                                 |
| `retry/RetryWakeTest`                  | 13    | When the timer and the kick arm, and what suppresses them.                              |
| `retry/OfflineRetryTest`               | 4     | No attempt is spent while offline.                                                      |
| `config/SyncConfigStoreTest`           | 16    | Round trip of every option, and the secret lifecycle.                                   |
| `config/KeystoreSecretStoreTest`       | 4     | Encryption behind the `SecretStore` seam.                                               |
| `connectivity/ConnectivityMonitorTest` | 7     | Lifecycle, idempotency, StateFlow initial UNKNOWN.                                      |
| `work/BackgroundSyncManagerTest`       | 24    | Periodic work, the retry kick, `foregroundOnly`.                                        |
| `work/BootCompletedReceiverTest`       | 5     | Boot and package-replaced actions.                                                      |
| `work/SyncWorkerTest`                  | 7     | `TestListenableWorkerBuilder` + `mockkObject(SyncProviderRuntime.Companion)`.           |
| `work/RecoveryWorkerTest`              | 5     | Reset-in-flight + reschedule + failure → retry.                                         |

### Robolectric SDK 30 (the sweet spot)

Robolectric 4.13 does not ship shadows for `compileSdk 36`. SDK 30 (Android 11) is the highest fully-shadowed level that still covers `ConnectivityManager`, `Room`, and `WorkManager`. Every test that needs Robolectric annotates `@Config(sdk = [30])`.

For tests that only need pure JVM behavior (e.g. `RetryPolicyEvaluator`), `testOptions.unitTests.returnDefaultValues = true` lets Android shims return defaults without Robolectric entirely.

### Mocking

- **MockK 1.13.13** — `coEvery` / `coVerify` for suspend functions; `mockk<T>(relaxed = true)`; `mockkObject(SyncProviderRuntime.Companion)` for the `SyncWorker` runtime injection seam (`unmockkObject` in `@After`).
- **MockWebServer 4.12.0** — same major as production OkHttp. Drives realistic HTTP scenarios from the dispatcher tests.

## iOS (XCTest)

Lives under `ios/Tests/`. The test target is wired on the library side via `s.test_spec 'Tests'` in `SyncProvider.podspec`. CocoaPods 1.10+ defaults `:test_type` to `:unit` and auto-prefixes the resulting scheme with `Unit-`, so `pod install` generates the scheme **`SyncProvider-Unit-Tests`** (not `SyncProvider-Tests`). The library does not rename the test_spec — the segment is inserted by CocoaPods.

`use_native_modules!` autolinking does **not** propagate `:testspecs` to the consuming app. `example/ios/Podfile` opts in explicitly:

```ruby
pod 'SyncProvider', :path => '../..', :testspecs => ['Tests']
```

Without this line, CocoaPods will never generate the test target and `xcodebuild` will fail to resolve `SyncProvider-Unit-Tests`. The `post_install` block also calls `installer.pods_project.recreate_user_schemes(false)` to defensively share the generated test scheme.

### Setup order

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

CI runs the same command on `macos-latest` with `XCODE_VERSION=26` (see the `test-ios` job in `.github/workflows/ci.yml`) and uploads `.xcresult` artifacts. It picks the simulator automatically, preferring an iPhone 17, through `.github/scripts/pick-ios-simulator.mjs`, so the next runner image cannot leave the job without a device. A "Show available iOS simulators" diagnostic step prints the runtime list before the test step so destination drift is visible in the log.

### Test seams

These `internal init(...)` overloads exist solely for tests (they do not appear in the public HybridObject surface):

- `CoreDataStack(container:)` — `internal init` accepting an injected `NSPersistentContainer`. Backed by `InMemoryCoreDataStack`, which programmatically rebuilds the model — when the production model changes, mirror the change in `InMemoryCoreDataStack.makeModel()`. The compiled `.momd` is bundled into the test target for the migration tests only.
- `SyncDispatcher(... foregroundSession:backgroundSession:)` — `internal init` accepting injected sessions so `URLProtocolStub` can drive every request.
- `SyncEngine(...)` — injectable stores, clock, retry timer and scheduler. `InMemorySecretStore` stands in for the Keychain, which hostless XCTest does not have, and `FakeBackgroundTasks` for `BGTaskScheduler`.

`MockSyncEventEmitter` is a subclass that captures every emitted payload for assertion.

### Suites

| Suite                            | Cases | Notes                                                                                              |
| -------------------------------- | ----- | -------------------------------------------------------------------------------------------------- |
| `SyncEngineRetryTimerTests`      | 37    | When the timer arms, offline wakes, credential holds, hybrid attach and detach.                    |
| `SyncEngineConfigTests`          | 35    | Configuration across engine restarts, pause lifetime, stale credentials.                           |
| `SyncQueueStorageTests`          | 14    | enqueue / batch / QUEUE_FULL / sort / status transitions / history.                                |
| `SyncDispatcherTests`            | 17    | URLProtocolStub-driven; 200/500/408/429/400/401 paths + transport errors + paused skip.            |
| `SyncDispatcherCooldownTests`    | 10    | Cooldown written from the end of the attempt, and skipped items.                                   |
| `SyncRetryCooldownTests`         | 14    | Cooldowns in storage, recovery, and the predicate.                                                 |
| `RetryPolicyEvaluatorTests`      | 15    | linear/exp/fib + cap + jitter `[3000, 5000)` over 200 samples + perf measure baseline.             |
| `RetryWakeTimerTests`            | 8     | Single-shot arming and cancel.                                                                     |
| `SyncConfigStoreTests`           | 11    | Persistence, logout, orphaned secrets.                                                             |
| `BackgroundSyncManagerTests`     | 17    | Scheduling decisions and handlers, with a fake scheduler.                                          |
| `BackgroundSyncPersistenceTests` | 7     | Options and enabled state across relaunches.                                                       |
| `BackgroundTaskRegistrarTests`   | 6     | Registration, and identifiers missing from `Info.plist`.                                           |
| `CoreDataMigrationV1ToV2Tests`   | 5     | Model v1→v2 migration.                                                                             |
| `CoreDataStackTests`             | 5     | Store loading and failure.                                                                         |
| `ConnectivityMonitorTests`       | 7     | Singleton lifecycle + replay sync + raw enum values.                                                |

`URLSessionConfiguration.background` and a real `BGTaskScheduler` launch are not exercised in unit tests: they are OS-driven and cannot be run from a test host. That coverage needs a physical device: see [iOS Setup](../getting-started/ios-setup.md#7-test-the-background-handler).

## Local validation gotcha

The maintainer's Mac currently reports `iOS 26.4 not installed` for the `Any iOS Device` placeholder, which disables every simulator destination locally. CI on `macos-latest` with `XCODE_VERSION=26` is the source of truth for iOS test runs. Same story for Android: a clean Linux + JDK 17 toolchain is more reliable than the local mac for `testDebugUnitTest` end-to-end.
