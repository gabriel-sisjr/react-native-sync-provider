# @gabriel-sisjr/react-native-sync-provider

[![NPM Version](https://img.shields.io/npm/v/%40gabriel-sisjr%2Freact-native-sync-provider)](https://www.npmjs.com/package/@gabriel-sisjr/react-native-sync-provider)
[![NPM Beta](https://img.shields.io/npm/v/%40gabriel-sisjr%2Freact-native-sync-provider/beta)](https://www.npmjs.com/package/@gabriel-sisjr/react-native-sync-provider/v/beta)
[![NPM Downloads](https://img.shields.io/npm/dm/%40gabriel-sisjr%2Freact-native-sync-provider)](https://www.npmjs.com/package/@gabriel-sisjr/react-native-sync-provider)
[![NPM Total Downloads](https://img.shields.io/npm/dt/%40gabriel-sisjr%2Freact-native-sync-provider)](https://www.npmjs.com/package/@gabriel-sisjr/react-native-sync-provider)
[![CI Tests](https://github.com/gabriel-sisjr/react-native-sync-provider/actions/workflows/ci.yml/badge.svg)](https://github.com/gabriel-sisjr/react-native-sync-provider/actions/workflows/ci.yml)
[![Code Coverage](https://codecov.io/gh/gabriel-sisjr/react-native-sync-provider/branch/develop/graph/badge.svg)](https://codecov.io/gh/gabriel-sisjr/react-native-sync-provider)
[![Pre-release CI](https://github.com/gabriel-sisjr/react-native-sync-provider/actions/workflows/prerelease.yml/badge.svg?branch=develop&label=Pre-release)](https://github.com/gabriel-sisjr/react-native-sync-provider/actions/workflows/prerelease.yml)
[![Release CI](https://github.com/gabriel-sisjr/react-native-sync-provider/actions/workflows/publish.yml/badge.svg?branch=main&label=Release)](https://github.com/gabriel-sisjr/react-native-sync-provider/actions/workflows/publish.yml)
[![GitHub Stars](https://img.shields.io/github/stars/gabriel-sisjr/react-native-sync-provider)](https://github.com/gabriel-sisjr/react-native-sync-provider/stargazers)
[![License](https://img.shields.io/github/license/gabriel-sisjr/react-native-sync-provider)](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/LICENSE)
[![Bundlephobia](https://img.shields.io/bundlephobia/minzip/%40gabriel-sisjr%2Freact-native-sync-provider?label=size)](https://bundlephobia.com/package/@gabriel-sisjr/react-native-sync-provider)
[![New Architecture](https://img.shields.io/badge/Architecture-New-blue.svg)](https://reactnative.dev/docs/the-new-architecture/landing-page)
[![Nitro Module](https://img.shields.io/badge/Built%20with-Nitro%20Modules-purple.svg)](https://nitro.margelo.com/)
![Platform Android](https://img.shields.io/badge/platform-Android-green)
![Platform iOS](https://img.shields.io/badge/platform-iOS-blue)
![TypeScript](https://img.shields.io/badge/TypeScript-Ready-blue)

A cross-platform React Native library for offline-first HTTP sync built on Nitro Modules (New Architecture). Persists outbound requests on the native side, drains them when connectivity returns, and keeps flushing even when the app is killed — with retry, priority lanes, and OS-scheduled background dispatch on both Android and iOS.

**[Read the full documentation](https://gabriel-sisjr.github.io/react-native-sync-provider/)**

## Table of Contents

- [Why this library](#why-this-library)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Hooks](#hooks)
- [API Reference](#api-reference)
- [Types](#types)
- [Enums](#enums)
- [How it compares](#how-it-compares)
- [Ecosystem](#ecosystem)
- [Performance notes](#performance-notes)
- [Documentation](#documentation)
- [Platform Support](#platform-support)
- [FAQ](#faq)
- [Contributing](#contributing)
- [License](#license)

## Why this library

Most "offline queue" libraries in the React Native ecosystem flush their queue **only while the app is in foreground**. The moment the user backgrounds the app, the network drops, and the OS kills the JS runtime — your queued requests sit there until the user comes back.

`react-native-sync-provider` solves that with **first-class native execution**:

- **iOS**: queue persists in **Core Data**, dispatch runs on `URLSession`, background flushes are scheduled via **`BGTaskScheduler`** (BackgroundTasks framework).
- **Android**: queue persists in **Room** (KSP), dispatch uses **OkHttp + Coroutines**, background flushes are orchestrated by **`WorkManager`** (`PeriodicWorkRequest` + `OneTimeWorkRequest`), surviving Doze mode and reboots via `BootCompletedReceiver`.
- **JS**: thin, ergonomic facade with TypeScript-first design and React hooks. Powered by [Nitro Modules](https://nitro.margelo.com/) — no JSON bridge serialization, ~15× faster JSI calls.

If `react-native-background-location` is what you reach for when you need GPS that survives the app being closed, **`react-native-sync-provider` is its sibling for HTTP requests** that need the same guarantee. The two libraries are designed to be used together as part of the same ecosystem — same DX, same patterns.

## Features

- Cross-platform offline-first HTTP queue (Android and iOS)
- Native persistence: Room (Android) / Core Data (iOS) — queue survives force-close and device reboot
- OS-scheduled background sync: `WorkManager` (Android) / `BGTaskScheduler` (iOS) flush even with app closed
- Connectivity-aware dispatch via `ConnectivityManager.NetworkCallback` (Android) and `NWPathMonitor` (iOS)
- Declarative retry policy with linear / exponential / fibonacci backoff, jitter, and a per-status-code allowlist
- Priority lanes (`HIGH` / `NORMAL` / `LOW`) enforced by the native dispatcher
- Three sync strategies: `AUTOMATIC` (flush on connectivity), `MANUAL` (consumer-driven), `OPPORTUNISTIC` (charging + unmetered)
- 7 fully typed React hooks for connection, queue, status, events, config, and an opt-in orchestrator
- 15 typed event types streamed over a single `sync-event` channel for observability
- `SyncError` + `SyncErrorCode` discriminated error surface for clean pattern matching
- Idempotent by design — native-assigned ULIDs guarantee no duplicates across retries or restarts
- Nitro-powered JSI bridge — no JSON marshalling tax, ~15× faster than legacy native modules
- Fully typed TypeScript API (`verbatimModuleSyntax`, `noUncheckedIndexedAccess`) with types shipped from the Nitro spec

## Requirements

|                              | Minimum                    |
| ---------------------------- | -------------------------- |
| iOS                          | 15.0 (`URLSession.data(for:)`) |
| Android `minSdkVersion`      | 24                         |
| React Native                 | 0.73+                      |
| React                        | 18.2+                      |
| New Architecture             | **required**               |
| `react-native-nitro-modules` | ^0.35.6                    |

## Installation

```sh
npm install @gabriel-sisjr/react-native-sync-provider react-native-nitro-modules
# or
yarn add @gabriel-sisjr/react-native-sync-provider react-native-nitro-modules
```

### Android Setup

`AndroidManifest.xml` permissions are merged automatically by the library — no manual edits required for a standard setup:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

> See the [Android Setup Guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/android-setup) and the [Android Permissions](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/production/android-permissions) reference for the full WorkManager + boot-receiver setup in existing apps.

### iOS Setup

1. Declare the BGTaskScheduler identifier in `ios/<YourApp>/Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
</array>
```

2. In Xcode, enable the **Background fetch** and **Background processing** modes in **Signing & Capabilities → + Capability → Background Modes**.

3. Run `pod install` in your `ios/` directory:

```sh
cd ios && bundle install && bundle exec pod install
```

> See the [iOS Setup Guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/ios-setup) and the [iOS Background Modes](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/production/ios-background-modes) reference for full details and App Store compliance requirements.

## Quick Start

```tsx
import {
  configureSync,
  enqueue,
  enableBackgroundSync,
  useConnection,
  useSyncQueue,
  useSyncStatus,
  SyncPriority,
  SyncStrategy,
  BackoffStrategy,
} from '@gabriel-sisjr/react-native-sync-provider';

// 1. Configure once on app boot
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
});

await enableBackgroundSync({
  minimumIntervalMs: 15 * 60 * 1000,
  requiresUnmeteredNetwork: false,
});

// 2. Enqueue a request — works offline, online, foreground or background
await enqueue({
  method: 'POST',
  url: 'https://api.example.com/events',
  headers: { Authorization: 'Bearer ...' },
  contentType: 'application/json',
  body: JSON.stringify({ event: 'screen_view', screen: 'Home' }),
  priority: SyncPriority.HIGH,
});

// 3. React to queue + connection state
function StatusBar() {
  const { isOnline, isMetered } = useConnection();
  const { size: pending } = useSyncQueue();
  const { isSyncing, lastResult } = useSyncStatus();

  return (
    <Text>
      {isOnline ? 'online' : 'offline'} · {pending} pending
      {isSyncing ? ' · syncing…' : ''}
    </Text>
  );
}
```

Force-close the app, toggle airplane mode on, enqueue more items via a deep link or push, turn airplane mode off — `BGTaskScheduler`/`WorkManager` will flush the queue without the app being opened.

For step-by-step setup, see the [Quick Start Guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/quick-start).

## Hooks

| Hook                                                                                                                       | Purpose                                                                                       |
| -------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| [`useConnection`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useConnection)       | Live connectivity snapshot (`status`, `type`, `isOnline`, `isMetered`).                       |
| [`useSyncQueue`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncQueue)         | Queue contents + write operations (`enqueue`, `enqueueBatch`, `removeItem`, `clearQueue`).    |
| [`useSyncStatus`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncStatus)       | Sync engine state, progress, last result, `flush`/`pause`/`resume`.                           |
| [`useOfflineQueue`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useOfflineQueue)   | Convenience hook combining connection + queue + sync state for offline-first UIs.             |
| [`useSyncEvents`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncEvents)       | Typed event stream (15 `SyncEventType`s) with optional filtering.                             |
| [`useSyncConfig`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncConfig)       | Read / update the live `SyncOptions`.                                                         |
| [`useAutoSync`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useAutoSync)           | Orchestrator: periodic interval + reconnect flush + opt-in AppState foreground trigger.       |

See the [Hooks API Reference](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncQueue) for complete documentation, options, and examples.

## API Reference

All sync methods are top-level **named exports**. The library does not ship a default export.

```typescript
import {
  enqueue,
  flush,
  configureSync,
  enableBackgroundSync,
} from '@gabriel-sisjr/react-native-sync-provider';

await configureSync({ /* ... */ });
await enableBackgroundSync({ minimumIntervalMs: 15 * 60 * 1000 });
const id = await enqueue({ method: 'POST', url: 'https://api.example.com/events' });
const result = await flush();
```

### Queue operations

| Method            | Signature                                              | Description                                                  |
| ----------------- | ------------------------------------------------------ | ------------------------------------------------------------ |
| `enqueue`         | `(item: SyncItemInput) => Promise<string>`             | Persist a single HTTP request; returns its native ULID.      |
| `enqueueBatch`    | `(items: SyncItemInput[]) => Promise<string[]>`        | Atomic batch enqueue; returns ULIDs in input order.          |
| `removeItem`      | `(id: string) => Promise<boolean>`                     | Remove a queued item by ULID. `false` if not found.          |
| `clearQueue`      | `() => Promise<void>`                                  | Drop every pending item.                                     |
| `getQueueSize`    | `() => Promise<number>`                                | Current number of pending items.                             |
| `getPendingItems` | `() => Promise<SyncItem[]>`                            | List pending items, sorted by priority + `createdAt`.        |

### Sync operations

| Method        | Signature                          | Description                                                    |
| ------------- | ---------------------------------- | -------------------------------------------------------------- |
| `flush`       | `() => Promise<SyncResult>`        | Force a flush cycle; resolves with the outcome.                |
| `pauseSync`   | `() => Promise<void>`              | Pause the engine. Enqueues still accepted; no flushes.         |
| `resumeSync`  | `() => Promise<void>`              | Resume after `pauseSync()`. Auto-flushes if strategy allows.   |
| `isSyncing`   | `() => Promise<boolean>`           | `true` while a flush cycle is in progress.                     |

### Configuration

| Method            | Signature                                | Description                                                      |
| ----------------- | ---------------------------------------- | ---------------------------------------------------------------- |
| `configureSync`   | `(options: SyncOptions) => Promise<void>`| Replace the active configuration; takes effect next flush cycle. |
| `getSyncConfig`   | `() => Promise<SyncOptions>`             | Read the currently active configuration.                         |

### History

| Method               | Signature                                       | Description                                                   |
| -------------------- | ----------------------------------------------- | ------------------------------------------------------------- |
| `getLastSyncResult`  | `() => Promise<SyncResult \| undefined>`        | Most recent flush result, or `undefined` if no flush yet.     |
| `getSyncHistory`     | `(limit?: number) => Promise<SyncResult[]>`     | Persisted flush results, most recent first. `0`/omitted = all.|
| `clearSyncHistory`   | `() => Promise<void>`                           | Clear persisted history (does not affect the queue).          |

### Connectivity

| Method                | Signature                              | Description                                |
| --------------------- | -------------------------------------- | ------------------------------------------ |
| `getConnectionStatus` | `() => Promise<ConnectionState>`       | One-shot read of the OS connectivity state. |

### Background sync

| Method                    | Signature                                              | Description                                                          |
| ------------------------- | ------------------------------------------------------ | -------------------------------------------------------------------- |
| `enableBackgroundSync`    | `(options: BackgroundSyncOptions) => Promise<void>`    | Register the OS-level background task (`BGTaskScheduler`/`WorkManager`). |
| `disableBackgroundSync`   | `() => Promise<void>`                                  | Cancel the OS-level background task registration.                    |
| `isBackgroundSyncEnabled` | `() => Promise<boolean>`                               | Status of the background registration.                               |

### Event listeners

| Symbol                    | Signature                                                          | Description                                                                |
| ------------------------- | ------------------------------------------------------------------ | -------------------------------------------------------------------------- |
| `addSyncEventListener`    | `(cb: (e: SyncEvent) => void) => Promise<string>`                  | Subscribe to the `'sync-event'` channel; returns the subscription id.      |
| `removeSyncEventListener` | `(id: string) => Promise<void>`                                    | Unsubscribe.                                                               |
| `SYNC_EVENT_CHANNEL`      | `const SYNC_EVENT_CHANNEL = 'sync-event'`                          | Channel constant for advanced consumers using the underlying Nitro API.    |

### Utilities

| Method                    | Signature                              | Description                                                       |
| ------------------------- | -------------------------------------- | ----------------------------------------------------------------- |
| `generateId`              | `() => string`                         | UUID v4 helper (Math.random-backed) for `Idempotency-Key` headers.|
| `isNativeModuleAvailable` | `() => boolean`                        | `true` when the Nitro HybridObject is resolvable.                 |

## Types

### SyncItemInput

```typescript
interface SyncItemInput {
  method: HttpMethod;
  url: string;
  headers?: Record<string, string>;
  body?: string;
  contentType?: string;
  priority?: SyncPriority;
  metadata?: Record<string, string>;
}
```

> The `id` and `createdAt` fields are intentionally omitted — the native layer assigns a ULID at `id` and an epoch-millisecond `createdAt` so the queue can guarantee idempotency and monotonic ordering even when JS and native clocks disagree.

### SyncItem

```typescript
interface SyncItem {
  id: string;
  method: HttpMethod;
  url: string;
  headers?: Record<string, string>;
  body?: string;
  contentType?: string;
  priority?: SyncPriority;
  createdAt: number;
  metadata?: Record<string, string>;
}
```

### RetryPolicy

```typescript
interface RetryPolicy {
  maxAttempts: number;
  backoff: BackoffStrategy;
  baseDelayMs: number;
  maxDelayMs: number;
  jitter: boolean;
  retryOnStatusCodes: number[];
}
```

> Network errors and timeouts are always retried regardless of `retryOnStatusCodes`. Non-listed 4xx/5xx responses are treated as permanent failures. The optional per-attempt `shouldRetry(error)` callback is deferred to v0.2 — it requires a JS-alive bridge round-trip on every retry decision.

### SyncOptions

```typescript
interface SyncOptions {
  strategy: SyncStrategy;
  retryPolicy: RetryPolicy;
  batchSize?: number;
  requestTimeoutMs?: number;
  maxQueueSize?: number;
  persistQueue?: boolean;
  defaultHeaders?: Record<string, string>;
}
```

### SyncResult

```typescript
interface SyncResult {
  startedAt: number;
  finishedAt: number;
  successCount: number;
  failureCount: number;
  succeededIds: string[];
  failedIds: string[];
  errors: Record<string, string>;
}
```

### SyncEvent

```typescript
interface SyncEvent {
  type: SyncEventType;
  timestamp: number;
  itemId?: string;
  progress?: number;          // SYNC_PROGRESS only
  errorCode?: SyncErrorCode;  // SYNC_FAILED, ITEM_FAILED, ITEM_RETRYING
  statusCode?: number;        // ITEM_SUCCEEDED, ITEM_FAILED
  attempt?: number;           // ITEM_RETRYING (1-based)
  connectionStatus?: ConnectionStatus; // CONNECTION_CHANGED
  metadata?: Record<string, string>;
}
```

### BackgroundSyncOptions

```typescript
interface BackgroundSyncOptions {
  minimumIntervalMs: number;
  requiresCharging?: boolean;
  requiresUnmeteredNetwork?: boolean;
  requiresDeviceIdle?: boolean;
  taskIdentifier?: string;
}
```

> Android `WorkManager` clamps `minimumIntervalMs` to a minimum of 15 minutes for periodic work. iOS `BGTaskScheduler` treats it as a soft hint and schedules at its discretion.

### ConnectionState

```typescript
interface ConnectionState {
  status: ConnectionStatus;
  type: ConnectionType;
  isInternetReachable?: boolean;
  isExpensive?: boolean;
}
```

### SyncError

```typescript
class SyncError extends Error {
  readonly code: SyncErrorCode;
  readonly cause?: unknown;
}
```

> Every public function rejects with a `SyncError` carrying a stable `code: SyncErrorCode`. Pattern-match on `error.code` for typed error handling — the `instanceof` check is preserved across the native bridge.

## Enums

### SyncStrategy

| Value           | Description                                                                                                |
| --------------- | ---------------------------------------------------------------------------------------------------------- |
| `AUTOMATIC`     | Flush as soon as the queue is non-empty and connectivity allows.                                           |
| `MANUAL`        | Queue is only drained when the consumer explicitly calls `flush()`.                                        |
| `OPPORTUNISTIC` | Like `AUTOMATIC`, but only on opportunistic windows (foreground resume, charging, unmetered network).      |

### SyncPriority

| Value     | Description                                                          |
| --------- | -------------------------------------------------------------------- |
| `HIGH`    | Drains before `NORMAL`/`LOW`. Use sparingly to avoid starvation.     |
| `NORMAL`  | Default bucket.                                                      |
| `LOW`     | Drains after `HIGH`/`NORMAL`. Suitable for telemetry and analytics.  |

### BackoffStrategy

| Value         | Formula                                                  |
| ------------- | -------------------------------------------------------- |
| `LINEAR`      | `delay = baseDelayMs * attempt` (capped at `maxDelayMs`).|
| `EXPONENTIAL` | `delay = baseDelayMs * 2^(attempt - 1)` (capped).        |
| `FIBONACCI`   | `delay = baseDelayMs * fib(attempt)` (capped).           |

### HttpMethod

| Value     | Notes                                                  |
| --------- | ------------------------------------------------------ |
| `GET`     | Must not carry a body.                                 |
| `POST`    | Typically carries a body.                              |
| `PUT`     | Full-resource replace.                                 |
| `PATCH`   | Partial update.                                        |
| `DELETE`  | Body is optional and discouraged.                      |

### ConnectionStatus

| Value          | Description                                                                  |
| -------------- | ---------------------------------------------------------------------------- |
| `CONNECTED`    | Device has an active internet path (Wi-Fi, cellular, ethernet, etc.).        |
| `DISCONNECTED` | Device is offline — no usable network path is available.                     |
| `METERED`      | Online but the active link is metered. Background sync is more conservative. |
| `UNKNOWN`      | Connectivity could not be determined yet.                                    |

### ConnectionType

| Value       | Description                                                |
| ----------- | ---------------------------------------------------------- |
| `WIFI`      | Wi-Fi network (typically unmetered).                       |
| `CELLULAR`  | Cellular data (LTE, 5G, etc.). Always treat as metered.    |
| `ETHERNET`  | Wired ethernet (tablets with USB-Ethernet adapters, tvOS). |
| `BLUETOOTH` | Personal area network bridged over Bluetooth.              |
| `VPN`       | Active VPN tunnel.                                         |
| `OTHER`     | Transport not covered above.                               |
| `NONE`      | No transport — paired with `DISCONNECTED`.                 |
| `UNKNOWN`   | Transport could not be determined yet.                     |

### SyncEventType

| Value                       | Triggered when                                                                  |
| --------------------------- | ------------------------------------------------------------------------------- |
| `ITEM_ENQUEUED`             | A new item was accepted into the queue.                                         |
| `ITEM_REMOVED`              | An item was removed (manual or post-success).                                   |
| `SYNC_STARTED`              | A flush cycle has begun.                                                        |
| `SYNC_PROGRESS`             | Coarse progress update for the current flush (`progress` in `[0, 1]`).          |
| `SYNC_SUCCEEDED`            | The current flush cycle finished with no failures.                              |
| `SYNC_FAILED`               | The current flush cycle finished with at least one failure.                     |
| `ITEM_SUCCEEDED`            | A single item dispatched successfully.                                          |
| `ITEM_FAILED`               | A single item failed permanently (retry budget exhausted).                      |
| `ITEM_RETRYING`             | A single item was scheduled for retry.                                          |
| `QUEUE_CLEARED`             | The queue was cleared (`clearQueue()` or `maxQueueSize` reached).               |
| `CONNECTION_CHANGED`        | Network connectivity changed.                                                   |
| `PAUSED`                    | Sync was paused via `pauseSync()`.                                              |
| `RESUMED`                   | Sync was resumed via `resumeSync()`.                                            |
| `BACKGROUND_SYNC_STARTED`   | OS-scheduled background sync window started.                                    |
| `BACKGROUND_SYNC_COMPLETED` | OS-scheduled background sync window finished.                                   |

### SyncErrorCode

| Value                                 | Permanent? | Thrown by                                       |
| ------------------------------------- | :--------: | ----------------------------------------------- |
| `NETWORK_ERROR`                       |     no     | `flush`                                         |
| `SERVER_ERROR`                        |     no     | `flush`                                         |
| `TIMEOUT`                             |     no     | `flush`                                         |
| `MAX_ATTEMPTS_EXCEEDED`               |    yes     | `flush` (per-item; surfaced in `SyncResult.errors`) |
| `UNAUTHORIZED`                        |    yes     | `flush`                                         |
| `INVALID_PAYLOAD`                     |    yes     | `enqueue`, `enqueueBatch`                       |
| `INVALID_URL`                         |    yes     | `enqueue`, `enqueueBatch`                       |
| `QUEUE_FULL`                          |    yes     | `enqueue`, `enqueueBatch`                       |
| `DUPLICATE_ITEM`                      |    yes     | `enqueue`, `enqueueBatch`                       |
| `BACKGROUND_TASK_REGISTRATION_FAILED` |    yes     | `enableBackgroundSync`                          |
| `NATIVE_MODULE_UNAVAILABLE`           |    yes     | every facade method when the native module is missing |

## How it compares

| Concern                                   | `react-native-sync-provider` | `@tanstack/react-query` (offline mutations) | `redux-offline` | `react-native-queue` (legacy) |
| ----------------------------------------- | :--------------------------: | :-----------------------------------------: | :-------------: | :---------------------------: |
| Native persistence (survives force-close) |              yes             |                     no                      |     partial     |            partial            |
| Background sync (app closed)              |              yes             |                     no                      |       no        |              no               |
| New Architecture / JSI                    |              yes             |                     n/a                     |       n/a       |              no               |
| Configurable retry + jitter               |              yes             |                     yes                     |     partial     |             yes               |
| Priority lanes                            |              yes             |                     no                      |       no        |             yes               |
| Connectivity-aware flush                  |              yes             |                   partial                   |       yes       |            partial            |
| TypeScript-first                          |              yes             |                     yes                     |     partial     |            partial            |

`yes` first-class · `partial` requires plugins / not the default · `no` not supported

## Ecosystem

This library is part of an opinionated set of React Native libraries that share the same DX and quality bar:

| Library                                                                                                                | Purpose                                        |
| ---------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| [`@gabriel-sisjr/react-native-background-location`](https://github.com/gabriel-sisjr/react-native-background-location) | Background GPS tracking + geofencing.          |
| `@gabriel-sisjr/react-native-sync-provider` (this library)                                                             | Offline-first HTTP queue with background sync. |

Use them independently or together — for example, pipe `useLocationUpdates()` events from `background-location` straight into `enqueue()` from `sync-provider` to build a fully offline-capable trip-tracking app.

## Performance notes

- The bridge is **Nitro Modules**, not TurboModules. Object payloads (e.g. `SyncItem`) cross the JSI boundary as native objects — no JSON `parse`/`stringify` tax.
- The native dispatcher runs on a dedicated background queue. Hooks subscribe via Nitro callbacks; React renders are debounced.
- Default batch size is `10`. Tunable via `configureSync({ batchSize })`.
- Background dispatch on iOS uses `URLSessionConfiguration.background` so uploads continue if the OS suspends the app mid-flight.

## Documentation

Browse the **[full documentation site](https://gabriel-sisjr.github.io/react-native-sync-provider/)** for comprehensive guides, API reference, and production checklists.

### Getting Started

- [Introduction](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/introduction)
- [Installation](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/installation) — Detailed setup for existing apps
- [Quick Start](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/quick-start) — Get running in 5 minutes
- [iOS Setup](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/ios-setup) · [Android Setup](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/android-setup)

### Guides

- [Offline Queue](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/offline-queue) — Queue lifecycle and contracts
- [Background Sync](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/background-sync) — `BGTaskScheduler` + `WorkManager` deep dive
- [Retry Policy](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/retry-policy) — Backoff curves, jitter, status-code allowlists
- [Connectivity Detection](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/connectivity-detection) — `NWPathMonitor` / `ConnectivityManager`
- [Error Handling](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/error-handling) — `SyncError` pattern matching
- [Priority & Ordering](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/priority-and-ordering) — Per-item priority semantics
- [Idempotency](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/idempotency) — Native ULIDs + `Idempotency-Key`

### API Reference

- [Functions](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/functions)
- [Hooks](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncQueue)
- [Types](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/types)
- [Enums](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/enums)
- [Errors](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/errors)
- [Listeners](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/listeners)
- [Context](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/context)

### Architecture

- [Overview](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/architecture/overview)
- [Data Flow](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/architecture/data-flow)
- [iOS Native](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/architecture/ios-native)
- [Android Native](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/architecture/android-native)

### Production

- [Production Checklist](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/production/production-checklist)
- [Privacy Manifest (iOS)](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/production/privacy-manifest)
- [iOS Background Modes](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/production/ios-background-modes)
- [Android Permissions](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/production/android-permissions)

### Help

- [Troubleshooting Guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/troubleshooting) — Symptom → cause → fix for common integration issues

## Platform Support

| Platform | Status    | Notes                                                                                 |
| -------- | --------- | ------------------------------------------------------------------------------------- |
| Android  | Supported | Kotlin native implementation. Min SDK 24, target SDK 36. Room + WorkManager + OkHttp. |
| iOS      | Supported | Swift native implementation. iOS 15+. Core Data + URLSession + BGTaskScheduler.       |

> **New Architecture is required.** The library is built on [Nitro Modules](https://nitro.margelo.com/), which require Fabric + TurboModules. See the [Platform Comparison](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/architecture/overview) for the cross-platform behavior matrix.

## FAQ

**Q. Will this work without the New Architecture?**
No. The library is built on Nitro Modules, which require Fabric + TurboModules.

**Q. Does it work on Expo?**
Yes, via Expo Dev Client / EAS Build (custom native code). It will not run on Expo Go.

**Q. How is this different from just calling `fetch` inside a `BackgroundFetch` task?**
You'd be writing all of this yourself: native persistence, retry policy, priority ordering, connectivity reaction, idempotency, recovery from interrupted in-flight items, queue inspection from JS, and React bindings. This library packages that.

**Q. Can I send multipart/form-data uploads?**
Yes — set `metadata.contentType = 'multipart/form-data'` and provide the body either as Base64 in `body` or as a file URL via `metadata.bodyFileUri`. See the [multipart uploads guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/offline-queue) for the full recipe.

**Q. What happens to in-flight items if the OS kills the process mid-upload?**
On next launch (or next `WorkManager` tick), the recovery layer atomically marks orphaned items as `pending` and they re-enter the queue. Combined with the ULID `id` and a server-side `Idempotency-Key` header (set via `metadata`), duplicates are eliminated.

**Q. Is the queue encrypted at rest?**
Not by default — encrypted storage at rest is a future enhancement under consideration.

## Contributing

Contributions are welcome. See the [Contributing Guide](CONTRIBUTING.md) for development workflow, coding standards, and how to submit pull requests.

This library uses [Nitro Modules](https://nitro.margelo.com/). Whenever you change `src/SyncProvider.nitro.ts`, you **must** run `yarn nitrogen` to regenerate the native bindings before the example app will compile.

## License

MIT © [Gabriel Santana](https://github.com/gabriel-sisjr)

Powered by [Nitro Modules](https://nitro.margelo.com/).
