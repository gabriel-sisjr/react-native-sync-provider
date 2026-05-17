---
sidebar_position: 5
title: Production Checklist
description: Cross-platform pre-release checklist for apps using @gabriel-sisjr/react-native-sync-provider — permissions, compliance, functionality, UX, testing, and performance.
keywords:
  - production checklist
  - release readiness
  - testing
  - permissions
  - compliance
  - background sync
  - react-native
  - ios
  - android
---

# Production Checklist

Use this checklist before releasing an app that uses `@gabriel-sisjr/react-native-sync-provider`. It consolidates the platform-specific requirements into a single cross-platform reference.

## Permissions and Compliance

- [ ] Android `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` permissions present (auto-merged by the lib — verify your manifest doesn't override them).
- [ ] iOS `Background fetch` and `Background processing` capabilities enabled on the app target.
- [ ] iOS `Info.plist` contains `BGTaskSchedulerPermittedIdentifiers` with the identifier you configured (default `com.gabriel-sisjr.syncprovider.background`).
- [ ] iOS `PrivacyInfo.xcprivacy` present at the app level (the lib ships its own; if your app independently uses tracked APIs you'll need your own manifest too).
- [ ] App Store Connect Privacy Nutrition Labels reviewed (this lib does not collect data and does not track users).
- [ ] Google Play Console Data Safety form reviewed.

See [iOS Background Modes](./ios-background-modes.md), [Android Permissions](./android-permissions.md), and [Privacy Manifest](./privacy-manifest.md).

## Functionality

- [ ] Endpoint base URL points to the production environment (not a tunnel / localhost).
- [ ] `SyncOptions.defaultHeaders` set to whatever your backend requires (auth, app version, device id).
- [ ] `RetryPolicy.retryOnStatusCodes` matches your backend's transient-error contract.
- [ ] `RetryPolicy.maxAttempts` is high enough to ride out a typical outage but low enough to surface terminal failures within a useful window.
- [ ] `SyncOptions.maxQueueSize` configured so the queue can't grow without bound on devices that stay offline for weeks.
- [ ] `enableBackgroundSync` called once at app launch with the right `BackgroundSyncOptions` (`requiresUnmeteredNetwork`, `requiresCharging`).
- [ ] `useAutoSync` (or equivalent) wired so the queue drains when connectivity returns.
- [ ] `flush()` failures are observed (`SyncResult.errors` map read, not just the thrown exception).
- [ ] `SyncError` cases handled with appropriate UI feedback (`UNAUTHORIZED` → re-auth flow, `MAX_ATTEMPTS_EXCEEDED` → notify user / dead-letter UI).

## User Experience

- [ ] Offline / waiting-for-connection state surfaced in the UI (use `useOfflineQueue` `isWaitingForConnection`).
- [ ] In-flight indicator shown while `useSyncStatus().isSyncing` is true.
- [ ] Background sync explained in the privacy / settings screen if your app advertises it as a feature.
- [ ] Manual "Sync now" button wired to `flush()` for users who want to force a drain.

## Testing

### Cross-Platform

- [ ] Tested on real devices (not emulator/simulator alone).
- [ ] Tested airplane-mode round trip (enqueue → airplane on → kill app → reopen → airplane off → observe drain).
- [ ] Tested batch enqueue (`enqueueBatch` with > 100 items) and confirmed atomic persistence + ordered drain.
- [ ] Tested `flush()` mid-flight with intermittent connectivity (toggle Wi-Fi several times during a flush).
- [ ] Verified `useAutoSync` does not double-flush when both periodic timer and `CONNECTION_CHANGED` fire close together.

### Android

- [ ] Tested on Android 7 (API 24) through current target SDK 36.
- [ ] Tested device reboot during pending queue — `BootCompletedReceiver` re-arms periodic work and `RecoveryWorker` resets in-flight items.
- [ ] Tested Doze mode (`adb shell dumpsys deviceidle force-idle`) — periodic work is correctly deferred and runs on the next maintenance window.
- [ ] Tested manufacturer auto-start whitelisting on Xiaomi MIUI / Huawei EMUI / Samsung OneUI if those markets are in scope.
- [ ] ProGuard / R8 enabled in release build and queue still drains (lib ships consumer rules — verify no shrink errors).

### iOS

- [ ] Tested on iOS 13.0 (minimum supported) through current iOS.
- [ ] Tested force-quit recovery — items stuck in-flight at force-quit are reset to pending on next launch.
- [ ] Triggered a BGTaskScheduler window via the Xcode debugger (`e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.gabriel-sisjr.syncprovider.background"]`).
- [ ] Verified the Privacy Manifest is bundled in the final IPA (`unzip -l YourApp.ipa | grep PrivacyInfo`).
- [ ] Background `URLSession` uploads survive an OS-driven app suspend/resume.

## Performance

- [ ] Idle CPU footprint measured with the lib enabled (should be ~0% — only event-driven work).
- [ ] Memory footprint inspected after a long offline + drain cycle (should not leak — `SyncQueueStorage` reuses the same managed context / Room database singleton).
- [ ] Battery impact disclosed to users if your app keeps `requiresUnmeteredNetwork=false` AND a high-frequency `intervalMs` (the combination dispatches over cellular periodically).
- [ ] Core Data / Room database files inspected on a real device to confirm sane growth (`SyncResultEntity` retention bounded by your `clearSyncHistory()` policy).
