# react-native-sync-provider

Offline-first HTTP request queue for React Native, with native persistence and OS-scheduled background sync.

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

A cross-platform React Native library for offline-first HTTP sync built on Nitro Modules, for the New Architecture. Persists outbound requests on the native side, retries them on a persisted backoff, and can dispatch them in OS-scheduled background windows while the app is not running, on both Android and iOS.

**[Read the full documentation](https://gabriel-sisjr.github.io/react-native-sync-provider/)**

## Features

- Persistent HTTP request queue (Room on Android, Core Data on iOS) that survives a force-quit and a device reboot
- OS-scheduled background sync (WorkManager on Android, BGTaskScheduler on iOS) that flushes the queue while the app is not running, at times the OS chooses
- Declarative retry policy with linear, exponential or Fibonacci backoff, jitter and a status-code allowlist. Cooldowns persist across restarts
- Connectivity monitoring (ConnectivityManager on Android, NWPathMonitor on iOS) with a network-quality level on every snapshot, plus an opt-in [quality gate and health check](#network-quality-and-health-checks-optional)
- Dead-letter queue: a request that fails permanently is kept with its last error until you replay or discard it
- Persisted configuration that background workers honor without JS. `defaultHeaders` are encrypted at rest (Android Keystore, iOS Keychain)
- Priority lanes (`HIGH`, `NORMAL`, `LOW`) enforced by the native dispatcher
- React hooks: `useConnection`, `useSyncQueue`, `useSyncStatus`, `useOfflineQueue`, `useSyncEvents`, `useSyncConfig`, `useAutoSync`, `useSyncSnapshot`, `useSyncContext`
- Typed events (16 `SyncEventType`s on one `sync-event` channel) and errors (`SyncError` with a `SyncErrorCode` to pattern-match on)
- At-least-once delivery: a request can be sent twice after a crash, so endpoints must be [idempotent](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/idempotency)

## Requirements

| Requirement                  | Version                            |
| ---------------------------- | ---------------------------------- |
| React Native                 | >=0.74 (New Architecture required) |
| React                        | >=18.2                             |
| iOS                          | >=15.0                             |
| Android minSdk               | 24                                 |
| `react-native-nitro-modules` | ^0.35.8 (below 0.36)               |

## Installation

```bash
yarn add @gabriel-sisjr/react-native-sync-provider "react-native-nitro-modules@^0.35.8"
cd ios && pod install
```

Autolinking registers the library with Gradle and CocoaPods, and Gradle's manifest merger adds the library's `INTERNET`, `ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED` permissions and its boot receiver to your app manifest. Check the Gradle versions in the [Android setup guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/android-setup).

iOS apps must still enable the **Background fetch** and **Background processing** background modes, list `com.gabriel-sisjr.syncprovider.background` and `com.gabriel-sisjr.syncprovider.processing` under `BGTaskSchedulerPermittedIdentifiers` in `Info.plist`, and forward `application(_:handleEventsForBackgroundURLSession:completionHandler:)` from the `AppDelegate` to `HybridSyncProvider.handleBackgroundURLSessionEvents`. The library registers its BGTask handlers itself, so do not register them. See the [Installation guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/installation), the [iOS setup guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/ios-setup) and [iOS Background Modes](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/production/ios-background-modes) for full details.

## Quick Start

```tsx
import { Text } from 'react-native';
import {
  BackoffStrategy,
  configureSync,
  enableBackgroundSync,
  enqueue,
  SyncPriority,
  SyncStrategy,
  useAutoSync,
  useConnection,
  useSyncQueue,
  useSyncStatus,
} from '@gabriel-sisjr/react-native-sync-provider';

// 1. Configure on app boot, and again whenever the token changes.
//    defaultHeaders are encrypted at rest and available to background workers.
export async function startSync(token: string) {
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
    defaultHeaders: { Authorization: `Bearer ${token}` },
  });
  await enableBackgroundSync({
    minimumIntervalMs: 15 * 60 * 1000,
    requiresUnmeteredNetwork: false,
  });
}

// 2. Enqueue a request. It is stored at once and sent on the next flush.
export function trackScreen(screen: string) {
  return enqueue({
    method: 'POST',
    url: 'https://api.example.com/events',
    contentType: 'application/json',
    body: JSON.stringify({ event: 'screen_view', screen }),
    priority: SyncPriority.HIGH,
  });
}

// 3. Flush while the app runs, and react to queue and connection state.
export function SyncStatusBar() {
  useAutoSync({ enabled: true, flushOnAppForeground: true });
  const { isOnline } = useConnection();
  const { size: pending } = useSyncQueue();
  const { isSyncing } = useSyncStatus();

  return (
    <Text>
      {isOnline ? 'online' : 'offline'} · {pending} pending
      {isSyncing ? ' · syncing…' : ''}
    </Text>
  );
}
```

Enqueueing does not dispatch: a flush does, from `useAutoSync`, `flush()`, or an OS background window. The queue survives a force-quit, but background windows do not: iOS does not launch an app the user force-quit, and Android stops its work after a Force Stop, until the user opens the app again. See the [Quick Start guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/quick-start) for a complete screen and the `<SyncProvider>` setup.

## Network Quality and Health Checks (optional)

Every connection snapshot carries a `networkQuality` level, from worst to best `OFFLINE`, `POOR`, `MODERATE`, `GOOD` and `EXCELLENT`. Two opt-in checks can skip a flush cycle, and both are off by default. With `minNetworkQuality`, a flush on a link below that level runs no cycle. With `healthCheckUrl`, the engine checks an endpoint of yours before a cycle, and also uses it to measure the link's latency. Without that URL nothing is measured and no extra request is sent, so the level comes from link signals only and never reaches `EXCELLENT`.

```tsx
import { Text } from 'react-native';
import {
  configureSync,
  NetworkQuality,
  useConnection,
  type SyncOptions,
} from '@gabriel-sisjr/react-native-sync-provider';

// configureSync replaces the whole configuration, so pass your full options.
export async function enableNetworkChecks(options: SyncOptions) {
  await configureSync({
    ...options,
    // Skip flush cycles on a link below MODERATE, without spending attempts.
    minNetworkQuality: NetworkQuality.MODERATE,
    // Check this endpoint before a cycle, and measure latency against it.
    healthCheckUrl: 'https://api.example.com/health',
  });
}

export function NetworkQualityBadge() {
  const { networkQuality } = useConnection();
  return <Text>{networkQuality}</Text>;
}
```

| Option                 | Default          | Description                                                                                                                     |
| ---------------------- | ---------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `minNetworkQuality`    | unset (no gate)  | Lowest level at which a flush cycle may run. `POOR` blocks only `OFFLINE`                                                       |
| `healthCheckUrl`       | unset (no check) | Absolute `http(s)` URL. One `HEAD`, with a 2 s timeout, before a cycle claims its first item; a final `2xx` is healthy. Also the latency target |
| `healthCheckTtlMs`     | `60000`          | How long a healthy result is reused. `0` checks before every cycle                                                              |
| `healthCheckBackoffMs` | `30000`          | How long cycles are skipped after a failed check                                                                                |

A skipped cycle changes no item, spends no attempt and emits no `SYNC_STARTED`. The flush emits `SYNC_FAILED` with one of two codes, and `flush()` resolves with a 0/0 result:

| Error code                | Reported when                                                                             |
| ------------------------- | ----------------------------------------------------------------------------------------- |
| `NETWORK_QUALITY_TOO_LOW` | The current `networkQuality` is below `minNetworkQuality`                                 |
| `HEALTH_CHECK_FAILED`     | The `HEAD` did not end with a final `2xx`, or the backoff after a failed check is running |

The probes carry no `defaultHeaders`, so point `healthCheckUrl` at an endpoint that answers without authentication. The in-process retry timer checks before it flushes: it holds a wake the gate blocks, and waits out the backoff after a failed check, without emitting `SYNC_FAILED`. See the Connectivity Detection guide for [how the level is classified](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/connectivity-detection#network-quality), [the quality gate](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/connectivity-detection#the-quality-gate) and [the health check](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/connectivity-detection#health-check).

## Documentation

- [Introduction](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/introduction)
- [Why This Library](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/introduction#why-this-library)
- [How It Compares](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/introduction#how-it-compares)
- [Ecosystem](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/introduction#ecosystem)
- [Installation](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/installation)
- [Quick Start](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/getting-started/quick-start)
- [API Reference](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/functions)
- [Hooks](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/hooks/useSyncQueue)
- [Types](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/types)
- [Enums](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/enums)
- [Errors](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/api-reference/errors)
- [Background Sync Guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/background-sync)
- [Connectivity Detection Guide](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/guides/connectivity-detection)
- [Performance Notes](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/architecture/overview#performance-notes)
- [Production Checklist](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/production/production-checklist)
- [Platform Comparison](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/production/platform-comparison)
- [Migration Guides](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/migration)
- [Troubleshooting](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/troubleshooting)
- [FAQ](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/troubleshooting#faq)

## License

MIT — see [LICENSE](./LICENSE).
