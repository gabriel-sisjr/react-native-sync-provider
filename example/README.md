# Sync Provider Example App

Example React Native application demonstrating `@gabriel-sisjr/react-native-sync-provider`.

## What It Demonstrates

- **Hooks-first sync API** -- `useSyncQueue`, `useSyncStatus`, `useSyncEvents`, `useSyncConfig`, `useOfflineQueue`, `useConnection`, and `useAutoSync` wired into three real screens.
- **Native persistence** -- Items survive force-quit and device reboot via Core Data (iOS) and Room (Android).
- **Background sync** -- BGTaskScheduler on iOS and WorkManager on Android dispatch queued requests even when the app is killed.
- **Connection awareness** -- `useConnection` reflects native connectivity callbacks (`NWPathMonitor` / `ConnectivityManager.NetworkCallback`), including the metered flag.
- **Sync history & error drill-down** -- The History tab pages through `getSyncHistory()` and lets you expand `SyncErrorCode` per failed item.
- **Configurable retry policy** -- The Config tab edits `SyncOptions` live via `configureSync`, including exponential backoff and per-status retry list.
- **Crash & boot recovery** -- The `BootCompletedReceiver` on Android and the `handleBackgroundURLSessionEvents` hook on iOS drive recovery without user action.

## Screenshots

> Screenshots are placeholders -- capture them from a real device/simulator after running the demo.

## Running the Example

From the repository root:

```bash
# Install workspace dependencies
yarn

# Regenerate Nitro bindings (required before any build)
yarn nitrogen

# Start Metro bundler
yarn example start

# Run on Android device/emulator
yarn example android

# Run on iOS simulator (requires CocoaPods)
yarn example ios
```

For iOS, install CocoaPods on first run or whenever native deps change:

```bash
cd example && bundle install && bundle exec pod install --project-directory=ios
```

## Default Configuration

The example bootstraps `SyncProvider` with `INITIAL_SYNC_OPTIONS` (see `src/App.tsx`):

| Field                            | Default                               | Notes                                                   |
| -------------------------------- | ------------------------------------- | ------------------------------------------------------- |
| `strategy`                       | `SyncStrategy.AUTOMATIC`              | Drains the queue opportunistically when online.         |
| `retryPolicy.maxAttempts`        | `5`                                   | Per-item attempts before marking permanently failed.    |
| `retryPolicy.backoff`            | `BackoffStrategy.EXPONENTIAL`         | Doubles delay each attempt up to `maxDelayMs`.          |
| `retryPolicy.baseDelayMs`        | `1000`                                | First retry waits 1 second.                             |
| `retryPolicy.maxDelayMs`         | `60000`                               | Cap of 60 seconds between retries.                      |
| `retryPolicy.jitter`             | `true`                                | Adds randomization to avoid thundering herd.            |
| `retryPolicy.retryOnStatusCodes` | `[408, 425, 429, 500, 502, 503, 504]` | HTTP codes that trigger retry; others fail immediately. |
| `batchSize`                      | `10`                                  | Max items per dispatch batch.                           |
| `requestTimeoutMs`               | `30000`                               | Per-request timeout.                                    |
| `maxQueueSize`                   | `1000`                                | Hard cap; further enqueues throw `QUEUE_FULL`.          |
| `persistQueue`                   | `true`                                | Items survive process death and reboot.                 |
| `defaultHeaders.endpoint`        | `https://httpbin.org/post`            | Echo endpoint used by the "Add random item" button.     |

## Screen Walkthrough

### Queue

Lists every pending `SyncItem` in priority order. Wires `useSyncQueue` for the items, `useSyncStatus` for the global syncing flag, `useOfflineQueue` for the count badge, and `useSyncConfig` to read the current strategy. The toolbar exposes "Add random item" (`enqueue`), "Force flush" (`flush`), per-row "Remove" (`removeItem`), and "Clear queue" (`clearQueue`).

### History

Shows the last results returned by `getSyncHistory()`. Uses `useSyncEvents` to live-prepend new entries as they fire (`SYNC_STARTED`, `SYNC_COMPLETED`, `ITEM_FAILED`, etc.) and `useSyncStatus` for the active-run indicator. Each row expands to reveal HTTP status, `SyncErrorCode`, and the captured error message. "Clear history" calls `clearSyncHistory`.

### Config

Form-driven editor for `SyncOptions`. Uses `useSyncConfig` to read the current snapshot and `useAutoSync` to display whether automatic dispatch is currently armed. Submitting the form calls `configureSync`. Toggling "Background sync" calls `enableBackgroundSync` / `disableBackgroundSync`.

## Hooks Reference

| Hook              | Where used                     | What it shows                                                    |
| ----------------- | ------------------------------ | ---------------------------------------------------------------- |
| `useConnection`   | `ConnectionBadge`              | Connectivity state, metered flag, transport type.                |
| `useSyncQueue`    | `QueueScreen`                  | Reactive list of pending `SyncItem` records.                     |
| `useSyncStatus`   | `QueueScreen`, `HistoryScreen` | Whether a sync run is currently in flight.                       |
| `useOfflineQueue` | `QueueScreen`                  | Number of items waiting while offline.                           |
| `useSyncEvents`   | `HistoryScreen`                | Live stream of sync events from the native side.                 |
| `useSyncConfig`   | `QueueScreen`, `ConfigScreen`  | Current `SyncOptions` snapshot from native.                      |
| `useAutoSync`     | `ConfigScreen`                 | Whether automatic dispatch is enabled and which strategy is set. |

## Offline Test

End-to-end offline scenario showing that the queue survives connectivity loss, force-quit and reboot.

### Steps

1. Open the example app, switch to the **Queue** tab.
2. With network on, tap **Add random item** five times. Five rows should appear, each enqueued via `enqueue` and tagged with `SyncPriority.NORMAL`.
3. Enable **Airplane mode** on the device.
4. Observe the **ConnectionBadge** flip to `DISCONNECTED`. Tap **Force flush** -- nothing dispatches; items stay queued (verify count is still 5).
5. Tap **Add random item** twice more. Queue grows to 7 -- proving local persistence.
6. **Force-quit** the app (swipe up on iOS, swipe away on Android).
7. **Reboot the device** (optional but recommended on Android, to validate `RECEIVE_BOOT_COMPLETED` -> `BootCompletedReceiver` -> `RecoveryWorker`).
8. Reopen the example app. The Queue tab still lists 7 pending items (loaded from Core Data / Room).
9. Disable **Airplane mode**.
10. The native connectivity callback (`NWPathMonitor` on iOS / `ConnectivityManager.NetworkCallback` on Android) fires; the **History** tab fills with one or more `SYNC_SUCCEEDED` entries.
11. Queue is drained to 0.

### What you should observe

- Each `httpbin.org/post` echo returns 200 OK, marking the item succeeded.
- The **History** tab shows the timestamp, succeeded count and any failures, with tap-to-expand drill-down for `SyncErrorCode`.
- The **Config** tab continues to reflect the active `SyncOptions`; toggling **Auto-sync** off and on calls `enableBackgroundSync` / `disableBackgroundSync`.

### iOS notes

- Background Modes (`fetch`, `processing`) and BGTaskSchedulerPermittedIdentifiers (`com.gabriel-sisjr.syncprovider.background`, `com.gabriel-sisjr.syncprovider.processing`) are already declared in `Info.plist`.
- Recovery on relaunch is handled in `AppDelegate.swift` via `SyncProvider.handleBackgroundURLSessionEvents(...)`.

### Android notes

- The example app declares `INTERNET`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED` in `AndroidManifest.xml`.
- Consumers must declare the same permissions in their own apps for boot recovery to work.

## Project Structure

```text
example/
  src/
    App.tsx                       # Root: SafeAreaProvider -> SyncProvider -> NavigationContainer
    styles.ts                     # Centralized StyleSheet.create()
    screens/
      QueueScreen.tsx             # Queue tab (useSyncQueue, useSyncStatus, useOfflineQueue, useSyncConfig)
      HistoryScreen.tsx           # History tab (useSyncEvents, useSyncStatus)
      ConfigScreen.tsx            # Config tab (useSyncConfig, useAutoSync)
    components/
      ConnectionBadge.tsx         # Shared connectivity indicator (useConnection)
      SyncItemRow.tsx             # Single queued-item row
      HistoryRow.tsx              # Single history entry with expandable error drill-down
  screenshots/                    # Placeholder folder for captured screens
  android/                        # Android project (manifest declares the 3 required permissions)
  ios/                            # iOS project (Background Modes + BGTaskScheduler identifiers wired)
```

## Requirements

- **Node** 24.13.0 (pinned via `.nvmrc`).
- **Yarn** 4.11 (Berry; declared via `packageManager`).
- **iOS simulator** running iOS 15+, with Background Modes capability already enabled in the example's Xcode project.
- **Android emulator** API 24+ with `INTERNET`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED` permissions declared in `AndroidManifest.xml`.
- `yarn nitrogen` must be run before the first build (the `nitrogen/` directory is gitignored).

## Troubleshooting

- **CocoaPods install fails** -- run `bundle install` inside `example/` first, then `bundle exec pod install --project-directory=ios`.
- **Native module unavailable on startup** -- the `nitrogen/` directory is missing; run `yarn nitrogen` from the repo root.
- **`yarn install` fails with version mismatch** -- ensure you are on Node 24.13.0 (`nvm use`) and Yarn 4.11 (`corepack enable`).
- **Android boot recovery does not trigger** -- confirm `RECEIVE_BOOT_COMPLETED` is declared in your app's `AndroidManifest.xml`; on Android 10+ it must also be granted at runtime.
- **iOS background task never fires** -- BGTaskScheduler will not run a task in the simulator unless triggered via the debug helper; test on a real device for true background dispatch.

## See Also

- [Quick Start](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/quick-start)
- [Hooks API Reference](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/category/hooks)
- [Installation Guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/installation)
- [Background Sync Advanced Guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/advanced/background-sync)
