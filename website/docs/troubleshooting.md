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

**Fix:** This is by design. For latency-sensitive payloads, schedule a `OneTimeWorkRequest` opportunistically — `useAutoSync` does this automatically on `CONNECTION_CHANGED`. If you need sub-15-minute cadence while the app is in foreground, call `flush()` from a JS timer.

### Symptom: WorkManager flushes deferred for hours during normal phone use

**Cause:** Doze mode, App Standby Buckets, or manufacturer-specific (Xiaomi MIUI, Huawei EMUI) "kill background tasks" behavior.

**Fix:** Document this for users in your app. Optional mitigations:
- Set `BackgroundSyncOptions.requiresCharging = false` (default) so windows can run on battery.
- For Android 12+ time-sensitive workloads, expose a "high priority sync" button that calls `flush()` directly — this runs as expedited foreground work.
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

**Fix:** Confirm `codeGenerationType="manual/none"` is set on both entities in `SyncProvider.xcdatamodeld/SyncProvider.xcdatamodel/contents`. The explicit Swift files are the only source of truth.

### Symptom: `BGTaskScheduler` never fires

**Cause:** One of:
- `BGTaskSchedulerPermittedIdentifiers` missing from `Info.plist`, or doesn't match the identifier the lib was configured with.
- The `Background fetch` and/or `Background processing` capabilities are not enabled on the app target.
- Running on the iOS Simulator (BGTaskScheduler does not run there).

**Fix:** See the [iOS Background Modes](./production/ios-background-modes.md) page for the full setup. Simulate the window via the Xcode debugger to confirm registration once setup is complete.

### Symptom: Background `URLSession` upload "lost" after force-quit

**Cause:** Force-quitting the app from the App Switcher cancels every in-flight `URLSession` task. By design — Apple distinguishes user-initiated termination from OS-initiated suspension.

**Fix:** `RecoveryManager` runs at next launch and atomically transitions any items left in `in-flight` back to `pending`. The next flush picks them up. Confirm by observing `SYNC_STARTED` immediately after launch when the queue had pending items at the time of force-quit.

### Symptom: Local Mac shows `iOS 26.4 not installed` for `Any iOS Device`

**Cause:** Host Xcode tooling glitch. The simulator runtime is technically present but the Xcode UI flags the placeholder destination as missing, disabling every simulator target.

**Fix:** This is the maintainer's Mac being broken, not a code issue. Run on CI (`macos-latest`, `XCODE_VERSION=26`) for ground truth. Locally: pick a specific simulator instead of `Any iOS Device`, or wipe `~/Library/Developer/CoreSimulator/` and reinstall the runtime via `xcrun simctl runtime install`.

## Cross-platform / JS

### Symptom: `NATIVE_MODULE_UNAVAILABLE` error on web or in SSR

**Cause:** The Nitro HybridObject can't be created without the native runtime — web bundles, Jest without the lib's mock, or SSR.

**Fix:** Gate calls with `isNativeModuleAvailable()`:

```ts
import { isNativeModuleAvailable, enqueue } from '@gabriel-sisjr/react-native-sync-provider';

if (isNativeModuleAvailable()) {
  await enqueue({ method: 'POST', url: '...' });
} else {
  // fall back to direct fetch, queue in your own JS store, etc.
}
```

The 8 hooks already short-circuit safely when the module is missing — they return inert state instead of throwing.

### Symptom: Type errors in unrelated files after upgrading the library

**Cause:** The Nitro spec was regenerated, the `lib/typescript/` declarations refreshed, and your app's `tsconfig` is caching the old shapes. Or you forgot to run `yarn nitrogen` after pulling.

**Fix:**

```bash
yarn nitrogen
yarn typecheck
# Also wipe the IDE cache: VSCode → "TypeScript: Restart TS Server"
```

If errors persist, delete `node_modules/@gabriel-sisjr/react-native-sync-provider/lib` and reinstall — the published `lib/typescript/` is the source of truth your editor reads.

### Symptom: `flush()` resolves but I'm seeing failures in production

**Cause:** `flush()` only rejects on cycle-level errors (no native module, etc.). Per-item failures resolve normally with `failureCount > 0` and detailed errors in the `SyncResult`.

**Fix:** Read `result.errors` (keyed by item ULID) and `result.failedIds`:

```ts
const result = await flush();
result.failedIds.forEach((id) => {
  const code = result.errors[id]; // SyncErrorCode value as string
  reportToCrashlytics({ id, code });
});
```

For push-style observation, subscribe to `ITEM_FAILED` via `useSyncEvents({ types: ['ITEM_FAILED'] })`.

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
