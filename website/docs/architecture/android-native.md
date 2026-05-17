---
sidebar_position: 4
title: Android Native Architecture
description: Deep dive into the Android native layer — Room database, OkHttp dispatcher, ConnectivityManager monitor, WorkManager-based background sync, and boot-time recovery.
keywords:
  - android
  - kotlin
  - room
  - okhttp
  - workmanager
  - connectivitymanager
  - coroutines
  - background sync
  - boot recovery
---

# Android Native Architecture

All Android native code lives under `android/src/main/java/com/margelo/nitro/syncprovider/`. The implementation uses Kotlin 2.0.21 with Coroutines, Room (KSP) for the persistent queue, OkHttp for HTTP, `ConnectivityManager.NetworkCallback` for connectivity, and WorkManager for the periodic background sync window.

## Directory Map

| Directory          | Purpose                                                                                                  |
| ------------------ | -------------------------------------------------------------------------------------------------------- |
| `database/`        | Room `SyncDatabase`, `SyncItemEntity`, `SyncResultEntity`, DAOs (suspend + Flow). KSP processes the schema. |
| `storage/`         | `SyncQueueStorage` — high-level wrapper around the DAOs (claim atomicity, status transitions, sort).    |
| `http/`            | `SyncDispatcher` (OkHttp 4.12.0 + Coroutines) integrated with `RetryPolicyEvaluator`.                   |
| `connectivity/`    | `ConnectivityMonitor` over `ConnectivityManager.NetworkCallback`.                                       |
| `work/`            | `SyncWorker` (`CoroutineWorker`), `BackgroundSyncManager`, `BootCompletedReceiver`, `RecoveryWorker`.   |
| `events/`          | `SyncEventEmitter` — id-based listeners on the single `'sync-event'` channel.                            |
| `error/`           | `SyncException` — typed errors mapped to `SyncErrorCode`.                                                |
| `util/`            | `Ulid`, `JsonMap`, `UrlValidator` and other small helpers.                                               |

The HybridObject implementation lives in `SyncProvider.kt` (`class SyncProvider : HybridSyncProviderSpec()`, marked `@DoNotStrip`). `SyncProviderRuntime.kt` does manual DI wiring on first call. `SyncProviderPackage.kt` is the Nitro autolinking entry point and calls `System.loadLibrary("syncprovider")`.

## Room Database

`SyncDatabase` is a single Room database (version 1) with two entities. KSP is the annotation processor — Kotlin 2.0.x is incompatible with Room 2.6.1's KSP processor (`unexpected jvm signature V` on suspend `Unit` DAOs), so the project pins Room **2.7.0**.

| Entity              | Key columns                                                                                                  |
| ------------------- | ------------------------------------------------------------------------------------------------------------ |
| `SyncItemEntity`    | `id`, `method`, `url`, `headers` (JSON String), `body`, `contentType`, `priority`, `priorityWeight`, `createdAt`, `status`, `attempts`, `lastAttemptAt`, `lastErrorCode`. |
| `SyncResultEntity`  | `id`, `startedAt`, `finishedAt`, `itemsAttempted`, `itemsSucceeded`, `itemsFailed`. Backs `getSyncHistory()`. |

DAOs expose `suspend` reads and `Flow<List<SyncItemEntity>>` for reactive subscribers. Writes go through transactional methods (`claimPending`, `markSucceeded`, `markRetry`, `markFailed`, `resetInFlight`) so concurrent dispatcher + recovery passes never collide.

## SyncQueueStorage

`SyncQueueStorage` wraps the DAOs and is the only path the rest of the Android code uses to mutate the queue. It enforces:

- **Atomic batch insert** (`enqueueBatch` either persists every item or throws — no partial success).
- **`maxQueueSize` enforcement** with `QUEUE_FULL` thrown at the boundary.
- **`URL` validation** at insert time (throws `INVALID_URL` for non-http(s) inputs).
- **Sort order**: `priorityWeight` descending, `createdAt` ascending — the same order iOS uses, kept in sync deliberately.

## SyncDispatcher (OkHttp + Coroutines)

`SyncDispatcher` owns a single shared `OkHttpClient` (built once, reused) and runs flush cycles inside a Coroutine `supervisorScope` so a single failed item never cancels the cycle.

Per item it:

1. Calls `claimPending` to atomically transition `pending → in-flight`.
2. Builds the `Request` with merged headers (`SyncOptions.defaultHeaders` first, then per-item `headers`).
3. Awaits `client.newCall(request).await()` with the configured `requestTimeoutMs`.
4. Hands the `(request, response | exception)` pair to `RetryPolicyEvaluator` which returns one of `Success | RetryAt(delay) | Permanent(SyncErrorCode)`.
5. Applies the result via `markSucceeded | markRetry | markFailed` and emits the corresponding `ITEM_SUCCEEDED | ITEM_RETRYING | ITEM_FAILED` event.

`isSyncing` flips around the entire cycle so `useSyncStatus` can mirror it.

## ConnectivityMonitor

`ConnectivityMonitor` registers a single `NetworkCallback` against the `ApplicationContext` (not an `Activity` — registering against an `Activity` loses callbacks during configuration changes). It exposes the latest `ConnectionState` as a `StateFlow<ConnectionState>` and emits `CONNECTION_CHANGED` whenever the snapshot changes.

`NetworkCapabilities` are inspected on every change to drive the `METERED` discriminator (`!hasCapability(NET_CAPABILITY_NOT_METERED)`).

## SyncWorker (WorkManager)

`SyncWorker` is a `CoroutineWorker` registered via WorkManager:

- **`PeriodicWorkRequest`** for the regular cadence — enforced floor of **15 minutes** (WorkManager hard limit).
- **`OneTimeWorkRequest`** for opportunistic flushes (e.g., from `flushOnReconnect`).

Both use `Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)` (or `UNMETERED` when `BackgroundSyncOptions.requiresUnmeteredNetwork` is true) plus `setRequiresCharging` / `setRequiresDeviceIdle` mappings.

`SyncWorker.doWork()` calls `SyncProviderRuntime.bootstrap(applicationContext).dispatcher.flush()` and translates the outcome into `Result.success() | Result.retry() | Result.failure()` so WorkManager's exponential backoff kicks in for transient failures.

## BootCompletedReceiver + RecoveryWorker

`BootCompletedReceiver` is declared in `AndroidManifest.xml` with intent filters for:

- `android.intent.action.BOOT_COMPLETED`
- `android.intent.action.LOCKED_BOOT_COMPLETED`
- `android.intent.action.MY_PACKAGE_REPLACED`

On any of these, it enqueues a one-time `RecoveryWorker` that:

1. Runs `SyncQueueStorage.resetInFlight()` to flip any items left in `in-flight` (e.g., process killed mid-dispatch) back to `pending`.
2. Re-arms the periodic `SyncWorker` if `BackgroundSyncManager.isEnabled()` reports it should be running.

This is the analog of iOS's `RecoveryManager`, but driven by the boot broadcast instead of the app delegate.

## SyncEventEmitter

The emitter implements the Nitro listener pattern:

- A single channel string `'sync-event'` (the `SYNC_EVENT_CHANNEL` constant on the JS side).
- `addListener(event, cb)` returns a stable string id used by `removeListener(event, id)`.
- Multiple subscribers are supported per process — each receives every emitted event.
- Native validates the channel name and throws `INVALID_PAYLOAD` on anything other than `'sync-event'`.

## Manifest & Permissions

`AndroidManifest.xml` (merged automatically into the consumer app):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver
  android:name="com.margelo.nitro.syncprovider.work.BootCompletedReceiver"
  android:enabled="true"
  android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.BOOT_COMPLETED" />
    <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
    <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
  </intent-filter>
</receiver>
```

The library does **not** declare a foreground service. WorkManager + `BootCompletedReceiver` are sufficient for the offline-first HTTP queue use case — the user-facing notification overhead of a foreground service is not justified.

## Key Dependency Versions

| Dependency                                          | Version  | Why this floor                                                 |
| --------------------------------------------------- | -------- | -------------------------------------------------------------- |
| `androidx.room:room-runtime` / `room-ktx` / `room-compiler` (KSP) | `2.7.0`  | Floor that ships KSP processor compatible with Kotlin 2.0.x.   |
| `androidx.work:work-runtime-ktx`                    | `2.9.1`  | Stable WorkManager with `setExpedited` (API 31+) support.      |
| `com.squareup.okhttp3:okhttp`                       | `4.12.0` | Latest 4.x — same major as the test dep `mockwebserver`.       |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android`  | `1.8.1`  | Matches Kotlin 2.0.x; `Dispatchers.IO` parallelism unchanged.  |

## ProGuard / R8

`android/proguard-rules.pro` keeps:

- `com.margelo.nitro.syncprovider.**` (Nitro HybridObject + impl).
- Anything annotated `@com.facebook.proguard.annotations.DoNotStrip` or `@androidx.annotation.Keep`.
- Room entities, DAOs, and generated `*_Impl` classes.
- WorkManager `Worker` / `CoroutineWorker` / `ListenableWorker` subclasses.
- OkHttp / Okio / Coroutines internal class names that R8 would otherwise strip.

The library ships its own ProGuard file via `consumerProguardFiles` so consumer apps inherit the rules automatically.
