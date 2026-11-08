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

A cross-platform React Native library for offline-first HTTP sync built on Nitro Modules (New Architecture). Persists outbound requests on the native side, retries them on a persisted backoff, and can dispatch them in OS-scheduled background windows while the app is not running, on both Android and iOS.

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
- OS-scheduled background sync: `WorkManager` (Android) / `BGTaskScheduler` (iOS) flush while the app is not running. The OS decides when, and iOS does not launch an app the user force-quit
- Connectivity monitoring via `ConnectivityManager.NetworkCallback` (Android) and `NWPathMonitor` (iOS), used by the retry timer, `drainUntilEmpty` and `useAutoSync`
- Declarative retry policy with linear / exponential / fibonacci backoff, jitter, and a per-status-code allowlist. Cooldowns persist across restarts, and a timer brings items back while the app runs
- Persisted configuration, so a worker or BGTask with no JS honors it. `defaultHeaders` are encrypted at rest (Android Keystore / iOS Keychain)
- Priority lanes (`HIGH` / `NORMAL` / `LOW`) enforced by the native dispatcher
- Two sync strategies: `AUTOMATIC` (the engine retries on its own) and `MANUAL` (it schedules nothing). `OPPORTUNISTIC` is reserved and currently behaves like `AUTOMATIC`
- 9 fully typed React hooks for connection, queue, status, events, config, a read-only dashboard snapshot, an opt-in orchestrator, and Provider-context access
- 15 typed event types streamed over a single `sync-event` channel for observability
- `SyncError` + `SyncErrorCode` discriminated error surface — with structured `validationErrors` — for clean pattern matching
- At-least-once delivery. A request can be sent twice after a crash, so endpoints must be idempotent. See the [Idempotency guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/idempotency)
- Nitro-powered JSI bridge — no JSON marshalling tax, ~15× faster than legacy native modules
- Fully typed TypeScript API (`verbatimModuleSyntax`, `noUncheckedIndexedAccess`) with types shipped from the Nitro spec

## Requirements

|                              | Minimum                        |
| ---------------------------- | ------------------------------ |
| iOS                          | 15.0 (`URLSession.data(for:)`) |
| Android `minSdkVersion`      | 24                             |
| React Native                 | 0.74+                          |
| React                        | 18.2+                          |
| New Architecture             | **required**                   |
| `react-native-nitro-modules` | ^0.35.6                        |

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

1. Declare both BGTask identifiers in `ios/<YourApp>/Info.plist`:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
  <string>com.gabriel-sisjr.syncprovider.processing</string>
</array>
```

2. In Xcode, enable the **Background fetch** and **Background processing** modes in **Signing & Capabilities → + Capability → Background Modes**.

3. Forward background `URLSession` events from your `AppDelegate`. The library registers its BGTask handlers by itself, so do not register them yourself.

```swift
import SyncProvider

func application(
  _ application: UIApplication,
  handleEventsForBackgroundURLSession identifier: String,
  completionHandler: @escaping () -> Void
) {
  HybridSyncProvider.handleBackgroundURLSessionEvents(
    identifier: identifier,
    completionHandler: completionHandler
  )
}
```

4. Run `pod install` in your `ios/` directory:

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
  useAutoSync,
  useConnection,
  useSyncQueue,
  useSyncStatus,
  SyncPriority,
  SyncStrategy,
  BackoffStrategy,
} from '@gabriel-sisjr/react-native-sync-provider';

// 1. Configure on app boot, and again whenever the token changes.
//    defaultHeaders are encrypted at rest and available to background workers.
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
  defaultHeaders: { Authorization: 'Bearer ...' },
});

await enableBackgroundSync({
  minimumIntervalMs: 15 * 60 * 1000,
  requiresUnmeteredNetwork: false,
});

// 2. Enqueue a request. It is stored at once and sent on the next flush.
await enqueue({
  method: 'POST',
  url: 'https://api.example.com/events',
  contentType: 'application/json',
  body: JSON.stringify({ event: 'screen_view', screen: 'Home' }),
  priority: SyncPriority.HIGH,
});

// 3. Flush while the app runs, and react to queue + connection state
function StatusBar() {
  const { isOnline } = useConnection();
  useAutoSync({ enabled: true, flushOnAppForeground: true });

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

Enqueueing does not dispatch: a flush does, from `useAutoSync`, `flush()`, or an OS background window. The queue survives a force-quit, but background windows do not: iOS does not launch an app the user force-quit, and Android stops its work after a Force Stop, until the user opens the app again.

For step-by-step setup, see the [Quick Start Guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/quick-start).

## Hooks

| Hook                                                                                                                     | Purpose                                                                                        |
| ------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------- |
| [`useConnection`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useConnection)     | Live connectivity snapshot (`status`, `type`, `isOnline`, `isMetered`).                        |
| [`useSyncQueue`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncQueue)       | Queue contents + write operations (`enqueue`, `enqueueBatch`, `removeItem`, `clearQueue`).     |
| [`useSyncStatus`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncStatus)     | Sync engine state, progress, last result, `flush`/`pause`/`resume`.                            |
| [`useOfflineQueue`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useOfflineQueue) | Convenience hook combining connection + queue + sync state for offline-first UIs.              |
| [`useSyncEvents`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncEvents)     | Typed event stream (15 `SyncEventType`s) with optional filtering.                              |
| [`useSyncConfig`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncConfig)     | Read / update the live `SyncOptions`.                                                          |
| [`useAutoSync`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useAutoSync)         | Orchestrator: periodic interval + reconnect flush + opt-in AppState foreground trigger.        |
| [`useSyncSnapshot`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncSnapshot) | Read-only dashboard aggregator (connection + queue + sync state) driven by one subscription.   |
| [`useSyncContext`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncContext)   | Read the `<SyncProvider>` context (`config` + `hasProvider`); never throws outside a Provider. |

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

await configureSync(options);
await enableBackgroundSync({ minimumIntervalMs: 15 * 60 * 1000 });
const id = await enqueue({
  method: 'POST',
  url: 'https://api.example.com/events',
});
const result = await flush();
```

### Queue operations

| Method            | Signature                                       | Description                                             |
| ----------------- | ----------------------------------------------- | ------------------------------------------------------- |
| `enqueue`         | `(item: SyncItemInput) => Promise<string>`      | Persist a single HTTP request; returns its native ULID. |
| `enqueueBatch`    | `(items: SyncItemInput[]) => Promise<string[]>` | Atomic batch enqueue; returns ULIDs in input order.     |
| `removeItem`      | `(id: string) => Promise<boolean>`              | Remove an item by ULID, in any status. `false` if no row has that id. Does not cancel a request in flight. |
| `clearQueue`      | `() => Promise<void>`                           | Drop every item, including failed ones.                 |
| `getQueueSize`    | `() => Promise<number>`                         | `PENDING` items, cooling ones included.                 |
| `getPendingItems` | `() => Promise<SyncItem[]>`                     | `PENDING` items, cooling included, in dispatch order.   |

### Sync operations

| Method            | Signature                                          | Description                                                                                                             |
| ----------------- | -------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `flush`           | `() => Promise<SyncResult>`                        | Run one cycle: one batch of eligible items. Per-item failures never reject. Does not check connectivity.                |
| `drainUntilEmpty` | `(options?: DrainOptions) => Promise<DrainResult>` | Flush until the queue is empty or a bound stops it. Sleeps through cooldowns and does not flush offline.                |
| `cancelDrain`     | `() => void`                                       | Hard stop for a running `drainUntilEmpty()`. No-op when none is running.                                                |
| `pauseSync`       | `() => Promise<void>`                              | Pause the engine until `resumeSync()` or the next launch. Enqueues still accepted; no flushes.                          |
| `resumeSync`      | `() => Promise<void>`                              | Resume after `pauseSync()`. Unless the strategy is `MANUAL`, pending items are dispatched, offline ones on reconnect.   |
| `isSyncing`       | `() => Promise<boolean>`                           | `true` while a flush cycle is in progress.                                                                              |

### Configuration

| Method          | Signature                                 | Description                                                                                  |
| --------------- | ----------------------------------------- | -------------------------------------------------------------------------------------------- |
| `configureSync` | `(options: SyncOptions) => Promise<void>` | Replace and persist the configuration. Omitting `defaultHeaders` deletes the stored copy.    |
| `getSyncConfig` | `() => Promise<SyncOptions>`              | Read the currently active configuration.                                                     |

### History

| Method              | Signature                                   | Description                                                    |
| ------------------- | ------------------------------------------- | -------------------------------------------------------------- |
| `getLastSyncResult` | `() => Promise<SyncResult \| undefined>`    | Most recent flush result, or `undefined` if no flush yet.      |
| `getSyncHistory`    | `(limit?: number) => Promise<SyncResult[]>` | Persisted flush results, most recent first. `0`/omitted = all. |
| `clearSyncHistory`  | `() => Promise<void>`                       | Clear persisted history (does not affect the queue).           |

### Connectivity

| Method                | Signature                        | Description                                 |
| --------------------- | -------------------------------- | ------------------------------------------- |
| `getConnectionStatus` | `() => Promise<ConnectionState>` | One-shot read of the OS connectivity state. |

### Background sync

| Method                    | Signature                                           | Description                                                              |
| ------------------------- | --------------------------------------------------- | ------------------------------------------------------------------------ |
| `enableBackgroundSync`    | `(options: BackgroundSyncOptions) => Promise<void>` | Register the OS-level background task (`BGTaskScheduler`/`WorkManager`). |
| `disableBackgroundSync`   | `() => Promise<void>`                               | Cancel the OS-level task and any pending Android retry kick.             |
| `isBackgroundSyncEnabled` | `() => Promise<boolean>`                            | Reports intent: `true` from enable to disable, even with `foregroundOnly`. |

### Event listeners

| Symbol                    | Signature                                         | Description                                                             |
| ------------------------- | ------------------------------------------------- | ----------------------------------------------------------------------- |
| `addSyncEventListener`    | `(cb: (e: SyncEvent) => void) => Promise<string>` | Subscribe to the `'sync-event'` channel; returns the subscription id.   |
| `removeSyncEventListener` | `(id: string) => Promise<void>`                   | Unsubscribe.                                                            |
| `SYNC_EVENT_CHANNEL`      | `const SYNC_EVENT_CHANNEL = 'sync-event'`         | Channel constant for advanced consumers using the underlying Nitro API. |

### Utilities

| Method                    | Signature       | Description                                                        |
| ------------------------- | --------------- | ------------------------------------------------------------------ |
| `generateId`              | `() => string`  | UUID v4 helper (Math.random-backed) for `Idempotency-Key` headers. |
| `isNativeModuleAvailable` | `() => boolean` | `true` when the Nitro HybridObject is resolvable.                  |

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
  attempts: number;
  nextRetryAt: number;
  metadata?: Record<string, string>;
}
```

> `attempts` and `nextRetryAt` are the per-item retry bookkeeping (added in v0.2). `attempts` counts the attempts recorded for the item, so `attempts === 0` means none was recorded, not that the item was never tried. `nextRetryAt === 0` means the item is eligible now; a positive value means a retry cooldown or a credential hold is pending, and the item is eligible once `nextRetryAt <= now`. Both are returned verbatim, never translated to `undefined`.

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

> Network errors and timeouts are always retried regardless of `retryOnStatusCodes`. Non-listed 4xx/5xx responses are permanent failures, and 401/403 stay permanent even when listed. A JS-side per-attempt retry override is planned for a later release. It would run only while JS is alive, because every retry decision would need a bridge round-trip.

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

> `batchSize` defaults to `25` and `maxQueueSize` to `10000`. `persistQueue` is stored but not used yet: the queue is always persisted. `defaultHeaders` are encrypted at rest, and calling `configureSync` without them deletes the stored copy, which makes it the logout path.

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

> `successCount` and `failureCount` are set on both platforms. An item waiting for a retry counts as neither. `succeededIds`, `failedIds` and `errors` are always empty on iOS. On Android, `errors` values read `"<SyncErrorCode>: <detail>"`.

### DrainOptions / DrainResult

```typescript
interface DrainOptions {
  maxIterations?: number; // default 32
  timeoutMs?: number; // default 60000
  delayBetweenCyclesMs?: number;
}

interface DrainResult {
  iterations: number;
  itemsDispatched: number;
  queueRemaining: number;
  stoppedReason: 'EMPTY' | 'MAX_ITERATIONS' | 'TIMEOUT' | 'CANCELLED';
}
```

> Bounds and outcome for [`drainUntilEmpty`](#sync-operations). Every option has a safe default, so a no-arg `drainUntilEmpty()` always ends, even under concurrent enqueues. `timeoutMs` and `cancelDrain()` are hard stops: every wait races them, and a native cycle already running keeps going after the drain returns. `maxIterations` counts real flushes, and `iterations` means cycles started. Starting a drain while another is finishing throws `DRAIN_ALREADY_ACTIVE`, so await the previous drain first.

### SyncEvent

```typescript
interface SyncEvent {
  type: SyncEventType;
  timestamp: number;
  itemId?: string;
  progress?: number; // SYNC_PROGRESS only (iOS)
  errorCode?: SyncErrorCode; // ITEM_FAILED, ITEM_RETRYING; SYNC_FAILED only if the cycle died
  statusCode?: number; // ITEM_SUCCEEDED, ITEM_FAILED, ITEM_RETRYING
  attempt?: number; // ITEM_SUCCEEDED, ITEM_FAILED, ITEM_RETRYING (1-based)
  connectionStatus?: ConnectionStatus; // CONNECTION_CHANGED
  metadata?: Record<string, string>; // iOS engine diagnostics, not the item's metadata
}
```

### BackgroundSyncOptions

```typescript
interface BackgroundSyncOptions {
  minimumIntervalMs: number;
  requiresCharging?: boolean;
  requiresUnmeteredNetwork?: boolean;
  requiresDeviceIdle?: boolean;
  foregroundOnly?: boolean;
  taskIdentifier?: string;
}
```

> Android `WorkManager` clamps `minimumIntervalMs` to a minimum of 15 minutes for periodic work. iOS `BGTaskScheduler` treats it as a soft hint and schedules at its discretion. `requiresUnmeteredNetwork` and `requiresDeviceIdle` apply on Android only, and on iOS `requiresCharging` applies to the processing task only. `taskIdentifier` is stored but not used yet: both platforms use fixed identifiers. Set `foregroundOnly: true` (added in v0.2, default `false`) to schedule **no** OS-level background dispatch — sync then runs only while the app is alive; launch/boot data-integrity recovery is never suppressed.

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
  readonly validationErrors?: ValidationIssue[];
}
```

> Every public function rejects with a `SyncError` carrying a stable `code: SyncErrorCode`. Pattern-match on `error.code` for typed error handling. Validation failures (`INVALID_URL` / `INVALID_PAYLOAD`) also populate `validationErrors` with every detected issue (added in v0.2). Native codes do not reach JS through a rejection yet: each function maps a native rejection to one fallback code, so a full queue rejects with `INVALID_PAYLOAD` and no `validationErrors`, not `QUEUE_FULL`. See [Errors](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/errors).

### ValidationIssue

```typescript
type ValidationIssue = {
  field: string; // e.g. "url", "headers.Authorization", "input"
  reason: string;
  value?: unknown;
  itemIndex?: number; // batch position; omitted for single-item enqueue
};
```

> Attached to `SyncError.validationErrors`. `enqueueBatch` collects issues across all items (each tagged with `itemIndex`) and throws **one** `INVALID_PAYLOAD`; `enqueue` throws the first issue's specific code with the full list still attached. `value` is set only for `method`, `priority` and `contentType`, because errors end up in logs and the other fields can hold credentials. `reason` never contains the URL, but the `message` of a single-item `enqueue` can.

## Enums

### SyncStrategy

| Value           | Description                                                                                                                                                       |
| --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `AUTOMATIC`     | The engine retries on its own: a failed item is dispatched again when its cooldown ends, and pending items on `resumeSync()`. Enqueueing does not trigger a flush. |
| `MANUAL`        | The engine schedules nothing on its own: no retry timer and no Android retry kick. Items go on `flush()`, plus any OS windows enabled by `enableBackgroundSync`. `useAutoSync` does not read the strategy, so it keeps flushing while enabled. |
| `OPPORTUNISTIC` | Reserved. Currently behaves like `AUTOMATIC`.                                                                                                                     |

### SyncPriority

| Value    | Description                                                         |
| -------- | ------------------------------------------------------------------- |
| `HIGH`   | Drains before `NORMAL`/`LOW`. Use sparingly to avoid starvation.    |
| `NORMAL` | Default bucket.                                                     |
| `LOW`    | Drains after `HIGH`/`NORMAL`. Suitable for telemetry and analytics. |

### BackoffStrategy

| Value         | Formula                                                   |
| ------------- | --------------------------------------------------------- |
| `LINEAR`      | `delay = baseDelayMs * attempt` (capped at `maxDelayMs`). |
| `EXPONENTIAL` | `delay = baseDelayMs * 2^(attempt - 1)` (capped).         |
| `FIBONACCI`   | `delay = baseDelayMs * fib(attempt)` (capped).            |

### HttpMethod

| Value    | Notes                             |
| -------- | --------------------------------- |
| `GET`    | Must not carry a body.            |
| `POST`   | Typically carries a body.         |
| `PUT`    | Full-resource replace.            |
| `PATCH`  | Partial update.                   |
| `DELETE` | Body is optional and discouraged. |

### ConnectionStatus

| Value          | Description                                                                  |
| -------------- | ---------------------------------------------------------------------------- |
| `CONNECTED`    | Device has an active internet path (Wi-Fi, cellular, ethernet, etc.).        |
| `DISCONNECTED` | Device is offline — no usable network path is available.                     |
| `METERED`      | Online but the active link is metered (cellular, hotspot).                   |
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

| Value                       | Triggered when                                                         |
| --------------------------- | ---------------------------------------------------------------------- |
| `ITEM_ENQUEUED`             | A new item was accepted into the queue.                                |
| `ITEM_REMOVED`              | An item was removed by `removeItem()`.                                 |
| `SYNC_STARTED`              | A flush cycle has begun.                                               |
| `SYNC_PROGRESS`             | Coarse progress update for the current flush. iOS only.                |
| `SYNC_SUCCEEDED`            | The cycle finished with no permanent failure, including 0/0 cycles.    |
| `SYNC_FAILED`               | The cycle finished with a permanent failure, or died.                  |
| `ITEM_SUCCEEDED`            | A single item dispatched successfully.                                 |
| `ITEM_FAILED`               | A single item failed permanently.                                      |
| `ITEM_RETRYING`             | A single item went back to wait for a retry.                           |
| `QUEUE_CLEARED`             | The queue was cleared by `clearQueue()`.                               |
| `CONNECTION_CHANGED`        | Network connectivity changed.                                          |
| `PAUSED`                    | Sync was paused via `pauseSync()`.                                     |
| `RESUMED`                   | Sync was resumed via `resumeSync()`, or iOS recovered items at launch. |
| `BACKGROUND_SYNC_STARTED`   | A background window started: a BGTask on iOS, the periodic worker on Android. |
| `BACKGROUND_SYNC_COMPLETED` | The same window finished. No `errorCode`: a failed flush reports it on `SYNC_FAILED`. |

### SyncErrorCode

| Value                                 | Permanent?      | Where you see it                                                       |
| ------------------------------------- | :-------------: | ---------------------------------------------------------------------- |
| `NETWORK_ERROR`                       |       no        | Item events. Also what `flush` rejects with when its cycle dies.       |
| `SERVER_ERROR`                        | if not retried  | Item events.                                                           |
| `TIMEOUT`                             |       no        | Item events.                                                           |
| `MAX_ATTEMPTS_EXCEEDED`               |       yes       | `ITEM_FAILED`, for a retriable failure with no attempts left.          |
| `UNAUTHORIZED`                        |       yes       | Item events, on a 401 or 403.                                          |
| `INVALID_PAYLOAD`                     |       yes       | `enqueue`, `enqueueBatch`, and most functions when native rejects.     |
| `INVALID_URL`                         |       yes       | `enqueue`. `enqueueBatch` reports URLs under `INVALID_PAYLOAD`.        |
| `QUEUE_FULL`                          |       yes       | Raised by native; reaches JS as `INVALID_PAYLOAD` for now.             |
| `DUPLICATE_ITEM`                      |       yes       | Reserved. Neither platform raises it.                                  |
| `BACKGROUND_TASK_REGISTRATION_FAILED` |       yes       | `enableBackgroundSync`, `disableBackgroundSync`, `isBackgroundSyncEnabled`. |
| `NATIVE_MODULE_UNAVAILABLE`           |       yes       | Every function when the native module is missing.                      |
| `DRAIN_ALREADY_ACTIVE`                |       yes       | `drainUntilEmpty`, when a drain is already running.                    |

## How it compares

| Concern                                   | `react-native-sync-provider` | `@tanstack/react-query` (offline mutations) | `redux-offline` | `react-native-queue` (legacy) |
| ----------------------------------------- | :--------------------------: | :-----------------------------------------: | :-------------: | :---------------------------: |
| Native persistence (survives force-close) |             yes              |                     no                      |     partial     |            partial            |
| Background sync (app closed)              |             yes              |                     no                      |       no        |              no               |
| New Architecture / JSI                    |             yes              |                     n/a                     |       n/a       |              no               |
| Configurable retry + jitter               |             yes              |                     yes                     |     partial     |              yes              |
| Priority lanes                            |             yes              |                     no                      |       no        |              yes              |
| Connectivity-aware flush                  |             yes              |                   partial                   |       yes       |            partial            |
| TypeScript-first                          |             yes              |                     yes                     |     partial     |            partial            |

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
- The native dispatcher runs off the JS thread. Hooks subscribe via Nitro callbacks.
- Default batch size is `25`. Tunable via `configureSync({ batchSize })`. Android sends a batch concurrently, iOS one item at a time.
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
- [Migration Guides](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/migration) — Upgrade procedure between versions

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
You'd be writing all of this yourself: native persistence, retry policy, priority ordering, recovery from interrupted in-flight items, queue inspection from JS, and React bindings. This library packages that.

**Q. Does `enqueue` send the request?**
No. It stores the item. A flush sends it, from `useAutoSync`, `flush()`, or an OS background window. `AUTOMATIC` retries failed items on its own, but it does not flush on enqueue.

**Q. Can I send file uploads?**
No. `body` is a string sent as UTF-8 text, and there is no file-based body. You can send text-based formats by setting `contentType`, but binary multipart uploads are not supported.

**Q. What happens to in-flight items if the OS kills the process mid-upload?**
On the next launch, the recovery layer marks orphaned items as `PENDING` and they re-enter the queue. With no JS loaded, an iOS BGTask or an Android worker does the same before it flushes, unless a pause or `foregroundOnly` blocks the Android worker. Android also runs it after a reboot or an app update. Their attempt counters and cooldowns are kept, and the server may already have processed the request, so delivery is at least once. Send an `Idempotency-Key` header, set through `headers` with a key you generate before enqueueing, and dedupe on the server.

**Q. Is the queue encrypted at rest?**
No. Per-item `headers` and `body` are stored in plain text. Put credentials in `SyncOptions.defaultHeaders`, which are encrypted at rest (Android Keystore / iOS Keychain).

## Contributing

Contributions are welcome. See the [Contributing Guide](CONTRIBUTING.md) for development workflow, coding standards, and how to submit pull requests.

This library uses [Nitro Modules](https://nitro.margelo.com/). Whenever you change `src/SyncProvider.nitro.ts`, you **must** run `yarn nitrogen` to regenerate the native bindings before the example app will compile.

## License

MIT © [Gabriel Santana](https://github.com/gabriel-sisjr)

Powered by [Nitro Modules](https://nitro.margelo.com/).
