---
sidebar_position: 3
title: Android Native Architecture
description: Deep dive into the Android native layer — Room database, OkHttp dispatcher, ConnectivityManager monitor, WorkManager-based background sync, retry wake, persisted configuration, and boot-time recovery.
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

All Android native code lives under `android/src/main/java/com/margelo/nitro/syncprovider/`. The implementation uses Kotlin (2.0.21 by default, or the host's `kotlinVersion`) with Coroutines, Room (KSP) for the persistent queue, OkHttp for HTTP, `ConnectivityManager.NetworkCallback` for connectivity, and WorkManager for the periodic background sync window.

## Directory Map

| Directory          | Purpose                                                                                                  |
| ------------------ | -------------------------------------------------------------------------------------------------------- |
| `database/`        | Room `SyncDatabase`, `SyncItemEntity`, `SyncDeadLetterEntity`, `SyncResultEntity`, DAOs (suspend + Flow). KSP processes the schema. |
| `storage/`         | `SyncQueueStorage` — high-level wrapper around the DAOs (claim atomicity, status transitions, sort).    |
| `http/`            | `SyncDispatcher` (OkHttp 4.12.0 + Coroutines) and `CredentialPolicy`; `HeadProbe` (one `HEAD` request) and `HealthCheck` (the check before a cycle, with its cache and backoff); `FlushOutcome` (what a flush did, and why it skipped a cycle). |
| `retry/`           | `RetryPolicyEvaluator` (the backoff math), `RetryTimer` (in-process timer), `RetryWake` (arms the timer and the kick), `GateRecheck` (when a wake the quality gate holds is checked again). |
| `config/`          | `SyncConfigStore`, `SecretStore`, `KeystoreSecretStore`, `SyncOptionsValidator` (the checks `configureSync` runs before anything is applied). |
| `connectivity/`    | `ConnectivityMonitor` over the default-network callback, `NetworkQualityClassifier`, `LatencyProbe`.     |
| `work/`            | `SyncWorker` (`CoroutineWorker`), `BackgroundSyncManager`, `BootCompletedReceiver`, `RecoveryWorker`.   |
| `events/`          | `SyncEventEmitter` — id-based listeners on the single `'sync-event'` channel.                            |
| `error/`           | `SyncException` — typed errors mapped to `SyncErrorCode`.                                                |
| `util/`            | `Ulid`, `JsonMap`, `UrlValidator` and other small helpers.                                               |

The HybridObject implementation lives in `SyncProvider.kt` (`class SyncProvider : HybridSyncProviderSpec()`, marked `@DoNotStrip`). `SyncProviderRuntime.kt` is the process-wide container, shared with WorkManager workers. It restores the persisted configuration whichever process builds it, and tracks attached JS engines: the retry timer and the connectivity watch only run while one is attached. `SyncProviderPackage.kt` is the Nitro autolinking entry point and calls `System.loadLibrary("syncprovider")`.

## Room Database

`SyncDatabase` is a single Room database (version 3, migrated from versions 1 and 2) with three entities. It is named `syncprovider.db`. KSP is the annotation processor — Kotlin 2.0.x is incompatible with Room 2.6.1's KSP processor (`unexpected jvm signature V` on suspend `Unit` DAOs), so the project pins Room **2.7.0**.

| Entity              | Key columns                                                                                                  |
| ------------------- | ------------------------------------------------------------------------------------------------------------ |
| `SyncItemEntity`    | `id`, `method`, `url`, `headers` (JSON String), `body`, `contentType`, `priority`, `priorityWeight`, `createdAt`, `status`, `attempts`, `lastAttemptAt`, `lastErrorCode`, `metadata`, `nextRetryAt`. |
| `SyncDeadLetterEntity` | Table `sync_dead_letters`: the item's request columns plus `attempts`, `lastAttemptAt`, `lastErrorCode`, `lastErrorMessage` and `deadLetteredAt` (indexed). No `nextRetryAt`. Backs `listDeadLetter()`. |
| `SyncResultEntity`  | `id`, `startedAt`, `finishedAt`, `successCount`, `failureCount`, `succeededIds`, `failedIds`, `errors`. Backs `getSyncHistory()`. |

DAOs expose `suspend` reads and `Flow<List<SyncItemEntity>>` for reactive subscribers. Writes go through transactional methods (`claimPending`, `markSucceeded`, `markRetry`, `resetInFlight`, and the dead-letter move and replay) so concurrent dispatcher + recovery passes never collide. A terminal failure deletes the queue row and inserts the dead letter in one transaction.

`MIGRATION_2_3` creates the dead-letter table and its index, copies every `FAILED` row into it and deletes them from `sync_items`, in one transaction. A v1 store runs `MIGRATION_1_2` first. The exported schemas live under `android/schemas/`. A downgrade still falls back to a destructive migration.

## SyncQueueStorage

`SyncQueueStorage` wraps the DAOs and is the only path the rest of the Android code uses to mutate the queue. It enforces:

- **Atomic batch insert** (`enqueueBatch` either persists every item or throws — no partial success).
- **`maxQueueSize` enforcement** with `QUEUE_FULL` thrown at the boundary, for enqueues and dead-letter replays. The cap counts every queue row, `PENDING` and `IN_FLIGHT`. Dead letters live in their own table and do not count. `getQueueSize()` and `getPendingItems()` cover `PENDING` rows only.
- **`URL` validation** at insert time (throws `INVALID_URL` for non-http(s) inputs).
- **Sort order**: `priorityWeight` ascending (`HIGH` is `0`), `createdAt` ascending — the same order iOS uses, kept in sync deliberately. A claim also requires `nextRetryAt <= now`.

## SyncDispatcher (OkHttp + Coroutines)

`SyncDispatcher` owns a single shared `OkHttpClient` (built once, reused) and runs flush cycles inside a Coroutine `supervisorScope` so a single failed item never cancels the cycle. A mutex serializes cycles: a concurrent `flush()` waits for the running one, then runs its own. It holds the pause check too, so workers obey it.

A cycle:

1. Calls `claimPending` to atomically transition one batch of up to `batchSize` eligible rows from `PENDING` to `IN_FLIGHT`.
2. For each row, concurrently, builds the `Request` with merged headers (`SyncOptions.defaultHeaders` first, then per-item `headers`) and runs one attempt with the configured `requestTimeoutMs`. `attempts` continues from what earlier cycles persisted.
3. Applies the result via `markSucceeded | markRetry | markFailed` and emits the corresponding `ITEM_SUCCEEDED | ITEM_RETRYING | ITEM_FAILED` event. On a retriable failure with attempts left, the row returns to `PENDING` with `nextRetryAt` measured from the end of the attempt. A non-retryable failure keeps its real code, and only a retriable one with no attempts left becomes `MAX_ATTEMPTS_EXCEEDED`.
4. Records the `SyncResult`, emits the terminal event, and calls back to arm the next retry wake.

With default headers restored from disk, one item goes first. If the server rejects the credential, the rest of the batch is released untouched, so an expired token costs one request per wake. That item stays `PENDING`, no attempt is spent, and it waits at least 60 seconds. See [Cold-start configuration](../guides/background-sync.md#cold-start-configuration).

When paused, or with default headers that cannot be read, `flush()` returns an all-zero result and no cycle runs.

`isSyncing` flips around the entire cycle so hooks can mirror it.

## Retry Wake

`RetryWake` decides when the queue wakes next, and arms both mechanisms from one place. It runs after every cycle, after recovery, on `configureSync` and on `resumeSync`, and does nothing while paused, under `MANUAL`, or with headers that cannot be read.

- **`RetryTimer`** is a single-shot in-process timer, armed only while a JS engine is attached. If it fires offline it does not flush: it owes the wake and delivers it when connectivity returns.
- **The retry kick** is a delayed `OneTimeWorkRequest` (`syncprovider-flush-now`, `ExistingWorkPolicy.REPLACE`) that survives process death. `scheduleOneTimeFlush` arms it only with background sync enabled and `foregroundOnly` off, under the same constraints as the periodic work.

`RetryWake` also keeps the credential hold, in memory, as iOS does. After a rejected stored credential, every arm path waits for it, so neither the timer nor a replacement kick fires before the hold ends. `configureSync` clears it, and a new process starts without it. See [When a stored token expires](../guides/background-sync.md#when-a-stored-token-expires).

After a failed health check, the timer and the kick wait for the end of its backoff, and at least 1000 ms after the failure, so a `healthCheckBackoffMs` of `0` cannot make them spin. See [Health check](../guides/connectivity-detection.md#health-check).

See [Who wakes the queue](../guides/retry-policy.md#who-wakes-the-queue-and-when).

## Persisted Configuration

`SyncConfigStore` persists what a process with no JS needs: every `SyncOptions` field and the pause flag in the `syncprovider.config.prefs` SharedPreferences file, and `defaultHeaders` through `SecretStore`. Only the fact that headers exist is stored in the clear.

`KeystoreSecretStore` encrypts with AES-256-GCM under a non-exportable Android Keystore key, and keeps only the ciphertext and IV in `syncprovider.secret.prefs`. Ciphertext restored from a backup onto another device cannot be read, so dispatch waits. `SyncProviderRuntime` retries a failed read until it succeeds or JS configures. Exception messages are not logged, only their type, and header values are never logged.

The background options live in `syncprovider.bgsync.prefs`, managed by `BackgroundSyncManager`.

## ConnectivityMonitor

`ConnectivityMonitor` registers a default-network callback (`registerDefaultNetworkCallback`), so it follows only the network the app's traffic uses. It registers against the `ApplicationContext`, not an `Activity`, so the registration never holds a screen in memory and outlives any one of them. It exposes the latest `ConnectionState` as a `StateFlow<ConnectionState>`. When a reported field changes, `networkQuality` included, it stores the new state first and emits `CONNECTION_CHANGED` from a background coroutine, so a JS listener receives it asynchronously, on the JS thread, and `getConnectionStatus()` can already return the new state, or a later one, when the event arrives. It seeds its state before it registers the callback, so the state it starts in is never emitted.

`NetworkCapabilities` are inspected on every change. A default network without `NET_CAPABILITY_INTERNET` is `DISCONNECTED`. Otherwise the link is `METERED` unless it has `NET_CAPABILITY_NOT_METERED`, or, on Android 11 (API 30) and later, `NET_CAPABILITY_TEMPORARILY_NOT_METERED`. `NetworkQualityClassifier` grades the link with the [shared threshold table](../guides/connectivity-detection.md#how-it-is-classified), capping a network without `NET_CAPABILITY_VALIDATED` at `POOR`. With `healthCheckUrl` set, `LatencyProbe` measures it over the `HeadProbe` client, which the health check (`http/HealthCheck.kt`) shares; a measurement is keyed by the network handle, its validation and the URL. The monitor starts when the first JS engine attaches.

## SyncWorker (WorkManager)

`SyncWorker` is a `CoroutineWorker` registered via WorkManager:

- **`PeriodicWorkRequest`** (`syncprovider-flush-periodic`) for the regular cadence — enforced floor of **15 minutes** (WorkManager hard limit).
- **`OneTimeWorkRequest`** (`syncprovider-flush-now`) for the [retry kick](#retry-wake).

Both use `Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)` (or `UNMETERED` when `BackgroundSyncOptions.requiresUnmeteredNetwork` is true) plus `setRequiresCharging` / `setRequiresDeviceIdle` mappings. WorkManager refuses the backoff policy the library sets on an idle-constrained request, so `requiresDeviceIdle: true` makes `enableBackgroundSync()` reject with `BACKGROUND_TASK_REGISTRATION_FAILED`.

`SyncWorker.doWork()` stays inert when dispatch is blocked by a persisted pause or `foregroundOnly`. `MANUAL` does not block it, because enabling background sync is an explicit request. Otherwise, with no JS engine attached, it first resets rows a dead process left `IN_FLIGHT`. It then calls `dispatcher.flushWithOutcome()` once and returns `Result.success()` on any completed flush, so WorkManager's backoff cannot race the persisted cooldowns. `Result.retry()` is reserved for a worker that fails as a whole.

Only the periodic job emits `BACKGROUND_SYNC_STARTED` / `BACKGROUND_SYNC_COMPLETED`.

## BootCompletedReceiver + RecoveryWorker

`BootCompletedReceiver` is declared in `AndroidManifest.xml` with intent filters for:

- `android.intent.action.BOOT_COMPLETED`
- `android.intent.action.LOCKED_BOOT_COMPLETED`
- `android.intent.action.MY_PACKAGE_REPLACED`

`LOCKED_BOOT_COMPLETED` is in the filter, but the receiver is not direct-boot aware, so a locked device does not deliver it. On `BOOT_COMPLETED`, after the first unlock, and on `MY_PACKAGE_REPLACED`, it enqueues a one-time `RecoveryWorker`, which also runs each time a JS engine attaches. The worker:

1. Resets items left `IN_FLIGHT` (e.g., process killed mid-dispatch) back to `PENDING`, under the flush mutex so a running cycle is not disturbed.
2. Re-arms the periodic `SyncWorker` if background sync is enabled and `foregroundOnly` is off.
3. Arms the next retry wake.

It sends no request of its own, and it is never skipped: this is data integrity, not background dispatch. This is the analog of iOS's `RecoveryManager`.

`SyncWorker` runs the same reset itself in a process with no JS engine attached, before it flushes, for both the periodic job and the retry kick. It takes the same flush mutex and, unlike dispatch, does not need stored headers. Unlike `RecoveryWorker`, it can be skipped: a worker blocked by a persisted pause or `foregroundOnly` returns first. In that case, or when no worker is scheduled, an item can stay `IN_FLIGHT` until the next launch, boot or app update.

Neither path changes `nextRetryAt`. A row whose cooldown has not elapsed goes back to `PENDING`, but that flush does not send it.

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
| `androidx.work:work-runtime-ktx`                    | `2.9.1`  | Stable WorkManager with `ExistingPeriodicWorkPolicy.UPDATE`.   |
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

## Next Steps

- [iOS Native Architecture](./ios-native.md) -- The iOS counterpart: Core Data, URLSession, BGTaskScheduler and launch recovery
- [Data Flow](./data-flow.md) -- Step-by-step traces of enqueue, flush, connectivity and recovery
- [Architecture Overview](./overview.md) -- The three layers, the key design decisions and the technology stack
- [Android Permissions](../production/android-permissions.md) -- What the library merges into your manifest, and why
