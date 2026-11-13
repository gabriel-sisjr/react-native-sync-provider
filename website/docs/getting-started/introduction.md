---
sidebar_position: 1
title: Introduction
description: Learn about @gabriel-sisjr/react-native-sync-provider, a Nitro-powered offline-first HTTP sync queue for React Native that survives app death and syncs in the background.
keywords:
  - react-native
  - offline-first
  - sync
  - http-queue
  - nitro-modules
  - background-sync
  - typescript
---

# Introduction

[![NPM Version](https://img.shields.io/npm/v/%40gabriel-sisjr%2Freact-native-sync-provider)](https://www.npmjs.com/package/@gabriel-sisjr/react-native-sync-provider)
[![NPM Downloads](https://img.shields.io/npm/dm/%40gabriel-sisjr%2Freact-native-sync-provider)](https://www.npmjs.com/package/@gabriel-sisjr/react-native-sync-provider)
[![CI Tests](https://github.com/gabriel-sisjr/react-native-sync-provider/actions/workflows/ci.yml/badge.svg)](https://github.com/gabriel-sisjr/react-native-sync-provider/actions/workflows/ci.yml)
[![License](https://img.shields.io/github/license/gabriel-sisjr/react-native-sync-provider)](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/LICENSE)

`@gabriel-sisjr/react-native-sync-provider` is an offline-first HTTP sync queue for React Native, built on [Nitro Modules](https://nitro.margelo.com/). Outbound HTTP requests are persisted on the native side (Core Data on iOS, Room on Android), so they survive a force-quit or a device reboot. They are dispatched by a flush while the app runs, and in OS-scheduled background windows while it does not.

## Why This Library

Most offline queues for React Native flush only while the app is in the foreground. Once the user backgrounds the app and the OS suspends or kills the JS runtime, queued requests wait until the user comes back.

`react-native-sync-provider` runs the queue natively:

- **Android** -- The queue persists in Room (KSP), requests go out through OkHttp and Coroutines, and `WorkManager` (`PeriodicWorkRequest` + `OneTimeWorkRequest`) flushes it in the background. After a reboot, `BootCompletedReceiver` schedules a recovery that re-registers the periodic work.
- **iOS** -- The queue persists in Core Data, requests go out through `URLSession`, and `BGTaskScheduler` (BackgroundTasks framework) flushes it in the background.
- **JS** -- A thin, typed facade and React hooks over [Nitro Modules](https://nitro.margelo.com/), so payloads cross the bridge as native objects, with no JSON serialization.

The OS decides when a background window runs, and it gives none to an app the user force-quit until the user opens it again. See [Background Sync](../guides/background-sync.md).

If you use `react-native-background-location` for GPS that keeps working in the background, this library is its sibling for HTTP requests. See [Ecosystem](#ecosystem).

## Key Features

- **Native persistent queue** -- Requests survive app force-quit, process kill, and device reboot (Core Data on iOS, Room on Android).
- **Background dispatch** -- Flushes via `BGTaskScheduler` (iOS) and `WorkManager` (Android) with no JS runtime required. The OS decides when, and a force-quit stops it until the user opens the app again.
- **Connectivity awareness** -- `NWPathMonitor` (iOS) and `ConnectivityManager` (Android) report the network state. `useAutoSync` flushes on reconnect, and the retry timer and `drainUntilEmpty` wait for the network.
- **Network quality and health checks** -- Every connection snapshot carries a `networkQuality` level. Opt in to `minNetworkQuality` to skip flushes on a poor link without spending attempts, and to `healthCheckUrl` to probe your backend before a batch. See [Connectivity Detection](../guides/connectivity-detection.md).
- **Dead-letter queue** -- A request that fails permanently is kept with its last error until you replay or discard it.
- **Declarative retry policy** -- Linear, exponential, or Fibonacci backoff with jitter, configurable retry-on-status codes, and a per-item attempt budget. Cooldowns persist across restarts, and a timer brings items back while the app runs.
- **Persisted configuration** -- A worker or BGTask with no JS honors your options, and `defaultHeaders` are encrypted at rest (Android Keystore, iOS Keychain).
- **Per-item priority lanes** -- `HIGH` / `NORMAL` / `LOW` dispatch order, deterministic within a flush cycle.
- **At-least-once delivery** -- A request can be sent twice after a crash, so endpoints must be idempotent. See the [Idempotency guide](../guides/idempotency.md).
- **First-class TypeScript** -- 24 facade functions, 2 listener helpers, 9 React hooks, and a complete type / enum / error surface, all strict-mode clean.
- **Optional Provider** -- An optional `<SyncProvider>` Context wraps `configureSync` + `enableBackgroundSync` lifecycle for app-level setup.
- **Unified hooks library** -- `useConnection`, `useSyncQueue`, `useSyncStatus`, `useOfflineQueue`, `useSyncEvents`, `useSyncConfig`, `useAutoSync`, `useSyncSnapshot`, `useSyncContext`.

## Platform Support

| Feature | Android | iOS | Tech |
|---|---|---|---|
| Queue persistence | Supported | Supported | Room (KSP) / Core Data |
| Background sync | Supported | Supported | WorkManager / BGTaskScheduler |
| Retry policy | Supported | Supported | Native-side (LINEAR / EXPONENTIAL / FIBONACCI + jitter) |
| Connectivity detection | Supported | Supported | ConnectivityManager / NWPathMonitor |
| Hooks | Supported | Supported | React 18+ hooks |
| Item ids (ULID) | Supported | Supported | Native ULID generator |
| Priority lanes | Supported | Supported | HIGH / NORMAL / LOW dispatch order |
| Crash recovery | `RecoveryWorker` on JS attach, boot and app update; `SyncWorker` with no JS attached | `RecoveryManager` on engine start, including a BGTask launch | Native |

## Requirements

| Requirement | Minimum Version |
|---|---|
| React Native | >= 0.74 |
| React | >= 18.2 |
| New Architecture (Nitro / Fabric) | Required |
| `react-native-nitro-modules` | ^0.35.8 (peer) |
| iOS deployment target | 15.0 |
| Android `minSdkVersion` | 24 |
| Android `compileSdkVersion` / `targetSdkVersion` | 36 |
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.2 |
| Node.js (dev) | >= 22.11 |

> **Warning:** This library requires the React Native New Architecture. Nitro Modules do not run on the legacy bridge. See the [Installation guide](./installation.md) for a verification step.

## How It Works

The library is layered so the JS facade is thin, the bridge is generated, and every persistence + dispatch concern lives natively:

1. **TypeScript layer** -- Validates inputs, hides the channel name, and translates the `getLastSyncResult()` sentinel back into `undefined`.
2. **Nitro spec** -- `src/SyncProvider.nitro.ts` is the source of truth. `yarn nitrogen` regenerates the platform base classes.
3. **Native layer** -- Each platform persists items, monitors connectivity, dispatches HTTP requests with retry, and registers an OS background task.

## Architecture Overview

```text
React Hooks / Functions
          |
          v
 src/index.tsx (JS facade)
          |
          v
Nitro HybridObject (generated bridge)
          |
     -----+-----
     |         |
     v         v
  Android     iOS
  Kotlin      Swift
     |         |
     v         v
  Room        Core Data
  OkHttp      URLSession
  WorkManager BGTaskScheduler
```

For a full architecture breakdown, see the [Architecture Overview](../architecture/overview.md).

## How It Compares

How the library compares with other offline-queue approaches for React Native:

| Concern | `react-native-sync-provider` | `@tanstack/react-query` (offline mutations) | `redux-offline` | `react-native-queue` (legacy) |
| --- | :---: | :---: | :---: | :---: |
| Native persistence (survives force-close) | yes | no | partial | partial |
| Background sync (app not running) | yes | no | no | no |
| New Architecture / JSI | yes | n/a | n/a | no |
| Configurable retry + jitter | yes | yes | partial | yes |
| Priority lanes | yes | no | no | yes |
| Connectivity-aware flush | yes | partial | yes | partial |
| TypeScript-first | yes | yes | partial | partial |

`yes` first-class · `partial` requires plugins / not the default · `no` not supported

Background sync runs in windows the OS schedules, and not at all after a force-quit until the user opens the app again. See [Background Sync](../guides/background-sync.md).

## Ecosystem

This library is part of a set of React Native libraries that share the same developer experience and conventions:

| Library | Purpose |
|---|---|
| [`@gabriel-sisjr/react-native-background-location`](https://github.com/gabriel-sisjr/react-native-background-location) | Background GPS tracking and geofencing, built on the New Architecture. |
| `@gabriel-sisjr/react-native-sync-provider` (this library) | Offline-first HTTP queue with background sync. |

`react-native-background-location` is a TurboModule. It drives a foreground service on Android and `CLLocationManager` on iOS, persists every fix to Room and Core Data, and ships native geofencing and crash recovery. See its [documentation](https://gabriel-sisjr.github.io/react-native-background-location/), its [npm package](https://www.npmjs.com/package/@gabriel-sisjr/react-native-background-location) and its [GitHub repository](https://github.com/gabriel-sisjr/react-native-background-location).

Use them independently or together. For example, enqueue each location update that background-location's `useLocationUpdates()` hook delivers, so trips upload once the device is back online:

```tsx
import { useLocationUpdates } from '@gabriel-sisjr/react-native-background-location';
import {
  enqueue,
  generateId,
  HttpMethod,
} from '@gabriel-sisjr/react-native-sync-provider';

export function LocationUploader() {
  useLocationUpdates({
    onLocationUpdate: (location) => {
      // background-location sends latitude and longitude as strings.
      enqueue({
        method: HttpMethod.POST,
        url: 'https://api.example.com/locations',
        contentType: 'application/json',
        headers: { 'Idempotency-Key': generateId() },
        body: JSON.stringify(location),
      }).catch((e) => console.warn('enqueue failed', e));
    },
  });

  return null;
}
```

The callback runs in JS, so it enqueues only the fixes that reach JS. Enqueued items still need a flush: mount [`useAutoSync`](../api-reference/hooks/useAutoSync.md), call `flush()`, or [enable background sync](../guides/background-sync.md).

## Next Steps

- [Installation](./installation.md) -- Add the package and wire up the platform setup
- [Quick Start](./quick-start.md) -- A working hooks-first screen in five minutes
- [iOS Setup](./ios-setup.md) -- Background Modes, Info.plist, Privacy Manifest
- [Android Setup](./android-setup.md) -- Permissions, Gradle versions, Doze mode
