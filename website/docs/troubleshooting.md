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

Each entry below follows the same Symptom / Cause / Fix shape. If your issue is not listed here and looks reproducible, please open a [GitHub issue](https://github.com/gabriel-sisjr/react-native-sync-provider/issues) using the bug report template.

## Android

### Symptom: KSP build fails on Room with `java.lang.IllegalStateException: unexpected jvm signature V`

**Cause:** Incompatibility between Room 2.6.1's KSP processor and Kotlin 2.0.x metadata. Any `suspend fun` in a DAO that returns `Unit` (JVM signature `void`) trips it.

**Fix:** The library pins Room 2.7.0, which fixes this upstream. If you fork the lib, ensure the bump landed in `android/build.gradle`:

```gradle
implementation 'androidx.room:room-runtime:2.7.0'
implementation 'androidx.room:room-ktx:2.7.0'
ksp 'androidx.room:room-compiler:2.7.0'
```

### Symptom: `ConnectivityMonitor` misses "connection restored" events after toggling airplane mode

**Cause:** `NetworkCallback` was registered against an `Activity` instead of `ApplicationContext`. The `Activity` is destroyed during config changes, taking the callback registration with it.

**Fix:** The library registers against `ApplicationContext` internally — verify your fork did not change this in `connectivity/ConnectivityMonitor.kt`. If you wrap the lib in your own foreground service, register your additional callbacks against `getApplicationContext()` too.

### Symptom: Periodic flush never fires at intervals shorter than 15 minutes

**Cause:** `WorkManager.PeriodicWorkRequest` enforces a hard 15-minute floor. The library clamps `BackgroundSyncOptions.minimumIntervalMs` to that value.

**Fix:** This is by design. For sub-15-minute cadence while the app is running, mount `useAutoSync`, which flushes from JS on an interval and on `CONNECTION_CHANGED`. Failed items also come back on their own through the [retry timer and kick](./guides/retry-policy.md#who-wakes-the-queue-and-when).

### Symptom: WorkManager flushes deferred for hours during normal phone use

**Cause:** Doze mode, App Standby Buckets, or manufacturer-specific (Xiaomi MIUI, Huawei EMUI) "kill background tasks" behavior.

**Fix:** Document this for users in your app. Optional mitigations:

- Set `BackgroundSyncOptions.requiresCharging = false` (default) so windows can run on battery.
- For time-sensitive workloads, expose a "sync now" button that calls `flush()` directly. It runs in your app's process, independent of WorkManager.
- Recommend users add your app to their device's "auto-start whitelist" on aggressive ROMs.

### Symptom: `[CXX5304] SDK XML version 4` warning during Android build

**Cause:** CMake step from a transitive dep (often `netinfo`) targets an older Android SDK XML format than your installed NDK.

**Fix:** Harmless. Pre-existing in the React Native ecosystem. Ignore.

## iOS

### Symptom: `pod install` fails with `Multiple commands produce '...PrivacyInfo.xcprivacy'`

**Cause:** RN 0.85+ aggregates every pod's privacy manifest into the host app's manifest at build time. If a pod lists its `PrivacyInfo.xcprivacy` under `s.resources` instead of `s.resource_bundles`, both the listing and the aggregator's output try to land at the same path.

**Fix:** The library already uses `s.resource_bundles` exactly to avoid this. If you fork or vendor the podspec, ensure the privacy manifest stays under `s.resource_bundles = { "SyncProvider_Privacy" => ["ios/PrivacyInfo.xcprivacy"] }`.

### Symptom: Build fails with `Invalid redeclaration of 'SyncItemEntity' / 'SyncResultEntity'`

**Cause:** Xcode is auto-generating Core Data classes for entities in `SyncProvider.xcdatamodeld` AND the explicit `+CoreDataClass.swift` files are also being compiled — both produce the same symbol.

**Fix:** Confirm neither entity has a `codeGenerationType` attribute, in both model versions under `SyncProvider.xcdatamodeld`. With no attribute, Xcode shows **Manual/None** and generates nothing, while `class` or `category` generates the clashing classes. Delete the attribute, or set **Codegen** to Manual/None in the Data Model inspector. Do not write `manual/none` as the value: `momc` fails with `unexpected code generation type 'manual/none'`. The explicit Swift files are the only source of truth.

### Symptom: App crashes at launch after upgrading to v0.2

**Cause:** Most likely your `AppDelegate` still registers a library identifier with `BGTaskScheduler.shared.register`. The library now registers its handlers at process start, and iOS traps on a second registration for the same identifier. This is the expected outcome rather than one reproduced on a device.

**Fix:** Delete that block. An earlier version of the [iOS Setup](./getting-started/ios-setup.md) page showed it, along with a `SyncProviderBackgroundTaskRunner` class that never existed.

### Symptom: `BGTaskScheduler` never fires

**Cause:** One of:

- An identifier is missing from `BGTaskSchedulerPermittedIdentifiers`. Both fixed identifiers are needed, and `taskIdentifier` has no effect.
- The `Background fetch` and/or `Background processing` capabilities are not enabled on the app target.
- Running on the iOS Simulator, where `BGTaskScheduler` does not accept requests. `enableBackgroundSync` rejects there.
- The user force-quit the app. iOS does not launch it for background tasks until the user opens it again.
- The OS has not granted a window. Nothing guarantees when one runs.

**Fix:** See the [iOS Background Modes](./production/ios-background-modes.md) page for the full setup. Simulate the window via the Xcode debugger, on a physical device, to confirm registration once setup is complete.

### Symptom: Background `URLSession` upload "lost" after force-quit

**Cause:** Force-quitting the app from the App Switcher cancels every in-flight `URLSession` task. By design — Apple distinguishes user-initiated termination from OS-initiated suspension.

**Fix:** `RecoveryManager` runs at next launch and atomically transitions any items left `IN_FLIGHT` back to `PENDING`. It sends nothing itself: the next flush picks them up, so make sure something starts one. The attempt is already spent, and the server may have received the request, so endpoints must be [idempotent](./guides/idempotency.md).

### Symptom: Local Mac shows `iOS 26.4 not installed` for `Any iOS Device`

**Cause:** Host Xcode tooling glitch. The simulator runtime is technically present but the Xcode UI flags the placeholder destination as missing, disabling every simulator target.

**Fix:** This is the maintainer's Mac being broken, not a code issue. Run on CI (`macos-latest`, `XCODE_VERSION=26`) for ground truth. Locally: pick a specific simulator instead of `Any iOS Device`, or wipe `~/Library/Developer/CoreSimulator/` and reinstall the runtime via `xcrun simctl runtime install`.

## Cross-platform / JS

### Symptom: `NATIVE_MODULE_UNAVAILABLE` error on web or in SSR

**Cause:** The Nitro HybridObject can't be created without the native runtime — web bundles, Jest without the lib's mock, or SSR.

**Fix:** Gate calls with `isNativeModuleAvailable()`:

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

### Symptom: Type errors in unrelated files after upgrading the library

**Cause:** The Nitro spec was regenerated, the `lib/typescript/` declarations refreshed, and your app's `tsconfig` is caching the old shapes. Or you forgot to run `yarn nitrogen` after pulling.

**Fix:**

```bash
yarn nitrogen
yarn typecheck
# Also wipe the IDE cache: VSCode → "TypeScript: Restart TS Server"
```

If errors persist, delete `node_modules/@gabriel-sisjr/react-native-sync-provider/lib` and reinstall — the published `lib/typescript/` is the source of truth your editor reads.

### Symptom: Items are not sent after `enqueue`

**Cause:** Enqueueing stores the item. It does not dispatch it, under any strategy. `AUTOMATIC` retries failed items on its own, but it does not flush on enqueue or on reconnect.

**Fix:** Mount [`useAutoSync`](./api-reference/hooks/useAutoSync.md) with `enabled: true`, or call `flush()`. In a background window, a flush happens when the OS grants one.

### Symptom: Items stay pending and `flush()` returns 0/0

**Cause:** Nothing was eligible, or no cycle ran. Check in this order:

- **Cooling.** Every item is waiting out a retry cooldown. See the next symptom.
- **Paused.** `pauseSync()` is in force. `flush()` dispatches nothing until `resumeSync()`.
- **Stored headers cannot be read.** `defaultHeaders` were configured, but they cannot be recovered in this process, so nothing is dispatched: sending without them would fail every item. This happens when the Keystore key was invalidated, when the iOS device has not been unlocked since boot, or after a backup restore onto another device. With Android `allowBackup`, the ciphertext comes back but the key does not.

**Fix:** For unreadable headers, call `configureSync` with the headers again, which ends the wait. The library also retries the read on each flush, so an iOS device resumes after the first unlock.

:::warning Do not re-apply `getSyncConfig()` in this state
While headers are unreadable, `getSyncConfig()` returns none, so `setConfig({ ...config })` or `configureSync(await getSyncConfig())` deletes them, which acts as a logout. Pass the headers explicitly.
:::

### Symptom: Items stay pending and the server logs one 401 per wake

**Cause:** A stored token expired. In a process that has not called `configureSync`, a 401 or 403 holds the item instead of failing it: it stays `PENDING`, no attempt is spent, the next try waits at least 60 seconds, and the cycle stops. You see `ITEM_RETRYING` with `UNAUTHORIZED`, if anything is listening. See [When a stored token expires](./guides/background-sync.md#when-a-stored-token-expires).

**Fix:** Call `configureSync` with a fresh token at launch and whenever it changes. The library does not refresh tokens, and a process with no JS cannot renew one. Each held item then dispatches once its hold ends.

### Symptom: `flush()` does nothing after `pauseSync()`, or the queue runs again after a restart

**Cause:** A pause lasts until `resumeSync()` or the next launch that reaches the library, including a JS reload. Workers and BGTasks stay inert while the app is dead. A launch clears the pause without emitting `RESUMED`, and on iOS, React Native can start during a background launch, which counts if JS reaches the library there.

**Fix:** Call `resumeSync()` to end a pause. To keep one across launches, call `pauseSync()` again at startup. Hooks cannot read the persisted flag: `isPaused` starts `false` and follows events.

### Symptom: `drainUntilEmpty()` stops early

**Cause:** Read `stoppedReason`:

| `stoppedReason`  | What happened                                                                                                                                  |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| `TIMEOUT`        | `timeoutMs` passed, or the soonest cooldown ends at or after that deadline, so waiting was pointless. Also how an offline drain ends, with items intact. |
| `MAX_ITERATIONS` | `maxIterations` flushes started and items remain. It counts real flushes only.                                                                  |
| `CANCELLED`      | `cancelDrain()` was called.                                                                                                                    |

**Fix:** For a long backoff, raise `timeoutMs`. For a large queue, raise `maxIterations` or `batchSize`. A native cycle already running is not aborted, so `queueRemaining` can be stale right after a stop: re-read `getQueueSize()`.

### Symptom: `DRAIN_ALREADY_ACTIVE` right after `cancelDrain()`

**Cause:** A cancelled drain still has to settle, so a drain started in the same tick is rejected.

**Fix:** Await the previous drain before starting another. See the example under [`cancelDrain`](./api-reference/functions.md#canceldrain).

### Symptom: `enqueue` rejects with `INVALID_PAYLOAD` and no `validationErrors`

**Cause:** Native rejected the call, most often because the queue reached `maxQueueSize`. The native `QUEUE_FULL` code [does not reach JS yet](./api-reference/errors.md#native-rejections).

On Android this can happen while `getQueueSize()` reads below the cap, because the cap also counts `FAILED` and in-flight rows, which that function does not show.

**Fix:** Flush, or raise `maxQueueSize`. On Android, remove failed items as described in [Permanent Failures](./guides/retry-policy.md#permanent-failures).

### Symptom: Items wait after the device reconnects, with `useAutoSync` mounted

**Cause:** `enabled` is gated on the connection, for example on `useConnection().isOnline`. While `enabled` is `false` the hook is not subscribed, so its reconnect trigger cannot fire, and turning `enabled` back on does not flush. Items wait for the next interval tick. Before v0.2 this gate kept the interval from flushing offline.

**Fix:** Leave `enabled` on. The hook skips its interval and foreground flushes while the status is `DISCONNECTED`. See [Skip flushes while offline](./api-reference/hooks/useAutoSync.md#skip-the-interval-while-offline).

### Symptom: Items reach `FAILED` while the device is offline

**Cause:** A flush ran during the outage. Neither native dispatcher checks connectivity, so each failed request spends a retry attempt. `useAutoSync` skips only while the status is `DISCONNECTED`, so `UNKNOWN` still flushes, and a direct `flush()` has no check at all.

**Fix:** Read `getConnectionStatus()` before a direct `flush()`, or use [`drainUntilEmpty`](./api-reference/functions.md#drainuntilempty), which does not flush while `DISCONNECTED`.

### Symptom: `progress` stays at `0` on Android

**Cause:** Android does not emit `SYNC_PROGRESS`. Only iOS does.

**Fix:** Show an indeterminate indicator while `isSyncing` is `true`.

### Symptom: `flush()` resolves but I'm seeing failures in production

**Cause:** `flush()` only rejects when the native module is missing or the cycle itself dies. Per-item failures resolve normally with `failureCount > 0`.

**Fix:** Subscribe to `ITEM_FAILED`, which works on both platforms:

```ts
useSyncEvents({
  types: ['ITEM_FAILED'],
  onEvent: (e) => reportToCrashlytics({ id: e.itemId, code: e.errorCode }),
});
```

`result.failedIds` and `result.errors` hold the same detail on Android, and are always empty on iOS.

### Symptom: A failed item doesn't retry immediately after `flush()`

**Cause:** As of v0.2, a failed-retriable item gets a persisted `nextRetryAt` cooldown, measured from the end of the attempt, and is **skipped by the dispatcher until that deadline passes** — on both platforms, and across restarts/reboots. This is intentional: it prevents a hot retry loop against a degraded server. Before v0.2, iOS retried with no spacing and Android only spaced retries within a single flush cycle.

**Fix:** This is by design. In a running app with a strategy other than `MANUAL`, the retry timer brings the item back on its own. Inspect the per-item deadline via `getPendingItems()` — `item.nextRetryAt` is `0` for an item never attempted, and otherwise the epoch-ms time it becomes eligible. A cooling item still counts toward `getQueueSize()`. To wait out cooldowns explicitly, use `drainUntilEmpty()`, which sleeps until the soonest `nextRetryAt` by itself. See the [retry-cooldown guide](./guides/retry-policy.md#retry-cooldown-across-restarts).

```ts
const pending = await getPendingItems();
pending.forEach((item) => {
  const waitMs = Math.max(0, item.nextRetryAt - Date.now());
  console.log(
    `${item.id}: ${item.attempts} attempt(s), eligible in ${waitMs}ms`
  );
});
```

## Build & tooling

### Symptom: `yarn install --immutable` fails right after editing `package.json`

**Cause:** Adding/removing a dep (or a workspace) mutates the lockfile, which `--immutable` forbids.

**Fix:** Run plain `yarn install` first locally to refresh `yarn.lock`, commit the lockfile change, and then `--immutable` works in CI.

### Symptom: `yarn docs:build` fails with `Broken anchor` or `Broken link`

**Cause:** The Docusaurus config sets `onBrokenLinks: 'throw'`. A relative link or anchor in a markdown file does not resolve.

**Fix:** Fix the heading slug or the path. Docusaurus generates anchor slugs by lowercasing and hyphenating the heading text — `## Background Sync` → `#background-sync`. Use relative paths within `website/docs/` (`./other-page` or `../section/page`); avoid linking to root markdown files from inside `docs/` (use the GitHub URL instead).

### Symptom: `yarn nitrogen` reports zero HybridObjects generated

**Cause:** `nitro.json` is missing or malformed, or the spec file is not picked up.

**Fix:** Confirm `nitro.json` lives at the repo root and lists `SyncProvider` under `autolinking`. The spec must end in `*.nitro.ts` and live under `src/`. Re-run `yarn nitrogen` and check the log: a successful run prints `Generated 1/1 HybridObject specs`.
