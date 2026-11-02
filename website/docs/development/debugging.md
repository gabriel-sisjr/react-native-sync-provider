---
sidebar_position: 3
title: Debugging
description: Verifying the New Architecture, simulating offline states, inspecting the persisted queue, and forcing background sync windows during development.
keywords:
  - debugging
  - new architecture
  - logcat
  - xcode console
  - bgtaskscheduler
  - workmanager
  - airplane mode
---

# Debugging

This page collects the techniques the maintainers use to debug `react-native-sync-provider` itself — most of them are equally useful when debugging an app that consumes the library.

## Verify the New Architecture is on

The library only supports the New Architecture (Fabric + concurrent root). When the example or your app boots Metro, look for this line in the logs:

```
"fabric":true,"concurrentRoot":true
```

If `fabric` is `false`, the lib will throw `NATIVE_MODULE_UNAVAILABLE` on every call. Re-check the host app's Podfile (`:fabric_enabled => true`) and `gradle.properties` (`newArchEnabled=true`).

## "HybridObject not registered" / module-not-found errors

The most common cause is forgetting `yarn nitrogen` after editing `src/SyncProvider.nitro.ts`. The example app references generated headers and base classes that live under `nitrogen/` (which is gitignored). Without them:

```
Cannot find HybridSyncProviderSpec.hpp
java.lang.UnsatisfiedLinkError: ... HybridSyncProviderSpec
```

Fix:

```bash
yarn nitrogen
yarn example android   # or ios — full rebuild needed
```

Make this the first thing you try after any branch switch or fresh clone.

## Simulating offline

The library reacts to OS-level connectivity. Forcing JavaScript-side `fetch` to fail will not exercise the offline path correctly — the queue dispatcher runs in native code.

| Platform | Method                                                            |
| -------- | ----------------------------------------------------------------- |
| iOS sim  | Simulator → Device → Network Link Conditioner → 100% loss profile |
| iOS dev  | Settings → Airplane mode (toggle on)                               |
| Android  | adb: `adb shell svc wifi disable && adb shell svc data disable`    |
| Both     | Toggle airplane mode in the system tray / Quick Settings          |

After toggling, watch for `CONNECTION_CHANGED` events emitted by the library — they appear in the JS console when subscribed via `useSyncEvents`.

## Android logcat tag filters

```bash
adb logcat *:S SyncProvider:V SyncDispatcher:V SyncWorker:V RecoveryWorker:V RetryTimer:V BackgroundSyncManager:V WM-WorkerWrapper:V
```

Useful tags:

| Tag                  | What it tells you                                                   |
| -------------------- | ------------------------------------------------------------------- |
| `SyncProvider`, `SyncDispatcher` | Library's own log lines (bridge errors, dispatcher state, skipped cycles). |
| `SyncWorker`, `RecoveryWorker`, `RetryTimer`, `BackgroundSyncManager` | Background work and retry wakes. |
| `WM-WorkerWrapper`   | WorkManager scheduling decisions and `Result.success/retry/failure`.|
| `WorkerLifecycle`    | Worker start/stop transitions.                                      |
| `JobScheduler`       | Underlying job scheduling state — useful if work seems "stuck".     |

## Xcode console subsystem filters

The library logs through `os.log` with subsystem `com.gabriel-sisjr.syncprovider`. Turn on Info messages. In the Xcode console, set the filter to:

```
subsystem:com.gabriel-sisjr.syncprovider
```

Categories used: `core-data`, `dispatcher`, `queue`, `background`, `events`, `connectivity`, `recovery`, `general`.

Do not treat a log line as proof of dispatch: check the request log on your endpoint.

## Inspecting the persisted queue

### iOS — Core Data

```bash
# Find the simulator's app data directory
xcrun simctl get_app_container booted YOUR.BUNDLE.ID data
# Open the SQLite store
open "<path>/Library/Application Support/SyncProvider.sqlite"
```

[DB Browser for SQLite](https://sqlitebrowser.org/) opens the store cleanly. Look at `ZSYNCITEMENTITY` and `ZSYNCRESULTENTITY`.

### Android — Room

```bash
adb shell run-as com.your.app
cd databases
ls
# Pull the database off the device
exit
adb exec-out run-as com.your.app cat databases/syncprovider.db > sync.db
```

Open `sync.db` with DB Browser. Tables: `sync_items`, `sync_results`.

## Forcing a background sync window

### iOS

On a physical device, after calling `enableBackgroundSync`, with the app paused in the Xcode debugger:

```
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.gabriel-sisjr.syncprovider.background"]
```

Resume execution and watch for `BACKGROUND_SYNC_STARTED` / `BACKGROUND_SYNC_COMPLETED` events. Simulators do not accept `BGTaskScheduler` requests. See [iOS Setup](../getting-started/ios-setup.md#7-test-the-background-handler) for the processing task.

### Android

WorkManager exposes a job id you can run on demand:

```bash
# Find the job id
adb shell dumpsys jobscheduler | grep com.your.app
# Force-run it
adb shell cmd jobscheduler run -f com.your.app <jobid>
```

## When `flush()` resolves but `failureCount > 0`

This is intentional. `flush()` rejects only when the native module is missing or the cycle itself dies. A network failure is a per-item failure, not a rejection. Per-item failures are reported in the `SyncResult`, on Android only:

```ts
const result = await flush();
if (result.failureCount > 0) {
  // failedIds and errors are always empty on iOS.
  for (const id of result.failedIds) {
    console.warn(`Item ${id} failed: ${result.errors[id]}`);
  }
}
```

Use `useSyncEvents({ types: ['ITEM_FAILED'] })` for reporting that works on both platforms.

## `SyncError.cause` is your friend

Every `SyncError` thrown by the JS facade preserves the underlying reason in `.cause`. Always log both:

```ts
try {
  await enqueue(item);
} catch (err) {
  if (err instanceof SyncError) {
    console.error(`[${err.code}] ${err.message}`, err.cause);
  } else {
    throw err;
  }
}
```

Native rejections that the facade did not anticipate are wrapped with `cause` set to the original native error so nothing is lost.
