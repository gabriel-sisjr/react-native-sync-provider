---
sidebar_position: 1
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

See the [iOS Background Modes](./ios-background-modes.md), [Android Permissions](./android-permissions.md) and [iOS Privacy Manifest](./privacy-manifest.md) guides for full details on each requirement.

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

See the [Background Sync](../guides/background-sync.md#cold-start-configuration), [Retry Policy](../guides/retry-policy.md), [Idempotency](../guides/idempotency.md) and [Error Handling](../guides/error-handling.md#dead-letter-flows) guides for implementation patterns.

## User Experience

- [ ] Offline / waiting-for-connection state surfaced in the UI (use `useOfflineQueue` `isWaitingForConnection`).
- [ ] In-flight indicator shown while `useSyncStatus().isSyncing` is true.
- [ ] Background sync explained in the privacy / settings screen if your app advertises it as a feature.
- [ ] Manual "Sync now" button wired to `flush()` for users who want to force a drain.

See the [useOfflineQueue](../api-reference/hooks/useOfflineQueue.md) and [useSyncStatus](../api-reference/hooks/useSyncStatus.md) reference pages for the state these items read.

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
- [ ] Tested a device reboot during a flush, with items left `IN_FLIGHT` — after the first unlock, `BootCompletedReceiver` schedules `RecoveryWorker`, which resets them and re-arms the periodic work when background sync is enabled and `foregroundOnly` is off. Pending items are not touched.
- [ ] Tested Doze mode (`adb shell dumpsys deviceidle force-idle`) — periodic work is correctly deferred and runs on the next maintenance window.
- [ ] Tested manufacturer auto-start whitelisting on Xiaomi MIUI / Huawei EMUI / Samsung OneUI if those markets are in scope.
- [ ] ProGuard / R8 enabled in release build and queue still drains (lib ships consumer rules — verify no shrink errors).

### iOS

- [ ] Tested on iOS 15.0 (minimum supported) through current iOS.
- [ ] Tested force-quit recovery — items stuck in-flight at force-quit are reset to pending on next launch. iOS does not run background work for a force-quit app until the user opens it.
- [ ] Triggered a BGTaskScheduler window on a physical device via the Xcode debugger (`e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.gabriel-sisjr.syncprovider.background"]`). See [iOS Setup](../getting-started/ios-setup.md#7-test-the-background-handler).
- [ ] Verified the Privacy Manifest is bundled in the final IPA (`unzip -l YourApp.ipa | grep PrivacyInfo`).
- [ ] Background `URLSession` uploads survive an OS-driven app suspend/resume.

See the [Platform Comparison](./platform-comparison.md) guide for behavioral differences between Android and iOS, and the [Debugging](../development/debugging.md) page for simulating offline and forcing background windows.

## Performance

- [ ] Idle CPU footprint measured with the lib enabled (should be ~0% — only event-driven work).
- [ ] Memory footprint inspected after a long offline + drain cycle (should not leak — `SyncQueueStorage` reuses the same managed context / Room database singleton).
- [ ] Battery and cellular data use disclosed to users if your app dispatches over metered links. `useAutoSync` ticks flush on `METERED` links too, so a short `intervalMs` sends over cellular while the app runs. Background windows run on metered links unless you pass `requiresUnmeteredNetwork: true`, which only Android applies.
- [ ] Core Data / Room database files inspected on a real device to confirm sane growth. History is not trimmed automatically, and every cycle adds a row, including 0/0 cycles, so call `clearSyncHistory()` on a schedule if `useAutoSync` runs for long periods.

See the [Battery Optimization](../guides/battery-optimization.md) and [Background Sync](../guides/background-sync.md) guides for the options that decide when the library uses the network, and [`clearSyncHistory`](../api-reference/functions.md#clearsynchistory) for trimming the history.

## Network Quality and Health Check (If Enabled)

The quality gate and the health check are off by default. Skip this section if your app sets neither `minNetworkQuality` nor `healthCheckUrl`.

- [ ] If `minNetworkQuality` is set, the chosen level can be reached on your users' links: without `healthCheckUrl`, `EXCELLENT` never passes and `GOOD` excludes metered links.
- [ ] The minimum is no higher than your payloads need. `POOR`, the most permissive level, blocks only `OFFLINE`, which also keeps a manual `flush()` from spending attempts offline.
- [ ] If `healthCheckUrl` is set, it answers `HEAD` with a `2xx` without authentication, and UI that reacts to `SYNC_FAILED` checks `errorCode` for `NETWORK_QUALITY_TOO_LOW` and `HEALTH_CHECK_FAILED`.
- [ ] `healthCheckUrl` is an absolute http(s) URL. `configureSync` rejects anything else with `INVALID_URL`, and an invalid `minNetworkQuality`, `healthCheckTtlMs` or `healthCheckBackoffMs` with `INVALID_PAYLOAD`.
- [ ] The endpoint can take the traffic: one `HEAD` per cycle unless a healthy result younger than `healthCheckTtlMs` (default `60000`) exists, and three `HEAD`s per latency measurement. A measurement is taken when the device moves to a network, when the URL changes, when a path or capability update finds the last one 30 s old or older (on Android, a `configureSync` call too), and, with `minNetworkQuality` set, when the gate does.
- [ ] `healthCheckBackoffMs` (default `30000`) suits your outages: every cycle is skipped until it ends, and automatic retries wait at least 1000 ms after a failed check.
- [ ] Code that calls `drainUntilEmpty()` handles `MAX_ITERATIONS` and `TIMEOUT`: a skipped flush counts as an iteration, so on a gated link the drain ends with one of them.
- [ ] Background windows a gate or a health check skips are expected: the worker or BGTask completes normally, and the window is spent.

> **Note:** With `healthCheckUrl` set, the level comes from link signals until the first measurement of a network lands, so `minNetworkQuality: EXCELLENT` also refuses the first flushes on a newly joined network. Measurements live in memory, so a process the OS starts for a background run has none yet, and its flush is usually refused the same way.

See the [Connectivity Detection](../guides/connectivity-detection.md#the-quality-gate) guide for how the gate and the health check decide.

## See Also

- [Android Permissions](./android-permissions.md) -- The permissions and the receiver merged into your manifest
- [iOS Privacy Manifest](./privacy-manifest.md) -- What the library declares, and what your app may need to add
- [Platform Comparison](./platform-comparison.md) -- Android vs iOS differences
- [iOS Background Modes](./ios-background-modes.md) -- Capabilities, task identifiers and what the OS guarantees
- [Background Sync](../guides/background-sync.md) -- Background windows, cold-start configuration and expired tokens
- [Battery Optimization](../guides/battery-optimization.md) -- What makes the library use the network, and the option that controls each
- [Crash Recovery](../guides/crash-recovery.md) -- What happens to in-flight items after a kill, a reboot or an update
- [Connectivity Detection](../guides/connectivity-detection.md) -- Network quality, the quality gate and the health check
