---
sidebar_position: 1
title: iOS Background Modes
description: Configure Background Modes capability and BGTaskSchedulerPermittedIdentifiers in your host app so the library can register OS-scheduled background sync windows.
keywords:
  - ios
  - background modes
  - bgtaskscheduler
  - info.plist
  - background fetch
  - background processing
  - capability
---

# iOS Background Modes

The library drives background sync through Apple's `BGTaskScheduler`. Two host-app changes are required for it to function — without them, `enableBackgroundSync()` throws `BACKGROUND_TASK_REGISTRATION_FAILED`.

## 1. Enable the capabilities

In Xcode, with your app target selected:

1. Open the **Signing & Capabilities** tab.
2. Click **+ Capability** → **Background Modes**.
3. Tick **Background fetch** and **Background processing**.

This adds the following keys to your project's entitlements automatically.

## 2. Declare the task identifiers

Add the identifier you want the library to use to your `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
</array>
```

`com.gabriel-sisjr.syncprovider.background` is the default identifier. If you override it via `BackgroundSyncOptions.taskIdentifier`, the value here MUST match.

:::warning
The OS validates identifier registration at process launch. Adding a new identifier later requires a fresh app launch — `BGTaskScheduler` does not pick up runtime changes to the permitted list.
:::

## 3. Verify the registration

After installing on a real device (BGTaskScheduler does not run on the simulator), open the Console.app on your Mac, attach to the device, filter by your app's bundle id, and look for:

```
BGTaskScheduler: Successfully scheduled task com.gabriel-sisjr.syncprovider.background
```

You can also force a window from the Xcode debugger while the app is paused:

```
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.gabriel-sisjr.syncprovider.background"]
```

The library emits `BACKGROUND_SYNC_STARTED` and `BACKGROUND_SYNC_COMPLETED` events around each window — subscribe via `useSyncEvents` to confirm.

## What the OS actually guarantees

BGTaskScheduler is a **soft hint**, not a hard guarantee. Apple's scheduler decides when to run your task based on:

- Time since last successful run (target ~15 minutes for `BGAppRefreshTaskRequest`).
- Battery state, low-power mode.
- Network availability.
- App usage history (less-used apps run less often).

Plan your sync UX around this: even if you request a 15-minute interval, the OS may defer the next window for hours on devices with low engagement. Use `useAutoSync` (foreground) plus `enableBackgroundSync` (background) together so the queue drains both inside and outside the app session.

## When to use which task type

The library schedules both a `BGAppRefreshTaskRequest` (short, network-only) and a `BGProcessingTaskRequest` (longer, may require power). You don't choose between them — the library picks the right one based on the queue size and the constraints in `BackgroundSyncOptions`. Both share the single identifier you declared above.

:::tip
If your app aggressively dispatches with `requiresCharging=true`, expect very few background windows during normal day-to-day use. This is intentional — set it to `false` for time-sensitive telemetry.
:::
