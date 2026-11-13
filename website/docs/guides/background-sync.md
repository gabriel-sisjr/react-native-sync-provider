---
sidebar_position: 2
title: Background Sync
description: Configure OS-scheduled background sync with BGTaskScheduler (iOS) and WorkManager (Android) — constraints, identifiers, cold-start configuration, gated windows, and platform differences.
keywords:
  - background-sync
  - bgtaskscheduler
  - workmanager
  - background-fetch
  - periodic-work
  - ios-background-modes
  - doze-mode
  - cold-start
---

# Background Sync

This guide covers how to configure background sync on iOS and Android, the limits each OS imposes, and what to expect in production.

## Overview

Background sync lets `react-native-sync-provider` flush the queue while the app is **not running**. iOS uses `BGTaskScheduler`; Android uses `WorkManager`.

The OS decides when a window runs, and each window runs one flush cycle, which dispatches up to `batchSize` items. Nothing guarantees a window at a given time. A process started for a window has no JS, so it works from the [persisted configuration](#cold-start-configuration).

## Enable / Disable

```ts
import {
  enableBackgroundSync,
  disableBackgroundSync,
  isBackgroundSyncEnabled,
} from '@gabriel-sisjr/react-native-sync-provider';

await enableBackgroundSync({
  minimumIntervalMs: 15 * 60 * 1000,
  requiresUnmeteredNetwork: true,
});

const enabled = await isBackgroundSyncEnabled(); // => true

await disableBackgroundSync();
```

Or hand the lifecycle to the optional Provider:

```tsx
<SyncProvider
  options={SYNC_OPTIONS}
  backgroundSync={{
    minimumIntervalMs: 15 * 60 * 1000,
    requiresUnmeteredNetwork: true,
  }}
>
  <Root />
</SyncProvider>
```

The Provider calls `enableBackgroundSync` on mount and `disableBackgroundSync` on unmount, **only** if the Provider was the one that enabled it.

`isBackgroundSyncEnabled()` reports intent, not OS state: it stays `true` with `foregroundOnly: true`, when no OS work is scheduled.

## `BackgroundSyncOptions`

| Field                      | Type      | Default | Platform | Description                                                                                                     |
| -------------------------- | --------- | ------- | -------- | --------------------------------------------------------------------------------------------------------------- |
| `minimumIntervalMs`        | `number`  | --      | Both     | Android: the period of the periodic work, raised to 15 minutes when lower. iOS: only the refresh task's earliest begin date, at least 60 seconds after the submit. The processing task has none. |
| `requiresCharging`         | `boolean` | `false` | Both     | Only fire when the device is plugged in. On iOS this applies to the processing task only.                       |
| `requiresUnmeteredNetwork` | `boolean` | `false` | Android  | Only fire on Wi-Fi / ethernet.                                                                                  |
| `requiresDeviceIdle`       | `boolean` | `false` | Android  | Leave it unset: `true` makes `enableBackgroundSync()` reject with `BACKGROUND_TASK_REGISTRATION_FAILED`, because WorkManager refuses the backoff policy the library sets on an idle-constrained request. iOS does not apply it. |
| `foregroundOnly`           | `boolean` | `false` | Both     | Kill-switch that schedules **no** OS background work at all. See [Foreground-only mode](#foreground-only-mode).  |
| `taskIdentifier`           | `string`  | --      | Neither  | Stored but not used yet. Both platforms use fixed identifiers.                                                  |

The options persist across relaunches, on both platforms.

## What Stops What

| Control                   | Retry timer                      | Android retry kick | Periodic worker / BGTask                    |
| ------------------------- | -------------------------------- | ------------------ | ------------------------------------------- |
| `pauseSync()`             | Stopped                          | Stopped            | Inert, even while the app is dead           |
| `strategy: MANUAL`        | Stopped                          | Stopped            | **Still runs**                              |
| `foregroundOnly: true`    | **Still runs**, it is in-process | Stopped            | Stopped                                     |
| `disableBackgroundSync()` | **Still runs**                   | Stopped            | Stopped                                     |

`MANUAL` does not stop background windows, because calling `enableBackgroundSync` is an explicit request. To stop them, use `foregroundOnly` or do not enable them. The [retry timer and kick](./retry-policy.md#who-wakes-the-queue-and-when) are covered in the retry guide.

None of these controls turns off [`useAutoSync`](../api-reference/hooks/useAutoSync.md). It never reads the strategy, so it keeps flushing under `MANUAL`, and while the engine is paused its flushes run no cycle. Pass `enabled: false` to stop it.

A pause lasts until `resumeSync()` or the next launch that reaches the library, including a JS reload. A launch clears it without emitting `RESUMED`. On iOS, React Native can start during a background launch, and if JS reaches the library there, that counts as a launch.

## `BGTaskScheduler` (iOS) {#ios-bgtaskscheduler}

The library uses two task types, each with a fixed identifier:

- **`BGAppRefreshTaskRequest`** (`com.gabriel-sisjr.syncprovider.background`) -- short-lived refresh. `enableBackgroundSync` submits it.
- **`BGProcessingTaskRequest`** (`com.gabriel-sisjr.syncprovider.processing`) -- longer-lived task. The refresh handler submits it, so the first one exists only after a refresh window has run.

Each handler re-submits before doing any work, so the chain continues from the persisted options with no JS loaded.

### Identifier Registration

The handlers register at process start through `+load`, with no JS and no `AppDelegate` code. Do not register them yourself: see [iOS Setup](../getting-started/ios-setup.md#5-forward-background-urlsession-events). Declare both identifiers in your app's `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
  <string>com.gabriel-sisjr.syncprovider.processing</string>
</array>
```

A missing identifier does not crash: that handler is skipped. `enableBackgroundSync` rejects with `BACKGROUND_TASK_REGISTRATION_FAILED` when `.background` is the one missing.

### OS-Imposed Scheduling

`minimumIntervalMs` is a **soft hint**: it only sets the refresh task's earliest begin date, at least 60 seconds after the submit, and the processing task has none. iOS uses on-device usage patterns, battery level, and network state to decide when to fire. In practice:

- New apps or rarely opened apps may go hours between fires.
- Frequently used apps fire more often.
- Low Power Mode delays fires until the device exits it.
- A force-quit app is not launched for background tasks or uploads until the user opens it again.

### Capability Requirement

**Background fetch** and **Background processing** must be enabled in Xcode -> Signing & Capabilities, and your `AppDelegate` must [forward `handleEventsForBackgroundURLSession`](../getting-started/ios-setup.md#5-forward-background-urlsession-events).

See [iOS Setup](../getting-started/ios-setup.md) for the full walkthrough.

### Background Uploads

While the app is not active, uploads go through a background `URLSession`, so the OS can finish them while the app is suspended. With `foregroundOnly`, this session is not used.

> **Warning — A relaunch can lose the outcome of an upload:** A background upload that finishes after a relaunch is not recorded. Launch recovery puts the item back to `PENDING` with its attempt spent and no cooldown, and the server may already have accepted the request. Delivery is at least once, so endpoints must be idempotent. See the [Idempotency guide](./idempotency.md).

The [Crash Recovery](./crash-recovery.md) guide covers launch recovery in full.

## `WorkManager` (Android) {#android-workmanager}

The library uses a unique `PeriodicWorkRequest` for the recurring schedule, and a unique `OneTimeWorkRequest` for the [retry kick](./retry-policy.md#who-wakes-the-queue-and-when).

### 15-Minute Floor

```ts
await enableBackgroundSync({
  minimumIntervalMs: 5 * 60 * 1000, // 5 minutes -- silently raised to 15
});
```

`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is 15 minutes. The library clamps smaller values rather than rejecting them.

### Constraints Map to `Constraints.Builder`

| `BackgroundSyncOptions`          | `Constraints`                                    |
| -------------------------------- | ------------------------------------------------ |
| `requiresCharging: true`         | `.setRequiresCharging(true)`                     |
| `requiresUnmeteredNetwork: true` | `.setRequiredNetworkType(NetworkType.UNMETERED)` |
| `requiresDeviceIdle: true`       | `.setRequiresDeviceIdle(true)`, which WorkManager refuses with the backoff policy the library sets, so `enableBackgroundSync()` rejects with `BACKGROUND_TASK_REGISTRATION_FAILED`. Leave it unset. |

When no constraints are set, the library defaults to `NetworkType.CONNECTED` so the worker only fires when the device has any network. The retry kick honors the same constraints.

### Doze Mode

Android Doze (API 23+) defers periodic work during deep idle. Expect:

- Fewer fires than `minimumIntervalMs` indicates while the device is stationary.
- Resumed fires within a maintenance window when the device moves or is plugged in.
- OEM battery optimizers (Xiaomi MIUI, Huawei EMUI, Samsung Adaptive Battery) further restrict execution.

The library does not request battery whitelisting -- that is a UX choice for the host app. For the work the library itself adds, see [Battery Optimization](./battery-optimization.md).

### Reboot Recovery

The library's `BootCompletedReceiver` is registered automatically. `LOCKED_BOOT_COMPLETED` is in its filter, but the receiver is not direct-boot aware, so a locked device does not deliver it. On `BOOT_COMPLETED`, which arrives after the first unlock, and on `MY_PACKAGE_REPLACED`:

1. The receiver enqueues a one-time `RecoveryWorker`.
2. The worker resets items left in flight back to `PENDING`, keeping their cooldowns.
3. It re-registers the periodic job from the persisted options, unless `foregroundOnly` is set.
4. It arms the next retry wake. With no JS running, that means the retry kick, so only when background sync is enabled.

It sends no request of its own. Pending items wait for the next window, kick or flush.

Users that Force Stop the app must open it once before any of this runs again -- this is an Android-wide constraint, not library-specific.

See [Crash Recovery](./crash-recovery.md) for recovery on both platforms.

## Platform Comparison

| Concern                 | Android (`WorkManager`)                 | iOS (`BGTaskScheduler`)                                |
| ----------------------- | --------------------------------------- | ------------------------------------------------------ |
| Min interval            | Hard 15-minute floor                    | Earliest start of the refresh task, at least 60 seconds; none on the processing task |
| Charging constraint     | Supported                               | Processing task only                                   |
| Unmetered constraint    | Supported                               | Not applied                                            |
| Idle constraint         | Makes `enableBackgroundSync()` reject   | Not applied                                            |
| Network required        | Always                                  | Always, on the processing task                         |
| Retry kick              | Opt-in, through `enableBackgroundSync`  | None                                                   |
| Identifier registration | Implicit (unique work names)            | Required in `Info.plist`, both identifiers             |
| Reboot recovery         | `BootCompletedReceiver` reschedules     | No boot hook: recovery runs when the app or a BGTask next starts |
| OS deferral             | Doze-aware, OEM-restricted              | Aggressive (battery/usage based)                       |
| Capability flag         | None (auto via merged manifest)         | Background Modes (`fetch` + `processing`)              |

For the platform differences across the whole library, see [Platform Comparison](../production/platform-comparison.md).

## Cold-Start Configuration

**Added in v0.2.** A worker or BGTask can run in a process that never loaded JS. `configureSync` persists its options, so that process honors `maxAttempts`, `batchSize`, `requestTimeoutMs` and the rest, including `minNetworkQuality` and the health-check options, so a cold worker applies the [quality gate and the health check](#when-the-gate-or-the-health-check-skips-a-window) too. No new dependency is involved.

A stored value that cannot be used, such as an unusable `healthCheckUrl` or a negative duration, can only come from a damaged record, because `configureSync` rejects them. It loads as unset instead of breaking the engine.

| Data                                     | Android                                                                            | iOS                                                                                |
| ---------------------------------------- | ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| Every option except `defaultHeaders`     | SharedPreferences                                                                  | UserDefaults suite `com.gabriel-sisjr.syncprovider`                                |
| `defaultHeaders`                         | AES-256-GCM under a non-exportable Android Keystore key, ciphertext in SharedPreferences | Keychain, `AfterFirstUnlockThisDeviceOnly`, not synchronized                 |

> **Warning — Only `defaultHeaders` are encrypted:** Per-item `headers` and `body` are stored in plain text in the queue. Put credentials in `defaultHeaders`.

### Tokens and Logout

`configureSync` replaces the whole configuration, so:

- **Call it when the token changes.** The library does not refresh tokens, and a process with no JS cannot renew one.
- **Pass `defaultHeaders` on every call** that should keep them, including through `<SyncProvider options>`.
- **Call it without `defaultHeaders` on logout.** That deletes the stored copy.

```ts
// At launch, and whenever the token changes.
await configureSync({
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy,
  defaultHeaders: { Authorization: `Bearer ${token}` },
});

// On logout.
await configureSync({ strategy: SyncStrategy.AUTOMATIC, retryPolicy });
```

### When a Stored Token Expires

A token read back from storage can be stale, and only the app can renew it. So in a process that has not called `configureSync`, a 401 or 403 does not fail the item. It stays `PENDING`, no attempt is spent, the next try waits at least 60 seconds, and the cycle stops, so an expired token costs one request per wake. You see `ITEM_RETRYING` with `UNAUTHORIZED`, if anything is listening.

In a process that has called `configureSync`, the app owns the token, and a 401 stays terminal.

Every retry wake in the process waits for that hold to end, on both platforms, even when other items are already due: the end of a cycle, `resumeSync()`, a JS attach or reload and the recovery re-arm, for both the retry timer and the Android retry kick. Every `configureSync` call clears the hold.

Two cases can still send a request with the rejected token:

- **A process restart.** The hold lives in memory, so an OS kill, a reboot or an app update drops it. The rejected item keeps its own `nextRetryAt`, but another due item can wake the queue in the new process. That costs at most one request, whose rejection sets a new hold.
- **A background window.** The periodic worker and the BGTasks are not retry wakes and do not wait for the hold, so a window that opens during one can cost one request.

### When Stored Headers Cannot Be Read

If headers were configured but cannot be read, nothing is dispatched: sending without them would fail every item. Items stay `PENDING`, the worker or task reports success, and the library tries again on each flush. `flush()` resolves with 0/0. This happens when:

- The Keystore key was invalidated (Android).
- The device has not been unlocked since boot (iOS).
- A backup was restored onto another device. With `allowBackup` on Android, the ciphertext comes back but the key does not.

Calling `configureSync` ends the wait in every case. While headers are unreadable, `getSyncConfig()` returns none, so `setConfig({ ...config })` deletes them: pass `defaultHeaders` from your app's session instead. See [Troubleshooting](../troubleshooting.md#symptom-items-stay-pending-and-flush-returns-00).

### What Needs a Running App

A process with no JS-facing hybrid, such as a worker, a boot broadcast or an iOS background launch where JS never reaches the library, runs no retry timer: it would dispatch outside the constraints you set. Android relies on the kick there. Events are not delivered either, since nothing is listening.

With `healthCheckUrl` set, such a process can still send requests of its own. On Android, a `SyncWorker` run, periodic or retry kick, starts a latency measurement only when `minNetworkQuality` is above `OFFLINE`, `healthCheckUrl` is set, and the last measurement of that network is missing or 30 s old or older. It starts 1 s after the gate check and runs alongside the flush, which does not wait for it. In a new process the gate decides from the link signals (`GOOD` unmetered, `MODERATE` metered, at most `POOR` unvalidated), so `EXCELLENT` never passes there. The worker also sends the health check's one `HEAD` before the cycle, unless a `2xx` younger than `healthCheckTtlMs` exists in that process.

## Foreground-Only Mode

**Added in v0.2.** Set `foregroundOnly: true` to turn OS-scheduled background dispatch **off** entirely while keeping the rest of the library working:

```ts
await enableBackgroundSync({
  minimumIntervalMs: 15 * 60 * 1000,
  foregroundOnly: true, // never schedule OS background work
});
```

When `true`:

- **iOS** does not `submit` any `BGTaskScheduler` request, cancels any previously submitted ones, and makes a task that still fires complete inert. Handlers stay registered, as iOS requires. The background `URLSession` is not used.
- **Android** cancels and never enqueues the library's `WorkManager` jobs: the periodic flush and the [retry kick](./retry-policy.md#who-wakes-the-queue-and-when). A worker that still fires stays inert.

Sync still progresses while the app is alive: manual [`flush()`](../api-reference/functions.md#flush), [`drainUntilEmpty()`](../api-reference/functions.md#drainuntilempty), [`useAutoSync`](../api-reference/hooks/useAutoSync.md), and the in-process retry timer, which this flag does not stop. Persisted retry cooldowns are still honored.

The flag persists across relaunches.

> **Note — Recovery is never suppressed:** `foregroundOnly` gates **scheduled background dispatch only**. Data-integrity recovery — launch recovery and the Android boot-completed recovery pass — still runs, so items left mid-flight by an OS kill are always reconciled. The flag is not a switch for crash/kill recovery.

It can be flipped at runtime by calling `enableBackgroundSync` again with a different value. Typical uses: QA / development builds (where background dispatch interferes with logs and breakpoints) and corporate policies that forbid background networking.

## Listening for Background Fires

```tsx
import { useSyncEvents } from '@gabriel-sisjr/react-native-sync-provider';

useSyncEvents({
  types: ['BACKGROUND_SYNC_STARTED', 'BACKGROUND_SYNC_COMPLETED'],
  onEvent: (e) => console.log(e.type, new Date(e.timestamp).toISOString()),
});
```

These events reach a listener only if a JS engine is running at the time of the OS callback. If the OS delivers a fire with no JS loaded, the queue still flushes, but you will not see the event. On Android, the periodic worker emits them and the retry kick does not.

Neither event carries `errorCode`, on either platform. The codes go elsewhere:

- **A cycle that dies** emits `SYNC_FAILED` with its code. On iOS, `BACKGROUND_SYNC_COMPLETED` follows with `metadata.ok: "false"`. On Android, the periodic worker then emits no `BACKGROUND_SYNC_COMPLETED`.
- **A cycle that ends with permanent item failures** emits `SYNC_FAILED` without a code, and each item's code is on its `ITEM_FAILED`.
- **A skipped window** emits `SYNC_FAILED` with `NETWORK_QUALITY_TOO_LOW` or `HEALTH_CHECK_FAILED`, then `BACKGROUND_SYNC_COMPLETED`. See [When the Gate or the Health Check Skips a Window](#when-the-gate-or-the-health-check-skips-a-window).

On iOS, `metadata.kind` is `"refresh"` or `"processing"` on every `BACKGROUND_SYNC_COMPLETED` and on the processing task's `BACKGROUND_SYNC_STARTED`, and `metadata.ok` is set only when the flush throws. Android sends base fields only.

## When the Gate or the Health Check Skips a Window {#when-the-gate-or-the-health-check-skips-a-window}

A background window runs the same [checks as any flush](../api-reference/functions.md#checks-before-a-cycle). With [`minNetworkQuality`](./connectivity-detection.md#the-quality-gate) or [`healthCheckUrl`](./connectivity-detection.md#health-check) set, a window can therefore run no cycle. It spends no attempt and changes no item, and the skipped flush emits `SYNC_FAILED` with `NETWORK_QUALITY_TOO_LOW` or `HEALTH_CHECK_FAILED`, which a listener receives only if JS is running.

| Window | Below `minNetworkQuality` | Health check failed, or its backoff running |
| ------ | ------------------------- | ------------------------------------------- |
| Android periodic worker | Returns `Result.success()`. The next period tries again. | Returns `Result.success()`, and arms the [retry kick](./retry-policy.md#who-wakes-the-queue-and-when) for the soonest cooldown, no earlier than the end of the backoff. The next period tries again. |
| Android retry kick | Returns `Result.success()` and re-arms itself on the held-wake backoff: 30 s, doubling up to 15 minutes. The streak survives process death. | Returns `Result.success()` and re-arms no earlier than the end of the backoff, and at least 1000 ms after the failed check. |
| iOS BGTask | The flush returns normally, `BACKGROUND_SYNC_COMPLETED` fires with `metadata.kind` only, and the task completes successfully. The next attempt is the next window the OS grants. | The same as below the minimum. |

A skipped worker never returns `Result.retry()`, so WorkManager's own backoff does not compete with the gate's. On iOS, a gated flush that finds the path not reported yet waits up to 1.5 s for the first update, then grades the link `OFFLINE`.

On iOS, a skipped window is not a failed one: its `BACKGROUND_SYNC_COMPLETED` carries neither `errorCode` nor `metadata.ok: "false"`, and the next request was already scheduled at the top of the handler. Read the reason from the cycle's `SYNC_FAILED`.

> **Tip — Keep the gate and the Android constraints consistent:** On Android, `minNetworkQuality` does not replace `requiresUnmeteredNetwork`. WorkManager checks its constraints before it starts a worker, while the gate runs inside the worker, after the window was granted, so a window the gate skips is spent. If you gate on `GOOD` without `healthCheckUrl`, which in practice means unmetered links, also set `requiresUnmeteredNetwork: true`.

## When Sync May Be Deferred

| Cause                        | Android                               | iOS                   |
| ---------------------------- | ------------------------------------- | --------------------- |
| Low battery / Low Power Mode | Defers fires                          | Defers fires          |
| Doze mode                    | Defers periodic work                  | n/a                   |
| OEM battery optimizer        | Often defers heavily                  | n/a                   |
| User force-quit / Force Stop | Until the app is opened               | Until the app is opened |
| App not opened recently      | WorkManager respects Adaptive Battery | iOS reduces frequency |
| Network constraint not met   | Skips this fire                       | Skips this fire       |
| Below `minNetworkQuality`, or health check failing | Window runs no cycle | Window runs no cycle |

For predictable cadence in development, use `useAutoSync({ enabled: true, flushOnReconnect: true, flushOnAppForeground: true })` -- it triggers a JS-side flush on the events you care about, independent of OS scheduling.

## Best Practices

1. **Do not depend on a cadence.** The OS decides when a window runs: `minimumIntervalMs` only sets the earliest start of the iOS refresh task, and has a 15-minute floor on Android. For predictable flushes while the app runs, mount [`useAutoSync`](../api-reference/hooks/useAutoSync.md).

2. **Call `configureSync` at launch and whenever the token changes.** A window runs from the persisted configuration, so pass `defaultHeaders` on every call that should keep them. See [Tokens and Logout](#tokens-and-logout).

3. **Turn background dispatch off with `foregroundOnly`, not `MANUAL`.** `MANUAL` does not stop background windows. See [What Stops What](#what-stops-what).

4. **Keep the gate and the Android constraints consistent.** If you gate on `GOOD` without `healthCheckUrl`, also set `requiresUnmeteredNetwork: true`, so WorkManager does not start windows the gate then skips.

5. **Make endpoints idempotent.** A relaunch can lose the outcome of an iOS background upload, and the item is then sent again. See the [Idempotency guide](./idempotency.md).

6. **Test on a physical device.** The iOS Simulator rejects `enableBackgroundSync`. Use the LLDB command in [iOS Setup](../getting-started/ios-setup.md#7-test-the-background-handler).

## Troubleshooting

Common background sync problems. For the full list, see [Troubleshooting](../troubleshooting.md).

### Background Sync Never Fires (iOS)

1. Declare both identifiers in `BGTaskSchedulerPermittedIdentifiers`, and enable **Background fetch** and **Background processing**.
2. Test on a physical device: the Simulator does not accept `BGTaskScheduler` requests.
3. Check that the user did not force-quit the app. iOS grants it no window until the user opens it again.
4. Allow for the OS: nothing guarantees when a window runs.

See [`BGTaskScheduler` never fires](../troubleshooting.md#symptom-bgtaskscheduler-never-fires) in Troubleshooting.

### Periodic Work Runs Late or Not at All (Android)

1. Expect at least 15 minutes between periodic runs, and longer under Doze, App Standby Buckets or an OEM battery manager.
2. Check that the user did not Force Stop the app: background work waits until the user opens it again.
3. Check the constraints. Every window needs a network, and with `requiresUnmeteredNetwork` or `requiresCharging` it also waits until those are met. Leave `requiresDeviceIdle` unset: `true` makes `enableBackgroundSync()` reject.

See [WorkManager flushes deferred for hours during normal phone use](../troubleshooting.md#symptom-workmanager-flushes-deferred-for-hours-during-normal-phone-use) in Troubleshooting.

### A Window Runs but Sends Nothing

1. If JS is running, read the cycle's `SYNC_FAILED`: `NETWORK_QUALITY_TOO_LOW` or `HEALTH_CHECK_FAILED` means the gate or the health check skipped it. See [When the Gate or the Health Check Skips a Window](#when-the-gate-or-the-health-check-skips-a-window).
2. Check that the engine is not paused: workers and BGTasks stay inert while a pause is in force.
3. Check that stored headers can be read. See [When Stored Headers Cannot Be Read](#when-stored-headers-cannot-be-read).

See [Items stay pending and `flush()` returns 0/0](../troubleshooting.md#symptom-items-stay-pending-and-flush-returns-00) in Troubleshooting.

## Next Steps

- [Connectivity Detection](./connectivity-detection.md) -- Trigger flushes on reconnect
- [Crash Recovery](./crash-recovery.md) -- How items left in flight come back after a kill or a reboot
- [Battery Optimization](./battery-optimization.md) -- What wakes the device and uses the network, and the option that controls each
- [iOS Setup](../getting-started/ios-setup.md) -- Background Modes + Info.plist
- [Android Setup](../getting-started/android-setup.md) -- Permissions + Doze
