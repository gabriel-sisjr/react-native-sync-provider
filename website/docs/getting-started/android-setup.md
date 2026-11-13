---
sidebar_position: 5
title: Android Setup
description: Android-specific setup for react-native-sync-provider — Gradle versions, manifest permissions, BootCompletedReceiver, ProGuard, and Doze mode considerations.
keywords:
  - android
  - setup
  - gradle
  - workmanager
  - permissions
  - manifest
  - boot-completed-receiver
  - doze-mode
  - proguard
---

# Android Setup

This guide walks through every Android-specific step required for `@gabriel-sisjr/react-native-sync-provider` to persist queued items to Room, drain them via OkHttp + Coroutines, and reschedule work via `WorkManager` -- including after device reboot.

## Prerequisites

| Requirement | Minimum Version | Notes |
|---|---|---|
| React Native | 0.74 | New Architecture required |
| `react-native-nitro-modules` | ^0.35.8 | Peer dependency, below 0.36. See [Installation](./installation.md#peer-dependencies) |
| Android `minSdkVersion` | 24 | Android 7.0. See Step 1 for the full toolchain |
| JDK (host) | 17 | The library compiles with Java 17 source and target |

## Step 1: Gradle Versions {#1-gradle-versions}

Verify your project meets the required versions. The library will fail to compile against older toolchains.

| Setting | Required |
|---|---|
| `minSdkVersion` | 24 (Android 7.0) |
| `compileSdkVersion` | 36 |
| `targetSdkVersion` | 36 |
| Kotlin | 2.0.21 to 2.2.21 |
| Android Gradle Plugin | 8.7.2 |
| JDK (host) | 17 |

In `android/build.gradle`:

```gradle
buildscript {
  ext {
    minSdkVersion = 24
    compileSdkVersion = 36
    targetSdkVersion = 36
    kotlinVersion = "2.0.21"
  }
  dependencies {
    classpath("com.android.tools.build:gradle:8.7.2")
    classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
  }
}
```

The library reads `minSdkVersion`, `compileSdkVersion`, `targetSdkVersion` and `kotlinVersion` from this `ext` block, and uses the values above for any that is not set. It picks the KSP version that matches your `kotlinVersion` from a table of the Kotlin releases from 2.0.21 to 2.2.21. Any other version gets the KSP version for 2.0.21.

## Step 2: Permissions (Auto-Merged) {#2-permissions-auto-merged}

The library declares the required permissions in its own `AndroidManifest.xml`. Gradle's manifest merger pulls them into the merged app manifest automatically. After a clean build, your app's merged manifest will contain at least:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

| Permission | Why it is needed |
|---|---|
| `INTERNET` | OkHttp uses it to dispatch every queued request. |
| `ACCESS_NETWORK_STATE` | `ConnectivityManager` reports online/offline state to `useConnection`. |
| `RECEIVE_BOOT_COMPLETED` | The `BootCompletedReceiver` schedules recovery after a reboot. |

You may copy the declarations into your app's manifest for visibility, but it is not required.

> **Note — No foreground service:** Unlike background-location libraries, this library does **not** use a foreground service: it declares none and never starts one. There is no persistent notification and no `FOREGROUND_SERVICE_TYPE` declaration. All background work is handled by `WorkManager`'s deferrable, OS-respecting model. WorkManager's own manifest still merges the `FOREGROUND_SERVICE` and `WAKE_LOCK` permissions and its `SystemForegroundService` into your app.

## Step 3: BootCompletedReceiver {#3-bootcompletedreceiver}

The library's manifest registers:

```xml
<receiver
  android:name="com.margelo.nitro.syncprovider.work.BootCompletedReceiver"
  android:enabled="true"
  android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.BOOT_COMPLETED" />
    <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
    <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
  </intent-filter>
</receiver>
```

`LOCKED_BOOT_COMPLETED` is in the filter, but the receiver is not direct-boot aware, so a locked device does not deliver it. On `BOOT_COMPLETED`, after the first unlock, and on `MY_PACKAGE_REPLACED`, the receiver enqueues a one-time `RecoveryWorker` that:

1. Resets items left in flight back to `PENDING`.
2. Re-registers the periodic `WorkManager` job from the persisted `BackgroundSyncOptions`, unless `foregroundOnly` is set.
3. Arms the next retry wake, which with no JS running means the opt-in [retry kick](../guides/retry-policy.md#who-wakes-the-queue-and-when).

It sends no request of its own. No app-side code is required.

## Step 4: ProGuard / R8 {#4-proguard--r8}

The library bundles `android/proguard-rules.pro` and applies it via `consumerProguardFiles` in its `build.gradle`. The rules keep:

- `@com.facebook.proguard.annotations.DoNotStrip`-marked classes (the Nitro `HybridSyncProviderSpec` subclass).
- Room-generated DAO and database classes.
- `WorkManager` worker classes, which `WorkManager` instantiates by reflection.
- Coroutines metadata required by the `SyncWorker`.

You do not need to add rules to your app. If you have custom ProGuard rules that strip Kotlin metadata, restore them only for your own packages.

## Step 5: WorkManager Constraints {#5-workmanager-constraints}

The library uses `PeriodicWorkRequest` for OS-scheduled background sync. Android imposes a hard floor:

> **Warning — 15-minute floor:** `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is **15 minutes**. A smaller `minimumIntervalMs` passed to `enableBackgroundSync` is silently raised to it.

For tighter cadences while the app runs, mount `useAutoSync({ enabled: true, flushOnReconnect: true })` -- it flushes from JS on an interval and when connectivity returns, regardless of WorkManager's schedule. Failed items also come back on their own, through the [retry timer and kick](../guides/retry-policy.md#who-wakes-the-queue-and-when).

## Step 6: Doze Mode and Battery Optimizations {#6-doze-mode-and-battery-optimizations}

On Android 6.0+, devices enter **Doze mode** when stationary and unplugged. WorkManager respects Doze and defers periodic jobs until a maintenance window. Expected behavior:

- Periodic sync runs less often than `minimumIntervalMs` while the device is in deep Doze.
- High-priority work resumes immediately when the device exits Doze (screen on, plugged in).
- OEM battery managers (Xiaomi, Huawei, Oppo, Samsung Adaptive Battery) may further restrict execution.

The library does not request battery-optimization whitelisting -- that is a UX decision for the host app. If you need stricter background guarantees, surface a setting that opens `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

For the network and wake-up work the library itself adds, and the option that controls each, see [Battery Optimization](../guides/battery-optimization.md).

## Troubleshooting

Common Android setup issues. For the full list, see [Troubleshooting](../troubleshooting.md).

### Manifest Merger Conflict on `RECEIVE_BOOT_COMPLETED`

The library declares `RECEIVE_BOOT_COMPLETED` with `android:name` only, so it cannot cause an attribute conflict itself. If the merger reports one between other manifests, usually on `android:maxSdkVersion`, fix it in your app's `<uses-permission>` entry: `tools:replace="android:maxSdkVersion"` together with the value to keep, or `tools:remove="android:maxSdkVersion"` to drop the limit. Do not use `tools:node="remove"`: it removes the permission, and boot recovery with it. `<uses-permission>` has no `android:required` attribute: that one belongs to `<uses-feature>` and `<uses-library>`.

### Sync Never Fires After Reboot

Check that the user has not Force Stopped your app. Android disables `RECEIVE_BOOT_COMPLETED` for apps in the "Force Stopped" state until the user opens the app at least once.

### Periodic Sync Runs at Random Times

This is expected. WorkManager batches periodic work to honor Doze and Adaptive Battery. Use `useSyncEvents` and listen for `BACKGROUND_SYNC_STARTED` to log actual fire times during development. The event reaches you only if JS is running.

### Items Wait After a Backup Restore

`defaultHeaders` are encrypted under a Keystore key that never leaves the device. With `allowBackup`, a restore onto another device brings the ciphertext back but not the key, so items wait until `configureSync` runs. See [Troubleshooting](../troubleshooting.md#symptom-items-stay-pending-and-flush-returns-00).

## Verification Checklist

After setup, run your app on an Android device and verify the following:

- [ ] Your project meets the Gradle versions in Step 1, and the app builds
- [ ] The merged manifest contains `INTERNET`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED`, and registers `BootCompletedReceiver`
- [ ] `isNativeModuleAvailable()` returns `true`
- [ ] `enqueue` resolves with an id, and a `flush()` delivers the item to your endpoint
- [ ] `isBackgroundSyncEnabled()` resolves `true` after `enableBackgroundSync`
- [ ] After a reboot and the first unlock, `adb logcat -s BootCompletedReceiver` shows `Received android.intent.action.BOOT_COMPLETED — scheduling recovery worker`

On Android, the library logs only problems and recovery work, so a successful `enqueue` or `flush` writes no log line. Confirm delivery on your endpoint, or listen with [`useSyncEvents`](../api-reference/hooks/useSyncEvents.md). The [Debugging](../development/debugging.md) page lists the library's log tags.

## Next Steps

- [iOS Setup](./ios-setup.md) -- The matching iOS-side configuration
- [Background Sync](../guides/background-sync.md) -- BGTaskScheduler + WorkManager deep dive
- [Android Permissions](../production/android-permissions.md) -- Production-ready permission documentation
