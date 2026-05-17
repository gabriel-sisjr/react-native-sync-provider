# @gabriel-sisjr/react-native-sync-provider

> Offline-first HTTP request queue with native persistence and true background sync for React Native — even when the app is closed.

[![npm version](https://img.shields.io/npm/v/@gabriel-sisjr/react-native-sync-provider.svg)](https://www.npmjs.com/package/@gabriel-sisjr/react-native-sync-provider)
[![npm downloads](https://img.shields.io/npm/dm/@gabriel-sisjr/react-native-sync-provider.svg)](https://www.npmjs.com/package/@gabriel-sisjr/react-native-sync-provider)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![CI](https://github.com/gabriel-sisjr/react-native-sync-provider/actions/workflows/ci.yml/badge.svg)](https://github.com/gabriel-sisjr/react-native-sync-provider/actions/workflows/ci.yml)
[![New Architecture](https://img.shields.io/badge/Architecture-New-blue.svg)](https://reactnative.dev/docs/the-new-architecture/landing-page)
[![Nitro Module](https://img.shields.io/badge/Built%20with-Nitro%20Modules-purple.svg)](https://nitro.margelo.com/)

> ⚠️ **Status**: pre-release. The public API is being designed and may change before `v1.0.0`.

## Why this library

Most "offline queue" libraries in the React Native ecosystem flush their queue **only while the app is in foreground**. The moment the user backgrounds the app, the network drops, and the OS kills the JS runtime — your queued requests sit there until the user comes back.

`react-native-sync-provider` solves that with **first-class native execution**:

- **iOS**: queue persists in **Core Data**, dispatch runs on `URLSession`, background flushes are scheduled via **`BGTaskScheduler`** (BackgroundTasks framework).
- **Android**: queue persists in **Room** (KSP), dispatch uses **OkHttp + Coroutines**, background flushes are orchestrated by **`WorkManager`** (`PeriodicWorkRequest` + `OneTimeWorkRequest`), surviving Doze mode and reboots via `BootCompletedReceiver`.
- **JS**: thin, ergonomic facade with TypeScript-first design and React hooks. Powered by [Nitro Modules](https://nitro.margelo.com/) — no JSON bridge serialization, ~15× faster JSI calls.

If `react-native-background-location` is what you reach for when you need GPS that survives the app being closed, **`react-native-sync-provider` is its sibling for HTTP requests** that need the same guarantee. The two libraries are designed to be used together as part of the same ecosystem — same DX, same patterns.

## Features

- 🔌 **Truly offline-first** — enqueue HTTP requests, the lib flushes when connectivity returns.
- 🌙 **Background sync** — `BGTaskScheduler` (iOS) / `WorkManager` (Android) flush even with app closed.
- 💾 **Native persistence** — Core Data + Room. Queue survives app force-close and device reboot.
- 🔁 **Configurable retry** — linear / exponential / fibonacci backoff with jitter, status-code allowlist, and an optional `shouldRetry(ctx)` JS override.
- 📡 **Connectivity-aware** — `NWPathMonitor` (iOS) / `ConnectivityManager.NetworkCallback` (Android) detect online/offline/metered transitions.
- 🪝 **7 React hooks** — `useConnection`, `useSyncQueue`, `useSyncStatus`, `useOfflineQueue`, `useSyncEvents`, `useSyncConfig`, `useAutoSync`.
- 🚦 **Priority lanes** — `HIGH` / `NORMAL` / `LOW` ordering enforced by the native dispatcher.
- 🛡️ **Idempotent by design** — every item gets a ULID; re-flushes never duplicate.
- ⚡ **Nitro-powered** — JS↔native bridge built on JSI. No JSON marshalling tax.
- 🆕 **New Architecture only** — TurboModules + Fabric.
- 🧪 **TypeScript strict** — `verbatimModuleSyntax`, `noUncheckedIndexedAccess`. Types ship from the spec.

## Requirements

|                              | Minimum                    |
| ---------------------------- | -------------------------- |
| iOS                          | 13.0 (BGTaskScheduler API) |
| Android `minSdkVersion`      | 24                         |
| React Native                 | 0.73+                      |
| React                        | 18.2+                      |
| New Architecture             | **required**               |
| `react-native-nitro-modules` | ^0.35.6                    |

## Installation

```sh
yarn add @gabriel-sisjr/react-native-sync-provider react-native-nitro-modules react-native-mmkv @react-native-community/netinfo
```

Then on iOS:

```sh
cd ios && bundle install && bundle exec pod install
```

### iOS — extra setup for background sync

In `ios/<YourApp>/Info.plist`, enable the BGTaskScheduler identifier:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
  <string>com.gabriel-sisjr.syncprovider.background</string>
</array>
```

Then in Xcode, enable the capabilities **Background fetch** and **Background processing** (Signing & Capabilities → + Capability → Background Modes).

### Android — extra setup for background sync

`AndroidManifest.xml` permissions are merged automatically by the library:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

No manual changes required.

## Quick start

```tsx
import {
  configureSync,
  enqueue,
  enableBackgroundSync,
  useConnection,
  useSyncQueue,
  useSyncStatus,
  SyncPriority,
} from '@gabriel-sisjr/react-native-sync-provider';

// 1. Configure once on app boot
configureSync({
  retryPolicy: {
    maxAttempts: 5,
    backoff: 'exponential',
    baseDelayMs: 1000,
    maxDelayMs: 60_000,
    jitter: true,
    retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
  },
});

await enableBackgroundSync({
  minIntervalMinutes: 15,
  requiresUnmeteredNetwork: false,
});

// 2. Enqueue a request — works offline, online, foreground or background
await enqueue({
  method: 'POST',
  url: 'https://api.example.com/events',
  headers: { Authorization: 'Bearer ...' },
  body: JSON.stringify({ event: 'screen_view', screen: 'Home' }),
  priority: SyncPriority.HIGH,
});

// 3. React to queue + connection state
function StatusBar() {
  const { isOnline, isMetered } = useConnection();
  const { size: pending } = useSyncQueue();
  const { isSyncing, lastSyncAt } = useSyncStatus();

  return (
    <Text>
      {isOnline ? '🟢 online' : '🔴 offline'} · {pending} pending
      {isSyncing ? ' · syncing…' : ''}
    </Text>
  );
}
```

That's it. Force-close the app, toggle airplane mode on, enqueue more items via a deep link or push, turn airplane mode off — `BGTaskScheduler`/`WorkManager` will flush the queue without the app being opened.

## API at a glance

### Functions

| Function                        | What it does                                                                |
| ------------------------------- | --------------------------------------------------------------------------- |
| `enqueue(item)`                 | Add a single HTTP request to the queue.                                     |
| `enqueueBatch(items)`           | Atomic batch enqueue.                                                       |
| `removeItem(id)`                | Remove a queued item by id.                                                 |
| `clearQueue()`                  | Drop everything.                                                            |
| `getQueueSize()`                | Number of pending items.                                                    |
| `getPendingItems()`             | List pending items (sorted by priority + createdAt).                        |
| `flush()`                       | Force a sync attempt now.                                                   |
| `pauseSync()` / `resumeSync()`  | Soft-pause without dropping the queue.                                      |
| `isSyncing()`                   | Is a sync currently in flight?                                              |
| `configureSync(options)`        | Set base URL, default headers, retry policy, batch size.                    |
| `getSyncConfig()`               | Read current config.                                                        |
| `getLastSyncResult()`           | Last result (success count, failures, timing).                              |
| `getSyncHistory(limit?)`        | Recent results.                                                             |
| `clearSyncHistory()`            | Clear history.                                                              |
| `getConnectionStatus()`         | One-shot read of current connectivity.                                      |
| `enableBackgroundSync(options)` | Register `BGTaskScheduler` (iOS) / `WorkManager` periodic worker (Android). |
| `disableBackgroundSync()`       | Unregister background sync.                                                 |
| `isBackgroundSyncEnabled()`     | Status of background registration.                                          |

### Hooks

| Hook                  | Returns                                                                                            |
| --------------------- | -------------------------------------------------------------------------------------------------- |
| `useConnection()`     | `{ status, type, isOnline, isMetered }`                                                            |
| `useSyncQueue()`      | `{ size, items, isLoading, error, enqueue, enqueueBatch, removeItem, clearQueue, refresh }`        |
| `useSyncStatus()`     | `{ isSyncing, isPaused, progress, lastResult, error, flush, pause, resume }`                       |
| `useOfflineQueue()`   | `{ connection, size, items, isSyncing, isWaitingForConnection, error, enqueue, flush }`            |
| `useSyncEvents(opts)` | `void` — subscribe to typed sync events (15 `SyncEventType`s) with optional filter.                |
| `useSyncConfig()`     | `{ config, isLoading, error, setConfig, refresh }`                                                 |
| `useAutoSync(opts)`   | `void` — orchestrator: periodic interval + reconnect flush + opt-in AppState foreground trigger.   |

### Listener helpers

| Symbol                                       | Purpose                                                                                |
| -------------------------------------------- | -------------------------------------------------------------------------------------- |
| `addSyncEventListener(cb): Promise<string>`  | Subscribe to the `'sync-event'` channel; returns the subscription id.                  |
| `removeSyncEventListener(id): Promise<void>` | Unsubscribe.                                                                           |
| `SYNC_EVENT_CHANNEL` (`'sync-event'`)        | Channel constant for advanced consumers using the underlying Nitro `addListener` API.  |

### Types & enums

`SyncItem`, `SyncItemInput`, `SyncOptions`, `SyncResult`, `SyncEvent`, `RetryPolicy`, `BackgroundSyncOptions`, `ConnectionState`, `ConnectionStatus`, `ConnectionType`, `SyncStrategy`, `SyncPriority`, `SyncEventType`, `BackoffStrategy`, `HttpMethod`, `SyncError`, `SyncErrorCode`.

Full reference is live at **<https://gabriel-sisjr.github.io/react-native-sync-provider/>**.

## How it compares

| Concern                                   | `react-native-sync-provider` | `@tanstack/react-query` (offline mutations) | `redux-offline` | `react-native-queue` (legacy) |
| ----------------------------------------- | :--------------------------: | :-----------------------------------------: | :-------------: | :---------------------------: |
| Native persistence (survives force-close) |              ✅              |                     ❌                      |       ⚠️        |              ⚠️               |
| Background sync (app closed)              |              ✅              |                     ❌                      |       ❌        |              ❌               |
| New Architecture / JSI                    |              ✅              |                     n/a                     |       n/a       |              ❌               |
| Configurable retry + jitter               |              ✅              |                     ✅                      |       ⚠️        |              ✅               |
| Priority lanes                            |              ✅              |                     ❌                      |       ❌        |              ✅               |
| Connectivity-aware flush                  |              ✅              |                     ⚠️                      |       ✅        |              ⚠️               |
| TypeScript-first                          |              ✅              |                     ✅                      |       ⚠️        |              ⚠️               |

`✅` first-class · `⚠️` partial / requires plugins · `❌` not supported

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

Full reference is live at **<https://gabriel-sisjr.github.io/react-native-sync-provider/>**:

- [Introduction](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/introduction)
- [Installation](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/installation)
- [Quick Start](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/quick-start)
- [iOS Setup](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/ios-setup) · [Android Setup](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/android-setup)
- Guides: [Offline Queue](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/offline-queue) · [Background Sync](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/background-sync) · [Retry Policy](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/retry-policy) · [Connectivity](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/connectivity-detection) · [Error Handling](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/error-handling) · [Priority & Ordering](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/priority-and-ordering) · [Idempotency](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/idempotency)
- API: [Functions](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/functions) · [Hooks](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncQueue) · [Types](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/types) · [Enums](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/enums) · [Errors](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/errors)
- Architecture: [Overview](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/architecture/overview) · [iOS](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/architecture/ios-native) · [Android](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/architecture/android-native)
- [Troubleshooting](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/troubleshooting)

## Roadmap

The **ALPHA VERSION** of library is being delivered iteratively toward `v0.1.0`.

| Milestone | Deliverable                                         |
| --------- | --------------------------------------------------- |
| **M1**    | API frozen (Nitro spec + types + errors).           |
| **M2**    | JS-only beta — testable in simulators / web mocks.  |
| **M3**    | iOS feature complete (Core Data + BGTaskScheduler). |
| **M4**    | Android feature complete (Room + WorkManager).      |
| **M5**    | Docs site live on GitHub Pages.                     |
| **M6**    | `v0.1.0` published to npm.                          |

## FAQ

**Q. Will this work without the New Architecture?**
No. The library is built on Nitro Modules, which require Fabric + TurboModules.

**Q. Does it work on Expo?**
Yes, via Expo Dev Client / EAS Build (custom native code). It will not run on Expo Go.

**Q. How is this different from just calling `fetch` inside a `BackgroundFetch` task?**
You'd be writing all of this yourself: native persistence, retry policy, priority ordering, connectivity reaction, idempotency, recovery from interrupted in-flight items, queue inspection from JS, and React bindings. This library packages that.

**Q. Can I send multipart/form-data uploads?**
Yes — set `metadata.contentType = 'multipart/form-data'` and provide the body either as Base64 in `body` or as a file URL via `metadata.bodyFileUri`. See `docs/advanced/multipart-uploads.md` (ships with M5).

**Q. What happens to in-flight items if the OS kills the process mid-upload?**
On next launch (or next `WorkManager` tick), the recovery layer atomically marks orphaned items as `pending` and they re-enter the queue. Combined with the ULID `id` and a server-side `Idempotency-Key` header (set via `metadata`), duplicates are eliminated.

**Q. Is the queue encrypted at rest?**
Not by default. Roadmap item for v0.2: optional integration with `react-native-mmkv` encrypted storage for sensitive headers/bodies.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

This library uses [Nitro Modules](https://nitro.margelo.com/). Whenever you change `src/SyncProvider.nitro.ts`, you **must** run `yarn nitrogen` to regenerate the native bindings before the example app will compile.

## License

MIT © [Gabriel Santana](https://github.com/gabriel-sisjr)
Powered by [Nitro Modules](https://nitro.margelo.com/)
