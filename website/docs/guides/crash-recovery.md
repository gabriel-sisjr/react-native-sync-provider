---
sidebar_position: 8
title: Crash Recovery
description: How react-native-sync-provider keeps queued requests through app crashes, force-quits, OS kills, device reboots and app updates — Android RecoveryWorker, iOS RecoveryManager, and what happens to a request that was in flight.
keywords:
  - crash-recovery
  - in-flight
  - recovery-worker
  - recovery-manager
  - boot-completed
  - at-least-once
  - react-native
  - react-native-sync-provider
---

# Crash Recovery

This guide explains how the library keeps queued requests through app crashes, force-quits, OS kills, device reboots and app updates, what happens to a request that was in flight when the process died, and what your app still has to do.

## How Persistence Works

The queue lives in native storage, not in JS. An item is on disk once `enqueue()` resolves: the native layer returns its id only after the write. Persistence cannot be turned off: `SyncOptions.persistQueue` is stored but has no effect.

### What Survives a Restart

| Data | Survives | Android | iOS |
| --- | --- | --- | --- |
| Queued items | Yes | Room table `sync_items` | Core Data entity `SyncItemEntity` |
| Each item's `attempts` and `nextRetryAt` | Yes | Columns of `sync_items` | Attributes of `SyncItemEntity` |
| Dead letters | Yes | Room table `sync_dead_letters` | Core Data entity `SyncDeadLetterEntity` |
| Sync history | Yes | Room table `sync_results` | Core Data entity `SyncResultEntity` |
| `configureSync` options | Yes | SharedPreferences. `defaultHeaders` are encrypted under an Android Keystore key | UserDefaults. `defaultHeaders` go to the Keychain |
| `enableBackgroundSync` options | Yes | SharedPreferences | UserDefaults |
| A pause from `pauseSync()` | Until a JS engine attaches | SharedPreferences | UserDefaults |
| Retry timer, credential hold, health-check backoff | No | Memory. The retry kick is WorkManager work and outlives the process | Memory |
| Hook state and event listeners | No | Memory | Memory |

Events are not stored either. On both platforms, an event emitted while no listener is registered is dropped.

> **Important — A downgrade wipes the Android queue:** Upgrades run explicit Room migrations. A downgrade to a build with an older database version falls back to a destructive migration, so the app wipes the queue instead of failing to open it.

## Android Recovery

### RecoveryWorker

`RecoveryWorker` is a one-time WorkManager job, enqueued as the unique work `syncprovider-recovery`. A request made while one has not finished is dropped. It is enqueued when:

- **A JS engine attaches.** The first native call from a JS engine attaches it to the native runtime, which enqueues recovery. A JS reload starts a new engine, so recovery runs again.
- **The device boots.** `BootCompletedReceiver` handles `BOOT_COMPLETED`, which arrives after the first unlock. `LOCKED_BOOT_COMPLETED` is in its filter, but the receiver is not direct-boot aware, so it does not receive that broadcast while the device is still locked.
- **The app is updated.** The same receiver handles `MY_PACKAGE_REPLACED`.

The worker then:

1. Resets every `IN_FLIGHT` item to `PENDING`, in one statement. It takes the flush mutex first, so it never touches the items of a running cycle. Only the status changes: `attempts` and `nextRetryAt` are kept.
2. Re-registers the periodic `SyncWorker` from the persisted options, if background sync is enabled and `foregroundOnly` is off.
3. Arms the next retry wake.

It sends no request, and nothing skips it: neither a pause nor `foregroundOnly` stops it. If it fails, it returns `Result.retry()`. It logs how many items it reset and emits no event.

### Recovery in SyncWorker

A `SyncWorker` run, periodic or retry kick, in a process with no JS engine attached resets `IN_FLIGHT` items itself before it flushes. Without that, its flush could not see the items a dead process left in flight. It takes the same flush mutex.

Unlike `RecoveryWorker`, it can be skipped: a worker blocked by a persisted pause or by `foregroundOnly` returns before the reset.

### Cancelled Cycles

A cycle that is cancelled while the process lives on leaves nothing in flight. Each request already sent finishes and records its outcome, and the items whose request never started go back to `PENDING`.

## iOS Recovery

### RecoveryManager

`RecoveryManager` runs once each time a process starts the `SyncEngine`. The engine starts on the first use of the library from JS, which creates the native object, or when a fired BGTask needs it with no JS loaded. Recovery runs asynchronously and does not block JS.

It resets every `IN_FLIGHT` item to `PENDING`, in one save. Only the status changes: `attempts` and `nextRetryAt` are kept. It sends no request, and neither a pause nor `foregroundOnly` stops it.

Not everything waits for it:

- **BGTask handlers wait.** Both handlers wait for recovery before they flush.
- **The retry timer waits only on attach.** The arm made when a JS-facing object attaches waits for recovery. A `configureSync()` or `resumeSync()` during startup, or the end of a cycle a startup `flush()` ran, re-arms without waiting, so a due cooldown can wake a flush before the reset.
- **A JS `flush()` does not wait.** A flush you call is not ordered after recovery, and can run before the reset.

> **Note — `RESUMED` is not a recovery signal:** When recovery resets at least one item, it emits `RESUMED` with `metadata.recovered` set to the count. The emitter keeps no backlog, so only a listener registered before that moment receives it, and Android emits nothing. Do not build on it.

### Background Uploads After a Relaunch

While the app is not active and `foregroundOnly` is off, uploads go through a background `URLSession`. The OS can finish such an upload while the app is suspended, and can relaunch the app for it.

The handlers that record each upload's outcome live in memory. When an upload completes in a later process, its outcome is dropped. The row stays `IN_FLIGHT` until recovery puts it back to `PENDING`, with its attempt spent and no new cooldown. The next flush sends it again, although the server may have accepted it.

### No Boot Hook

The library has no boot hook on iOS. After a reboot, recovery runs at the next engine start: when the user opens the app and JS uses the library, or when a BGTask fires.

## Platform Comparison

| Feature | Android | iOS |
| --- | --- | --- |
| Recovery component | `RecoveryWorker`, a one-time WorkManager job | `RecoveryManager`, run by `SyncEngine` at startup |
| Started by | The first native call from each JS engine, boot and app update | The first use from JS in the process, or a BGTask launch |
| After a reboot | When the boot broadcast arrives, through `BootCompletedReceiver` | At the next engine start |
| Background run with no JS | `SyncWorker` resets before its flush, unless a pause or `foregroundOnly` blocks it | The BGTask launch starts the engine, and the handler waits for recovery |
| Stopped by a pause or `foregroundOnly` | `RecoveryWorker`: never. The `SyncWorker` reset: yes | Never |
| Items in flight per cycle | The whole claimed batch, up to `batchSize` | One |
| Kill mid-request | Spends no attempt | Spends the attempt |
| `nextRetryAt` after the reset | Kept | Kept |
| Re-arms | The periodic job, if enabled, then the next retry wake | The retry timer, once a JS-facing object is attached |
| Event | None. The count is logged | `RESUMED` with `metadata.recovered`, to listeners registered at that moment |
| Requests sent by recovery | None | None |

## What Happens to In-Flight Items

What a dead process leaves behind depends on how far each item had got:

| State when the process dies | Android | iOS |
| --- | --- | --- |
| Claimed, request not started | `IN_FLIGHT`: a claim marks the whole batch. Recovery returns it to `PENDING`, unchanged. | Items the cycle has not reached are still `PENDING`: a cycle claims each item only when its dispatch starts, so there is nothing to recover. An item claimed but not yet sent (between the claim and the request, across a main-thread hop) is `IN_FLIGHT` with its attempt spent. |
| Request sent, outcome not recorded | `IN_FLIGHT`. An attempt is recorded only with its outcome, so `attempts` is unchanged. | `IN_FLIGHT`. The claim already counted the attempt, so it is spent. |
| Background upload that completes after a relaunch | Does not apply. | The outcome is dropped. Recovery returns the item to `PENDING`, with its attempt spent. |

After recovery, on both platforms:

- **No new cooldown.** The item keeps its `nextRetryAt`. A claimed item was already due, so it is eligible at once.
- **It is sent again.** If the request reached the server before the process died, the server receives it twice. Delivery is at least once: see [Idempotency](./idempotency.md).

## Recovery Scenarios

### Scenario 1: App Crash Mid-Dispatch

1. The process dies while a cycle is running.
2. The items in flight stay `IN_FLIGHT` on disk: the whole claimed batch on Android, the one item being sent on iOS.
3. The next time JS uses the library, recovery returns them to `PENDING`. On Android, a worker run with no JS, a boot or an app update does it too, and on iOS a BGTask launch.
4. The next flush sends them again.

**Your action:** Keep a flush trigger running, and make your endpoints idempotent.

### Scenario 2: Force-Quit or Force Stop

1. The user force-quits the app on iOS, or Force Stops it from the Android settings.
2. The queue stays on disk. Items in flight are handled as in Scenario 1.
3. No background work runs until the user opens the app again. iOS cancels the background uploads of an app the user force-quit, and does not launch it for BGTasks or for background uploads. Android stops the app's work after a Force Stop.
4. When the user opens the app and JS uses the library, recovery runs. On Android, `RecoveryWorker` also re-registers the periodic job, if background sync is enabled and `foregroundOnly` is off.

**Your action:** None beyond Scenario 1. Expect no progress while the app stays closed.

### Scenario 3: OS Kill in the Background

1. The OS ends the process while the app is in the background.
2. **Android.** A cycle that was running leaves its claimed batch `IN_FLIGHT`. The next `SyncWorker` run with no JS resets those items before it flushes, unless a pause or `foregroundOnly` blocks it. Otherwise `RecoveryWorker` resets them when JS next attaches, or at the next boot or app update.
3. **iOS.** An upload on the background session can still complete, and iOS can relaunch the app for it. The new process drops its outcome, and recovery returns the item to `PENDING` with its attempt spent. A BGTask launch runs recovery before its flush.
4. The next flush sends the recovered items again.

**Your action:** Make your endpoints idempotent.

### Scenario 4: Device Reboot

1. The device restarts. Items in flight are handled as in Scenario 1.
2. **Android.** `BootCompletedReceiver` enqueues `RecoveryWorker`. It resets the items, re-registers the periodic job if background sync is enabled and `foregroundOnly` is off, and arms the next retry wake. It sends nothing.
3. **Android.** Items that never failed wait for the next periodic run or flush. Items with a cooldown can also be woken by the retry kick, when background sync is enabled and `foregroundOnly` is off.
4. **iOS.** Recovery runs at the next engine start: when the user opens the app, or when a BGTask fires.

**Your action:** None.

### Scenario 5: App Update

1. A new version of the app is installed.
2. **Android.** `MY_PACKAGE_REPLACED` enqueues `RecoveryWorker`, as after a reboot.
3. **iOS.** Recovery runs at the next engine start.

**Your action:** None for an upgrade. A downgrade wipes the Android queue: see [What Survives a Restart](#what-survives-a-restart).

### Scenario 6: Repeated Crashes

1. The app keeps dying while a cycle is running. Recovery keeps no count of restarts on either platform.
2. **Android.** A kill records no attempt, so the items it cuts short keep their attempt count and are sent again after every restart.
3. **iOS.** Every kill spends the attempt of the item being sent. Once its `attempts` reach `maxAttempts`, the next cycle moves it to the dead letters with `MAX_ATTEMPTS_EXCEEDED`, without sending it again.

**Your action:** Fix the crash. On iOS, find the item with `listDeadLetter()` and put it back with `replayDeadLetter(id)` once the crash is fixed.

## Implementing Recovery

Recovery itself needs no code. Your app has two jobs: use the library early in every launch, so that recovery starts, and keep a flush trigger running, because recovery sends nothing.

```tsx
import { useMemo } from 'react';
import {
  BackoffStrategy,
  SyncProvider,
  SyncStrategy,
  useAutoSync,
} from '@gabriel-sisjr/react-native-sync-provider';
import { Root } from './Root';

function AutoSync() {
  // Recovery sends nothing: this keeps a flush coming after every launch.
  useAutoSync({ enabled: true, flushOnAppForeground: true });
  return null;
}

export default function App({ token }: { token: string }) {
  const options = useMemo(
    () => ({
      strategy: SyncStrategy.AUTOMATIC,
      retryPolicy: {
        maxAttempts: 5,
        backoff: BackoffStrategy.EXPONENTIAL,
        baseDelayMs: 1000,
        maxDelayMs: 60_000,
        jitter: true,
        retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
      },
      defaultHeaders: { Authorization: `Bearer ${token}` },
    }),
    [token]
  );

  // At the root, the Provider uses the library as the app starts,
  // so recovery starts on every launch.
  return (
    <SyncProvider options={options}>
      <AutoSync />
      <Root />
    </SyncProvider>
  );
}
```

What this relies on:

- **The retry wakes skip items without a cooldown.** The retry timer and the Android retry kick wake only for items with a cooldown (`nextRetryAt > 0`). The timer makes an exception after `resumeSync()`, and on iOS also after a failed health check: it then arms for the soonest `PENDING` item, cooldown or not. Otherwise a recovered item without a cooldown waits for a flush.
- **`useAutoSync` does not flush on mount.** Its first tick comes `intervalMs` after it is enabled, 60 seconds by default. The reconnect and foreground triggers fire on a change only.
- **A startup `flush()` can miss recovered items.** On Android, recovery is a separate WorkManager job. On iOS, a JS `flush()` does not wait for recovery. Either way, a flush called at startup can run before the reset, and the next trigger sends what it missed.

See [Quick Start](../getting-started/quick-start.md) for the same setup with background sync.

## Best Practices

### 1. Use the Library Early in Every Launch

Recovery starts the first time JS uses the library in a process, and on Android also at boot and after an app update. Until recovery runs, the items a dead process left `IN_FLIGHT` are neither listed nor sent. Mount `<SyncProvider>` or your sync hooks at the root, not on a screen the user may never open.

### 2. Keep a Flush Trigger Running

Mount `useAutoSync` for the life of the app, as in [Implementing Recovery](#implementing-recovery). A single `flush()` at startup is not enough: it can run before recovery.

### 3. Send an Idempotency-Key

A request can reach the server twice after a crash. Generate the key before `enqueue`:

```tsx
import {
  enqueue,
  generateId,
  HttpMethod,
} from '@gabriel-sisjr/react-native-sync-provider';

await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/orders',
  contentType: 'application/json',
  // Stored with the item: a request sent again after a crash carries the same key.
  headers: { 'Idempotency-Key': generateId() },
  body: JSON.stringify({ productId: 'sku-42', qty: 1 }),
});
```

Then dedupe on the server. See [Idempotency](./idempotency.md).

### 4. Treat an Item as Saved When `enqueue()` Resolves

The id comes back only once the item is on disk. If the process dies before `enqueue()` resolves, the item may or may not have been stored. Keep the data in your own state until then, and reuse the same `Idempotency-Key` if you enqueue it again, so the server can drop a duplicate.

## Troubleshooting

### Items Missing Right After a Relaunch

1. `getQueueSize()` and `getPendingItems()` count `PENDING` items only. An item a dead process left `IN_FLIGHT` is missing from them until recovery returns it to `PENDING`.
2. On Android, recovery is a WorkManager job that the first native call enqueues, so a read at startup can come before it.
3. `useSyncQueue` does not refresh for recovery: Android emits no event, and `RESUMED` is not one of its refresh events. Call its `refresh()`, or wait for the next cycle, whose events refresh it.

### Recovered Items Are Not Sent

1. Recovery sends nothing. Make sure something flushes: `useAutoSync`, `flush()` or background sync.
2. The retry timer and the Android retry kick do not wake for items without a cooldown. The timer makes an exception after `resumeSync()`, and on iOS also after a failed health check: it then arms for the soonest `PENDING` item, cooldown or not.
3. On Android, a worker run with no JS skips the reset and the flush while a persisted pause or `foregroundOnly` blocks it. The pause is cleared when a JS engine attaches.

### Duplicate Requests After a Crash

1. This is expected. A request the server accepted before the process died is sent again after recovery.
2. Send an `Idempotency-Key` and dedupe on the server. See [Idempotency](./idempotency.md).

### Items Dead-Lettered After Repeated Crashes (iOS)

1. On iOS, each kill mid-request spends an attempt. Once the item's `attempts` reach `maxAttempts`, the next cycle dead-letters it with `MAX_ATTEMPTS_EXCEEDED`, without a further request.
2. `listDeadLetter()` lists it. Once the crash is fixed, `replayDeadLetter(id)` puts it back in the queue as a fresh item.

## Next Steps

- [Idempotency](./idempotency.md) -- Make a request that is sent twice safe to receive
- [Background Sync](./background-sync.md) -- What runs while the app is in the background or closed
- [Retry Policy](./retry-policy.md) -- How attempts and cooldowns schedule the next try
- [Data Flow](../architecture/data-flow.md) -- The recovery flow next to the enqueue and flush flows
- [Battery Optimization](./battery-optimization.md) -- What makes the library use the network, and the options that control it
