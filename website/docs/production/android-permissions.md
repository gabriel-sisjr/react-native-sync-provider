---
sidebar_position: 2
title: Android Permissions
description: Permissions automatically merged by the library, what they grant, and notes on Doze mode + WorkManager scheduling guarantees on Android.
keywords:
  - android
  - permissions
  - workmanager
  - doze mode
  - boot completed
  - manifest
---

# Android Permissions

The library declares its required permissions in its own `AndroidManifest.xml`. The Android Gradle Plugin merges them into your app's manifest at build time — you do not need to add anything by hand. This page documents what gets merged and why, so you can audit the diff in your release manifest.

## Merged permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

| Permission                            | Why the library needs it                                                                                                       |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `INTERNET`                            | OkHttp dispatch of every queued request.                                                                                       |
| `ACCESS_NETWORK_STATE`                | `ConnectivityMonitor` reads `NetworkCapabilities` to drive the metered/unmetered discriminator and emit `CONNECTION_CHANGED`.  |
| `RECEIVE_BOOT_COMPLETED`              | `BootCompletedReceiver` re-arms the periodic `SyncWorker` and runs `RecoveryWorker` after a device reboot.                     |

None of these are runtime ("dangerous") permissions on Android 6+ — they are install-time and require no permission prompt.

## Merged receiver

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

`MY_PACKAGE_REPLACED` is included so an OTA update of your app also re-runs recovery — without it, items left in-flight at update time would stay in-flight forever.

## What the library does NOT request

- **No foreground service**: WorkManager handles the periodic dispatch. The library deliberately avoids the user-facing notification overhead of a foreground service. If your use case demands "always-on while in foreground" instead of "drain when constraints met", combine the lib with your own foreground service that calls `flush()` on a timer.
- **No `FOREGROUND_SERVICE_*` permissions**: follows from the above.
- **No `POST_NOTIFICATIONS`**: the library never posts notifications.
- **No `WAKE_LOCK`**: WorkManager owns its wakelocks internally.

## WorkManager scheduling realities

The Android scheduler is the source of truth for when a flush actually runs. Important constraints to plan around:

- **15-minute floor**: `PeriodicWorkRequest` cannot be scheduled at less than a 15-minute interval. The library rejects (clamps) `BackgroundSyncOptions.minimumIntervalMs < 15 * 60 * 1000`.
- **Doze mode** (Android 6+): if the device is idle, periodic work is deferred to the next maintenance window (which can be hours away). Test by running `adb shell dumpsys deviceidle force-idle` against your device.
- **App standby buckets** (Android 9+): apps the user rarely opens get put into restrictive buckets. Periodic work cadence drops accordingly.
- **Manufacturer auto-start whitelisting**: Xiaomi MIUI, Huawei EMUI, OPPO ColorOS, and a handful of other ROMs aggressively kill WorkManager unless the user manually allows your app to "auto-start". Document this in your support docs if those markets are in scope.

:::tip
For latency-sensitive payloads, schedule a `OneTimeWorkRequest` opportunistically (for example, on `CONNECTION_CHANGED` to `CONNECTED`) instead of relying on the periodic cadence. The library's `useAutoSync` + `enableBackgroundSync` combo does this automatically.
:::

## Verifying the merged manifest

After building a release APK / AAB:

```bash
./gradlew :app:bundleRelease
unzip -p app/build/outputs/bundle/release/app-release.aab base/manifest/AndroidManifest.xml | head -100
```

You should see all three permissions and the receiver in the output. If any are missing, your app likely declares `tools:node="remove"` on a parent permission node — remove it.
