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

## 1. Gradle Versions

Verify your project meets the required versions. The library will fail to compile against older toolchains.

| Setting | Required |
|---|---|
| `minSdkVersion` | 24 (Android 7.0) |
| `compileSdkVersion` | 36 |
| `targetSdkVersion` | 36 |
| Kotlin | 2.0.21 |
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

## 2. Permissions (Auto-Merged)

The library declares the required permissions in its own `AndroidManifest.xml`. Gradle's manifest merger pulls them into the merged app manifest automatically. After a clean build, your app's merged manifest will contain:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

| Permission | Why it is needed |
|---|---|
| `INTERNET` | OkHttp uses it to dispatch every queued request. |
| `ACCESS_NETWORK_STATE` | `ConnectivityManager` reports online/offline state to `useConnection`. |
| `RECEIVE_BOOT_COMPLETED` | The `BootCompletedReceiver` reschedules pending work after a reboot. |

You may copy the declarations into your app's manifest for visibility, but it is not required.

:::note No foreground service
Unlike background-location libraries, this library does **not** use a foreground service. There is no persistent notification, no `FOREGROUND_SERVICE` permission, and no `FOREGROUND_SERVICE_TYPE` declaration. All background work is handled by `WorkManager`'s deferrable, OS-respecting model.
:::

## 3. BootCompletedReceiver

The library's manifest registers:

```xml
<receiver
  android:name="com.margelo.nitro.syncprovider.boot.BootCompletedReceiver"
  android:enabled="true"
  android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.BOOT_COMPLETED" />
  </intent-filter>
</receiver>
```

On boot, the receiver enqueues a one-time `RecoveryWorker` that:

1. Reads any persisted `BackgroundSyncOptions` from MMKV.
2. Re-registers the periodic `WorkManager` job.
3. Triggers a one-shot drain if pending items exist and connectivity is available.

No app-side code is required.

## 4. ProGuard / R8

The library bundles `android/proguard-rules.pro` and applies it via `consumerProguardFiles` in its `build.gradle`. The rules keep:

- `@com.facebook.proguard.annotations.DoNotStrip`-marked classes (the Nitro `HybridSyncProviderSpec` subclass).
- Room-generated DAO and database classes.
- Coroutines metadata required by the `SyncWorker`.

You do not need to add rules to your app. If you have custom ProGuard rules that strip Kotlin metadata, restore them only for your own packages.

## 5. WorkManager Constraints

The library uses `PeriodicWorkRequest` for OS-scheduled background sync. Android imposes a hard floor:

:::warning 15-minute floor
`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is **15 minutes**. Passing a smaller `minimumIntervalMs` to `enableBackgroundSync` is silently clamped by the OS.
:::

For tighter cadences, use `OPPORTUNISTIC` or `AUTOMATIC` strategy plus `useAutoSync({ flushOnReconnect: true })` -- the library will drain the queue the moment connectivity returns, regardless of WorkManager's schedule.

## 6. Doze Mode and Battery Optimizations

On Android 6.0+, devices enter **Doze mode** when stationary and unplugged. WorkManager respects Doze and defers periodic jobs until a maintenance window. Expected behavior:

- Periodic sync runs less often than `minimumIntervalMs` while the device is in deep Doze.
- High-priority work resumes immediately when the device exits Doze (screen on, plugged in).
- OEM battery managers (Xiaomi, Huawei, Oppo, Samsung Adaptive Battery) may further restrict execution.

The library does not request battery-optimization whitelisting -- that is a UX decision for the host app. If you need stricter background guarantees, surface a setting that opens `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

## 7. Verifying the Setup

Run the example or your app on an Android device:

```bash
yarn example android
adb logcat | grep -E "SyncProvider|SyncWorker|BootCompleted"
```

You should see log lines from `SyncProvider` and `SyncWorker` once you call `enqueue` / `flush` / `enableBackgroundSync`.

## 8. Common Android Issues

:::tip Manifest merger conflict on `RECEIVE_BOOT_COMPLETED`
If another library declares the permission with `android:required="false"` or similar, add `tools:replace="android:required"` to your app's manifest.
:::

:::tip Sync never fires after reboot
Check that the user has not Force Stopped your app. Android disables `RECEIVE_BOOT_COMPLETED` for apps in the "Force Stopped" state until the user opens the app at least once.
:::

:::tip Periodic sync runs at random times
This is expected. WorkManager batches periodic work to honor Doze and Adaptive Battery. Use `useSyncEvents` and listen for `BACKGROUND_SYNC_STARTED` to log actual fire times during development.
:::

For the full list of issues, see [Troubleshooting](../troubleshooting.md).

## Next Steps

- [iOS Setup](./ios-setup.md) -- The matching iOS-side configuration.
- [Background Sync guide](../guides/background-sync.md) -- BGTaskScheduler + WorkManager deep dive.
- [Android Permissions](../production/android-permissions.md) -- Production-ready permission documentation.
