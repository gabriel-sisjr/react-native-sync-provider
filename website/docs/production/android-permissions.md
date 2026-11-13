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

## Overview of Requirements

| Requirement | Provided by | Your action |
| --- | --- | --- |
| `INTERNET` permission | The library's manifest, merged at build time | Nothing. Keep it in the merged manifest. |
| `ACCESS_NETWORK_STATE` permission | The library's manifest, merged at build time | Nothing. Keep it in the merged manifest. |
| `RECEIVE_BOOT_COMPLETED` permission | The library's manifest, merged at build time | Nothing. Keep it in the merged manifest. |
| `BootCompletedReceiver` | The library's manifest, merged at build time | Nothing. Keep it in the merged manifest. |
| Runtime permission prompt | Not needed: all three permissions are install-time | Nothing. |
| Google Play Data safety form | Your app | Declare the data your queued requests send. |

## Merged Permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

| Permission                            | Why the library needs it                                                                                                       |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `INTERNET`                            | OkHttp dispatch of every queued request.                                                                                       |
| `ACCESS_NETWORK_STATE`                | `ConnectivityMonitor` reads `NetworkCapabilities` to drive the metered/unmetered discriminator and emit `CONNECTION_CHANGED`.  |
| `RECEIVE_BOOT_COMPLETED`              | `BootCompletedReceiver` schedules `RecoveryWorker` after a device reboot, which resets in-flight items and re-arms the periodic `SyncWorker` when background sync is enabled and `foregroundOnly` is off. |

None of these are runtime ("dangerous") permissions on Android 6+ — they are install-time and require no permission prompt.

## Merged Receiver

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

`MY_PACKAGE_REPLACED` is included so an OTA update of your app also re-runs recovery. The receiver runs recovery right after an app update, and after a reboot once the device is first unlocked, before any JS runs. `LOCKED_BOOT_COMPLETED` is in the filter, but the receiver is not direct-boot aware, so a locked device does not deliver it. Recovery also runs each time a JS engine attaches, and `SyncWorker` resets in-flight rows itself when no JS engine is attached, unless a persisted pause or `foregroundOnly` blocks it. Without the receiver, items left in flight at update time would wait for one of those.

## What the Library Does NOT Request

- **No foreground service**: WorkManager handles the periodic dispatch. The library deliberately avoids the user-facing notification overhead of a foreground service. If your use case demands "always-on while in foreground" instead of "drain when constraints met", combine the lib with your own foreground service that calls `flush()` on a timer.
- **No `FOREGROUND_SERVICE_*` permissions**: follows from the above. WorkManager's own manifest still merges the base `FOREGROUND_SERVICE` permission and its `SystemForegroundService`, which the library never starts.
- **No `POST_NOTIFICATIONS`**: the library never posts notifications.
- **No `WAKE_LOCK` of its own**: WorkManager owns its wakelocks, and its manifest merges the `WAKE_LOCK` permission into your app.

## WorkManager Scheduling Realities

The Android scheduler is the source of truth for when a flush actually runs. Important constraints to plan around:

- **15-minute floor**: `PeriodicWorkRequest` cannot be scheduled at less than a 15-minute interval. The library clamps `BackgroundSyncOptions.minimumIntervalMs < 15 * 60 * 1000` up to it.
- **Doze mode** (Android 6+): if the device is idle, periodic work is deferred to the next maintenance window (which can be hours away). Test by running `adb shell dumpsys deviceidle force-idle` against your device.
- **App standby buckets** (Android 9+): apps the user rarely opens get put into restrictive buckets. Periodic work cadence drops accordingly.
- **Manufacturer auto-start whitelisting**: Xiaomi MIUI, Huawei EMUI, OPPO ColorOS, and a handful of other ROMs aggressively kill WorkManager unless the user manually allows your app to "auto-start". Document this in your support docs if those markets are in scope.

> **Tip:** For latency-sensitive payloads while the app runs, mount `useAutoSync`, which flushes from JS on an interval and on reconnect, instead of relying on the periodic cadence. Failed items also come back through the [retry timer and the opt-in retry kick](../guides/retry-policy.md#who-wakes-the-queue-and-when).

## Google Play Data Safety

The library collects no data of its own. It sends the requests your app enqueues, to the URLs your app chooses, and, when you set `healthCheckUrl`, `HEAD` requests to that URL that carry no headers. Answer the Data safety form from what your requests send.

On the device, a queued request, its headers and body included, stays in the library's Room database until it is delivered, removed or cleared. A dead letter keeps them until it is replayed, discarded or cleared. `defaultHeaders` are encrypted with a non-exportable Android Keystore key, while per-item `headers` are stored in plain text, so keep credentials in `defaultHeaders`.

## Verifying the Merged Manifest

After building a release APK / AAB:

```bash
./gradlew :app:bundleRelease
unzip -p app/build/outputs/bundle/release/app-release.aab base/manifest/AndroidManifest.xml | head -100
```

You should see all three permissions and the receiver in the output. If any are missing, your app likely declares `tools:node="remove"` on a parent permission node — remove it.

## Checklist Before Submission

- [ ] The release manifest has `INTERNET`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED`.
- [ ] The release manifest has `BootCompletedReceiver` with its three actions.
- [ ] No `tools:node="remove"` in your manifest drops a library permission.
- [ ] R8 / ProGuard is enabled in the release build, and the queue still drains.
- [ ] Credentials travel in `defaultHeaders`, not in per-item `headers`.
- [ ] The Google Play Data safety form covers the data your queued requests send.

## Testing Before Submission

1. **Reboot test**: reboot during a flush, so items are left `IN_FLIGHT`. After the first unlock, `BootCompletedReceiver` schedules `RecoveryWorker`, which resets them and re-arms the periodic work when background sync is enabled and `foregroundOnly` is off. Pending items are not touched.
2. **Update test**: install an update over a build with items in flight. `MY_PACKAGE_REPLACED` re-runs recovery.
3. **Doze test**: run `adb shell dumpsys deviceidle force-idle`. Periodic work is deferred, and runs in the next maintenance window.
4. **Release-build test**: build with R8 enabled, and confirm that the queue drains.

## See Also

- [Production Checklist](./production-checklist.md) -- The cross-platform pre-release checklist
- [Android Setup](../getting-started/android-setup.md) -- Installing and configuring the library on Android
- [Android Native Architecture](../architecture/android-native.md) -- The receiver, the workers and the ProGuard rules
- [Battery Optimization](../guides/battery-optimization.md) -- WorkManager constraints, metered networks and OS scheduling
- [Crash Recovery](../guides/crash-recovery.md) -- What recovery does after a crash, a reboot or an app update
- [Platform Comparison](./platform-comparison.md) -- Android vs iOS differences
- [iOS Privacy Manifest](./privacy-manifest.md) -- What the library declares for the App Store
