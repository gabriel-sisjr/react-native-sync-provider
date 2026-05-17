---
sidebar_position: 2
title: Background Sync
description: Configure OS-scheduled background sync with BGTaskScheduler (iOS) and WorkManager (Android) — constraints, identifiers, and platform differences.
keywords:
  - background-sync
  - bgtaskscheduler
  - workmanager
  - background-fetch
  - periodic-work
  - ios-background-modes
  - doze-mode
---

# Background Sync

Background sync lets `react-native-sync-provider` drain the queue even when the app is **not running**. iOS uses `BGTaskScheduler`; Android uses `WorkManager`. This guide covers configuration, OS limits, and what to expect in production.

## Enable / Disable

```ts
import {
  enableBackgroundSync,
  disableBackgroundSync,
  isBackgroundSyncEnabled,
} from '@gabriel-sisjr/react-native-sync-provider';

await enableBackgroundSync({
  minimumIntervalMs: 15 * 60 * 1000,
  requiresUnmeteredNetwork: true,
});

const enabled = await isBackgroundSyncEnabled(); // => true

await disableBackgroundSync();
```

Or hand the lifecycle to the optional Provider:

```tsx
<SyncProvider
  options={...}
  backgroundSync={{
    minimumIntervalMs: 15 * 60 * 1000,
    requiresUnmeteredNetwork: true,
  }}
>
  <Root />
</SyncProvider>
```

The Provider calls `enableBackgroundSync` on mount and `disableBackgroundSync` on unmount, **only** if the Provider was the one that enabled it.

## `BackgroundSyncOptions`

| Field | Type | Default | Description |
|---|---|---|---|
| `minimumIntervalMs` | `number` | -- | Minimum gap between background fires. Soft hint on iOS, hard floor of 15 minutes on Android. |
| `requiresCharging` | `boolean` | `false` | Only fire when the device is plugged in. |
| `requiresUnmeteredNetwork` | `boolean` | `false` | Only fire on Wi-Fi / ethernet. |
| `requiresDeviceIdle` | `boolean` | `false` | Only fire when the device is idle (Android only; ignored on iOS). |
| `taskIdentifier` | `string` | `com.gabriel-sisjr.syncprovider.background` | Custom task identifier. Must be declared in iOS `Info.plist`. |

## iOS: `BGTaskScheduler`

The library registers two task types:

- **`BGAppRefreshTaskRequest`** -- short-lived refresh, used when no constraints require deferral.
- **`BGProcessingTaskRequest`** -- longer-lived processing task, used when `requiresCharging` or `requiresUnmeteredNetwork` is set.

### Identifier registration

Add the identifier you pass to `enableBackgroundSync` (or the default if you do not override) to your app's `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
</array>
```

Without this, `BGTaskScheduler` rejects the registration silently and `BACKGROUND_SYNC_STARTED` events never fire.

### OS-imposed scheduling

`minimumIntervalMs` is a **soft hint**. iOS uses on-device usage patterns, battery level, and network state to decide when to fire. In practice:

- New apps or rarely opened apps may go hours between fires.
- Frequently used apps see fires every 15-30 minutes.
- Low Power Mode delays fires until the device exits it.

### Capability requirement

Both **Background fetch** and **Background processing** must be enabled in Xcode -> Signing & Capabilities. Without them, registration throws `BACKGROUND_TASK_REGISTRATION_FAILED`.

See [iOS Setup](../getting-started/ios-setup.md) for the full walkthrough.

## Android: `WorkManager`

The library uses `PeriodicWorkRequest` (with `unique` work name) for the recurring schedule and `OneTimeWorkRequest` for connectivity-triggered drains.

### 15-minute floor

```ts
await enableBackgroundSync({
  minimumIntervalMs: 5 * 60 * 1000, // 5 minutes -- silently clamped to 15 by Android
});
```

`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is 15 minutes. Android does not error on smaller values -- it clamps them.

### Constraints map to `Constraints.Builder`

| `BackgroundSyncOptions` | `Constraints` |
|---|---|
| `requiresCharging: true` | `.setRequiresCharging(true)` |
| `requiresUnmeteredNetwork: true` | `.setRequiredNetworkType(NetworkType.UNMETERED)` |
| `requiresDeviceIdle: true` | `.setRequiresDeviceIdle(true)` |

When no constraints are set, the library defaults to `NetworkType.CONNECTED` so the worker only fires when the device has any network.

### Doze mode

Android Doze (API 23+) defers periodic work during deep idle. Expect:

- Fewer fires than `minimumIntervalMs` indicates while the device is stationary.
- Resumed fires within a maintenance window when the device moves or is plugged in.
- OEM battery optimizers (Xiaomi MIUI, Huawei EMUI, Samsung Adaptive Battery) further restrict execution.

The library does not request battery whitelisting -- that is a UX choice for the host app.

### Reboot recovery

The library's `BootCompletedReceiver` is registered automatically. On `BOOT_COMPLETED`:

1. The receiver enqueues a one-time `RecoveryWorker`.
2. The worker re-registers the periodic job from persisted options.
3. If pending items exist and connectivity is available, a one-shot drain runs.

Users that Force Stop the app must open it once for the receiver to fire again -- this is an Android-wide constraint, not library-specific.

## Platform Comparison

| Concern | iOS (`BGTaskScheduler`) | Android (`WorkManager`) |
|---|---|---|
| Min interval | Soft hint | Hard 15-minute floor |
| Charging constraint | Supported (BGProcessingTask) | Supported |
| Unmetered constraint | Supported | Supported |
| Idle constraint | Not supported (ignored) | Supported |
| Identifier registration | Required in `Info.plist` | Implicit (unique work name) |
| Reboot recovery | Re-registers on app launch | `BootCompletedReceiver` reschedules |
| OS deferral | Aggressive (battery/usage based) | Doze-aware, OEM-restricted |
| Capability flag | Background Modes (`fetch` + `processing`) | None (auto via merged manifest) |

## Listening for Background Fires

```tsx
import { useSyncEvents } from '@gabriel-sisjr/react-native-sync-provider';

useSyncEvents({
  types: ['BACKGROUND_SYNC_STARTED', 'BACKGROUND_SYNC_COMPLETED'],
  onEvent: (e) => console.log(e.type, new Date(e.timestamp).toISOString()),
});
```

These events fire only when the app process is alive at the time of the OS callback. If the OS delivers a fire while the JS bridge is dormant, the queue still drains -- but you will not see the event in the JS console.

## When Sync May Be Deferred

| Cause | iOS | Android |
|---|---|---|
| Low battery / Low Power Mode | Defers fires | Defers fires |
| Doze mode | n/a | Defers periodic work |
| OEM battery optimizer | n/a | Often defers heavily |
| User Force Stop | Until next foreground | Until next foreground |
| App not opened recently | iOS reduces frequency | WorkManager respects Adaptive Battery |
| Network constraint not met | Skips this fire | Skips this fire |

For predictable cadence in development, use `useAutoSync({ flushOnReconnect: true, flushOnForeground: true })` -- it triggers a JS-side flush on the events you care about, independent of OS scheduling.

## Next Steps

- [Connectivity Detection](./connectivity-detection.md) -- Trigger flushes on reconnect.
- [iOS Setup](../getting-started/ios-setup.md) -- Background Modes + Info.plist.
- [Android Setup](../getting-started/android-setup.md) -- Permissions + Doze.
