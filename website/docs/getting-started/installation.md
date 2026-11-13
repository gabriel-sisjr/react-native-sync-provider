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

This page installs the package and its peer dependency, then summarizes the native setup each platform needs.

## Install the Package

```bash
# Using yarn (recommended)
yarn add @gabriel-sisjr/react-native-sync-provider

# Using npm
npm install @gabriel-sisjr/react-native-sync-provider
```

React Native's autolinking registers the library with Gradle and CocoaPods, so no `react-native link` step is required.

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

> **Note — Why 0.35.8:** In 0.35.6 and 0.35.7, Nitro's Swift `Promise` had no lock, so an iOS call could leave its JS promise pending forever. The fix, [Nitro PR #1338](https://github.com/margelo/nitro/pull/1338), was first published in 0.35.8 on npm. Nitro's GitHub has no v0.35.8 release: the fix is listed in the [v0.35.9 release notes](https://github.com/margelo/nitro/releases/tag/v0.35.9).

## Android Setup

Android needs no manual step beyond meeting the toolchain versions. Gradle's manifest merger adds the library's permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`) and its `BootCompletedReceiver`, and the library's ProGuard / R8 rules apply through `consumerProguardFiles`.

See the dedicated [Android Setup](./android-setup.md) page for:

- The Gradle versions: `minSdkVersion` 24, `compileSdkVersion` and `targetSdkVersion` 36, Kotlin 2.0.21 to 2.2.21, and which Android Gradle Plugin version applies
- What the boot receiver and its `RecoveryWorker` do after a reboot or an app update
- The `WorkManager` 15-minute floor for periodic work
- Doze mode and OEM battery managers

## iOS Setup

iOS requires configuration beyond installing the package. See the dedicated [iOS Setup](./ios-setup.md) page for complete instructions, including:

- The iOS 15.0 minimum deployment target
- The **Background fetch** and **Background processing** background modes
- Both BGTask identifiers in `Info.plist`
- Forwarding `handleEventsForBackgroundURLSession` from your `AppDelegate` ([Step 5](./ios-setup.md#5-forward-background-urlsession-events))
- The bundled privacy manifest, which needs nothing from you

Quick summary for minimal setup:

```bash
# 1. Set the iOS deployment target to 15.0 or later
# 2. In Xcode, enable Background Modes > Background fetch and Background processing
# 3. Add both identifiers to BGTaskSchedulerPermittedIdentifiers in Info.plist
# 4. Forward handleEventsForBackgroundURLSession from your AppDelegate
# 5. Install pods
cd ios && pod install && cd ..
```

The library registers its BGTask handlers by itself, so do not register them in your `AppDelegate`.

## New Architecture Verification

The New Architecture is required: the library is built and tested only on it. Nitro itself can install on the legacy bridge, but this library is not tested there. After installation, run the example or your app and check Metro logs for:

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

Common installation issues. For the full list, see [Troubleshooting](../troubleshooting.md).

### Build Fails on iOS with `Multiple commands produce PrivacyInfo.xcprivacy`

This happens when an older copy of the library was installed via `cp` or vendored. Run `pod deintegrate && pod install` and remove any duplicate `PrivacyInfo.xcprivacy` from your app target. For a podspec that lists the manifest as a plain resource, see [Troubleshooting](../troubleshooting.md#symptom-pod-install-fails-with-multiple-commands-produce-privacyinfoxcprivacy).

### Android KSP Crash on Room Schema Generation

Make sure you are on Room 2.7.0+ and on a Kotlin version the library supports, 2.0.21 to 2.2.21 (see [Android Setup](./android-setup.md#1-gradle-versions)). Older Room versions (2.6.x) crash on KSP under Kotlin 2.0.x.

### Native Module Not Found on the First Call

Rebuild the native app after installing the package, from Xcode and Android Studio or with `npx react-native run-ios` / `npx react-native run-android`. Hot-reload alone does not pick up new native modules.

## Next Steps

- [Quick Start](./quick-start.md) -- A working hooks-first screen in five minutes
- [iOS Setup](./ios-setup.md) -- Detailed iOS configuration
- [Android Setup](./android-setup.md) -- Detailed Android configuration
