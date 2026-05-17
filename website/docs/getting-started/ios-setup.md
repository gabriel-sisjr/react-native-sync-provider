---
sidebar_position: 4
title: iOS Setup
description: iOS-specific setup for react-native-sync-provider — Background Modes, Info.plist, AppDelegate hook, Privacy Manifest, and minimum iOS version.
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

## 1. Minimum Deployment Target

The library requires **iOS 13.0 or later**. Verify in Xcode under your app target -> **General** -> **Minimum Deployments**.

In `ios/Podfile`:

```ruby
platform :ios, '13.0'
```

## 2. Install Pods

After installing the npm package:

```bash
cd ios && pod install && cd ..
```

The pod is named **`SyncProvider`** (the iOS module name, distinct from the npm package name `@gabriel-sisjr/react-native-sync-provider`). It is autolinked through Nitro Modules' generated podspec lookup.

## 3. Enable Background Modes

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

:::warning
Without these capabilities, `enableBackgroundSync` throws `BACKGROUND_TASK_REGISTRATION_FAILED` at runtime, and `BGTaskScheduler` silently never fires the registered task.
:::

## 4. Register the Background Task Identifier

iOS requires every BGTask identifier to be declared in `Info.plist` before it can be registered. Add the library's default identifier (or your override) to your app's `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
</array>
```

If you pass a custom `taskIdentifier` to `enableBackgroundSync`, replace the string above with your value:

```ts
await enableBackgroundSync({
  minimumIntervalMs: 15 * 60 * 1000,
  taskIdentifier: 'com.example.app.sync',
});
```

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.example.app.sync</string>
</array>
```

## 5. AppDelegate Wiring (Optional, Advanced)

The library registers its `BGTaskScheduler` handler internally during `enableBackgroundSync`. For most apps, no AppDelegate code is required.

Advanced consumers that want to register the handler at app launch (for cold-start background fires before any JS code runs) can call into the library's manager from Swift:

```swift
import BackgroundTasks
import SyncProvider

@main
class AppDelegate: RCTAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    // Optional: register the BGTask handler before React Native bootstraps.
    // The library will reuse this registration on first JS-side enable call.
    BGTaskScheduler.shared.register(
      forTaskWithIdentifier: "com.gabriel-sisjr.syncprovider.background",
      using: nil
    ) { task in
      SyncProviderBackgroundTaskRunner.run(task: task as! BGProcessingTask)
    }

    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}
```

If you do not register at launch, the library wires the handler on the first `enableBackgroundSync` call -- which works for warm-start scenarios but may miss the first background window after an upgrade.

## 6. Privacy Manifest

The library ships its own `PrivacyInfo.xcprivacy` as a `resource_bundles` entry in `SyncProvider.podspec`. The bundle declares:

- `NSPrivacyAccessedAPICategoryFileTimestamp` with reason `C617.1` -- required by Apple for Core Data on-disk storage timestamps.
- No tracking domains, no tracking collection.

Consumers do **not** need to add anything to their app's `PrivacyInfo.xcprivacy` for the library's use of Core Data. If your app uses other APIs that require declarations, merge the library's reasons with yours -- they do not conflict.

See [Privacy Manifest](../production/privacy-manifest.md) for the full declaration.

## 7. Test the Background Handler

In Xcode:

1. Set a breakpoint in your sync event listener for `BACKGROUND_SYNC_STARTED`.
2. Run the app on a real device (the Simulator does not honor `BGTaskScheduler` reliably).
3. With the app in the background, in Xcode go to **Debug -> Simulate Background Fetch**.
4. Confirm the breakpoint fires and the queue drains.

Alternatively, trigger a background processing task from the LLDB console:

```text
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.gabriel-sisjr.syncprovider.background"]
```

## 8. Common iOS Issues

:::tip "Missing Background Modes capability"
If `enableBackgroundSync` throws `BACKGROUND_TASK_REGISTRATION_FAILED`, double-check that both **Background fetch** and **Background processing** are enabled in **Signing & Capabilities**.
:::

:::tip Identifier mismatch
Every `taskIdentifier` you register at runtime must appear verbatim in `BGTaskSchedulerPermittedIdentifiers`. iOS rejects unmatched identifiers silently at registration time.
:::

:::warning OS-imposed scheduling
`BGTaskScheduler` treats your `minimumIntervalMs` as a **soft hint**. The OS schedules the task at its discretion based on battery level, network state, and usage patterns. Do not depend on a specific cadence.
:::

For the full list of issues, see [Troubleshooting](../troubleshooting.md).

## Next Steps

- [Android Setup](./android-setup.md) -- The matching Android-side configuration.
- [Background Sync guide](../guides/background-sync.md) -- BGTaskScheduler + WorkManager deep dive.
- [Privacy Manifest](../production/privacy-manifest.md) -- App Store privacy compliance.
