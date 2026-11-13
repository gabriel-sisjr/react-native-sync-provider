---
sidebar_position: 99
title: Troubleshooting
description: Symptom / cause / fix entries for the most common issues with @gabriel-sisjr/react-native-sync-provider on iOS, Android, the JS facade, and the build pipeline.
keywords:
  - troubleshooting
  - errors
  - build issues
  - nitrogen
  - workmanager
  - bgtaskscheduler
  - core data
  - room
---

# Troubleshooting

Each entry below follows the same **Symptom, Cause, Fix** shape and ends with related links. Common questions are answered in the [FAQ](#faq). If your issue is not listed here and looks reproducible, please open a [GitHub issue](https://github.com/gabriel-sisjr/react-native-sync-provider/issues) using the bug report template.

---

## Android

### KSP build fails on Room with `java.lang.IllegalStateException: unexpected jvm signature V` {#symptom-ksp-build-fails-on-room-with-javalangillegalstateexception-unexpected-jvm-signature-v}

The Android build fails while KSP generates the Room code.

**Cause.** Incompatibility between Room 2.6.1's KSP processor and Kotlin 2.0.x metadata. Any `suspend fun` in a DAO that returns `Unit` (JVM signature `void`) trips it.

**Fix.**

1. The library pins Room 2.7.0, which fixes this upstream. If you fork the lib, ensure the bump landed in `android/build.gradle`:

   ```gradle
   implementation 'androidx.room:room-runtime:2.7.0'
   implementation 'androidx.room:room-ktx:2.7.0'
   ksp 'androidx.room:room-compiler:2.7.0'
   ```

**Related:** [Android Setup](./getting-started/android-setup.md#1-gradle-versions)

---

### `ConnectivityMonitor` misses "connection restored" events after toggling airplane mode {#symptom-connectivitymonitor-misses-connection-restored-events-after-toggling-airplane-mode}

After airplane mode is turned off, no `CONNECTION_CHANGED` event reports the restored connection.

**Cause.** `NetworkCallback` was registered against an `Activity` instead of `ApplicationContext`. The `Activity` is destroyed during config changes, taking the callback registration with it.

**Fix.**

1. The library registers against `ApplicationContext` internally — verify your fork did not change this in `connectivity/ConnectivityMonitor.kt`.
2. If you wrap the lib in your own foreground service, register your additional callbacks against `getApplicationContext()` too.

**Related:** [Connectivity Detection](./guides/connectivity-detection.md)

---

### Periodic flush never fires at intervals shorter than 15 minutes {#symptom-periodic-flush-never-fires-at-intervals-shorter-than-15-minutes}

The periodic worker runs at most once every 15 minutes, whatever `minimumIntervalMs` you pass.

**Cause.** `WorkManager.PeriodicWorkRequest` enforces a hard 15-minute floor. The library clamps `BackgroundSyncOptions.minimumIntervalMs` to that value.

**Fix.**

1. This is by design. For sub-15-minute cadence while the app is running, mount `useAutoSync`, which flushes from JS on an interval and on `CONNECTION_CHANGED`.
2. Failed items also come back on their own through the [retry timer and kick](./guides/retry-policy.md#who-wakes-the-queue-and-when).

**Related:** [Background Sync](./guides/background-sync.md#android-workmanager)

---

### WorkManager flushes deferred for hours during normal phone use {#symptom-workmanager-flushes-deferred-for-hours-during-normal-phone-use}

Periodic background flushes run hours apart, although the device is in daily use.

**Cause.** Doze mode, App Standby Buckets, or manufacturer-specific (Xiaomi MIUI, Huawei EMUI) "kill background tasks" behavior.

**Fix.** Document this for users in your app. Optional mitigations:

1. Set `BackgroundSyncOptions.requiresCharging = false` (default) so windows can run on battery.
2. For time-sensitive workloads, expose a "sync now" button that calls `flush()` directly. It runs in your app's process, independent of WorkManager.
3. Recommend users add your app to their device's "auto-start whitelist" on aggressive ROMs.

**Related:** [Android Setup](./getting-started/android-setup.md#6-doze-mode-and-battery-optimizations), [Background Sync](./guides/background-sync.md#doze-mode), [Battery Optimization](./guides/battery-optimization.md)

---

### `[CXX5304] SDK XML version 4` warning during Android build {#symptom-cxx5304-sdk-xml-version-4-warning-during-android-build}

The Android build prints a `[CXX5304]` warning about the SDK XML version.

**Cause.** CMake step from a transitive dep (often `netinfo`) targets an older Android SDK XML format than your installed NDK.

**Fix.**

1. Harmless. Pre-existing in the React Native ecosystem. Ignore.

**Related:** [Android Setup](./getting-started/android-setup.md)

---

## iOS

### Build fails with `Multiple commands produce '...PrivacyInfo.xcprivacy'` {#symptom-pod-install-fails-with-multiple-commands-produce-privacyinfoxcprivacy}

The Xcode build stops with a `Multiple commands produce` error for a `PrivacyInfo.xcprivacy` path.

**Cause.** RN 0.85+ aggregates every pod's privacy manifest into the host app's manifest at build time. If a pod lists its `PrivacyInfo.xcprivacy` under `s.resources` instead of `s.resource_bundles`, both the listing and the aggregator's output try to land at the same path.

**Fix.**

1. The library already uses `s.resource_bundles` exactly to avoid this. If you fork or vendor the podspec, ensure the privacy manifest stays under `s.resource_bundles = { "SyncProvider_Privacy" => ["ios/PrivacyInfo.xcprivacy"] }`.

**Related:** [iOS Privacy Manifest](./production/privacy-manifest.md), [Installation](./getting-started/installation.md#troubleshooting)

---

### Build fails with `Invalid redeclaration of 'SyncItemEntity' / 'SyncResultEntity'` {#symptom-build-fails-with-invalid-redeclaration-of-syncitementity--syncresultentity}

Xcode reports a second declaration of a Core Data entity class.

**Cause.** Xcode is auto-generating Core Data classes for entities in `SyncProvider.xcdatamodeld` AND the explicit `+CoreDataClass.swift` files are also being compiled — both produce the same symbol.

**Fix.**

1. Confirm no entity (`SyncItemEntity`, `SyncResultEntity`, and from v0.3 `SyncDeadLetterEntity`) has a `codeGenerationType` attribute, in any model version under `SyncProvider.xcdatamodeld` (`SyncProvider`, `SyncProvider 2` and `SyncProvider 3`). With no attribute, Xcode shows **Manual/None** and generates nothing, while `class` or `category` generates the clashing classes.
2. Delete the attribute, or set **Codegen** to Manual/None in the Data Model inspector. Do not write `manual/none` as the value: `momc` fails with `unexpected code generation type 'manual/none'`. The explicit Swift files are the only source of truth.

**Related:** [iOS Native Architecture](./architecture/ios-native.md)

---

### App crashes at launch after upgrading to v0.2 {#symptom-app-crashes-at-launch-after-upgrading-to-v02}

After the upgrade, the app crashes as it launches.

**Cause.** Most likely your `AppDelegate` still registers a library identifier with `BGTaskScheduler.shared.register`. The library now registers its handlers at process start, and iOS traps on a second registration for the same identifier. This is the expected outcome rather than one reproduced on a device.

**Fix.**

1. Delete that block. An earlier version of the [iOS Setup](./getting-started/ios-setup.md) page showed it, along with a `SyncProviderBackgroundTaskRunner` class that never existed.

**Related:** [iOS Setup](./getting-started/ios-setup.md#5-forward-background-urlsession-events), [Migration Guides](./migration/index.md#v0-1-to-v0-2)

---

### `BGTaskScheduler` never fires {#symptom-bgtaskscheduler-never-fires}

No background window ever runs for the app.

**Cause.** One of:

- An identifier is missing from `BGTaskSchedulerPermittedIdentifiers`. Both fixed identifiers are needed, and `taskIdentifier` has no effect.
- The `Background fetch` and/or `Background processing` capabilities are not enabled on the app target.
- Running on the iOS Simulator, where `BGTaskScheduler` does not accept requests. `enableBackgroundSync` rejects there.
- The user force-quit the app. iOS does not launch it for background tasks until the user opens it again.
- The OS has not granted a window. Nothing guarantees when one runs.

**Fix.**

1. See the [iOS Background Modes](./production/ios-background-modes.md) page for the full setup.
2. Simulate the window via the Xcode debugger, on a physical device, to confirm registration once setup is complete.

**Related:** [iOS Setup](./getting-started/ios-setup.md#7-test-the-background-handler), [Background Sync](./guides/background-sync.md#ios-bgtaskscheduler)

---

### Background `URLSession` upload "lost" after force-quit {#symptom-background-urlsession-upload-lost-after-force-quit}

An upload that was running when the user force-quit the app never completes.

**Cause.** Force-quitting the app from the App Switcher cancels every in-flight `URLSession` task. By design — Apple distinguishes user-initiated termination from OS-initiated suspension.

**Fix.**

1. `RecoveryManager` runs at next launch and atomically transitions any items left `IN_FLIGHT` back to `PENDING`. It sends nothing itself: the next flush picks them up, so make sure something starts one.
2. The attempt is already spent, and the server may have received the request, so endpoints must be [idempotent](./guides/idempotency.md).

**Related:** [Crash Recovery](./guides/crash-recovery.md), [Background Sync](./guides/background-sync.md#background-uploads)

---

### Local Mac shows `iOS 26.4 not installed` for `Any iOS Device` {#symptom-local-mac-shows-ios-264-not-installed-for-any-ios-device}

Xcode disables every simulator destination, although the simulator runtime is installed.

**Cause.** Host Xcode tooling glitch. The simulator runtime is technically present but the Xcode UI flags the placeholder destination as missing, disabling every simulator target.

**Fix.**

1. This is the maintainer's Mac being broken, not a code issue. Run on CI (`macos-latest`, with `XCODE_VERSION: 'latest-stable'`, which resolves to the newest stable Xcode on the runner image) for ground truth.
2. Locally: pick a specific simulator instead of `Any iOS Device`, or wipe `~/Library/Developer/CoreSimulator/` and reinstall the runtime via `xcrun simctl runtime install`.

**Related:** [CI/CD Pipeline](./development/ci-cd.md), [Testing](./development/testing.md)

---

## Cross-Platform / JS

### `NATIVE_MODULE_UNAVAILABLE` error on web or in SSR {#symptom-native_module_unavailable-error-on-web-or-in-ssr}

Calls to the library reject with `NATIVE_MODULE_UNAVAILABLE`.

**Cause.** The Nitro HybridObject can't be created without the native runtime — web bundles, Jest without the lib's mock, or SSR.

**Fix.**

1. Gate calls with `isNativeModuleAvailable()`:

   ```ts
   import {
     isNativeModuleAvailable,
     enqueue,
   } from '@gabriel-sisjr/react-native-sync-provider';

   if (isNativeModuleAvailable()) {
     await enqueue({ method: 'POST', url: '...' });
   } else {
     // fall back to direct fetch, queue in your own JS store, etc.
   }
   ```

The 9 hooks already short-circuit safely when the module is missing — they return inert state instead of throwing.

**Related:** [Installation](./getting-started/installation.md#verifying-the-installation)

---

### `Property 'networkQuality' is missing` after upgrading to v0.3 {#symptom-property-networkquality-is-missing-after-upgrading-to-v03}

TypeScript stops compiling a test fixture, a mock or an exhaustive `switch` after the upgrade.

**Cause.** `ConnectionState.networkQuality` and `UseConnectionResult.networkQuality` are required. A hand-built value, usually a test fixture or a mock of `getConnectionStatus`, stops compiling. An exhaustive `switch` over `SyncEventType` or `SyncErrorCode` with a `never` check fails for the same reason: `ITEM_DEAD_LETTERED` and three error codes were added.

**Fix.**

1. Add `networkQuality` to the fixtures, for example `networkQuality: 'GOOD'` for a connected unmetered link and `'OFFLINE'` for a disconnected one.
2. Add the new cases. See the [v0.2 → v0.3 migration](./migration/index.md#v0-2-to-v0-3).

**Related:** [Types & Interfaces](./api-reference/types.md#connectionstate)

---

### Type errors in unrelated files after upgrading the library {#symptom-type-errors-in-unrelated-files-after-upgrading-the-library}

After an upgrade, the type checker reports errors in files that did not change.

**Cause.** The Nitro spec was regenerated, the `lib/typescript/` declarations refreshed, and your app's `tsconfig` is caching the old shapes. Or you forgot to run `yarn nitrogen` after pulling.

**Fix.**

1. Regenerate the bindings and type-check again:

   ```bash
   yarn nitrogen
   yarn typecheck
   # Also wipe the IDE cache: VSCode → "TypeScript: Restart TS Server"
   ```

2. If errors persist, delete `node_modules/@gabriel-sisjr/react-native-sync-provider/lib` and reinstall — the published `lib/typescript/` is the source of truth your editor reads.

**Related:** [Contributing](./development/contributing.md)

---

### Items are not sent after `enqueue` {#symptom-items-are-not-sent-after-enqueue}

`enqueue` resolves with an id, but the request never reaches your server.

**Cause.** Enqueueing stores the item. It does not dispatch it, under any strategy. `AUTOMATIC` retries failed items on its own, but it does not flush on enqueue or on reconnect.

**Fix.**

1. Mount [`useAutoSync`](./api-reference/hooks/useAutoSync.md) with `enabled: true`, or call `flush()`. In a background window, a flush happens when the OS grants one.

**Related:** [Offline Queue](./guides/offline-queue.md#when-items-are-dispatched)

---

### Items stay pending and `flush()` returns 0/0 {#symptom-items-stay-pending-and-flush-returns-00}

`flush()` resolves with a `successCount` and a `failureCount` of `0`, and the items stay in the queue.

**Cause.** Nothing was eligible, or no cycle ran. Check in this order:

- **Cooling.** Every item is waiting out a retry cooldown. See [A failed item doesn't retry immediately after `flush()`](#symptom-a-failed-item-doesnt-retry-immediately-after-flush).
- **Paused.** `pauseSync()` is in force. `flush()` dispatches nothing until `resumeSync()`.
- **Stored headers cannot be read.** `defaultHeaders` were configured, but they cannot be recovered in this process, so nothing is dispatched: sending without them would fail every item. This happens when the Keystore key was invalidated, when the iOS device has not been unlocked since boot, or after a backup restore onto another device. With Android `allowBackup`, the ciphertext comes back but the key does not.
- **Quality gate.** The network quality is below `minNetworkQuality`. The flush emits `SYNC_FAILED` with `NETWORK_QUALITY_TOO_LOW`. See [the next entry](#symptom-nothing-syncs-with-minnetworkquality-or-healthcheckurl-set).
- **Health check.** The `HEAD` to `healthCheckUrl` failed, or its backoff is running. The flush emits `SYNC_FAILED` with `HEALTH_CHECK_FAILED`. See [the next entry](#symptom-nothing-syncs-with-minnetworkquality-or-healthcheckurl-set).

A cycle that found nothing eligible emits `SYNC_STARTED` and `SYNC_SUCCEEDED` and adds a 0/0 row to the sync history. A `flush()` blocked by a pause, unreadable headers, the gate or the health check runs no cycle: it emits no `SYNC_STARTED` and writes no history row. On Android it also returns `0` for `startedAt` and `finishedAt`. On iOS both are the time of the call, so only the missing event and history row tell the two apart.

**Fix.**

1. For unreadable headers, call `configureSync` with the headers again, which ends the wait. The library also retries the read on each flush, so an iOS device resumes after the first unlock.

> **Warning — Do not re-apply `getSyncConfig()` in this state:** While headers are unreadable, `getSyncConfig()` returns none (`undefined` on Android, `{}` on iOS), so `setConfig({ ...config })` or `configureSync(await getSyncConfig())` deletes them, which acts as a logout. Pass `defaultHeaders` from your app's session.

**Related:** [Background Sync](./guides/background-sync.md#when-stored-headers-cannot-be-read), [Retry Policy](./guides/retry-policy.md#retry-cooldown-across-restarts)

---

### Nothing syncs with `minNetworkQuality` or `healthCheckUrl` set {#symptom-nothing-syncs-with-minnetworkquality-or-healthcheckurl-set}

Items stay queued, and every flush is skipped although the device is online.

**Cause.** Every cycle is skipped at the start. Listen to `SYNC_FAILED` and read its `errorCode`:

| `errorCode` | Likely cause |
| ----------- | ------------ |
| `NETWORK_QUALITY_TOO_LOW` | The link never reaches the minimum. `EXCELLENT` without `healthCheckUrl` never passes, `GOOD` without it never passes on a metered link, and on Android a network without `NET_CAPABILITY_VALIDATED` is at most `POOR`. |
| `HEALTH_CHECK_FAILED` | The endpoint does not answer the `HEAD` with a `2xx`. The probe carries no `defaultHeaders`, so an endpoint that requires authentication answers `401`. Some servers answer `405` to `HEAD`. |

Read `networkQuality` from `getConnectionStatus()` to see the current level. While the gate holds the in-process retry timer, nothing is emitted at all: the wake runs once the quality reaches the minimum, and re-checks on a backoff that grows to 15 minutes.

**Fix.**

1. Lower `minNetworkQuality`, or set `healthCheckUrl` if you need `EXCELLENT`.
2. Point `healthCheckUrl` at an unauthenticated endpoint that answers `HEAD` with a `2xx`.
3. A `configureSync` call ends a running health-check backoff and restarts the gate's re-check backoff, so after fixing the endpoint, apply the options again.
4. To turn either feature off, call `configureSync` without the field.

**Related:** [Connectivity Detection](./guides/connectivity-detection.md#the-quality-gate)

---

### Items stay pending and the server logs one 401 per wake {#symptom-items-stay-pending-and-the-server-logs-one-401-per-wake}

Your server logs a `401` for the item at each wake, and the item stays `PENDING`.

**Cause.** A stored token expired. In a process that has not called `configureSync`, a 401 or 403 holds the item instead of failing it: it stays `PENDING`, no attempt is spent, the next try waits at least 60 seconds, and the cycle stops. You see `ITEM_RETRYING` with `UNAUTHORIZED`, if anything is listening. Retry wakes wait for the hold, but a process restart drops it, and a periodic worker or BGTask window does not wait for it, so each of those can cost one more request. See [When a stored token expires](./guides/background-sync.md#when-a-stored-token-expires).

**Fix.**

1. Call `configureSync` with a fresh token at launch and whenever it changes. The library does not refresh tokens, and a process with no JS cannot renew one. Each held item then dispatches once its hold ends.

**Related:** [Background Sync](./guides/background-sync.md#tokens-and-logout)

---

### `flush()` does nothing after `pauseSync()`, or the queue runs again after a restart {#symptom-flush-does-nothing-after-pausesync-or-the-queue-runs-again-after-a-restart}

`flush()` dispatches nothing while a pause is in force, and the pause is gone after the app relaunches.

**Cause.** A pause lasts until `resumeSync()` or the next launch that reaches the library, including a JS reload. Workers and BGTasks stay inert while the app is dead. A launch clears the pause without emitting `RESUMED`, and on iOS, React Native can start during a background launch, which counts if JS reaches the library there.

**Fix.**

1. Call `resumeSync()` to end a pause.
2. To keep one across launches, call `pauseSync()` again at startup.
3. Hooks cannot read the pause flag: `isPaused` starts `false` and follows `PAUSED` and `RESUMED`, so a hook mounted while an earlier `pauseSync()` is still in force reads `false` until the next `PAUSED`.

**Related:** [Background Sync](./guides/background-sync.md#what-stops-what)

---

### `drainUntilEmpty()` stops early {#symptom-drainuntilempty-stops-early}

`drainUntilEmpty()` resolves while items remain in the queue.

**Cause.** Read `stoppedReason`:

| `stoppedReason`  | What happened                                                                                                                                  |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `TIMEOUT`        | `timeoutMs` passed, or the soonest cooldown ends at or after that deadline, so waiting was pointless. Also how an offline drain ends, with items intact. |
| `MAX_ITERATIONS` | `maxIterations` flushes started and items remain. It counts real flushes only, including flushes the quality gate or the health check skipped.   |
| `CANCELLED`      | `cancelDrain()` was called.                                                                                                                    |

**Fix.**

1. For a long backoff, raise `timeoutMs`.
2. For a large queue, raise `maxIterations` or `batchSize`.
3. A native cycle already running is not aborted, so `queueRemaining` can be stale right after a stop: re-read `getQueueSize()`.

**Related:** [Functions](./api-reference/functions.md#drainuntilempty)

---

### `DRAIN_ALREADY_ACTIVE` right after `cancelDrain()` {#symptom-drain_already_active-right-after-canceldrain}

A drain started right after `cancelDrain()` rejects with `DRAIN_ALREADY_ACTIVE`.

**Cause.** A cancelled drain still has to settle, so a drain started in the same tick is rejected.

**Fix.**

1. Await the previous drain before starting another. See the example under [`cancelDrain`](./api-reference/functions.md#canceldrain).

**Related:** [Errors](./api-reference/errors.md)

---

### `enqueue` rejects with `INVALID_PAYLOAD` and no `validationErrors` {#symptom-enqueue-rejects-with-invalid_payload-and-no-validationerrors}

`enqueue` rejects with `INVALID_PAYLOAD`, and the error carries no `validationErrors`.

**Cause.** Native rejected the call, most often because the queue reached `maxQueueSize`. The native `QUEUE_FULL` code [does not reach JS yet](./api-reference/errors.md#native-rejections). `replayDeadLetter` rejects the same way on a full queue, and keeps the dead letter.

On Android this can happen while `getQueueSize()` reads below the cap, because the cap also counts items in flight, which that function does not show. Dead letters count toward the cap on neither platform.

**Fix.**

1. Flush, or raise `maxQueueSize`.

**Related:** [Offline Queue](./guides/offline-queue.md#queue-size-limits)

---

### Items wait after the device reconnects, with `useAutoSync` mounted {#symptom-items-wait-after-the-device-reconnects-with-useautosync-mounted}

The device comes back online, but nothing flushes until the next interval tick.

**Cause.** `enabled` is gated on the connection, for example on `useConnection().isOnline`. While `enabled` is `false` the hook is not subscribed, so its reconnect trigger cannot fire, and turning `enabled` back on does not flush. Items wait for the next interval tick. Before v0.2 this gate kept the interval from flushing offline.

**Fix.**

1. Leave `enabled` on. The hook skips its interval and foreground flushes while the status is `DISCONNECTED`. See [Skip flushes while offline](./api-reference/hooks/useAutoSync.md#skip-the-interval-while-offline).

**Related:** [Connectivity Detection](./guides/connectivity-detection.md#auto-flush-on-reconnect)

---

### Items end up in the dead-letter queue while the device is offline {#symptom-items-end-up-in-the-dead-letter-queue-while-the-device-is-offline}

Valid requests land in the dead-letter queue after an outage.

**Cause.** A flush ran during the outage. Without `minNetworkQuality`, neither native dispatcher checks connectivity, so each failed request spends a retry attempt, and an item with no attempts left is dead-lettered. `useAutoSync` skips only while the status is `DISCONNECTED`, so `UNKNOWN` still flushes, and a direct `flush()` has no check of its own.

**Fix.**

1. Set `minNetworkQuality: NetworkQuality.POOR`, the most permissive gate, which blocks every flush while the quality is `OFFLINE`, including an `UNKNOWN` link.
2. Without the gate, read `getConnectionStatus()` before a direct `flush()`, or use [`drainUntilEmpty`](./api-reference/functions.md#drainuntilempty), which does not flush while `DISCONNECTED`.
3. Then [`replayDeadLetter`](./api-reference/functions.md#replaydeadletter) the items the outage cost you.

**Related:** [Connectivity Detection](./guides/connectivity-detection.md#what-acts-on-connectivity)

---

### A failed item is no longer in `getPendingItems()` {#symptom-a-failed-item-is-no-longer-in-getpendingitems}

An item is gone from `getPendingItems()` and from the queue hooks, although it was never delivered.

**Cause.** It failed permanently and moved to the dead-letter queue, where `getQueueSize()`, `getPendingItems()` and the queue hooks do not look. Its `ITEM_FAILED` carried the code, and `ITEM_DEAD_LETTERED` followed. Items that had failed permanently before the upgrade to v0.3 were moved there too.

**Fix.**

1. Read it with [`listDeadLetter`](./api-reference/functions.md#listdeadletter) or `useSyncSnapshot().deadLetter`. `lastErrorCode` and `lastErrorMessage` say why it failed.
2. Replay it with `replayDeadLetter` once the cause is fixed, or delete it with `discardDeadLetter` or `removeItem`.
3. Nothing prunes the dead-letter queue, so discard what you do not replay.

**Related:** [Error Handling](./guides/error-handling.md#dead-letter-flows)

---

### `networkQuality` never reaches `EXCELLENT` {#symptom-networkquality-never-reaches-excellent}

`getConnectionStatus()` reports `GOOD` or lower, even on a fast link.

**Cause.** `EXCELLENT` requires a measured median under 200 ms, and only `healthCheckUrl` turns the measurement on. Even with it, a metered link is at most `GOOD`, iOS Low Data Mode at most `MODERATE`, and an Android network without `NET_CAPABILITY_VALIDATED` at most `POOR`.

**Fix.**

1. Set `healthCheckUrl` to a fast, unauthenticated endpoint of yours, close to your users.

**Related:** [Connectivity Detection](./guides/connectivity-detection.md#network-quality)

---

### Fewer `CONNECTION_CHANGED` events after upgrading to v0.3 {#symptom-fewer-connection_changed-events-after-upgrading-to-v03}

After the upgrade, `CONNECTION_CHANGED` fires less often than before.

**Cause.** The event now fires only when a reported field changes. iOS used to emit it on every path update, including duplicates. It is also emitted when only `networkQuality` changed, with an unchanged `connectionStatus`.

**Fix.**

1. None needed if you read the status from the event.
2. If you counted events, compare the new `connectionStatus` with the previous one, or call `getConnectionStatus()` on each event.

**Related:** [Connectivity Detection](./guides/connectivity-detection.md#connection_changed-events)

---

### `progress` stays at `0` on Android {#symptom-progress-stays-at-0-on-android}

During an Android flush, `useSyncStatus().progress` stays at `0` until the cycle ends.

**Cause.** Android does not emit `SYNC_PROGRESS`. Only iOS does.

**Fix.**

1. Show an indeterminate indicator while `isSyncing` is `true`.

**Related:** [useSyncStatus](./api-reference/hooks/useSyncStatus.md)

---

### `flush()` resolves but I'm seeing failures in production {#symptom-flush-resolves-but-im-seeing-failures-in-production}

`flush()` never rejects, yet requests fail in production.

**Cause.** `flush()` only rejects when the native module is missing or the cycle itself dies. Per-item failures resolve normally with `failureCount > 0`.

**Fix.**

1. Subscribe to `ITEM_FAILED`, which works on both platforms:

   ```ts
   useSyncEvents({
     types: ['ITEM_FAILED'],
     onEvent: (e) => reportToCrashlytics({ id: e.itemId, code: e.errorCode }),
   });
   ```

`result.failedIds` and `result.errors` hold the same detail on Android, and are always empty on iOS. The failed items themselves are in the dead-letter queue: read them with [`listDeadLetter`](./api-reference/functions.md#listdeadletter).

**Related:** [Error Handling](./guides/error-handling.md#listening-for-failures-via-events)

---

### A failed item doesn't retry immediately after `flush()` {#symptom-a-failed-item-doesnt-retry-immediately-after-flush}

A second `flush()` right after a failure skips the failed item.

**Cause.** As of v0.2, a failed-retriable item gets a persisted `nextRetryAt` cooldown, measured from the end of the attempt, and is **skipped by the dispatcher until that deadline passes** — on both platforms, and across restarts/reboots. This is intentional: it prevents a hot retry loop against a degraded server. Before v0.2, iOS retried with no spacing and Android only spaced retries within a single flush cycle.

**Fix.**

1. This is by design. In a running app with a strategy other than `MANUAL`, the retry timer brings the item back on its own.
2. Inspect the per-item deadline via `getPendingItems()` — `item.nextRetryAt` is `0` for an item that is eligible now, and otherwise the epoch-ms time it becomes eligible, after a cooldown or a credential hold. A cooling item still counts toward `getQueueSize()`.

   ```ts
   const pending = await getPendingItems();
   pending.forEach((item) => {
     const waitMs = Math.max(0, item.nextRetryAt - Date.now());
     console.log(
       `${item.id}: ${item.attempts} recorded attempt(s), eligible in ${waitMs}ms`
     );
   });
   ```

3. To wait out cooldowns explicitly, use `drainUntilEmpty()`, which sleeps until the soonest `nextRetryAt` by itself.

**Related:** [Retry Policy](./guides/retry-policy.md#retry-cooldown-across-restarts)

---

## Build & Tooling

### `yarn install --immutable` fails right after editing `package.json` {#symptom-yarn-install---immutable-fails-right-after-editing-packagejson}

`yarn install --immutable` refuses to run because the lockfile would change.

**Cause.** Adding/removing a dep (or a workspace) mutates the lockfile, which `--immutable` forbids.

**Fix.**

1. Run plain `yarn install` first locally to refresh `yarn.lock`.
2. Commit the lockfile change, and then `--immutable` works in CI.

**Related:** [Contributing](./development/contributing.md)

---

### `yarn docs:build` fails on a broken link, or warns about a broken anchor {#symptom-yarn-docsbuild-fails-with-broken-anchor-or-broken-link}

The docs build stops on a link to a missing page, or prints a warning for a broken anchor.

**Cause.** The Docusaurus config sets `onBrokenLinks: 'throw'`, so a link to a page that does not exist fails the build. It does not set `onBrokenAnchors`, which keeps its default, `'warn'`, so a broken `#anchor` only warns, and `markdown.hooks.onBrokenMarkdownLinks: 'warn'` makes an unresolved `.md` link a warning too. The CI link check is stricter: it runs lychee with `--offline --include-fragments` over every `*.md` file, and fails on a broken internal link or anchor.

**Fix.**

1. Fix the heading slug or the path. Docusaurus generates anchor slugs by lowercasing and hyphenating the heading text — `## Background Sync` → `#background-sync`. A heading with an explicit `{#id}` keeps that id instead.
2. Use relative `.md` paths within `website/docs/` (`./other-page.md` or `../section/page.md`), as every page does. Avoid linking to root markdown files from inside `docs/` (use the GitHub URL instead).
3. Read the build log for anchor warnings: they do not fail the build, but they fail the CI link check.

**Related:** [Contributing](./development/contributing.md)

---

### `yarn docs:build` fails with `Mermaid validation failed`

The docs build stops before Docusaurus runs, and the log lists each diagram that does not parse as `file:line - message`.

**Cause.** The website's `build` script runs `yarn validate:mermaid` before `docusaurus build`. The validator parses every mermaid diagram under `website/docs/`, and exits with an error when one does not parse.

**Fix.**

1. Fix the diagram at the file and line the log names.
2. Re-run the check alone with `yarn workspace react-native-sync-provider-website validate:mermaid`.

**Related:** [Contributing](./development/contributing.md)

---

### `yarn nitrogen` reports zero HybridObjects generated {#symptom-yarn-nitrogen-reports-zero-hybridobjects-generated}

`yarn nitrogen` completes without generating the `SyncProvider` spec.

**Cause.** `nitro.json` is missing or malformed, or the spec file is not picked up.

**Fix.**

1. Confirm `nitro.json` lives at the repo root and lists `SyncProvider` under `autolinking`.
2. The spec must end in `*.nitro.ts` and live under `src/`.
3. Re-run `yarn nitrogen` and check the log: a successful run prints `Generated 1/1 HybridObject specs`.

**Related:** [Contributing](./development/contributing.md)

---

## FAQ

### Will this work without the New Architecture?

No. The library is built on Nitro Modules, which do not run on the legacy bridge. See [New Architecture Verification](./getting-started/installation.md#new-architecture-verification) to check that it is enabled.

**Related:** [Introduction](./getting-started/introduction.md#requirements)

---

### Does it work on Expo?

Only in a development build. The library contains native code, so it does not run in Expo Go. It ships no Expo config plugin, so apply the [iOS Setup](./getting-started/ios-setup.md) steps to the native project yourself: the Background Modes, the two BGTask identifiers and the `AppDelegate` forwarding. Android needs no manual step, because the manifest merger adds the library's permissions and its boot receiver. The New Architecture must be enabled, as in any app.

**Related:** [Installation](./getting-started/installation.md), [Android Setup](./getting-started/android-setup.md)

---

### How is this different from just calling `fetch` inside a `BackgroundFetch` task?

You would be writing all of this yourself: native persistence, retry policy, priority ordering, recovery from interrupted in-flight items, queue inspection from JS, and React bindings. This library packages that, and its background windows dispatch with no JS loaded.

**Related:** [Background Sync](./guides/background-sync.md)

---

### Does `enqueue` send the request?

No. It stores the item. A flush sends it, from `useAutoSync`, `flush()`, or an OS background window. `AUTOMATIC` retries failed items on its own, but it does not flush on enqueue.

**Related:** [Offline Queue](./guides/offline-queue.md#when-items-are-dispatched)

---

### Can I send file uploads?

No. `body` is a string sent as UTF-8 text, and there is no file-based body. You can send text-based formats by setting `contentType`, but binary multipart uploads are not supported.

**Related:** [Types & Interfaces](./api-reference/types.md#synciteminput)

---

### What happens to in-flight items if the OS kills the process mid-upload?

On the next launch, the recovery layer marks orphaned items as `PENDING` and they re-enter the queue. With no JS loaded, an iOS BGTask or an Android worker does the same before it flushes, unless a pause or `foregroundOnly` blocks the Android worker. Android also runs it after a reboot or an app update. Their attempt counters and cooldowns are kept, and the server may already have processed the request, so delivery is at least once. Send an `Idempotency-Key` header, set through `headers` with a key you generate before enqueueing, and dedupe on the server.

**Related:** [Crash Recovery](./guides/crash-recovery.md), [Idempotency](./guides/idempotency.md)

---

### Is the queue encrypted at rest?

No. Per-item `headers` and `body` are stored in plain text, in the queue and in the dead-letter queue. Put credentials in `SyncOptions.defaultHeaders`, which are encrypted at rest (Android Keystore / iOS Keychain). `clearQueue()` deletes both queues, for example on logout. It does not need to wait for a running cycle: an item whose request has not started is not sent, and a request already in flight is not cancelled, but its item is never written back, as a queue row or as a dead letter.

**Related:** [Background Sync](./guides/background-sync.md#cold-start-configuration), [Error Handling](./guides/error-handling.md#purge-on-logout)

---

### What happens to a request that fails permanently?

It moves to the dead-letter queue, with its last error code and message, and `ITEM_FAILED` then `ITEM_DEAD_LETTERED` are emitted. Nothing retries or prunes it: read it with `listDeadLetter()`, put it back with `replayDeadLetter(id)` once the cause is fixed, or delete it with `discardDeadLetter(id)`.

**Related:** [Retry Policy](./guides/retry-policy.md#permanent-failures), [Error Handling](./guides/error-handling.md#dead-letter-flows)

---

## Still Stuck?

1. Re-read the [Quick Start](./getting-started/quick-start.md) and [Installation](./getting-started/installation.md) guides to make sure nothing was skipped.
2. Browse the [example app](https://github.com/gabriel-sisjr/react-native-sync-provider/tree/develop/example) for a working reference.
3. Search the [GitHub Issues](https://github.com/gabriel-sisjr/react-native-sync-provider/issues). When you open a new one, use the bug report template, and include full stack traces and the output of `npx react-native info`.
