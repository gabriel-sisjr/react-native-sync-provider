---
sidebar_position: 5
title: Enums
description: All enum definitions for @gabriel-sisjr/react-native-sync-provider — ConnectionStatus, ConnectionType, SyncStrategy, SyncPriority, BackoffStrategy, HttpMethod, SyncEventType, and NetworkQuality.
keywords:
  - react-native
  - sync-provider
  - enums
  - ConnectionStatus
  - SyncPriority
  - BackoffStrategy
  - SyncEventType
---

# Enums

All runtime enum values exported by the library. Each is exported as both a value (for runtime use) and a type (for type annotations).

```ts
import {
  ConnectionStatus,
  ConnectionType,
  SyncStrategy,
  SyncPriority,
  BackoffStrategy,
  HttpMethod,
  SyncEventType,
  NetworkQuality,
} from '@gabriel-sisjr/react-native-sync-provider';
```

---

## `ConnectionStatus`

High-level network status reported by the native [`ConnectivityMonitor`](../architecture/overview.md).

```ts
enum ConnectionStatus {
  CONNECTED = 'CONNECTED',
  DISCONNECTED = 'DISCONNECTED',
  METERED = 'METERED',
  UNKNOWN = 'UNKNOWN',
}
```

| Value | Description | Native mapping |
|-------|-------------|----------------|
| `CONNECTED` | Online on an unmetered connection. | iOS: `NWPath.status == .satisfied && !isExpensive` / Android: the default network has `NET_CAPABILITY_INTERNET` and either `NET_CAPABILITY_NOT_METERED` or, on Android 11 (API 30) and later, `NET_CAPABILITY_TEMPORARILY_NOT_METERED` |
| `DISCONNECTED` | No network. | iOS: `NWPath.status` is `.unsatisfied` or `.requiresConnection` / Android: no default network, or a default network without `NET_CAPABILITY_INTERNET`. Losing a secondary network does not count |
| `METERED` | Online but the connection is metered (cellular, hotspot). | iOS: `NWPath.status == .satisfied && isExpensive` / Android: the default network has `NET_CAPABILITY_INTERNET` but neither of the unmetered capabilities above |
| `UNKNOWN` | State has not been determined yet. | iOS: before the first `NWPathMonitor` report. Android has no such window: a network the OS has not described yet reads as `DISCONNECTED`. |

Android follows only the default network: the one the app's traffic uses. Losing a secondary network, such as cellular alongside Wi-Fi or a VPN that does not apply to the app, does not report `DISCONNECTED`, and a VPN that was the default network hands over without a false disconnect. A temporarily unmetered link (some 5G plans) reads as `CONNECTED` with `isExpensive: false` on Android 11 and later, and as `METERED` again when the exemption is withdrawn.

### Usage

```ts
import {
  ConnectionStatus,
  flush,
  getConnectionStatus,
} from '@gabriel-sisjr/react-native-sync-provider';

// A flush while offline can spend retry attempts, so skip it.
const { status } = await getConnectionStatus();
if (status !== ConnectionStatus.DISCONNECTED) {
  await flush();
}
```

---

## `ConnectionType`

The transport behind the current connection.

```ts
enum ConnectionType {
  WIFI = 'WIFI',
  CELLULAR = 'CELLULAR',
  ETHERNET = 'ETHERNET',
  BLUETOOTH = 'BLUETOOTH',
  VPN = 'VPN',
  OTHER = 'OTHER',
  NONE = 'NONE',
  UNKNOWN = 'UNKNOWN',
}
```

| Value | Description | Native mapping |
|-------|-------------|----------------|
| `WIFI` | 802.11 connection. | `NWInterface.InterfaceType.wifi` / `TRANSPORT_WIFI` |
| `CELLULAR` | Mobile network. | `.cellular` / `TRANSPORT_CELLULAR` |
| `ETHERNET` | Wired connection (typically tablets / dev kits). | `.wiredEthernet` / `TRANSPORT_ETHERNET` |
| `BLUETOOTH` | Tethered over Bluetooth. | Android only: `TRANSPORT_BLUETOOTH` |
| `VPN` | Tunneled. | Android only: `TRANSPORT_VPN`, checked before every other transport |
| `OTHER` | None of the above, but a transport is present. | `.other` / any other transport |
| `NONE` | No active transport. | Reported with `DISCONNECTED`. |
| `UNKNOWN` | Transport not yet known. | iOS: before the first path report, and for a path that is not `.unsatisfied` but uses none of the four interface types it checks. Android: only when the system offers no `ConnectivityManager`. |

**Platform:** Android and iOS, except `BLUETOOTH` and `VPN`, which only Android reports. iOS checks the path's interface types in the order Wi-Fi, cellular, wired Ethernet, other, and reports the first match as `WIFI`, `CELLULAR`, `ETHERNET` or `OTHER`.

### Usage

```ts
import {
  ConnectionType,
  getConnectionStatus,
} from '@gabriel-sisjr/react-native-sync-provider';

const { type } = await getConnectionStatus();
if (type === ConnectionType.WIFI) {
  console.log('On Wi-Fi');
}
```

---

## `SyncStrategy`

What the engine schedules on its own. `flush()` works the same under every strategy, and enqueueing never triggers a flush under any of them: mount [`useAutoSync`](./hooks/useAutoSync.md) or call `flush()`.

```ts
enum SyncStrategy {
  AUTOMATIC = 'AUTOMATIC',
  MANUAL = 'MANUAL',
  OPPORTUNISTIC = 'OPPORTUNISTIC',
}
```

| Value | Description |
|-------|-------------|
| `AUTOMATIC` | The engine retries on its own: a failed item is dispatched again when its cooldown ends, and pending items are dispatched on `resumeSync()`. The default. |
| `MANUAL` | The engine schedules nothing on its own: no retry timer and no Android retry kick. Items are dispatched by [`flush()`](./functions.md#flush), plus any OS windows enabled through [`enableBackgroundSync`](./functions.md#enablebackgroundsync): use `foregroundOnly` to stop those. [`useAutoSync`](./hooks/useAutoSync.md) does not read the strategy, so while it is mounted with `enabled: true` it keeps flushing under `MANUAL` too: pass `enabled: false` for a manual flow. |
| `OPPORTUNISTIC` | Reserved for flushing only on opportunistic windows. Not implemented yet: it currently behaves like `AUTOMATIC`. |

### Usage

```ts
import {
  BackoffStrategy,
  configureSync,
  SyncStrategy,
} from '@gabriel-sisjr/react-native-sync-provider';

await configureSync({
  strategy: SyncStrategy.MANUAL, // the engine schedules nothing on its own
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
```

---

## `SyncPriority`

Dispatch lane assigned to each item. Priority is ordered first, then age: a `HIGH` item goes before any older `NORMAL` item.

```ts
enum SyncPriority {
  HIGH = 'HIGH',
  NORMAL = 'NORMAL',
  LOW = 'LOW',
}
```

| Value | Description | Native mapping |
|-------|-------------|----------------|
| `HIGH` | Drained first. Use for user-blocking actions (form submits). | Room `priorityWeight` `0`. iOS ranks the stored string in memory. |
| `NORMAL` | Default lane. | `1` |
| `LOW` | Drained last. Use for telemetry, analytics. | `2` |

### Usage

```ts
import {
  enqueue,
  HttpMethod,
  SyncPriority,
} from '@gabriel-sisjr/react-native-sync-provider';

await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/forms',
  contentType: 'application/json',
  body: JSON.stringify({ formId: 'signup' }),
  priority: SyncPriority.HIGH, // dispatched before any older NORMAL or LOW item
});
```

---

## `BackoffStrategy`

Curve used by [`RetryPolicy`](./types.md#retrypolicy) to compute the next attempt delay.

```ts
enum BackoffStrategy {
  LINEAR = 'LINEAR',
  EXPONENTIAL = 'EXPONENTIAL',
  FIBONACCI = 'FIBONACCI',
}
```

| Value | Description |
|-------|-------------|
| `LINEAR` | `delay = baseDelayMs * attempt`. Bounded by `maxDelayMs`. |
| `EXPONENTIAL` | `delay = baseDelayMs * 2^(attempt-1)`. Bounded by `maxDelayMs`. Default. |
| `FIBONACCI` | `delay = baseDelayMs * fib(attempt)`. Bounded by `maxDelayMs`. Slower growth than exponential. |

See [Retry policy guide](../guides/retry-policy.md) for tuning advice.

### Usage

```ts
import {
  BackoffStrategy,
  type RetryPolicy,
} from '@gabriel-sisjr/react-native-sync-provider';

// Pass it as SyncOptions.retryPolicy to configureSync.
const retryPolicy: RetryPolicy = {
  maxAttempts: 8,
  backoff: BackoffStrategy.FIBONACCI, // grows slower than EXPONENTIAL
  baseDelayMs: 2000,
  maxDelayMs: 120_000,
  jitter: true,
  retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
};
```

---

## `HttpMethod`

HTTP verbs accepted by [`SyncItemInput.method`](./types.md#synciteminput).

```ts
enum HttpMethod {
  GET = 'GET',
  POST = 'POST',
  PUT = 'PUT',
  PATCH = 'PATCH',
  DELETE = 'DELETE',
}
```

| Value | Description |
|-------|-------------|
| `GET` | Read. Send no body: Android drops one. |
| `POST` | Create. |
| `PUT` | Replace. |
| `PATCH` | Partial update. |
| `DELETE` | Remove. Send no body: Android drops one. |

### Usage

```ts
import {
  enqueue,
  HttpMethod,
} from '@gabriel-sisjr/react-native-sync-provider';

await enqueue({
  method: HttpMethod.PATCH,
  url: 'https://api.example.com/profile',
  contentType: 'application/json',
  body: JSON.stringify({ displayName: 'Ana' }),
});
```

---

## `SyncEventType`

Every event type emitted on the [`SYNC_EVENT_CHANNEL`](./listeners.md#sync_event_channel).

```ts
enum SyncEventType {
  ITEM_ENQUEUED = 'ITEM_ENQUEUED',
  ITEM_REMOVED = 'ITEM_REMOVED',
  SYNC_STARTED = 'SYNC_STARTED',
  SYNC_PROGRESS = 'SYNC_PROGRESS',
  SYNC_SUCCEEDED = 'SYNC_SUCCEEDED',
  SYNC_FAILED = 'SYNC_FAILED',
  ITEM_SUCCEEDED = 'ITEM_SUCCEEDED',
  ITEM_FAILED = 'ITEM_FAILED',
  ITEM_RETRYING = 'ITEM_RETRYING',
  QUEUE_CLEARED = 'QUEUE_CLEARED',
  CONNECTION_CHANGED = 'CONNECTION_CHANGED',
  PAUSED = 'PAUSED',
  RESUMED = 'RESUMED',
  BACKGROUND_SYNC_STARTED = 'BACKGROUND_SYNC_STARTED',
  BACKGROUND_SYNC_COMPLETED = 'BACKGROUND_SYNC_COMPLETED',
  ITEM_DEAD_LETTERED = 'ITEM_DEAD_LETTERED',
}
```

| Value | When it fires | Notable payload fields |
|-------|---------------|------------------------|
| `ITEM_ENQUEUED` | After `enqueue` / `enqueueBatch` persists an item, or `replayDeadLetter` puts a dead letter back in the queue. | `itemId` |
| `ITEM_REMOVED` | After `removeItem` deletes a queued item or a dead letter, or `discardDeadLetter` deletes a dead letter. Not emitted when no row had that id. A delivered item leaves the queue with `ITEM_SUCCEEDED` instead. | `itemId` |
| `SYNC_STARTED` | A flush cycle begins. | -- |
| `SYNC_PROGRESS` | Once per batch item, when its request returns or the item is skipped, before that item's `ITEM_*` event. None for an empty cycle, or for the items left after a pause or a credential stop. **iOS only.** | `progress` (0..1) |
| `SYNC_SUCCEEDED` | A cycle finishes with no permanent failure. That includes a 0/0 cycle with nothing eligible, and a cycle whose items all went back to wait for a retry. | iOS: `metadata.attempted` and `metadata.succeeded`, plus `metadata.failed` on an empty cycle. Android: base fields only. |
| `SYNC_FAILED` | A cycle finishes with one or more permanent failures, or dies. Also emitted, with no `SYNC_STARTED` before it and no history row, when the [quality gate](../guides/connectivity-detection.md#the-quality-gate) or the [health check](../guides/connectivity-detection.md#health-check) skips the cycle. | `errorCode`, only when the cycle died (its code) or was skipped (`NETWORK_QUALITY_TOO_LOW` or `HEALTH_CHECK_FAILED`). iOS, for a cycle that ended with permanent failures: `metadata.attempted`, `metadata.succeeded` and `metadata.failed`. |
| `ITEM_SUCCEEDED` | A single item dispatched successfully. | `itemId`, `statusCode`, `attempt` |
| `ITEM_FAILED` | A single item failed permanently: a non-retryable failure, or a retriable one with no attempts left. The item has already moved to the dead-letter queue, and `ITEM_DEAD_LETTERED` follows. An item [removed or cleared while its request was in flight](./functions.md#what-a-purge-stops) gets `ITEM_FAILED` alone. | `itemId`, `errorCode`, `statusCode`, `attempt` |
| `ITEM_RETRYING` | A single item failed and went back to wait, including a [credential hold](../guides/background-sync.md#cold-start-configuration). | `itemId`, `errorCode`, `statusCode`, `attempt` |
| `QUEUE_CLEARED` | After `clearQueue`, which deletes dead letters too. Once per call. | -- |
| `CONNECTION_CHANGED` | A reported [`ConnectionState`](./types.md#connectionstate) field changed: `status`, `type`, `isInternetReachable`, `isExpensive` or `networkQuality`. Never for a duplicate, and on Android never for the state it starts in. iOS starts at `UNKNOWN`, so its first path report emits one. A change of `networkQuality` alone emits it with an unchanged `connectionStatus`. Delivered to JS asynchronously on both platforms, after the new state is stored. | `connectionStatus` |
| `PAUSED` | After `pauseSync`. | -- |
| `RESUMED` | After `resumeSync`. iOS also emits it at launch when recovery put interrupted items back in the queue. A pause cleared by a launch emits nothing. | iOS recovery's `RESUMED`: `metadata.recovered`, the count, as a string. Otherwise base fields only. |
| `BACKGROUND_SYNC_STARTED` | A background window starts: a BGTask on iOS, the periodic worker on Android. Android's one-time retry kick emits neither background event. | iOS processing task: `metadata.kind` (`"processing"`). Otherwise base fields only. |
| `BACKGROUND_SYNC_COMPLETED` | The same window finishes. iOS emits it whether the window's flush returned or threw. Android emits it only after the flush returns: a cycle that dies emits `SYNC_FAILED` with its code, and then no `BACKGROUND_SYNC_COMPLETED`. | iOS: `metadata.kind` (`"refresh"` or `"processing"`), and `metadata.ok: "false"` when the flush threw. Android: base fields only. `errorCode` is never set, on either platform: the code is on the `SYNC_FAILED` of a cycle that died or was skipped, and on each `ITEM_FAILED` of a cycle that ended with permanent item failures. |
| `ITEM_DEAD_LETTERED` | A permanently failed item was moved to the dead-letter queue. Emitted right after that item's `ITEM_FAILED`, on both platforms. Read the dead letter with [`listDeadLetter`](./functions.md#listdeadletter). | `itemId`, `errorCode: 'DEAD_LETTERED'`, `metadata.lastErrorCode` (the real failure code, the one `ITEM_FAILED` reported) |

**Platform:** Android and iOS, except `SYNC_PROGRESS`, which only iOS emits.

Events reach only listeners in a running JS engine. A worker or BGTask with no JS still dispatches, but nobody receives its events.

The `metadata` field on these events is never the item's `metadata`. See [`SyncEvent`](./types.md#syncevent).

> **Note — New members are appended:** `ITEM_DEAD_LETTERED` was added in v0.3. A `switch` over `SyncEventType` that ends in a `never` exhaustiveness check needs a case for it.

### Usage

```tsx
import {
  SyncEventType,
  useSyncEvents,
} from '@gabriel-sisjr/react-native-sync-provider';

function DeadLetterLogger() {
  useSyncEvents({
    types: [SyncEventType.ITEM_DEAD_LETTERED],
    onEvent: (event) => {
      console.warn(`${event.itemId} failed with ${event.metadata?.lastErrorCode}`);
    },
  });
  return null;
}
```

---

## `NetworkQuality`

Coarse quality of the current link, ordered **worst → best**: `OFFLINE < POOR < MODERATE < GOOD < EXCELLENT`. Reported as [`ConnectionState.networkQuality`](./types.md#connectionstate) by [`getConnectionStatus`](./functions.md#getconnectionstatus) and the hooks, and used as the threshold of [`SyncOptions.minNetworkQuality`](./types.md#syncoptions).

```ts
enum NetworkQuality {
  OFFLINE = 'OFFLINE',
  POOR = 'POOR',
  MODERATE = 'MODERATE',
  GOOD = 'GOOD',
  EXCELLENT = 'EXCELLENT',
}
```

| Value | Reported when |
|-------|---------------|
| `OFFLINE` | No link, or a link the OS has not reported yet: exactly when the status is [`DISCONNECTED`](#connectionstatus) or `UNKNOWN`. The only level `minNetworkQuality: POOR` blocks. |
| `POOR` | Online but slow: a measured median of 1000 ms or more, two or more failed probes, or an Android link without `NET_CAPABILITY_VALIDATED`. |
| `MODERATE` | A measured median of 500–999 ms, a metered link with no measurement, or a faster link held back by iOS Low Data Mode. |
| `GOOD` | A measured median of 200–499 ms, or an unmetered link with no measurement. The highest level a metered or expensive link reaches. |
| `EXCELLENT` | A measured median under 200 ms on a link no cap lowers. Only reported with `healthCheckUrl` set. |

Both platforms classify with the same fixed table, described in [Connectivity Detection](../guides/connectivity-detection.md#network-quality). Without `healthCheckUrl` nothing is measured, so the classification never reaches `EXCELLENT`.

### Usage

Compare two levels by their position in `Object.values(NetworkQuality)`, which lists them worst to best, never as strings:

```ts
import {
  getConnectionStatus,
  NetworkQuality,
} from '@gabriel-sisjr/react-native-sync-provider';

const LEVELS = Object.values(NetworkQuality);
const isAtLeast = (quality: NetworkQuality, min: NetworkQuality) =>
  LEVELS.indexOf(quality) >= LEVELS.indexOf(min);

const { networkQuality } = await getConnectionStatus();
if (isAtLeast(networkQuality, NetworkQuality.GOOD)) {
  startLargeUpload();
}
```

---

## See Also

- [Types & Interfaces](./types.md) -- `SyncEvent`, the payload every event type arrives in, and `SyncDeadLetterItem`, the record behind `ITEM_DEAD_LETTERED`
- [Event Listeners](./listeners.md) -- Subscribe to events outside React
- [useSyncEvents](./hooks/useSyncEvents.md) -- Subscribe to events from a component
- [Errors](./errors.md) -- `SyncErrorCode`
- [Connectivity Detection](../guides/connectivity-detection.md) -- Network quality, the quality gate and the health check
