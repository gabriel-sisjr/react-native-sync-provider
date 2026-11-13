---
sidebar_position: 4
title: iOS Setup
description: iOS-specific setup for react-native-sync-provider — Background Modes, Info.plist, AppDelegate forwarding, Privacy Manifest, and minimum iOS version.
keywords:
  - ios
  - setup
  - background-modes
  - bgtaskscheduler
  - info-plist
  - privacy-manifest
  - cocoapods
  - sync-provider
---

# iOS Setup

This guide walks through every iOS-specific step required for `@gabriel-sisjr/react-native-sync-provider` to dispatch HTTP requests in the background, persist them across app launches, and pass App Store review.

## Prerequisites

| Requirement | Minimum Version | Notes |
|---|---|---|
| iOS deployment target | 15.0 | Declared by `SyncProvider.podspec`. See Step 1 |
| React Native | 0.74 | New Architecture required |
| `react-native-nitro-modules` | ^0.35.8 | Peer dependency, below 0.36. See [Installation](./installation.md#peer-dependencies) |
| Physical iOS device | iOS 15.0 | Needed to test background tasks: the Simulator does not accept `BGTaskScheduler` requests |

## Step 1: Minimum Deployment Target {#1-minimum-deployment-target}

The library requires **iOS 15.0 or later**, and its podspec enforces that floor. React Native 0.85 raises the built pod to 15.1. Set `IPHONEOS_DEPLOYMENT_TARGET` to 15.0 or higher, and verify it in Xcode under your app target -> **General** -> **Minimum Deployments**.

In `ios/Podfile`:

```ruby
platform :ios, '15.0'
```

## Step 2: Install Pods {#2-install-pods}

After installing the npm package:

```bash
cd ios && pod install && cd ..
```

The pod is named **`SyncProvider`** (the iOS module name, distinct from the npm package name `@gabriel-sisjr/react-native-sync-provider`). It is autolinked through Nitro Modules' generated podspec lookup, and it links the `Foundation`, `CoreData`, `Network`, `BackgroundTasks` and `Security` frameworks.

## Step 3: Enable Background Modes {#3-enable-background-modes}

In Xcode:

1. Select your app target.
2. Go to **Signing & Capabilities**.
3. Click **+ Capability**.
4. Add **Background Modes**.
5. Tick the following:
   - **Background fetch**
   - **Background processing**

Xcode writes the corresponding entries to `Info.plist`:

```xml
<key>UIBackgroundModes</key>
<array>
  <string>fetch</string>
  <string>processing</string>
</array>
```

## Step 4: Declare the Background Task Identifiers {#4-declare-the-background-task-identifiers}

iOS requires every BGTask identifier to be declared in `Info.plist` before it can be registered. Add both of the library's identifiers:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
  <string>com.gabriel-sisjr.syncprovider.processing</string>
</array>
```

The identifiers are fixed. `BackgroundSyncOptions.taskIdentifier` is stored but not used, so a custom value has no effect, and replacing these strings with one turns background sync off.

A missing identifier does not crash the app: its handler is skipped. `enableBackgroundSync` rejects with `BACKGROUND_TASK_REGISTRATION_FAILED` when `.background` is the one missing. Without `.processing`, the refresh task still runs, and the longer processing task never does.

## Step 5: Forward Background URLSession Events {#5-forward-background-urlsession-events}

When the app is not active, the library sends uploads through a background `URLSession`, which the OS can finish while the app is suspended. iOS then delivers the result through your `AppDelegate`, and a library cannot intercept that call. Forward it:

```swift
import SyncProvider

class AppDelegate: UIResponder, UIApplicationDelegate {
  // ...

  func application(
    _ application: UIApplication,
    handleEventsForBackgroundURLSession identifier: String,
    completionHandler: @escaping () -> Void
  ) {
    HybridSyncProvider.handleBackgroundURLSessionEvents(
      identifier: identifier,
      completionHandler: completionHandler
    )
  }
}
```

This is the only `AppDelegate` code the library needs. The call is Swift-only, because the pod generates no Objective-C header. If your `AppDelegate` is Objective-C, call it from a small Swift helper in your app target.

> **Important — Do not register the BGTask handlers yourself:** The library registers its handlers at process start, through `+load`, before `didFinishLaunchingWithOptions` runs and with no JS loaded. Do not call `BGTaskScheduler.shared.register` for either identifier: iOS traps on a second registration for the same identifier, so the app is expected to crash at launch. An earlier version of this page showed such a block. If your app has it, delete it. See [`BREAKING_CHANGES.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/BREAKING_CHANGES.md).

## Step 6: Privacy Manifest {#6-privacy-manifest}

The library ships its own `PrivacyInfo.xcprivacy` as a `resource_bundles` entry in `SyncProvider.podspec`. The bundle declares:

- `NSPrivacyAccessedAPICategoryFileTimestamp` with reason `C617.1` -- required by Apple for Core Data on-disk storage timestamps.
- `NSPrivacyAccessedAPICategoryUserDefaults` with reason `CA92.1` -- the library keeps its configuration and background options in its own UserDefaults suite.
- No tracking domains, no tracking collection.

Consumers do **not** need to add anything to their app's `PrivacyInfo.xcprivacy` for the library. If your app uses other APIs that require declarations, merge the library's reasons with yours -- they do not conflict.

See [Privacy Manifest](../production/privacy-manifest.md) for the full declaration.

## Step 7: Test the Background Handler {#7-test-the-background-handler}

This needs a physical device. On a simulator, `BGTaskScheduler` does not accept requests, so `enableBackgroundSync` rejects with `BACKGROUND_TASK_REGISTRATION_FAILED`.

1. Run a Debug build from Xcode, and turn on Background App Refresh for the app in Settings.
2. Open Console.app, select the device, filter by `subsystem:com.gabriel-sisjr.syncprovider`, and turn on **Include Info Messages**. At launch you should see `Registered BGTask handlers for 2 declared identifiers`. The count covers every identifier in your `BGTaskSchedulerPermittedIdentifiers`, so it is higher when your app declares identifiers of its own.
3. Call `enableBackgroundSync`, then pause the app with **Debug -> Pause** and run this in the LLDB console:

   ```text
   e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.gabriel-sisjr.syncprovider.background"]
   ```

4. Continue. Confirm the log shows `BGAppRefresh task fired` and that your endpoint received the queued items. A log line alone is not proof of dispatch.

The processing request is submitted by the refresh handler, so simulate `.background` once before simulating `.processing`. A simulated launch does nothing unless a request for that identifier is pending. The command uses a private API and runs in a process that is already alive: it proves the handler logic, not that iOS will schedule the task.

**Debug -> Simulate Background Fetch** does not exercise these handlers.

## Troubleshooting

Common iOS setup issues. For the full list, see [Troubleshooting](../troubleshooting.md).

> **Warning — OS-imposed scheduling:** `BGTaskScheduler` treats your `minimumIntervalMs` as a **soft hint**: it only sets the refresh task's earliest begin date, at least 60 seconds after the submit, and the processing task has none. The OS schedules the task at its discretion based on battery level, network state, and usage patterns. Nothing guarantees when, or whether, a window runs. Do not depend on a specific cadence.

### `enableBackgroundSync` Throws `BACKGROUND_TASK_REGISTRATION_FAILED`

Check that `com.gabriel-sisjr.syncprovider.background` is in `BGTaskSchedulerPermittedIdentifiers`, that you are on a physical device, and that Background App Refresh is on for the app.

### Background Work Stops After the User Swipes the App Away

iOS does not launch a force-quit app for background tasks or uploads, until the user opens it again. The queue itself is safe: items stay on disk.

## Verification Checklist

After setup, verify the following:

- [ ] The app target's minimum deployment is iOS 15.0 or later
- [ ] `pod install` completes without errors and installs the `SyncProvider` pod
- [ ] **Background fetch** and **Background processing** are enabled, and `UIBackgroundModes` in `Info.plist` contains `fetch` and `processing`
- [ ] `BGTaskSchedulerPermittedIdentifiers` contains `com.gabriel-sisjr.syncprovider.background` and `com.gabriel-sisjr.syncprovider.processing`
- [ ] Your `AppDelegate` forwards `handleEventsForBackgroundURLSession` to `HybridSyncProvider.handleBackgroundURLSessionEvents`
- [ ] Your `AppDelegate` does not call `BGTaskScheduler.shared.register` for either library identifier
- [ ] `isNativeModuleAvailable()` returns `true`
- [ ] On a physical device, Console.app shows `Registered BGTask handlers for … declared identifiers` at launch
- [ ] `enableBackgroundSync` resolves on a physical device
- [ ] A simulated `.background` launch logs `BGAppRefresh task fired`, and your endpoint receives the queued items

## Next Steps

- [Android Setup](./android-setup.md) -- The matching Android-side configuration
- [Background Sync](../guides/background-sync.md) -- BGTaskScheduler + WorkManager deep dive
- [iOS Privacy Manifest](../production/privacy-manifest.md) -- App Store privacy compliance
