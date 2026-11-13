---
sidebar_position: 4
title: Platform Comparison
description: Feature-by-feature comparison of Android and iOS in @gabriel-sisjr/react-native-sync-provider — persistence, HTTP dispatch, background scheduling, recovery, connectivity, network quality, events, setup and testing.
keywords:
  - react-native
  - react-native-sync-provider
  - android
  - ios
  - platform comparison
  - workmanager
  - bgtaskscheduler
  - room
  - core data
---

# Platform Comparison

Side-by-side comparison of how `@gabriel-sisjr/react-native-sync-provider` works on Android and iOS. The JavaScript API is the same on both platforms. The native layers are not: Android uses Room, OkHttp and WorkManager, iOS uses Core Data, `URLSession` and `BGTaskScheduler`, and some results, events and timings differ as a result.

## Feature Matrix

| Feature | Android | iOS |
|---|---|---|
| Persistence | Room (SQLite), `syncprovider.db` | Core Data (SQLite), `SyncProvider.sqlite` |
| HTTP dispatch | OkHttp, one shared client | `URLSession`: a foreground session, and a background session while the app is not active |
| Items in one cycle | Sent concurrently, except that with `defaultHeaders` restored from disk the first item goes alone | Sent one at a time |
| Background scheduling | WorkManager periodic work, plus an opt-in retry kick | `BGTaskScheduler` app-refresh and processing tasks |
| Recovery of interrupted items | `RecoveryWorker`, on boot, on app update and each time a JS engine attaches | `RecoveryManager`, once per process, when the engine starts |
| Connectivity | `ConnectivityManager` default-network callback | `NWPathMonitor` |
| `CONNECTION_CHANGED` | Emitted from a background coroutine, after the new state is stored. Delivered to JS asynchronously: main thread, then the JS thread | Emitted inside the update that caused it, after the new state is stored. Delivered to JS asynchronously: main queue, then the JS thread |
| `SyncResult.succeededIds`, `failedIds`, `errors` | Filled | Always empty |
| `SYNC_PROGRESS` | Never emitted | Emitted once per batch item, before that item's `ITEM_*` event |
| Persisted configuration | SharedPreferences; `defaultHeaders` encrypted under an Android Keystore key | UserDefaults suite; `defaultHeaders` in the Keychain |
| Host-app setup | None: the permissions and the boot receiver merge from the library manifest | Background Modes, two `Info.plist` identifiers and one `AppDelegate` call |
| Minimum OS | Android 7.0 (API 24) | iOS 15.0 |

## Persistence

Both platforms keep the queue, the dead letters and the sync history in SQLite.

| Feature | Android | iOS |
|---|---|---|
| Framework | Room 2.7.0, schema processed by KSP | Core Data, through `NSPersistentContainer` |
| Store file | `syncprovider.db` | `SyncProvider.sqlite`, in `Library/Application Support` |
| Tables or entities | `sync_items`, `sync_dead_letters`, `sync_results` | `SyncItemEntity`, `SyncDeadLetterEntity`, `SyncResultEntity` |
| Current schema | Database version 3. The exported schemas live in `android/schemas/` | Model version `SyncProvider 3`. The earlier versions stay in the bundle |
| Upgrade | Room migrations `MIGRATION_1_2` and `MIGRATION_2_3` | An explicit mapping from `SyncProvider` or `SyncProvider 2`, run by `NSMigrationManager` before the store loads |
| Downgrade | The store is wiped (`fallbackToDestructiveMigrationOnDowngrade`) | A downgraded build cannot open the store |
| `maxQueueSize` counts | Every queue row, `PENDING` and `IN_FLIGHT` | `PENDING` rows only, cooling ones included |
| An attempt is recorded | With its outcome | When the item goes `IN_FLIGHT`, before the request is sent |
| A history row keeps | The counts, `succeededIds`, `failedIds` and `errors` | The counts and the first error message |

> **iOS:** If the upgrade fails, the original store is left untouched and the next launch tries again. Until then, every call that reads or writes the queue, the dead letters or the history rejects, with the facade's fallback code (`INVALID_PAYLOAD`, or `NETWORK_ERROR` for `flush`) and the store error in `message`: see [Native Rejections](../api-reference/errors.md#native-rejections). `configureSync`, `getSyncConfig` and `getConnectionStatus` still work.

> **Note:** Because the attempt is recorded at a different moment, an attempt cut short by a process kill counts against `maxAttempts` on iOS only. See [`SyncItem.attempts`](../api-reference/types.md#syncitem).

## HTTP Dispatch

| Feature | Android | iOS |
|---|---|---|
| Client | One shared `OkHttpClient`, built once | A foreground `URLSession` (`.default`, with `waitsForConnectivity`), and a background `URLSession` with identifier `com.gabriel-sisjr.syncprovider.background-upload` |
| Which client sends | Always the shared client | The background session while the app is not active, unless `foregroundOnly` is set. The foreground session otherwise |
| Items in one cycle | Up to `batchSize`, sent concurrently. With `defaultHeaders` restored from disk, the first item goes alone, and a 401 or 403 on it releases the rest unsent | Up to `batchSize`, sent one at a time |
| `flush()` while a cycle runs | Waits for it, then runs its own cycle | Returns at once, with the last finished cycle's result in this process, or a 0/0 one |
| `configureSync()` during a cycle | A running cycle keeps the options it started with, except `retryOnStatusCodes`, which is read again for each response, including the response to a request already in flight. `maxQueueSize` applies from the next enqueue, and `strategy` from the next retry wake | Applies once the call resolves, inside a running cycle too. The cycle keeps its batch and its gate and health-check outcome. A request in flight keeps its headers and timeout, but its response is classified under the new `retryPolicy` |
| Request timeout | `requestTimeoutMs` bounds the whole call, raised to 1 second when lower. The connection must also open within 10 s, and a read or write may stall for at most 30 s, whatever `requestTimeoutMs` is | `requestTimeoutMs`, as given |
| Timeout reported as | `NETWORK_ERROR` when `requestTimeoutMs` runs out: OkHttp ends the call with an `InterruptedIOException`. `TIMEOUT` only when a socket timeout fires first: connecting takes over 10 s, or a read or write stalls 30 s. Both are retried | `TIMEOUT` |
| Offline, with no `minNetworkQuality` | The request fails, and the attempt is spent | The foreground session waits for connectivity, so the flush can stay pending until the network returns |
| Body on `GET` and `DELETE` | Dropped | Not dropped |
| `contentType` and a `Content-Type` header | `contentType` wins. A body with no `contentType` is sent as `application/json` | A `Content-Type` from `headers` or `defaultHeaders` wins. `contentType` applies only when neither sets one |

Send no body with `GET` or `DELETE`, and set the content type through `contentType`, with no `Content-Type` in `headers` or `defaultHeaders`, so both platforms send the same request.

> **iOS:** A background upload can finish while the app is suspended. If its outcome arrives after a relaunch, it is dropped: see [Recovery After a Kill or Reboot](#recovery-after-a-kill-or-reboot).

## Background Scheduling

| Feature | Android | iOS |
|---|---|---|
| Scheduler | WorkManager: a unique `PeriodicWorkRequest`, `syncprovider-flush-periodic` | `BGTaskScheduler`: a `BGAppRefreshTaskRequest` (`com.gabriel-sisjr.syncprovider.background`) and a `BGProcessingTaskRequest` (`com.gabriel-sisjr.syncprovider.processing`) |
| What `enableBackgroundSync()` schedules | The periodic work | The app-refresh request. Its handler submits the processing request, so the first one exists only after a refresh window has run |
| `minimumIntervalMs` | Raised to 15 minutes when lower | Only the refresh task's earliest begin date, at least 60 seconds after the submit. The processing task has none |
| `requiresCharging` | Applied | Processing task only, as `requiresExternalPower` |
| `requiresUnmeteredNetwork` | Applied, as `NetworkType.UNMETERED` | Not applied |
| `requiresDeviceIdle` | `true` makes `enableBackgroundSync()` reject with `BACKGROUND_TASK_REGISTRATION_FAILED`: WorkManager refuses the backoff policy the library sets on an idle-constrained request. Leave it unset | Not applied |
| Network requirement | Always: `NetworkType.CONNECTED`, or `UNMETERED` | Processing task only |
| Retry kick | A delayed `OneTimeWorkRequest`, `syncprovider-flush-now`, that replaces the pending one. Armed only with background sync enabled and `foregroundOnly` off, under the same constraints as the periodic work | None |
| Handler registration | None: the work is identified by its unique names | At process start, from `+load`, for each of the library's two identifiers that the app lists in `BGTaskSchedulerPermittedIdentifiers` |
| `foregroundOnly: true` | Cancels the periodic work and the retry kick. A worker that still starts returns without dispatching | Cancels both requests and submits none. A task that still fires completes without dispatching, and the background `URLSession` is not used |
| Result reported to the OS | `Result.success()` after any completed flush. `Result.retry()` only when the worker fails as a whole | `setTaskCompleted(success: true)`, or `false` when the flush throws |
| Window skipped by the quality gate or the health check | Reported as a success. After a quality-gate skip, the periodic work waits for its next period and the retry kick re-arms itself on the gate backoff. After a health-check skip, either worker also arms the retry kick for the soonest cooldown, no earlier than the end of the backoff | Reported as a success. The next attempt is the next window the OS grants |
| After a force-quit or Force Stop | After a Force Stop, no work runs until the user opens the app again. Swiping the app from Recents does not stop WorkManager work on stock Android; some OEM builds treat it as a Force Stop | iOS cancels the background uploads in flight and launches the app for no task or upload until the user opens it again |

See [Background Sync](../guides/background-sync.md) for the configuration, and [When the gate or the health check skips a window](../guides/background-sync.md#when-the-gate-or-the-health-check-skips-a-window) for the timing of a skipped window.

## Recovery After a Kill or Reboot

| Feature | Android | iOS |
|---|---|---|
| Component | `BootCompletedReceiver` and `RecoveryWorker`. `SyncWorker` runs the same reset in a process with no JS engine | `RecoveryManager` |
| When it runs | On `BOOT_COMPLETED`, after the first unlock, on `MY_PACKAGE_REPLACED`, and each time a JS engine attaches. `LOCKED_BOOT_COMPLETED` is in the filter, but the receiver is not direct-boot aware, so a locked device does not deliver it | Once per process, when the engine is built: by the first JS-facing hybrid, or by a fired BGTask |
| After a reboot | The receiver schedules recovery | There is no boot hook. Recovery runs when the app or a BGTask next starts |
| What it does | Resets `IN_FLIGHT` rows to `PENDING`, keeping `attempts` and `nextRetryAt`. Re-registers the periodic work when background sync is enabled and `foregroundOnly` is off, and arms the next retry wake | Resets `IN_FLIGHT` rows to `PENDING`, keeping `attempts` and `nextRetryAt`. The BGTask handlers wait for it before they flush |
| Event | None | `RESUMED`, with `metadata.recovered`, when it reset at least one item |
| Under `foregroundOnly` or a pause | `RecoveryWorker` always runs. The `SyncWorker` reset is skipped while dispatch is blocked | Always runs |
| An upload that ends after a relaunch | Not applicable: there is no background session | Its outcome is dropped. Recovery puts the item back to `PENDING`, with its attempt spent and no cooldown |

Neither platform sends a request during recovery: the reset items wait for the next flush, window or retry wake. See [Crash Recovery](../guides/crash-recovery.md) for each scenario.

> **Important:** On both platforms a request can reach the server before its outcome is recorded, so it can be sent twice. Delivery is at least once: make your endpoints idempotent. See [Idempotency](../guides/idempotency.md).

## Connectivity

| Feature | Android | iOS |
|---|---|---|
| Source | `ConnectivityManager.registerDefaultNetworkCallback`, with `NetworkCapabilities` read on every change | `NWPathMonitor`, on a dedicated queue |
| Network followed | The default network only. Losing another network is not reported | The path `NWPathMonitor` reports |
| Monitor starts | When the first JS engine attaches. Until then, every read asks the system | When the engine is built, for the lifetime of the process |
| Starting state | Read from the system before the callback is registered, and never reported as a change | `UNKNOWN`. The first path update replaces it and is reported as a change |
| `UNKNOWN` status | Only when the system offers no `ConnectivityManager`. A network the OS has not described yet reads as `DISCONNECTED` | Until the first path update |
| `DISCONNECTED` | No default network, or one without `NET_CAPABILITY_INTERNET` | A path that is `unsatisfied` or `requiresConnection` |
| `METERED` | The default network lacks `NET_CAPABILITY_NOT_METERED` and, on Android 11 (API 30) and later, `NET_CAPABILITY_TEMPORARILY_NOT_METERED` | `NWPath.isExpensive` |
| `isInternetReachable` | `NET_CAPABILITY_VALIDATED` | `true` when the path is satisfied |
| `type` | `VPN`, `WIFI`, `CELLULAR`, `ETHERNET`, `BLUETOOTH` or `OTHER`, checked in that order. `NONE` when there is no default network | `WIFI`, `CELLULAR`, `ETHERNET` or `OTHER`. `NONE` on an unsatisfied path, otherwise `UNKNOWN`. Never `VPN` or `BLUETOOTH` |
| `CONNECTION_CHANGED` delivery | Emitted from a background coroutine, after the new state is stored. The emitter calls listeners on the main thread, and Nitro then queues each JS call on the JS thread | Emitted inside the path update, measurement or `configureSync` call that caused it, after the new state is stored. Listener calls are queued on the main queue, and Nitro queues each JS call on the JS thread |
| Offline, for the retry timer | `DISCONNECTED` only | `DISCONNECTED` and `UNKNOWN` |
| A gated flush before the first report | Not applicable: a read with no reported state asks the system | Waits up to 1.5 s for the first path update, then grades the link `OFFLINE` |

On both platforms, a JS listener receives `CONNECTION_CHANGED` asynchronously, after the new state is stored: a `getConnectionStatus()` call in the handler returns that state or a newer one.

See [Connectivity Detection](../guides/connectivity-detection.md) for what acts on these values.

## Network Quality

Both platforms grade the link with the same threshold table: see [Network Quality](../guides/connectivity-detection.md#network-quality). The caps, the way a measurement is tied to a network, and the probe client differ.

| Feature | Android | iOS |
|---|---|---|
| Caps, applied last, the lowest wins | No `NET_CAPABILITY_VALIDATED`: at most `POOR`. Metered: at most `GOOD` | Expensive or `METERED`: at most `GOOD`. Low Data Mode (`isConstrained`): at most `MODERATE` |
| A measurement belongs to | The network handle, its validation state and `healthCheckUrl` | The interface type, the expensive and constrained flags, the gateway addresses and `healthCheckUrl`. Two networks behind the same gateway address are taken for one. A measurement under 30 s old when the path changes is kept until a later path update, or with `minNetworkQuality` a gate evaluation, finds it 30 s old or older and measures again |
| Probe client | Its own OkHttp client, with no interceptors, cache or cookies. After a connection failure it tries the host's next address, and it repeats a `408` once | An ephemeral `URLSession`, with no cache or cookies. The latency comes from `URLSessionTaskMetrics` |

> **Android:** A captive portal before sign-in has no `NET_CAPABILITY_VALIDATED`, so its quality is at most `POOR`. iOS has no equivalent cap.

## Events

| Feature | Android | iOS |
|---|---|---|
| `CONNECTION_CHANGED` for the starting state | Never | Once, when the first path update replaces `UNKNOWN` |
| `SYNC_PROGRESS` | Never emitted | Once per batch item, when its request returns or the item is skipped, before that item's `ITEM_*` event, with `progress`, `metadata.index` and `metadata.total`. None for an empty cycle, and none for items left after a pause or a credential stop |
| `RESUMED` | After `resumeSync()` | After `resumeSync()`, and at launch when recovery reset items (`metadata.recovered`) |
| `BACKGROUND_SYNC_STARTED` | From the periodic worker only, with base fields. The retry kick emits none | From both tasks. The processing task sets `metadata.kind` to `"processing"` |
| `BACKGROUND_SYNC_COMPLETED` | From the periodic worker only, after its flush returns. None when the flush throws | From both tasks, whether the flush succeeded or failed. `metadata.kind` is `"refresh"` or `"processing"`, and `metadata.ok` is `"false"` when the flush failed |
| `metadata` on the `SYNC_SUCCEEDED` or `SYNC_FAILED` that ends a cycle | Not set | `attempted` and `succeeded`, plus `failed` when an item failed or the cycle was empty, except on the `SYNC_FAILED` of a cycle that throws, which carries `errorCode` and no `metadata` |

These events reach a listener only while a JS engine is running. A worker or BGTask launched with no JS still does its work, silently.

## Results and Dead Letters

| Feature | Android | iOS |
|---|---|---|
| `succeededIds`, `failedIds` and `errors` on `SyncResult` | Filled for the cycle, and kept in its history row | Always empty, in `flush()` results and in the history. Use `ITEM_FAILED` events |
| A `flush()` that ran no cycle | `startedAt` and `finishedAt` are `0` | `startedAt` and `finishedAt` are the time the check stopped the flush, which can be a moment after the call while the gate waits for the link or the health check's request runs. A `flush()` that joined a running cycle returns the last finished cycle's result, or a 0/0 one |
| `lastErrorMessage` of an HTTP failure | `HTTP <code> <reason>`, with the reason phrase the server sent, as in `HTTP 404 Not Found` over HTTP/1.1. HTTP/2 has no reason phrase, so `HTTP 404` | Foundation's reason phrase, localized and capitalized, as in `HTTP 404 Not Found` |

Branch on `lastErrorCode`, never on the `lastErrorMessage` text. See [`SyncResult`](../api-reference/types.md#syncresult) and [`SyncDeadLetterItem`](../api-reference/types.md#syncdeadletteritem).

## Persisted Configuration

| Feature | Android | iOS |
|---|---|---|
| Options and the pause flag | SharedPreferences file `syncprovider.config.prefs` | UserDefaults suite `com.gabriel-sisjr.syncprovider`, keys `config.v1` and `paused` |
| `defaultHeaders` | AES-256-GCM under a non-exportable Android Keystore key. Only the ciphertext and its IV are stored, in `syncprovider.secret.prefs` | Keychain, service `com.gabriel-sisjr.syncprovider`, account `defaultHeaders`, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, not synchronized |
| Background options | SharedPreferences file `syncprovider.bgsync.prefs` | The same UserDefaults suite, keys `background.options.v1` and `background.enabled` |
| Stored headers cannot be read when | The Keystore key cannot decrypt them, as after a backup was restored onto another device | The device has not been unlocked since boot, the stored data is unusable, or the Keychain item is missing while the stored configuration declares headers, as after a backup was restored onto another device (`ThisDeviceOnly` items are not restored there) |
| `getSyncConfig()` after `configureSync()` omitted a field | The field comes back `undefined` | `batchSize`, `requestTimeoutMs`, `maxQueueSize`, `persistQueue` and `defaultHeaders` come back with their defaults, `defaultHeaders: {}` included. The quality and health-check options come back only when set, as on Android |

On both platforms, nothing is dispatched while stored headers cannot be read, and calling `configureSync()` ends the wait. See [Background Sync](../guides/background-sync.md).

## Required Setup

| Feature | Android | iOS |
|---|---|---|
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED`, merged from the library manifest. None of them is a runtime permission. WorkManager's own manifest also merges the `FOREGROUND_SERVICE` and `WAKE_LOCK` permissions into your app | None |
| Components | `BootCompletedReceiver`, merged from the library manifest, plus WorkManager's own services and receivers, its `SystemForegroundService` among them. The library never runs work in the foreground | None |
| Capabilities | None | Background Modes: **Background fetch** and **Background processing** |
| `Info.plist` | Nothing | `BGTaskSchedulerPermittedIdentifiers` with `com.gabriel-sisjr.syncprovider.background` and `com.gabriel-sisjr.syncprovider.processing`. A missing identifier skips its handler. A missing refresh identifier also makes `enableBackgroundSync` reject; a missing processing identifier is silently never scheduled |
| App code | None | Forward `application(_:handleEventsForBackgroundURLSession:completionHandler:)` to `HybridSyncProvider.handleBackgroundURLSessionEvents`. Do not register the BGTask handlers: the library registers them |
| Privacy manifest | Not applicable | Shipped in the pod's `SyncProvider_Privacy` bundle: `NSPrivacyAccessedAPICategoryFileTimestamp` (`C617.1`) and `NSPrivacyAccessedAPICategoryUserDefaults` (`CA92.1`) |
| Code shrinking | Consumer ProGuard / R8 rules ship with the library | Not applicable |
| Minimum OS | `minSdkVersion` 24 (Android 7.0) | iOS 15.0 |

The step-by-step setup is in [Android Setup](../getting-started/android-setup.md), [iOS Setup](../getting-started/ios-setup.md), [Android Permissions](./android-permissions.md) and [iOS Background Modes](./ios-background-modes.md).

## Testing Differences

| Feature | Android | iOS |
|---|---|---|
| Force a background window | Find the job with `adb shell dumpsys jobscheduler`, then run it with `adb shell cmd jobscheduler run -f com.your.app <jobid>` | On a physical device, pause the app in the Xcode debugger after `enableBackgroundSync`, and run `_simulateLaunchForTaskWithIdentifier:` from LLDB. Simulate `.background` before `.processing` |
| Recovery | Reboot during a flush, so items are left `IN_FLIGHT`. After the first unlock, `BootCompletedReceiver` schedules `RecoveryWorker`, which resets them and re-registers the periodic work when background sync is enabled and `foregroundOnly` is off. Pending items are not touched | Force-quit during a flush, then reopen the app: the in-flight item is reset to pending at launch |
| Offline | `adb shell svc wifi disable && adb shell svc data disable` | Network Link Conditioner on a simulator, Airplane mode on a device |
| Logs | `adb logcat`, filtered by the library's tags, such as `SyncDispatcher`, `SyncWorker` and `RecoveryWorker` | Console or Xcode, filtered by `subsystem:com.gabriel-sisjr.syncprovider` |
| Inspect the store | Copy `syncprovider.db` together with `syncprovider.db-wal` and `syncprovider.db-shm`, keeping the names: recent writes can sit in the WAL file. For example, `adb exec-out run-as com.your.app cat databases/syncprovider.db > syncprovider.db`, then the same for the other two. `run-as` needs a debuggable build | `xcrun simctl get_app_container booted YOUR.BUNDLE.ID data`, then `Library/Application Support/SyncProvider.sqlite` |
| Native unit tests | JUnit and Robolectric (SDK 30), through `:gabriel-sisjr_react-native-sync-provider:testDebugUnitTest` | XCTest, scheme `SyncProvider-Unit-Tests`. The background `URLSession` and real BGTask launches are not covered |

> **iOS:** Use a physical device. On a simulator, `BGTaskScheduler` does not accept requests, so `enableBackgroundSync` rejects with `BACKGROUND_TASK_REGISTRATION_FAILED`. It rejects the same way on a device where the user turned off Background App Refresh. See [iOS Setup](../getting-started/ios-setup.md#7-test-the-background-handler).

> **Android:** Test Doze with `adb shell dumpsys deviceidle force-idle`: periodic work is deferred to the next maintenance window.

See [Testing](../development/testing.md) and [Debugging](../development/debugging.md) for the full commands.

## Cross-Platform Code Pattern

Code that reads failures from events, treats `UNKNOWN` as offline and branches on codes behaves the same on both platforms:

```tsx
import { Text } from 'react-native';
import {
  SyncErrorCode,
  useConnection,
  useSyncEvents,
} from '@gabriel-sisjr/react-native-sync-provider';

export function SyncFailureBanner() {
  // isOnline is false for UNKNOWN, which iOS reports before its first path update.
  const { isOnline } = useConnection();

  useSyncEvents({
    // SyncResult.errors is always empty on iOS; ITEM_FAILED works on both platforms.
    types: ['ITEM_FAILED'],
    onEvent: (e) => {
      // Branch on errorCode, never on a message or status line.
      if (e.errorCode === SyncErrorCode.UNAUTHORIZED) {
        console.warn('Sign in again to resume sync');
      } else {
        console.warn(`Item ${e.itemId} failed with ${e.errorCode}`);
      }
    },
  });

  return isOnline ? null : <Text>Offline: changes are queued</Text>;
}
```

## See Also

- [Production Checklist](./production-checklist.md) -- Per-platform release checks
- [iOS Background Modes](./ios-background-modes.md) -- Capabilities and task identifiers for BGTaskScheduler
- [Android Permissions](./android-permissions.md) -- What the library manifest merges into your app
- [iOS Privacy Manifest](./privacy-manifest.md) -- What the library declares for App Store review
- [Background Sync](../guides/background-sync.md) -- BGTaskScheduler and WorkManager in depth
- [Crash Recovery](../guides/crash-recovery.md) -- What happens to queued and in-flight items after a kill, a reboot or an update
- [Connectivity Detection](../guides/connectivity-detection.md) -- Connectivity, network quality and the health check
- [Android Native Architecture](../architecture/android-native.md) -- Room, OkHttp, WorkManager and recovery internals
- [iOS Native Architecture](../architecture/ios-native.md) -- Core Data, URLSession, BGTaskScheduler and recovery internals
