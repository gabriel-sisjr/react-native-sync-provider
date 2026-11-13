---
sidebar_position: 5
title: iOS Background Modes
description: Configure Background Modes capability and BGTaskSchedulerPermittedIdentifiers in your host app so the library can run OS-scheduled background sync windows.
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

The library drives background sync through Apple's `BGTaskScheduler`. Two host-app changes are required for it to function, and a third, one `AppDelegate` method, keeps background uploads from stalling. The library registers its task handlers by itself, at process start, so there is no registration code to write.

## Step 1: Enable the Capabilities {#1-enable-the-capabilities}

In Xcode, with your app target selected:

1. Open the **Signing & Capabilities** tab.
2. Click **+ Capability** → **Background Modes**.
3. Tick **Background fetch** and **Background processing**.

This adds `fetch` and `processing` to `UIBackgroundModes` in your `Info.plist`.

## Step 2: Declare the Task Identifiers {#2-declare-the-task-identifiers}

Add both of the library's identifiers to your `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
  <string>com.gabriel-sisjr.syncprovider.processing</string>
</array>
```

The identifiers are fixed. `BackgroundSyncOptions.taskIdentifier` has no effect.

> **Warning:** The library reads this list at process start, and iOS validates it. An identifier added later takes effect on the next launch. A missing identifier does not crash: its handler is skipped, and `enableBackgroundSync` rejects with `BACKGROUND_TASK_REGISTRATION_FAILED` when `.background` is the one missing.

## Step 3: Forward Background URLSession Events {#3-forward-background-urlsession-events}

Forward `handleEventsForBackgroundURLSession` from your `AppDelegate` to `HybridSyncProvider.handleBackgroundURLSessionEvents`. See [iOS Setup](../getting-started/ios-setup.md#5-forward-background-urlsession-events). This is the only `AppDelegate` code the library needs.

## Step 4: Verify the Registration {#4-verify-the-registration}

`BGTaskScheduler` does not accept requests on a simulator, so use a physical device. In Console.app, select the device, filter by `subsystem:com.gabriel-sisjr.syncprovider`, turn on **Include Info Messages**, and look for this line at launch:

```text
Registered BGTask handlers for 2 declared identifiers
```

You can also force a window from the Xcode debugger while the app is paused, after calling `enableBackgroundSync`:

```text
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.gabriel-sisjr.syncprovider.background"]
```

The library emits `BACKGROUND_SYNC_STARTED` and `BACKGROUND_SYNC_COMPLETED` events around each window. Those reach a running JS engine only, so also check that your endpoint received the requests.

## What the OS Actually Guarantees

BGTaskScheduler is a **soft hint**, not a hard guarantee. Nothing promises when a task runs, or that it runs at all. Apple's scheduler decides based on:

- Time since the last successful run.
- Battery state, low-power mode.
- Network availability.
- App usage history (less-used apps run less often).

iOS does not launch a force-quit app for background tasks or uploads, until the user opens it again.

Plan your sync UX around this: even if you request a 15-minute interval, the OS may defer the next window for hours on devices with low engagement. Use `useAutoSync` (foreground) plus `enableBackgroundSync` (background) together so the queue drains both inside and outside the app session.

## App Lifecycle and Background Sync

What the library does with the queue depends on the app's state:

| App state | What the library does |
| --- | --- |
| Active | Dispatches on the foreground `URLSession`, which waits for connectivity. |
| Not active | Dispatches on the background `URLSession`, unless `foregroundOnly` is set. The body goes through a temporary file, and the OS may finish the upload while the app is suspended. |
| Launched for a BGTask | The handler re-submits its request, waits for launch recovery and runs one flush cycle, with no JS loaded. |
| Force-quit | iOS does not launch the app for background tasks or uploads until the user opens it again. On that launch, recovery puts items left in flight back to `PENDING`. |

> **Warning:** A background-upload completion delivered after a relaunch is dropped. Launch recovery puts the item back to `PENDING`, with no cooldown and its attempt spent, so the server may receive the request twice. See [Idempotency](../guides/idempotency.md).

## Which Task Runs When

The library uses both task types, each with its own identifier, and you do not choose between them:

| Task                      | Identifier    | Submitted                                       |
| ------------------------- | ------------- | ----------------------------------------------- |
| `BGAppRefreshTaskRequest` | `.background` | By `enableBackgroundSync`, and by its own handler. |
| `BGProcessingTaskRequest` | `.processing` | By the refresh handler, and by its own handler. |

So the first processing request exists only after a refresh window has run. Each handler re-submits before doing any work, using the options persisted by `enableBackgroundSync`, so the chain continues with no JS loaded. It runs one flush cycle, which dispatches up to `batchSize` items.

`foregroundOnly: true`, `disableBackgroundSync()`, or a pause make a handler that fires complete without dispatching.

> **Tip:** `requiresCharging` applies to the processing task only. `requiresUnmeteredNetwork` and `requiresDeviceIdle` are not applied on iOS, and the processing task always requires network connectivity.

## See Also

- [iOS Setup](../getting-started/ios-setup.md) -- Installing and configuring the library on iOS
- [iOS Privacy Manifest](./privacy-manifest.md) -- What the library declares for the App Store
- [Platform Comparison](./platform-comparison.md) -- Android vs iOS differences
- [Background Sync](../guides/background-sync.md) -- Enabling background windows, cold-start configuration and expired tokens
- [Crash Recovery](../guides/crash-recovery.md) -- What launch recovery does with items left in flight
- [iOS Native Architecture](../architecture/ios-native.md) -- The BGTask handlers, the two URLSession sessions and launch recovery
