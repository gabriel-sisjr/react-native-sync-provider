---
sidebar_position: 1
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

The library drives background sync through Apple's `BGTaskScheduler`. Two host-app changes are required for it to function. The library registers its task handlers by itself, at process start, so there is no registration code to write.

## 1. Enable the capabilities

In Xcode, with your app target selected:

1. Open the **Signing & Capabilities** tab.
2. Click **+ Capability** → **Background Modes**.
3. Tick **Background fetch** and **Background processing**.

This adds `fetch` and `processing` to `UIBackgroundModes` in your `Info.plist`.

## 2. Declare the task identifiers

Add both of the library's identifiers to your `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
  <string>com.gabriel-sisjr.syncprovider.processing</string>
</array>
```

The identifiers are fixed. `BackgroundSyncOptions.taskIdentifier` has no effect.

:::warning
The library reads this list at process start, and iOS validates it. An identifier added later takes effect on the next launch. A missing identifier does not crash: its handler is skipped, and `enableBackgroundSync` rejects with `BACKGROUND_TASK_REGISTRATION_FAILED` when `.background` is the one missing.
:::

## 3. Forward background URLSession events

Forward `handleEventsForBackgroundURLSession` from your `AppDelegate` to `HybridSyncProvider.handleBackgroundURLSessionEvents`. See [iOS Setup](../getting-started/ios-setup.md#5-forward-background-urlsession-events). This is the only `AppDelegate` code the library needs.

## 4. Verify the registration

`BGTaskScheduler` does not accept requests on a simulator, so use a physical device. In Console.app, select the device, filter by `subsystem:com.gabriel-sisjr.syncprovider`, turn on **Include Info Messages**, and look for this line at launch:

```text
Registered BGTask handlers for 2 declared identifiers
```

You can also force a window from the Xcode debugger while the app is paused, after calling `enableBackgroundSync`:

```text
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.gabriel-sisjr.syncprovider.background"]
```

The library emits `BACKGROUND_SYNC_STARTED` and `BACKGROUND_SYNC_COMPLETED` events around each window. Those reach a running JS engine only, so also check that your endpoint received the requests.

## What the OS actually guarantees

BGTaskScheduler is a **soft hint**, not a hard guarantee. Nothing promises when a task runs, or that it runs at all. Apple's scheduler decides based on:

- Time since the last successful run.
- Battery state, low-power mode.
- Network availability.
- App usage history (less-used apps run less often).

iOS does not launch a force-quit app for background tasks or uploads, until the user opens it again.

Plan your sync UX around this: even if you request a 15-minute interval, the OS may defer the next window for hours on devices with low engagement. Use `useAutoSync` (foreground) plus `enableBackgroundSync` (background) together so the queue drains both inside and outside the app session.

## Which task runs when

The library uses both task types, each with its own identifier, and you do not choose between them:

| Task                      | Identifier    | Submitted                                       |
| ------------------------- | ------------- | ----------------------------------------------- |
| `BGAppRefreshTaskRequest` | `.background` | By `enableBackgroundSync`, and by its own handler. |
| `BGProcessingTaskRequest` | `.processing` | By the refresh handler, and by its own handler. |

So the first processing request exists only after a refresh window has run. Each handler re-submits before doing any work, using the options persisted by `enableBackgroundSync`, so the chain continues with no JS loaded. It runs one flush cycle, which dispatches up to `batchSize` items.

`foregroundOnly: true`, `disableBackgroundSync()`, or a pause make a handler that fires complete without dispatching.

:::tip
`requiresCharging` applies to the processing task only. `requiresUnmeteredNetwork` and `requiresDeviceIdle` are not applied on iOS, and the processing task always requires network connectivity.
:::
