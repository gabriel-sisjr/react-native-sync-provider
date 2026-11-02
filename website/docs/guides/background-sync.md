---
sidebar_position: 2
title: Background Sync
description: Configure OS-scheduled background sync with BGTaskScheduler (iOS) and WorkManager (Android) — constraints, identifiers, cold-start configuration, and platform differences.
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

Background sync lets `react-native-sync-provider` flush the queue while the app is **not running**. iOS uses `BGTaskScheduler`; Android uses `WorkManager`. This guide covers configuration, OS limits, and what to expect in production.

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

| Field                      | Type      | Default | Description                                                                                                     |
| -------------------------- | --------- | ------- | --------------------------------------------------------------------------------------------------------------- |
| `minimumIntervalMs`        | `number`  | --      | Minimum gap between background fires. Soft hint on iOS with a floor of 60 seconds, hard floor of 15 minutes on Android. |
| `requiresCharging`         | `boolean` | `false` | Only fire when the device is plugged in. On iOS this applies to the processing task only.                       |
| `requiresUnmeteredNetwork` | `boolean` | `false` | Only fire on Wi-Fi / ethernet. Android only.                                                                    |
| `requiresDeviceIdle`       | `boolean` | `false` | Only fire when the device is idle. Android only.                                                                |
| `foregroundOnly`           | `boolean` | `false` | Kill-switch that schedules **no** OS background work at all. See [Foreground-only mode](#foreground-only-mode).  |
| `taskIdentifier`           | `string`  | --      | Stored but not used yet. Both platforms use fixed identifiers.                                                  |

The options persist across relaunches, on both platforms.

## What stops what

| Control                   | Retry timer                      | Android retry kick | Periodic worker / BGTask                    |
| ------------------------- | -------------------------------- | ------------------ | ------------------------------------------- |
| `pauseSync()`             | Stopped                          | Stopped            | Inert, even while the app is dead           |
| `strategy: MANUAL`        | Stopped                          | Stopped            | **Still runs**                              |
| `foregroundOnly: true`    | **Still runs**, it is in-process | Stopped            | Stopped                                     |
| `disableBackgroundSync()` | **Still runs**                   | Stopped            | Stopped                                     |

`MANUAL` does not stop background windows, because calling `enableBackgroundSync` is an explicit request. To stop them, use `foregroundOnly` or do not enable them. The [retry timer and kick](./retry-policy.md#who-wakes-the-queue-and-when) are covered in the retry guide.

A pause lasts until `resumeSync()` or the next launch that reaches the library, including a JS reload. A launch clears it without emitting `RESUMED`. On iOS, React Native can start during a background launch, and if JS reaches the library there, that counts as a launch.

## iOS: `BGTaskScheduler`

The library uses two task types, each with a fixed identifier:

- **`BGAppRefreshTaskRequest`** (`com.gabriel-sisjr.syncprovider.background`) -- short-lived refresh. `enableBackgroundSync` submits it.
- **`BGProcessingTaskRequest`** (`com.gabriel-sisjr.syncprovider.processing`) -- longer-lived task. The refresh handler submits it, so the first one exists only after a refresh window has run.

Each handler re-submits before doing any work, so the chain continues from the persisted options with no JS loaded.

### Identifier registration

The handlers register at process start through `+load`, with no JS and no `AppDelegate` code. Do not register them yourself: see [iOS Setup](../getting-started/ios-setup.md#5-forward-background-urlsession-events). Declare both identifiers in your app's `Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
  <string>com.gabriel-sisjr.syncprovider.processing</string>
</array>
```

A missing identifier does not crash: that handler is skipped. `enableBackgroundSync` rejects with `BACKGROUND_TASK_REGISTRATION_FAILED` when `.background` is the one missing.

### OS-imposed scheduling

`minimumIntervalMs` is a **soft hint**. iOS uses on-device usage patterns, battery level, and network state to decide when to fire. In practice:

- New apps or rarely opened apps may go hours between fires.
- Frequently used apps fire more often.
- Low Power Mode delays fires until the device exits it.
- A force-quit app is not launched for background tasks or uploads until the user opens it again.

### Capability requirement

**Background fetch** and **Background processing** must be enabled in Xcode -> Signing & Capabilities, and your `AppDelegate` must [forward `handleEventsForBackgroundURLSession`](../getting-started/ios-setup.md#5-forward-background-urlsession-events).

See [iOS Setup](../getting-started/ios-setup.md) for the full walkthrough.

### Background uploads

While the app is not active, uploads go through a background `URLSession`, so the OS can finish them while the app is suspended. With `foregroundOnly`, this session is not used.

:::warning A relaunch can lose the outcome of an upload
A background upload that finishes after a relaunch is not recorded. Launch recovery puts the item back to `PENDING` with its attempt spent and no cooldown, and the server may already have accepted the request. Delivery is at least once, so endpoints must be idempotent. See the [Idempotency guide](./idempotency.md).
:::

## Android: `WorkManager`

The library uses a unique `PeriodicWorkRequest` for the recurring schedule, and a unique `OneTimeWorkRequest` for the [retry kick](./retry-policy.md#who-wakes-the-queue-and-when).

### 15-minute floor

```ts
await enableBackgroundSync({
  minimumIntervalMs: 5 * 60 * 1000, // 5 minutes -- silently raised to 15
});
```

`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is 15 minutes. The library clamps smaller values rather than rejecting them.

### Constraints map to `Constraints.Builder`

| `BackgroundSyncOptions`          | `Constraints`                                    |
| -------------------------------- | ------------------------------------------------ |
| `requiresCharging: true`         | `.setRequiresCharging(true)`                     |
| `requiresUnmeteredNetwork: true` | `.setRequiredNetworkType(NetworkType.UNMETERED)` |
| `requiresDeviceIdle: true`       | `.setRequiresDeviceIdle(true)`                   |

When no constraints are set, the library defaults to `NetworkType.CONNECTED` so the worker only fires when the device has any network. The retry kick honors the same constraints.

### Doze mode

Android Doze (API 23+) defers periodic work during deep idle. Expect:

- Fewer fires than `minimumIntervalMs` indicates while the device is stationary.
- Resumed fires within a maintenance window when the device moves or is plugged in.
- OEM battery optimizers (Xiaomi MIUI, Huawei EMUI, Samsung Adaptive Battery) further restrict execution.

The library does not request battery whitelisting -- that is a UX choice for the host app.

### Reboot recovery

The library's `BootCompletedReceiver` is registered automatically. On `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`:

1. The receiver enqueues a one-time `RecoveryWorker`.
2. The worker resets items left in flight back to `PENDING`, keeping their cooldowns.
3. It re-registers the periodic job from the persisted options, unless `foregroundOnly` is set.
4. It arms the next retry wake. With no JS running, that means the retry kick, so only when background sync is enabled.

It sends no request of its own. Pending items wait for the next window, kick or flush.

Users that Force Stop the app must open it once before any of this runs again -- this is an Android-wide constraint, not library-specific.

## Platform Comparison

| Concern                 | iOS (`BGTaskScheduler`)                                | Android (`WorkManager`)                 |
| ----------------------- | ------------------------------------------------------ | --------------------------------------- |
| Min interval            | Soft hint, floor of 60 seconds                         | Hard 15-minute floor                    |
| Charging constraint     | Processing task only                                   | Supported                               |
| Unmetered constraint    | Not applied                                            | Supported                               |
| Idle constraint         | Not applied                                            | Supported                               |
| Network required        | Always, on the processing task                         | Always                                  |
| Retry kick              | None                                                   | Opt-in, through `enableBackgroundSync`  |
| Identifier registration | Required in `Info.plist`, both identifiers             | Implicit (unique work names)            |
| Reboot recovery         | No boot hook: recovery runs when the app or a BGTask next starts | `BootCompletedReceiver` reschedules |
| OS deferral             | Aggressive (battery/usage based)                       | Doze-aware, OEM-restricted              |
| Capability flag         | Background Modes (`fetch` + `processing`)              | None (auto via merged manifest)         |

## Cold-Start Configuration

**Added in v0.2.** A worker or BGTask can run in a process that never loaded JS. `configureSync` persists its options, so that process honors `maxAttempts`, `batchSize`, `requestTimeoutMs` and the rest. No new dependency is involved.

| Data                                     | Android                                                                            | iOS                                                                                |
| ---------------------------------------- | ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| Every option except `defaultHeaders`     | SharedPreferences                                                                  | UserDefaults suite `com.gabriel-sisjr.syncprovider`                                |
| `defaultHeaders`                         | AES-256-GCM under a non-exportable Android Keystore key, ciphertext in SharedPreferences | Keychain, `AfterFirstUnlockThisDeviceOnly`, not synchronized                 |

:::warning Only `defaultHeaders` are encrypted
Per-item `headers` and `body` are stored in plain text in the queue. Put credentials in `defaultHeaders`.
:::

### Tokens and logout

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

### When a stored token expires

A token read back from storage can be stale, and only the app can renew it. So in a process that has not called `configureSync`, a 401 or 403 does not fail the item. It stays `PENDING`, no attempt is spent, the next try waits at least 60 seconds, and the cycle stops, so an expired token costs one request per wake. You see `ITEM_RETRYING` with `UNAUTHORIZED`, if anything is listening.

In a process that has called `configureSync`, the app owns the token, and a 401 stays terminal.

`resumeSync()` during such a hold differs by platform: iOS waits for the hold to end, Android does not.

### When stored headers cannot be read

If headers were configured but cannot be read, nothing is dispatched: sending without them would fail every item. Items stay `PENDING`, the worker or task reports success, and the library tries again on each flush. `flush()` resolves with 0/0. This happens when:

- The Keystore key was invalidated (Android).
- The device has not been unlocked since boot (iOS).
- A backup was restored onto another device. With `allowBackup` on Android, the ciphertext comes back but the key does not.

Calling `configureSync` ends the wait in every case. While headers are unreadable, `getSyncConfig()` returns none, so `setConfig({ ...config })` deletes them. See [Troubleshooting](../troubleshooting.md#symptom-items-stay-pending-and-flush-returns-00).

### What needs a running app

A process with no JS-facing hybrid, such as a worker, a boot broadcast or an iOS background launch where JS never reaches the library, runs no retry timer: it would dispatch outside the constraints you set. Android relies on the kick there. Events are not delivered either, since nothing is listening.

## Foreground-only mode

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

:::note Recovery is never suppressed
`foregroundOnly` gates **scheduled background dispatch only**. Data-integrity recovery — launch recovery and the Android boot-completed recovery pass — still runs, so items left mid-flight by an OS kill are always reconciled. The flag is not a switch for crash/kill recovery.
:::

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

## When Sync May Be Deferred

| Cause                        | iOS                   | Android                               |
| ---------------------------- | --------------------- | ------------------------------------- |
| Low battery / Low Power Mode | Defers fires          | Defers fires                          |
| Doze mode                    | n/a                   | Defers periodic work                  |
| OEM battery optimizer        | n/a                   | Often defers heavily                  |
| User force-quit / Force Stop | Until the app is opened | Until the app is opened             |
| App not opened recently      | iOS reduces frequency | WorkManager respects Adaptive Battery |
| Network constraint not met   | Skips this fire       | Skips this fire                       |

For predictable cadence in development, use `useAutoSync({ enabled: true, flushOnReconnect: true, flushOnAppForeground: true })` -- it triggers a JS-side flush on the events you care about, independent of OS scheduling.

## Next Steps

- [Connectivity Detection](./connectivity-detection.md) -- Trigger flushes on reconnect.
- [iOS Setup](../getting-started/ios-setup.md) -- Background Modes + Info.plist.
- [Android Setup](../getting-started/android-setup.md) -- Permissions + Doze.
