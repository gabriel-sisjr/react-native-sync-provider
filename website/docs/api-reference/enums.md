---
sidebar_position: 5
title: Enums
description: All enum definitions for @gabriel-sisjr/react-native-sync-provider — ConnectionStatus, ConnectionType, SyncStrategy, SyncPriority, BackoffStrategy, HttpMethod, and SyncEventType.
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
| `CONNECTED` | Online on an unmetered connection. | iOS: `NWPath.status == .satisfied && !isExpensive` / Android: `NetworkCapabilities.NET_CAPABILITY_NOT_METERED` |
| `DISCONNECTED` | No network. | iOS: `NWPath.status != .satisfied` / Android: `NetworkCallback.onLost` |
| `METERED` | Online but the connection is metered (cellular, hotspot). | iOS: `NWPath.isExpensive == true` / Android: `!NET_CAPABILITY_NOT_METERED` |
| `UNKNOWN` | State has not been determined yet. | Initial value before the first emission. |

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
| `BLUETOOTH` | Tethered over Bluetooth. | `.other` (iOS) / `TRANSPORT_BLUETOOTH` |
| `VPN` | Tunneled. | `NWPath.usesInterfaceType(.other)` w/ VPN heuristic / `TRANSPORT_VPN` |
| `OTHER` | None of the above, but a transport is present. | Fallback for unrecognised transports. |
| `NONE` | No active transport. | Reported with `DISCONNECTED`. |
| `UNKNOWN` | Transport not yet known. | Initial value. |

---

## `SyncStrategy`

When the engine flushes the queue.

```ts
enum SyncStrategy {
  AUTOMATIC = 'AUTOMATIC',
  MANUAL = 'MANUAL',
  OPPORTUNISTIC = 'OPPORTUNISTIC',
}
```

| Value | Description |
|-------|-------------|
| `AUTOMATIC` | Flush whenever the queue is non-empty and connectivity allows. The default. |
| `MANUAL` | Never flush implicitly -- only when [`flush()`](./functions.md#flush) is called. |
| `OPPORTUNISTIC` | Flush only on opportunistic windows (foreground resume, charging, unmetered). |

---

## `SyncPriority`

Dispatch lane assigned to each item. Higher priorities drain before lower priorities of the same age.

```ts
enum SyncPriority {
  HIGH = 'HIGH',
  NORMAL = 'NORMAL',
  LOW = 'LOW',
}
```

| Value | Description | Native mapping |
|-------|-------------|----------------|
| `HIGH` | Drained first. Use for user-blocking actions (form submits). | Stored as integer `2` in Core Data / Room for ORDER BY. |
| `NORMAL` | Default lane. | `1` |
| `LOW` | Drained last. Use for telemetry, analytics. | `0` |

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

| Value | Description | Native mapping |
|-------|-------------|----------------|
| `LINEAR` | `delay = baseDelayMs * attempt`. Bounded by `maxDelayMs`. | `RetryBackoff.linear()` |
| `EXPONENTIAL` | `delay = baseDelayMs * 2^(attempt-1)`. Default. | `RetryBackoff.exponential()` |
| `FIBONACCI` | `delay = baseDelayMs * fib(attempt)`. Slower growth than exponential. | `RetryBackoff.fibonacci()` |

See [Retry policy guide](../guides/retry-policy.md) for tuning advice.

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
| `GET` | Read. Body is ignored. |
| `POST` | Create. |
| `PUT` | Replace. |
| `PATCH` | Partial update. |
| `DELETE` | Remove. |

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
}
```

| Value | When it fires | Notable payload fields |
|-------|---------------|------------------------|
| `ITEM_ENQUEUED` | After `enqueue` / `enqueueBatch` persists an item. | `itemId`, `metadata` |
| `ITEM_REMOVED` | After `removeItem` succeeds. | `itemId` |
| `SYNC_STARTED` | A flush begins. | -- |
| `SYNC_PROGRESS` | Per-batch progress during a flush. | `progress` (0..1) |
| `SYNC_SUCCEEDED` | A flush finishes with no failures. | -- |
| `SYNC_FAILED` | A flush finishes with one or more failures. | -- |
| `ITEM_SUCCEEDED` | A single item dispatched successfully. | `itemId`, `statusCode`, `metadata` |
| `ITEM_FAILED` | A single item exhausted retries or hit a terminal status. | `itemId`, `errorCode`, `statusCode`, `attempt`, `metadata` |
| `ITEM_RETRYING` | A single item failed but will be retried. | `itemId`, `errorCode`, `attempt`, `metadata` |
| `QUEUE_CLEARED` | After `clearQueue`. | -- |
| `CONNECTION_CHANGED` | The native monitor reported a transition. | `connectionStatus` |
| `PAUSED` | After `pauseSync`. | -- |
| `RESUMED` | After `resumeSync`. | -- |
| `BACKGROUND_SYNC_STARTED` | A `BGTaskScheduler` / `WorkManager` run starts. | -- |
| `BACKGROUND_SYNC_COMPLETED` | The same run finishes. | -- |

---

## `NetworkQuality`

:::note Forward type (Block B / v0.3)
`NetworkQuality` is the **Gap #2** network-quality classification slated for Block B (v0.3). It is introduced early so consumer types can depend on its final shape, but in v0.2 it is surfaced **only** as the optional [`UseSyncSnapshotResult.networkQuality`](./types.md#usesyncsnapshotresult) field and is **ALWAYS `undefined`** until the v0.3 classifier ships.
:::

Coarse network-quality classification. The value set is ordered **worst → best** so consumers can compare or threshold on it once the v0.3 classifier populates it.

```ts
enum NetworkQuality {
  OFFLINE = 'OFFLINE',
  POOR = 'POOR',
  MODERATE = 'MODERATE',
  GOOD = 'GOOD',
  EXCELLENT = 'EXCELLENT',
}
```

| Value | Description |
|-------|-------------|
| `OFFLINE` | No usable network path -- equivalent to [`ConnectionStatus.DISCONNECTED`](#connectionstatus). |
| `POOR` | Connected but throughput/latency is poor -- sync should be conservative. |
| `MODERATE` | Usable mid-tier link -- acceptable for most foreground sync. |
| `GOOD` | Healthy link -- suitable for normal background and foreground sync. |
| `EXCELLENT` | Best-tier link (typically unmetered Wi-Fi/ethernet) -- no constraints. |

---

## See also

- [`SyncEvent` type](./types.md#syncevent), [Listeners](./listeners.md), [`useSyncEvents`](./hooks/useSyncEvents.md).
- [Errors](./errors.md) for `SyncErrorCode`.
- [`useSyncSnapshot`](./hooks/useSyncSnapshot.md) and [`SyncDeadLetterItem`](./types.md#syncdeadletteritem) -- where the forward `NetworkQuality` is surfaced.
