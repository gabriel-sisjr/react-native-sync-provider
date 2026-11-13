---
sidebar_position: 3
title: iOS Privacy Manifest
description: What the library declares in its PrivacyInfo.xcprivacy, how RN 0.85+ aggregates manifests at build time, and what you may need to add to your own app manifest.
keywords:
  - ios
  - privacy manifest
  - PrivacyInfo.xcprivacy
  - apple
  - required reason api
  - tracking
---

# iOS Privacy Manifest

Apple requires every iOS app to ship a Privacy Manifest (`PrivacyInfo.xcprivacy`) declaring which Required Reason APIs it uses, what data it collects, and whether it tracks users. SDKs that ship one of their own get aggregated into the host app's manifest at build time. The library ships its own manifest, so consumers usually have nothing extra to do. This page documents what's in it and when you do need to add to your own manifest.

## Overview of Requirements

| Requirement | Provided by | Your action |
| --- | --- | --- |
| Library Privacy Manifest | The library, in the `SyncProvider_Privacy` resource bundle | Nothing. |
| Merge into the app's manifest | React Native 0.85's CocoaPods aggregation, at build time | Nothing. |
| App-level Privacy Manifest | Your app | Needed when your app uses Required Reason APIs, collects data or contains tracking SDKs. |
| Privacy Nutrition Labels | Your app, in App Store Connect | Describe what your app does. The library adds nothing. |

## What the Library Declares

`ios/PrivacyInfo.xcprivacy` (bundled into the pod via `s.resource_bundles = { "SyncProvider_Privacy" => ["ios/PrivacyInfo.xcprivacy"] }`) declares two Required Reason APIs:

| Field                                | Value                                                                                          |
| ------------------------------------ | ---------------------------------------------------------------------------------------------- |
| `NSPrivacyAccessedAPICategoryFileTimestamp` | Reason `C617.1` — Core Data's SQLite store reads timestamps on its own database files.  |
| `NSPrivacyAccessedAPICategoryUserDefaults`  | Reason `CA92.1` — the library keeps its non-secret configuration in its own suite, `com.gabriel-sisjr.syncprovider`. |
| `NSPrivacyTracking`                  | `false` — the library does not track users across apps or websites.                            |
| `NSPrivacyTrackingDomains`           | _empty_ — no third-party domains are contacted.                                                |
| `NSPrivacyCollectedDataTypes`        | _empty_ — the library does not collect any data on Apple's data-type taxonomy.                 |

Specifically, the library does **not** access:

- `SystemBootTime` — not needed.
- `DiskSpace` — not needed.
- `ActiveKeyboards` — not needed.

## How RN 0.85+ Aggregates the Manifest

React Native 0.85's CocoaPods integration aggregates every pod's `PrivacyInfo.xcprivacy` into the host app's final manifest at build time. The aggregator (`privacy_manifest_utils.rb`) iterates `file_accessor.resource_bundles` — which is exactly why this library uses `s.resource_bundles` and not `s.resources` to expose the manifest:

```ruby
# Excerpt — RN 0.85 privacy_manifest_utils.rb (lines 124–125)
file_accessor.resource_bundles.each do |_, paths|
  # collect privacy manifests
end
```

If the manifest were listed under `s.resources`, the build would fail with:

```text
error: Multiple commands produce '.../PrivacyInfo.xcprivacy'
```

…because the aggregator's output and the resource-listed copy would both want to land at the same path. Using `s.resource_bundles` puts the manifest under a uniquely-named bundle (`SyncProvider_Privacy.bundle/PrivacyInfo.xcprivacy`), which the aggregator reads cleanly without colliding.

## What You May Need to Add to Your Own Manifest

In most cases, **nothing**. The aggregator handles the merge.

You DO need your own `PrivacyInfo.xcprivacy` if:

- Your app independently uses any Required Reason API (the most common: `UserDefaults` `CA92.1`, `SystemBootTime` `35F9.1`, `DiskSpace` `E174.1`).
- Your app collects user data beyond what the library does (basically anything — the lib collects nothing).
- Your app contains tracking SDKs.

Apple publishes the full Required Reason API list at [Describing data use in privacy manifests](https://developer.apple.com/documentation/bundleresources/privacy_manifest_files/describing_data_use_in_privacy_manifests).

## Verifying the Merged Manifest

After archiving, inspect the final aggregated manifest inside the IPA:

```bash
unzip -p YourApp.ipa "Payload/YourApp.app/PrivacyInfo.xcprivacy"
```

You should see entries for every Required Reason API your app and SDKs declare, including `NSPrivacyAccessedAPICategoryFileTimestamp` and `NSPrivacyAccessedAPICategoryUserDefaults` from this library.

## App Store Connect Privacy Nutrition Labels

The library does not affect your Privacy Nutrition Labels — it does not collect data and does not track users. Fill in your labels based on what your own app does, including the data you persist server-side as a result of dispatched HTTP requests.

Default headers are stored in the Keychain, encrypted at rest and on the device only. The Keychain is not a Required Reason API, so it adds no manifest entry. They are sent only to the URLs you enqueue.

> **Note:** Apple's manifest format is versioned and occasionally adds new Required Reason APIs. If your CI starts emitting "missing privacy manifest entry" warnings after an Xcode bump, audit your own app first — the library's manifest is updated in lockstep with Xcode's requirements.

## Testing Checklist

- [ ] The final IPA contains the aggregated manifest (`unzip -l YourApp.ipa | grep PrivacyInfo`).
- [ ] The aggregated manifest declares `NSPrivacyAccessedAPICategoryFileTimestamp` (`C617.1`) and `NSPrivacyAccessedAPICategoryUserDefaults` (`CA92.1`) from this library.
- [ ] If your app uses Required Reason APIs, collects data or contains tracking SDKs, its own `PrivacyInfo.xcprivacy` declares them.
- [ ] The Privacy Nutrition Labels describe the data your dispatched requests send to your servers.

## See Also

- [Production Checklist](./production-checklist.md) -- The cross-platform pre-release checklist
- [Android Permissions](./android-permissions.md) -- The Android counterpart: what the library merges into your manifest
- [Platform Comparison](./platform-comparison.md) -- Android vs iOS differences
- [iOS Background Modes](./ios-background-modes.md) -- Capabilities and task identifiers for background sync
- [iOS Native Architecture](../architecture/ios-native.md) -- The podspec, the resource bundle and the Core Data store
- [Describing data use in privacy manifests](https://developer.apple.com/documentation/bundleresources/privacy_manifest_files/describing_data_use_in_privacy_manifests) -- Apple's Required Reason API list
