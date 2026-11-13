---
sidebar_position: 5
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

---

## Verify the New Architecture Is On

The library only supports the New Architecture (Fabric + concurrent root). When the example or your app boots Metro, look for this line in the logs:

```text
"fabric":true,"concurrentRoot":true
```

If `fabric` is `false`, the lib will throw `NATIVE_MODULE_UNAVAILABLE` on every call. Re-check the host app's Podfile (`:fabric_enabled => true`) and `gradle.properties` (`newArchEnabled=true`).

---

## "HybridObject not registered" / Module-Not-Found Errors

The most common cause is forgetting `yarn nitrogen` after editing `src/SyncProvider.nitro.ts`. The example app references generated headers and base classes that live under `nitrogen/` (which is gitignored). Without them:

```text
Cannot find HybridSyncProviderSpec.hpp
java.lang.UnsatisfiedLinkError: ... HybridSyncProviderSpec
```

Fix:

```bash
yarn nitrogen
yarn example android   # or ios — full rebuild needed
```

Make this the first thing you try after any branch switch or fresh clone.

---

## Simulating Offline

The library reacts to OS-level connectivity. Forcing JavaScript-side `fetch` to fail will not exercise the offline path correctly — the queue dispatcher runs in native code.

| Platform | Method                                                            |
| -------- | ----------------------------------------------------------------- |
| iOS sim  | Simulator → Device → Network Link Conditioner → 100% loss profile |
| iOS dev  | Settings → Airplane mode (toggle on)                               |
| Android  | adb: `adb shell svc wifi disable && adb shell svc data disable`    |
| Both     | Toggle airplane mode in the system tray / Quick Settings          |

After toggling, watch for `CONNECTION_CHANGED` events emitted by the library — they appear in the JS console when subscribed via `useSyncEvents`.

---

## Android Logcat Tag Filters

```bash
adb logcat *:S SyncProvider:V SyncProviderRuntime:V SyncDispatcher:V SyncWorker:V RecoveryWorker:V RetryTimer:V BackgroundSyncManager:V BootCompletedReceiver:V ConnectivityMonitor:V SyncConfigStore:V KeystoreSecretStore:V SyncEventEmitter:V WM-WorkerWrapper:V
```

Useful tags:

| Tag                  | What it tells you                                                   |
| -------------------- | ------------------------------------------------------------------- |
| `SyncProvider`, `SyncDispatcher` | Problems only, not state. `SyncProvider` logs an error when a bridge call or one of its coroutines throws unexpectedly. `SyncDispatcher` logs a warning when stored `defaultHeaders` cannot be read, which skips the cycle, or when the next retry wake cannot be armed, and an error when a request cannot be built or an aborted cycle cannot be cleaned up. Requests are not logged. A cycle skipped by the quality gate or the health check is not logged either: it emits `SYNC_FAILED`. |
| `SyncWorker`, `RecoveryWorker`, `RetryTimer`, `BackgroundSyncManager` | Background work and retry wakes. |
| `SyncProviderRuntime`, `SyncConfigStore`, `KeystoreSecretStore` | The process-wide runtime, the persisted configuration and the encrypted `defaultHeaders`. |
| `ConnectivityMonitor`, `SyncEventEmitter`, `BootCompletedReceiver` | The default-network callback and its quality grade, event delivery, and the recovery scheduled on boot and app update. |
| `WM-WorkerWrapper`   | WorkManager scheduling decisions and `Result.success/retry/failure`.|
| `WorkerLifecycle`    | Worker start/stop transitions.                                      |
| `JobScheduler`       | Underlying job scheduling state — useful if work seems "stuck".     |

---

## Xcode Console Subsystem Filters

The library logs through `os.log` with subsystem `com.gabriel-sisjr.syncprovider`. Turn on Info messages. In the Xcode console, set the filter to:

```text
subsystem:com.gabriel-sisjr.syncprovider
```

Categories used: `core-data`, `dispatcher`, `queue`, `background`, `events`, `connectivity`, `recovery`, `general`.

Do not treat a log line as proof of dispatch: check the request log on your endpoint.

---

## Inspecting the Persisted Queue

### Core Data (iOS)

```bash
# Find the simulator's app data directory
xcrun simctl get_app_container booted YOUR.BUNDLE.ID data
# Open the SQLite store
open "<path>/Library/Application Support/SyncProvider.sqlite"
```

[DB Browser for SQLite](https://sqlitebrowser.org/) opens the store cleanly. Look at `ZSYNCITEMENTITY`, `ZSYNCDEADLETTERENTITY` and `ZSYNCRESULTENTITY`.

### Room (Android)

```bash
adb shell run-as com.your.app ls databases
# Pull the database off the device, with its WAL files: recent writes can sit in the WAL
adb exec-out run-as com.your.app cat databases/syncprovider.db > syncprovider.db
adb exec-out run-as com.your.app cat databases/syncprovider.db-wal > syncprovider.db-wal
adb exec-out run-as com.your.app cat databases/syncprovider.db-shm > syncprovider.db-shm
```

Open `syncprovider.db` with DB Browser, with the `-wal` and `-shm` files next to it. `run-as` needs a debuggable build. Tables: `sync_items`, `sync_dead_letters`, `sync_results`.

---

## Forcing a Background Sync Window

### iOS

On a physical device, after calling `enableBackgroundSync`, with the app paused in the Xcode debugger:

```text
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

---

## When `flush()` Resolves but `failureCount > 0`

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

---

## `SyncError.cause` Is Your Friend

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

---

## Verification Checklist

To confirm everything is working:

- [ ] Metro logs show `"fabric":true,"concurrentRoot":true`
- [ ] `yarn nitrogen` ran after the last spec change or fresh clone, and the app was rebuilt
- [ ] `isNativeModuleAvailable()` returns `true`
- [ ] `enqueue()` resolves with an id, and `ITEM_ENQUEUED` reaches `useSyncEvents`
- [ ] The item's row is in the persisted queue (`ZSYNCITEMENTITY` / `sync_items`) before a successful flush, and gone after it
- [ ] A `flush()` emits `SYNC_STARTED`, then `SYNC_SUCCEEDED` or `SYNC_FAILED` (a paused engine emits nothing, and a skipped cycle emits `SYNC_FAILED` alone)
- [ ] Your endpoint's request log shows the request
- [ ] Toggling airplane mode emits `CONNECTION_CHANGED`
- [ ] iOS: Console.app shows `Registered BGTask handlers for 2 declared identifiers` at launch
- [ ] A forced background window emits `BACKGROUND_SYNC_STARTED` and `BACKGROUND_SYNC_COMPLETED`

---

## Reporting Issues

If the problem persists after following this guide, open an issue at [github.com/gabriel-sisjr/react-native-sync-provider/issues](https://github.com/gabriel-sisjr/react-native-sync-provider/issues) with the **Bug Report** template, and provide:

1. The library version, the React Native version and the `react-native-nitro-modules` version
2. The platform and OS version, and whether you used a physical device, a simulator or an emulator
3. The architecture mode of your app (the library needs the New Architecture)
4. The sync state when the bug occurred: the queue size, the connection state, the `RetryPolicy` in effect, and whether background sync is enabled
5. Steps to reproduce, the expected behavior and the actual behavior, with every `SyncErrorCode`, message and `cause` you saw
6. Logs filtered as above: logcat on Android, or the Xcode console / Console.app with subsystem `com.gabriel-sisjr.syncprovider` on iOS. Redact URLs, tokens and payloads
7. A minimal repository or gist that reproduces the issue

Questions that are not bugs go to [GitHub Discussions](https://github.com/gabriel-sisjr/react-native-sync-provider/discussions).
