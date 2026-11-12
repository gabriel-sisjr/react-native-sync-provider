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

| Feature | iOS | Android | Tech |
|---|---|---|---|
| Queue persistence | Supported | Supported | Core Data / Room (KSP) |
| Background sync | Supported | Supported | BGTaskScheduler / WorkManager |
| Retry policy | Supported | Supported | Native-side (LINEAR / EXPONENTIAL / FIBONACCI + jitter) |
| Connectivity detection | Supported | Supported | NWPathMonitor / ConnectivityManager |
| Hooks | Supported | Supported | React 18+ hooks |
| Item ids (ULID) | Supported | Supported | Native ULID generator |
| Priority lanes | Supported | Supported | HIGH / NORMAL / LOW dispatch order |
| Crash recovery | `RecoveryManager` on engine start, including a BGTask launch | `RecoveryWorker` on JS attach, boot and app update; `SyncWorker` with no JS attached | Native |

## Requirements

| Requirement | Minimum Version |
|---|---|
| React Native | >= 0.74 |
| React | >= 18.2 |
| New Architecture (Nitro / Fabric) | Required |
| `react-native-nitro-modules` | ^0.35.6 (peer) |
| iOS deployment target | 15.0 |
| Android `minSdkVersion` | 24 |
| Android `compileSdkVersion` / `targetSdkVersion` | 36 |
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.2 |
| Node.js (dev) | >= 22.11 |

:::warning
This library requires the React Native New Architecture. Nitro Modules do not run on the legacy bridge. See the [Installation guide](./installation.md) for a verification step.
:::

## How It Works

The library is layered so the JS facade is thin, the bridge is generated, and every persistence + dispatch concern lives natively:

```text
React Hooks / Functions
        |
        v
  src/index.tsx (JS facade)
        |
        v
  Nitro HybridObject (generated bridge)
        |
    ----+----
    |       |
    v       v
   iOS    Android
  Swift   Kotlin
   |       |
   v       v
 Core    Room
 Data     +
   +    OkHttp
URLSes  + WM
   +
 BGTask
```

1. **TypeScript layer** -- Validates inputs, hides the channel name, and translates the `getLastSyncResult()` sentinel back into `undefined`.
2. **Nitro spec** -- `src/SyncProvider.nitro.ts` is the source of truth. `yarn nitrogen` regenerates the platform base classes.
3. **Native layer** -- Each platform persists items, monitors connectivity, dispatches HTTP requests with retry, and registers an OS background task.

For a full architecture breakdown, see the [Architecture overview](../architecture/overview.md).

## Next Steps

- [Installation](./installation.md) -- Add the package and wire up the platform setup.
- [Quick Start](./quick-start.md) -- A working hooks-first screen in five minutes.
- [iOS Setup](./ios-setup.md) -- Background Modes, Info.plist, Privacy Manifest.
- [Android Setup](./android-setup.md) -- Permissions, Gradle versions, Doze mode.
