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
- [ ] iOS `Info.plist` contains `BGTaskSchedulerPermittedIdentifiers` with both fixed identifiers: `com.gabriel-sisjr.syncprovider.background` and `com.gabriel-sisjr.syncprovider.processing`.
- [ ] iOS `AppDelegate` forwards `handleEventsForBackgroundURLSession` to `HybridSyncProvider.handleBackgroundURLSessionEvents`, and does **not** call `BGTaskScheduler.shared.register` for those identifiers.
- [ ] iOS `PrivacyInfo.xcprivacy` present at the app level (the lib ships its own; if your app independently uses tracked APIs you'll need your own manifest too).
- [ ] App Store Connect Privacy Nutrition Labels reviewed (this lib does not collect data and does not track users).
- [ ] Google Play Console Data Safety form reviewed.

See [iOS Background Modes](./ios-background-modes.md), [Android Permissions](./android-permissions.md), and [Privacy Manifest](./privacy-manifest.md).

## Functionality

- [ ] Endpoint base URL points to the production environment (not a tunnel / localhost).
- [ ] `SyncOptions.defaultHeaders` set to whatever your backend requires (auth, app version, device id). Credentials go here, not in per-item `headers`, which are stored in plain text.
- [ ] `configureSync` runs at launch and whenever the token changes, and without `defaultHeaders` on logout. A process with no JS cannot renew a token.
- [ ] `RetryPolicy.retryOnStatusCodes` matches your backend's transient-error contract.
- [ ] `RetryPolicy.maxAttempts` is high enough to ride out a typical outage but low enough to surface terminal failures within a useful window.
- [ ] `SyncOptions.maxQueueSize` reviewed for devices that stay offline for weeks (the default is `10000`).
- [ ] `enableBackgroundSync` called once at app launch with the right `BackgroundSyncOptions` (`requiresUnmeteredNetwork`, `requiresCharging`).
- [ ] `useAutoSync` (or equivalent) mounted. Nothing dispatches a new item on its own, under any strategy.
- [ ] Endpoints are idempotent, with a stable `Idempotency-Key` in `headers`. Delivery is at least once.
- [ ] Failures are observed through `ITEM_FAILED` events, which work on both platforms. `SyncResult.errors` is always empty on iOS.
- [ ] Failure cases handled with appropriate UI feedback (`UNAUTHORIZED` → re-auth flow, `MAX_ATTEMPTS_EXCEEDED` → notify user).
- [ ] Dead letters are handled: replayed with `replayDeadLetter` once the cause is fixed, or discarded with `discardDeadLetter`. The dead-letter queue is unbounded and keeps each request's headers and body.
- [ ] `clearQueue()` runs on logout, before `configureSync` drops the credentials, so the dead letters are purged along with the queue. It does not need to wait for a running cycle.
- [ ] If `minNetworkQuality` is set, the chosen level can be reached on your users' links: without `healthCheckUrl`, `EXCELLENT` never passes and `GOOD` excludes metered links.
- [ ] If `healthCheckUrl` is set, it answers `HEAD` with a `2xx` without authentication, and UI that reacts to `SYNC_FAILED` checks `errorCode` for `NETWORK_QUALITY_TOO_LOW` and `HEALTH_CHECK_FAILED`.

## User Experience

- [ ] Offline / waiting-for-connection state surfaced in the UI (use `useOfflineQueue` `isWaitingForConnection`).
- [ ] In-flight indicator shown while `useSyncStatus().isSyncing` is true.
- [ ] Background sync explained in the privacy / settings screen if your app advertises it as a feature.
- [ ] Manual "Sync now" button wired to `flush()` for users who want to force a drain.

## Testing

### Cross-Platform

- [ ] Tested on real devices (not emulator/simulator alone).
- [ ] Tested airplane-mode round trip (enqueue → airplane on → kill app → reopen → airplane off → observe drain). If `useAutoSync` is mounted, keep it enabled through the offline leg, check that attempts stay unchanged, and confirm that its reconnect trigger starts the drain.
- [ ] Tested an expired token: a worker with a rejected stored token keeps items `PENDING`, and they go out after the app calls `configureSync`.
- [ ] Tested logout: `configureSync` without `defaultHeaders` removes the stored credential.
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

- [ ] Tested on iOS 15.0 (minimum supported) through current iOS.
- [ ] Tested force-quit recovery — items stuck in-flight at force-quit are reset to pending on next launch. iOS does not run background work for a force-quit app until the user opens it.
- [ ] Triggered a BGTaskScheduler window on a physical device via the Xcode debugger (`e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.gabriel-sisjr.syncprovider.background"]`). See [iOS Setup](../getting-started/ios-setup.md#7-test-the-background-handler).
- [ ] Verified the Privacy Manifest is bundled in the final IPA (`unzip -l YourApp.ipa | grep PrivacyInfo`).
- [ ] Background `URLSession` uploads survive an OS-driven app suspend/resume.

## Performance

- [ ] Idle CPU footprint measured with the lib enabled (should be ~0% — only event-driven work).
- [ ] Memory footprint inspected after a long offline + drain cycle (should not leak — `SyncQueueStorage` reuses the same managed context / Room database singleton).
- [ ] Battery impact disclosed to users if your app keeps `requiresUnmeteredNetwork=false` AND a high-frequency `intervalMs` (the combination dispatches over cellular periodically).
- [ ] Core Data / Room database files inspected on a real device to confirm sane growth. History is not trimmed automatically, and every cycle adds a row, including 0/0 cycles, so call `clearSyncHistory()` on a schedule if `useAutoSync` runs for long periods.
