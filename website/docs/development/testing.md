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
- Full mock surface for all 21 HybridObject methods (queue/sync/config/history/connection/background + listeners).

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

Targets after Phase 5 (measured, not enforced):

| Path                      | Lines % | Notes                                                                     |
| ------------------------- | ------- | ------------------------------------------------------------------------- |
| `src/utils/`              | 90 %    | Validation, retry math, ULID generator, serialize/deserialize.           |
| `src/errors/`             | 100 %   | Trivial — both files are covered by the facade tests.                    |
| `src/index.tsx` (facade)  | 80 %    | Sentinel translation, error wrapping, listener wrappers.                 |
| `src/hooks/` (avg)        | 62 %    | `useOfflineQueue` + `useSyncConfig` still uncovered (known follow-up).   |
| `src/index.web.tsx`       | 0 %     | Stub — every export throws. Out of scope until v1.x ships web support.   |

The `coverageThreshold` is set to 0 % globally — same convention as the sibling lib. CI emits the report; gates are added later when the hooks gap closes.

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
| `database/SyncItemDaoTest`             | 10    | Room in-memory + Robolectric SDK 30. Insert/batch/count/delete, ordering, status flips. |
| `database/SyncResultDaoTest`           | 5     | History persistence, ordering DESC.                                                     |
| `storage/SyncQueueStorageTest`         | 15    | enqueue + INVALID_URL + QUEUE_FULL + claim atomicity + roundtrip.                       |
| `http/SyncDispatcherTest`              | 7     | MockWebServer 4.12.0 — 200/503/500/401/404 paths + isSyncing flip.                       |
| `retry/RetryPolicyEvaluatorTest`       | 12    | Linear/exp/fib + cap + full-jitter `[0, capped]` with seeded `Random`.                  |
| `connectivity/ConnectivityMonitorTest` | 5     | Lifecycle, idempotency, StateFlow initial UNKNOWN.                                      |
| `work/SyncWorkerTest`                  | 4     | `TestListenableWorkerBuilder` + `mockkObject(SyncProviderRuntime.Companion)`.           |
| `work/RecoveryWorkerTest`              | 3     | Reset-in-flight + reschedule + failure → retry.                                         |

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
yarn test:ios
```

This is a thin wrapper over `scripts/test-ios.sh`. It mirrors the CI invocation: it removes any stale `build/SyncProviderTests.xcresult` before each attempt (defends against the `"Existing file at -resultBundlePath"` failure mode) and walks a destination fallback list (`iPhone 16` → `iPhone 17` → `iPhone 16 Pro`, all `OS=latest`) so a missing simulator on a single machine does not block the run.

The equivalent direct invocation:

```bash
cd example/ios
xcodebuild test \
  -workspace SyncProviderExample.xcworkspace \
  -scheme SyncProvider-Unit-Tests \
  -destination 'platform=iOS Simulator,name=iPhone 16,OS=latest' \
  -resultBundlePath build/SyncProviderTests.xcresult \
  -skipPackagePluginValidation \
  -skipMacroValidation
```

CI runs the same command on `macos-latest` with `XCODE_VERSION=26` (see the `test-ios` job in `.github/workflows/ci.yml`), walks the same destination fallback loop, and uploads `.xcresult` artifacts. A "Show available iOS simulators" diagnostic step prints the runtime list before the test step so destination drift is visible in the log.

### Test seams

Two `internal init(...)` overloads exist solely for tests (do not appear in the public HybridObject surface):

- `CoreDataStack(container:)` — `internal init` accepting an injected `NSPersistentContainer`. Backed by `InMemoryCoreDataStack` which programmatically rebuilds the model (the `.xcdatamodeld` is not embedded in the test bundle — when the production model changes, mirror the change in `InMemoryCoreDataStack.makeModel()`).
- `SyncDispatcher(... session:)` — `internal init` accepting an injected `URLSession` so `URLProtocolStub` can drive every request.

`MockSyncEventEmitter` is a subclass that captures every emitted payload for assertion.

### Suites

| Suite                            | Cases | Notes                                                                                              |
| -------------------------------- | ----- | -------------------------------------------------------------------------------------------------- |
| `SyncQueueStorageTests`          | 15    | enqueue / batch / QUEUE_FULL / sort / status transitions / history.                                |
| `SyncDispatcherTests`            | 16    | URLProtocolStub-driven; 200/500/408/429/400/401 paths + transport errors + paused skip.            |
| `RetryPolicyEvaluatorTests`      | 12    | linear/exp/fib + cap + jitter `[3000, 5000)` over 200 samples + perf measure baseline.             |
| `ConnectivityMonitorTests`       | 8     | Singleton lifecycle + replay sync + raw enum values.                                                |
| `BackgroundSyncManagerTests`     | 3     | Defaults `isEnabled=false`, idempotency `disable()`, `BackgroundSyncOptionsValue` Equatable.        |

`URLSessionConfiguration.background` is not exercised in unit tests — it is OS-resumable and cannot be driven from a test host. That coverage lives in the example app's integration scheme (planned for a future phase).

## Local validation gotcha

The maintainer's Mac currently reports `iOS 26.4 not installed` for the `Any iOS Device` placeholder, which disables every simulator destination locally. CI on `macos-latest` with `XCODE_VERSION=26` is the source of truth for iOS test runs. Same story for Android: a clean Linux + JDK 17 toolchain is more reliable than the local mac for `testDebugUnitTest` end-to-end.
