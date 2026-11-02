---
sidebar_position: 4
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

All Android native code lives under `android/src/main/java/com/margelo/nitro/syncprovider/`. The implementation uses Kotlin 2.0.21 with Coroutines, Room (KSP) for the persistent queue, OkHttp for HTTP, `ConnectivityManager.NetworkCallback` for connectivity, and WorkManager for the periodic background sync window.

## Directory Map

| Directory          | Purpose                                                                                                  |
| ------------------ | -------------------------------------------------------------------------------------------------------- |
| `database/`        | Room `SyncDatabase`, `SyncItemEntity`, `SyncResultEntity`, DAOs (suspend + Flow). KSP processes the schema. |
| `storage/`         | `SyncQueueStorage` — high-level wrapper around the DAOs (claim atomicity, status transitions, sort).    |
| `http/`            | `SyncDispatcher` (OkHttp 4.12.0 + Coroutines) and `CredentialPolicy`.                                   |
| `retry/`           | `RetryPolicyEvaluator` (the backoff math), `RetryTimer` (in-process timer), `RetryWake` (arms the timer and the kick). |
| `config/`          | `SyncConfigStore`, `SecretStore`, `KeystoreSecretStore`.                                                |
| `connectivity/`    | `ConnectivityMonitor` over `ConnectivityManager.NetworkCallback`.                                       |
| `work/`            | `SyncWorker` (`CoroutineWorker`), `BackgroundSyncManager`, `BootCompletedReceiver`, `RecoveryWorker`.   |
| `events/`          | `SyncEventEmitter` — id-based listeners on the single `'sync-event'` channel.                            |
| `error/`           | `SyncException` — typed errors mapped to `SyncErrorCode`.                                                |
| `util/`            | `Ulid`, `JsonMap`, `UrlValidator` and other small helpers.                                               |

The HybridObject implementation lives in `SyncProvider.kt` (`class SyncProvider : HybridSyncProviderSpec()`, marked `@DoNotStrip`). `SyncProviderRuntime.kt` is the process-wide container, shared with WorkManager workers. It restores the persisted configuration whichever process builds it, and tracks attached JS engines: the retry timer and the connectivity watch only run while one is attached. `SyncProviderPackage.kt` is the Nitro autolinking entry point and calls `System.loadLibrary("syncprovider")`.

## Room Database

`SyncDatabase` is a single Room database (version 2, migrated from version 1) with two entities. It is named `syncprovider.db`. KSP is the annotation processor — Kotlin 2.0.x is incompatible with Room 2.6.1's KSP processor (`unexpected jvm signature V` on suspend `Unit` DAOs), so the project pins Room **2.7.0**.

| Entity              | Key columns                                                                                                  |
| ------------------- | ------------------------------------------------------------------------------------------------------------ |
| `SyncItemEntity`    | `id`, `method`, `url`, `headers` (JSON String), `body`, `contentType`, `priority`, `priorityWeight`, `createdAt`, `status`, `attempts`, `lastAttemptAt`, `lastErrorCode`, `metadata`, `nextRetryAt`. |
| `SyncResultEntity`  | `id`, `startedAt`, `finishedAt`, `successCount`, `failureCount`, `succeededIds`, `failedIds`, `errors`. Backs `getSyncHistory()`. |

DAOs expose `suspend` reads and `Flow<List<SyncItemEntity>>` for reactive subscribers. Writes go through transactional methods (`claimPending`, `markSucceeded`, `markRetry`, `markFailed`, `resetInFlight`) so concurrent dispatcher + recovery passes never collide.

## SyncQueueStorage

`SyncQueueStorage` wraps the DAOs and is the only path the rest of the Android code uses to mutate the queue. It enforces:

- **Atomic batch insert** (`enqueueBatch` either persists every item or throws — no partial success).
- **`maxQueueSize` enforcement** with `QUEUE_FULL` thrown at the boundary. The cap counts every row, including `FAILED` and `IN_FLIGHT` ones. `getQueueSize()` and `getPendingItems()` cover `PENDING` rows only.
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

See [Who wakes the queue](../guides/retry-policy.md#who-wakes-the-queue-and-when).

## Persisted Configuration

`SyncConfigStore` persists what a process with no JS needs: every `SyncOptions` field and the pause flag in the `syncprovider.config.prefs` SharedPreferences file, and `defaultHeaders` through `SecretStore`. Only the fact that headers exist is stored in the clear.

`KeystoreSecretStore` encrypts with AES-256-GCM under a non-exportable Android Keystore key, and keeps only the ciphertext and IV in `syncprovider.secret.prefs`. Ciphertext restored from a backup onto another device cannot be read, so dispatch waits. `SyncProviderRuntime` retries a failed read until it succeeds or JS configures. Exception messages are not logged, only their type, and header values are never logged.

The background options live in `syncprovider.bgsync.prefs`, managed by `BackgroundSyncManager`.

## ConnectivityMonitor

`ConnectivityMonitor` registers a single `NetworkCallback` against the `ApplicationContext` (not an `Activity` — registering against an `Activity` loses callbacks during configuration changes). It exposes the latest `ConnectionState` as a `StateFlow<ConnectionState>` and emits `CONNECTION_CHANGED` whenever the snapshot changes.

`NetworkCapabilities` are inspected on every change to drive the `METERED` discriminator (`!hasCapability(NET_CAPABILITY_NOT_METERED)`). The monitor starts when the first JS engine attaches.

## SyncWorker (WorkManager)

`SyncWorker` is a `CoroutineWorker` registered via WorkManager:

- **`PeriodicWorkRequest`** (`syncprovider-flush-periodic`) for the regular cadence — enforced floor of **15 minutes** (WorkManager hard limit).
- **`OneTimeWorkRequest`** (`syncprovider-flush-now`) for the [retry kick](#retry-wake).

Both use `Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)` (or `UNMETERED` when `BackgroundSyncOptions.requiresUnmeteredNetwork` is true) plus `setRequiresCharging` / `setRequiresDeviceIdle` mappings.

`SyncWorker.doWork()` stays inert when dispatch is blocked by a persisted pause or `foregroundOnly`. `MANUAL` does not block it, because enabling background sync is an explicit request. Otherwise, with no JS engine attached, it first resets rows a dead process left `IN_FLIGHT`. It then calls `dispatcher.flush()` once and returns `Result.success()` on any completed flush, so WorkManager's backoff cannot race the persisted cooldowns. `Result.retry()` is reserved for a worker that fails as a whole.

Only the periodic job emits `BACKGROUND_SYNC_STARTED` / `BACKGROUND_SYNC_COMPLETED`.

## BootCompletedReceiver + RecoveryWorker

`BootCompletedReceiver` is declared in `AndroidManifest.xml` with intent filters for:

- `android.intent.action.BOOT_COMPLETED`
- `android.intent.action.LOCKED_BOOT_COMPLETED`
- `android.intent.action.MY_PACKAGE_REPLACED`

On any of these, it enqueues a one-time `RecoveryWorker`, which also runs each time a JS engine attaches. The worker:

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
