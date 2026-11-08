---
sidebar_position: 2
title: Installation
description: Install @gabriel-sisjr/react-native-sync-provider, configure peer dependencies, and complete the iOS and Android setup for offline-first HTTP sync.
keywords:
  - react-native
  - installation
  - setup
  - sync-provider
  - nitro-modules
  - background-sync
  - core-data
  - workmanager
---

# Installation

## Install the Package

```bash
# Using yarn (recommended)
yarn add @gabriel-sisjr/react-native-sync-provider

# Using npm
npm install @gabriel-sisjr/react-native-sync-provider
```

The library is autolinked through Nitro Modules on RN 0.74+ with the New Architecture enabled. No `react-native link` step is required.

## Peer Dependencies

| Package | Required Version | Notes |
|---|---|---|
| `react` | >= 18.2 | Hooks API |
| `react-native` | >= 0.74 | New Architecture required |
| `react-native-nitro-modules` | ^0.35.8 | Bridge runtime. 0.35.8 or later, below 0.36 |
| `react-native-mmkv` | optional | Used by integration patterns; not a direct dependency |
| `@react-native-community/netinfo` | optional | Use the lib's `useConnection` instead -- this is only needed if you already use it elsewhere |

Install the required peer:

```bash
yarn add "react-native-nitro-modules@^0.35.8"
# or
npm install "react-native-nitro-modules@^0.35.8"
```

:::note Why 0.35.8
In 0.35.6 and 0.35.7, Nitro's Swift `Promise` had no lock, so an iOS call could leave its JS promise pending forever. The fix, [Nitro PR #1338](https://github.com/margelo/nitro/pull/1338), was first published in 0.35.8 on npm. Nitro's GitHub has no v0.35.8 release: the fix is listed in the [v0.35.9 release notes](https://github.com/margelo/nitro/releases/tag/v0.35.9).
:::

## iOS Setup

### 1. Install Pods

```bash
cd ios && pod install && cd ..
```

This pulls in the `SyncProvider` pod (the iOS module name -- not the npm package name) and its native dependencies (`Foundation`, `CoreData`, `Network`, `BackgroundTasks`, `Security`).

### 2. Enable Background Modes

In Xcode, open your app target -> **Signing & Capabilities** -> **+ Capability** -> **Background Modes**. Enable:

- **Background fetch**
- **Background processing**

### 3. Declare the Background Task Identifiers

Add both of the library's identifiers to your app's `Info.plist`. They are fixed:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
  <string>com.gabriel-sisjr.syncprovider.processing</string>
</array>
```

### 4. Forward Background URLSession Events

Forward `handleEventsForBackgroundURLSession` from your `AppDelegate`, as shown in [iOS Setup](./ios-setup.md#5-forward-background-urlsession-events). The library registers its BGTask handlers by itself, so do not register them yourself.

### 5. Privacy Manifest

The library ships its own `PrivacyInfo.xcprivacy` (declared as a `resource_bundles` entry in `SyncProvider.podspec`). Consumers do **not** need to add anything for the library -- Apple's required reasons are already declared. See [Privacy Manifest](../production/privacy-manifest.md) for full details.

### 6. Minimum iOS Version

Set `IPHONEOS_DEPLOYMENT_TARGET = 15.0` (or higher) in your project. The library's podspec enforces this floor, and React Native 0.85 raises the built pod to 15.1.

## Android Setup

### 1. Permissions (Auto-Merged)

The library declares the required permissions in its own `AndroidManifest.xml`. Gradle's manifest merger pulls them into your app automatically:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

You do not need to copy them into your app's manifest, but listing them is good practice for transparency.

### 2. Gradle Versions

Verify your project meets the floor:

| Setting | Required |
|---|---|
| `minSdkVersion` | 24 |
| `compileSdkVersion` | 36 |
| `targetSdkVersion` | 36 |
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.2 |

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
  }
}
```

### 3. Boot Receiver

The library's `BootCompletedReceiver` is already registered in the merged manifest. It schedules a `RecoveryWorker` at boot to reset in-flight items and re-arm background work. No action required.

### 4. ProGuard / R8

ProGuard rules are bundled with the library (`android/proguard-rules.pro`) and applied via the consumer-side `consumerProguardFiles`. No additional rules are required.

## New Architecture Verification

The library only runs on the New Architecture. After installation, run the example or your app and check Metro logs for:

```text
Running "AppName" with {"fabric":true,"initialProps":{"concurrentRoot":true},"rootTag":1}
```

If you see `"fabric":false`, enable the New Architecture:

- **iOS**: `RCT_NEW_ARCH_ENABLED=1 pod install` (or set `newArchEnabled=true` in `ios/Podfile.properties.json` for Expo).
- **Android**: `newArchEnabled=true` in `android/gradle.properties`.

## Verifying the Installation

```ts
import { isNativeModuleAvailable } from '@gabriel-sisjr/react-native-sync-provider';

if (isNativeModuleAvailable()) {
  console.log('Sync provider linked');
} else {
  console.warn('Native module not loaded -- check New Architecture and rebuild.');
}
```

If the helper returns `false`, the most common causes are:

- The New Architecture is not enabled.
- The native project was not rebuilt after installing the package.
- CocoaPods was not reinstalled (iOS).
- You are running on web / SSR (the library is intentionally inert there).

## Troubleshooting

:::tip Build fails on iOS with "Multiple commands produce PrivacyInfo.xcprivacy"
This happens when an older copy of the library was installed via `cp` or vendored. Run `pod deintegrate && pod install` and remove any duplicate `PrivacyInfo.xcprivacy` from your app target.
:::

:::tip Android KSP crash on Room schema generation
Make sure you are on Kotlin 2.0.21 and Room 2.7.0+. Older Room versions (2.6.x) crash on KSP under Kotlin 2.0.x.
:::

:::tip "Native module not found" on first call
Rebuild the native app after installing the package: `yarn example ios` / `yarn example android`. Hot-reload alone does not pick up new native modules.
:::

For a full list of common issues, see [Troubleshooting](../troubleshooting.md).

## Next Steps

- [Quick Start](./quick-start.md) -- A working hooks-first screen in five minutes.
- [iOS Setup](./ios-setup.md) -- Detailed iOS configuration.
- [Android Setup](./android-setup.md) -- Detailed Android configuration.
