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

`@gabriel-sisjr/react-native-sync-provider` is an offline-first HTTP sync queue for React Native, built on [Nitro Modules](https://nitro.margelo.com/). Outbound HTTP requests are persisted on the native side (Core Data on iOS, Room on Android), drained when connectivity allows, and dispatched in OS-scheduled background windows -- even after the app is force-quit or the device reboots.

## Key Features

- **Native persistent queue** -- Requests survive app force-quit, process kill, and device reboot (Core Data on iOS, Room on Android).
- **True background dispatch** -- Flushes via `BGTaskScheduler` (iOS) and `WorkManager` (Android) with no JS runtime required.
- **Connectivity-aware sync** -- `NWPathMonitor` (iOS) and `ConnectivityManager` (Android) trigger flushes the moment the device comes back online.
- **Declarative retry policy** -- Linear, exponential, or Fibonacci backoff with jitter, configurable retry-on-status codes, and a per-item attempt budget.
- **Per-item priority lanes** -- `HIGH` / `NORMAL` / `LOW` dispatch order, deterministic within a flush cycle.
- **ULID idempotency** -- Every item carries a native-assigned ULID so retries are safe; mirror it into your `Idempotency-Key` header for exactly-once server semantics.
- **First-class TypeScript** -- 19 facade functions, 7 React hooks, and a complete type / enum / error surface, all strict-mode clean.
- **Optional Provider** -- An optional `<SyncProvider>` Context wraps `configureSync` + `enableBackgroundSync` lifecycle for app-level setup.
- **Unified hooks library** -- `useConnection`, `useSyncQueue`, `useSyncStatus`, `useOfflineQueue`, `useSyncEvents`, `useSyncConfig`, `useAutoSync`.
- **Battery-friendly defaults** -- Conservative interval clamps, metered-link awareness, and OS-respecting constraints out of the box.

## Platform Support

| Feature | iOS | Android | Tech |
|---|---|---|---|
| Queue persistence | Supported | Supported | Core Data / Room (KSP) |
| Background sync | Supported | Supported | BGTaskScheduler / WorkManager |
| Retry policy | Supported | Supported | Native-side (LINEAR / EXPONENTIAL / FIBONACCI + jitter) |
| Connectivity detection | Supported | Supported | NWPathMonitor / ConnectivityManager |
| Hooks | Supported | Supported | React 18+ hooks |
| Idempotency (ULID) | Supported | Supported | Native ULID generator |
| Priority lanes | Supported | Supported | HIGH / NORMAL / LOW dispatch order |
| Crash recovery | App launch handler | BootCompletedReceiver + RecoveryWorker | Native |

## Requirements

| Requirement | Minimum Version |
|---|---|
| React Native | >= 0.74 |
| React | >= 18.2 |
| New Architecture (Nitro / Fabric) | Required |
| `react-native-nitro-modules` | ^0.35.6 (peer) |
| iOS deployment target | 13.0 |
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
