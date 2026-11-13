---
sidebar_position: 9
title: Battery Optimization
description: What makes react-native-sync-provider use the network and wake the device — flush triggers, background windows, probe traffic and batch dispatch — and the option that controls each, on Android and iOS.
keywords:
  - battery
  - workmanager-constraints
  - metered-network
  - background-urlsession
  - batch-size
  - foreground-only
  - react-native
  - react-native-sync-provider
---

# Battery Optimization

This guide explains what makes the library use the network and wake the device, and which option controls each source. It covers the library's own behavior. How Android and iOS defer background work is covered under [OS Scheduling](#os-scheduling).

## Overview

The library adds work in three ways:

- **Flush cycles.** A cycle sends up to `batchSize` queued requests. It runs only when something starts it: your code, `useAutoSync`, the retry timer or a background window. Enqueueing sends nothing.
- **Probe traffic.** With `healthCheckUrl` set, `HEAD` requests to that URL grade the network and check the backend. Without it, nothing is probed.
- **Background work.** After `enableBackgroundSync`, WorkManager jobs on Android and BGTasks on iOS run while the app is in the background or closed. On iOS, a request sent while the app is not active goes through a background `URLSession`, whether or not background sync is enabled, unless `foregroundOnly` is set.

It holds no wake lock of its own, starts no foreground service and never calls `beginBackgroundTask`. On Android, its manifest declares only `INTERNET`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED`, so it asks for no exemption from battery optimization. WorkManager's own manifest still merges the `FOREGROUND_SERVICE` and `WAKE_LOCK` permissions and its `SystemForegroundService` into your app, but the library never runs work in the foreground.

## What Starts a Flush

| Trigger | Platform | When | Controlled by |
| --- | --- | --- | --- |
| `useAutoSync` interval | Both | Every `intervalMs`, 60 000 ms by default. Skipped while `DISCONNECTED`. | `enabled`, `intervalMs`. The tick cannot be turned off while the hook is enabled. |
| `useAutoSync` reconnect | Both | On a change to `CONNECTED`, or to `METERED` with `flushOnMetered`. | `flushOnReconnect` (default `true`), `flushOnMetered` (default `false`) |
| `useAutoSync` foreground | Both | On every change to `active`. Skipped while `DISCONNECTED`. | `flushOnAppForeground` (default `false`) |
| Retry timer | Both | Once, at the soonest cooldown, and only in a process with JS. After `resumeSync()`, and on iOS also after a failed health check, at the soonest pending item, cooldown or not. | `pauseSync()`, `strategy: MANUAL` |
| Retry kick | Android | Once, at the soonest cooldown, as a WorkManager job under the background constraints. | `enableBackgroundSync`, `foregroundOnly`, `pauseSync()`, `MANUAL` |
| Periodic worker | Android | At least 15 minutes apart, under the background constraints. | `enableBackgroundSync` options, `foregroundOnly`, `pauseSync()` |
| BGTasks | iOS | When iOS grants a window. | `enableBackgroundSync` options, `foregroundOnly`, `pauseSync()` |
| Your code | Both | `flush()`, `drainUntilEmpty()`. | You |

The `useAutoSync` triggers run in JS, so they stop while the OS suspends the app. [Retry Policy](./retry-policy.md) lists every condition that stops a wake.

## Background Windows

### WorkManager Constraints (Android)

The periodic worker and the retry kick share one set of constraints:

| `BackgroundSyncOptions` | WorkManager |
| --- | --- |
| Not set | `NetworkType.CONNECTED` |
| `requiresUnmeteredNetwork: true` | `NetworkType.UNMETERED` |
| `requiresCharging: true` | `setRequiresCharging(true)` |
| `requiresDeviceIdle: true` | `enableBackgroundSync()` rejects with `BACKGROUND_TASK_REGISTRATION_FAILED`: WorkManager refuses the backoff policy the library sets on an idle-constrained request. Leave it unset |
| `minimumIntervalMs` | The period of the periodic worker, raised to 15 minutes when lower |

### BGTask Requests (iOS)

- **Refresh** (`com.gabriel-sisjr.syncprovider.background`). `enableBackgroundSync` submits it, to begin no earlier than `minimumIntervalMs` from then, and never less than 60 seconds. Each refresh run submits the next refresh and a processing request.
- **Processing** (`com.gabriel-sisjr.syncprovider.processing`). `requiresCharging` maps to `requiresExternalPower`, network connectivity is always required, and it sets no earliest begin date. Each processing run submits the next one.
- **Not mapped.** iOS applies neither `requiresUnmeteredNetwork` nor `requiresDeviceIdle`.

### Background URLSession (iOS)

While the app is not active, requests go through a background `URLSession`. It is configured with `isDiscretionary = false`, `allowsExpensiveNetworkAccess = true` and `allowsConstrainedNetworkAccess = true`, so these uploads can use cellular and Low Data Mode networks. `foregroundOnly: true` turns this session off.

iOS ignores `isDiscretionary = false` for transfers started while the app is in the background, which is when the library uses this session, so it may defer those uploads. The library sends one request at a time through this session, and Apple's [Downloading files in the background](https://developer.apple.com/documentation/foundation/downloading-files-in-the-background) notes that "if your app starts a single background download, gets resumed when the download completes, and then starts a new download, it will greatly increase the delay."

## Metered Networks

| Path | Runs on a metered network | How to keep it off |
| --- | --- | --- |
| `useAutoSync` interval and foreground triggers | Yes | `minNetworkQuality` |
| `useAutoSync` reconnect trigger | Only with `flushOnMetered: true` | Leave `flushOnMetered` off, the default |
| Retry timer, `flush()`, `drainUntilEmpty()` | Yes | `minNetworkQuality` |
| Android periodic worker and retry kick | Yes | `requiresUnmeteredNetwork: true` |
| iOS BGTasks | Yes: no unmetered constraint applies | `minNetworkQuality`, or `foregroundOnly: true` |
| iOS background `URLSession` | Yes, cellular and Low Data Mode included | `minNetworkQuality` |

`minNetworkQuality` gates every flush before it reads the queue. Without a measurement, an unmetered link grades `GOOD` and a metered one `MODERATE`. Android caps a link without `NET_CAPABILITY_VALIDATED` at `POOR`, and iOS Low Data Mode caps the grade at `MODERATE`. So `minNetworkQuality: NetworkQuality.GOOD` without `healthCheckUrl` lets cycles run only on unmetered links, on Android only validated ones, and on iOS outside Low Data Mode, and sends no probe. A gated flush dispatches no item and emits `SYNC_FAILED` with `NETWORK_QUALITY_TOO_LOW`. See [Connectivity Detection](./connectivity-detection.md#the-quality-gate).

`foregroundOnly: true` turns the background session off, but it is not a metered-network switch: a request then goes through the default session, which sets no restriction on cellular or Low Data Mode networks.

> **Tip — Pair the gate with the Android constraint:** WorkManager checks its constraints before it starts a worker, while the gate runs inside the worker. A window the gate skips is spent, so on Android also set `requiresUnmeteredNetwork: true`.

## Probe Traffic

Probes go to `healthCheckUrl` only, and only while it is set:

| Probe | What it sends | When |
| --- | --- | --- |
| Health check | One `HEAD`, with a 2 s timeout | Before a cycle, unless a `2xx` is younger than `healthCheckTtlMs` (default 60 000 ms). It runs before the queue is read, so a flush on an empty queue can still send it. After a failure, cycles are skipped without a request for `healthCheckBackoffMs` (default 30 000 ms). |
| Quality measurement | Three `HEAD`s in a row, with a 2 s timeout each | After a network change or a new `healthCheckUrl`, and, with `minNetworkQuality` set, when a flush or a held-wake re-check finds the last measurement 30 s old or older. The triggers and the delay differ by platform: see below. |

When a quality measurement starts:

- **Android.** A measurement is requested when the monitor starts, when the default network or its capabilities change, on every `configureSync`, and, with `minNetworkQuality` set, by every flush and held-wake check. It starts 1 000 ms after the request. A request sends nothing while a measurement of the same network, validation state and URL is younger than 30 s, or while one is running.
- **iOS.** A measurement starts 1 000 ms after a path update when the network has no measurement or one 30 s old or older (the first update of a process, a move, or a stale one on the same network), and 1 000 ms after a new `healthCheckUrl`. With `minNetworkQuality` set, it starts at once when a flush or a held-wake re-check reads one 30 s old or older.

A `2xx` measurement of the same URL also counts as a passed check for `healthCheckTtlMs`. It does not end a running backoff. To send fewer probes, raise `healthCheckTtlMs` above the period of your most frequent trigger, or leave `healthCheckUrl` unset. See [Connectivity Detection](./connectivity-detection.md#health-check).

## Batch Size

- **`batchSize` caps a cycle.** One cycle sends at most `batchSize` items, 25 by default.
- **Android sends a batch concurrently.** With `defaultHeaders` restored from disk, one item goes first, and the rest are sent only if the server does not reject its credential.
- **iOS sends a batch one item at a time.**
- **Leftover items wait.** A cycle that leaves items in the queue does not start another, and the retry wakes ignore items without a cooldown, except the timer after `resumeSync()`, and on iOS also after a failed health check. The next trigger takes them. `drainUntilEmpty()` runs cycles until the queue is empty or one of its bounds stops it.

## Built-In Safeguards

- **No offline flush from the retry timer.** A timer that fires offline keeps the wake and delivers it when connectivity returns.
- **No timer without JS.** A process with no JS, such as one started for a worker, a boot broadcast or a BGTask, arms no in-process retry timer.
- **One request per wake for an expired stored token.** With `defaultHeaders` restored from disk, a `401` or `403` stops the cycle, and the item waits at least 60 seconds.
- **No health-check request during its backoff.** Automatic wakes also wait at least 1 000 ms after a failed check.
- **Backoff on gated wakes.** A wake held below `minNetworkQuality` is re-checked after 30 s, then at doubling intervals up to 15 minutes.
- **No WorkManager retries for failed items.** `SyncWorker` returns `Result.success()` on every completed flush. `Result.retry()`, with exponential backoff from 1 minute, is kept for a worker that fails as a whole.
- **No offline ticks.** The `useAutoSync` interval and foreground triggers do nothing while the status is `DISCONNECTED`.

## Recommended Settings

| Goal | Settings |
| --- | --- |
| No background work | `foregroundOnly: true`. Sync then runs only while the app process is alive. |
| No metered traffic | Android: `requiresUnmeteredNetwork: true`. Both: `minNetworkQuality: NetworkQuality.GOOD` without `healthCheckUrl`, which also holds cycles on an Android link without `NET_CAPABILITY_VALIDATED` and in iOS Low Data Mode. |
| Background work only while charging | `requiresCharging: true`. It applies to the Android periodic worker and retry kick, and to the iOS processing task. iOS cannot meet this goal: the refresh task, which also flushes, ignores `requiresCharging`. |
| Fewer probes | Leave `healthCheckUrl` unset, or raise `healthCheckTtlMs`. |
| Fewer foreground flushes | A longer `intervalMs`, with `flushOnAppForeground` and `flushOnMetered` left off. |
| More items per wake | A larger `batchSize`. |

A configuration with metered traffic off:

```tsx
import {
  BackoffStrategy,
  configureSync,
  enableBackgroundSync,
  NetworkQuality,
  SyncStrategy,
} from '@gabriel-sisjr/react-native-sync-provider';

await configureSync({
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy: {
    maxAttempts: 5,
    backoff: BackoffStrategy.EXPONENTIAL,
    baseDelayMs: 1000,
    maxDelayMs: 60_000,
    jitter: true,
    retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
  },
  // Each call replaces the whole configuration: pass your defaultHeaders too.
  // Without healthCheckUrl, GOOD needs an unmetered link (validated, on Android), and nothing is probed.
  minNetworkQuality: NetworkQuality.GOOD,
});

await enableBackgroundSync({
  minimumIntervalMs: 15 * 60 * 1000,
  // Android: WorkManager starts no worker on a metered network.
  requiresUnmeteredNetwork: true,
});
```

## OS Scheduling

The OS decides when background windows run, and the library does not change that. Doze and OEM battery managers are covered in [Android Setup](../getting-started/android-setup.md), App Standby buckets and WorkManager scheduling in [Android Permissions](../production/android-permissions.md), and iOS scheduling, Low Power Mode and deferral in [Background Sync](./background-sync.md).

## Troubleshooting

### Background Uploads Use Cellular (iOS)

1. The background `URLSession` allows expensive and constrained networks, and iOS applies no unmetered constraint to BGTasks.
2. Gate cycles with `minNetworkQuality: NetworkQuality.GOOD` and no `healthCheckUrl`.
3. `foregroundOnly: true` stops the BGTasks and the background session, but not cellular use: a request then goes through the default session.

### A HEAD Request Before Every Flush

1. With `healthCheckUrl` set, a flush checks the backend unless a `2xx` is younger than `healthCheckTtlMs`, even on an empty queue.
2. Raise `healthCheckTtlMs`, lengthen `intervalMs`, or leave `healthCheckUrl` unset.

## Next Steps

- [Background Sync](./background-sync.md) -- Constraints, identifiers and foreground-only mode
- [Connectivity Detection](./connectivity-detection.md) -- The quality gate and the health check in depth
- [useAutoSync](../api-reference/hooks/useAutoSync.md) -- The JS-side flush triggers and their defaults
- [Retry Policy](./retry-policy.md) -- Who wakes the queue, and when
- [Crash Recovery](./crash-recovery.md) -- What happens to queued requests after a crash or a kill
