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
| `MANUAL` | The engine schedules nothing on its own: no retry timer and no Android retry kick. Items are dispatched by [`flush()`](./functions.md#flush), plus any OS windows enabled through [`enableBackgroundSync`](./functions.md#enablebackgroundsync): use `foregroundOnly` to stop those. |
| `OPPORTUNISTIC` | Reserved for flushing only on opportunistic windows. Not implemented yet: it currently behaves like `AUTOMATIC`. |

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
| `ITEM_ENQUEUED` | After `enqueue` / `enqueueBatch` persists an item. | `itemId` |
| `ITEM_REMOVED` | After `removeItem` removes an item. A delivered item leaves the queue with `ITEM_SUCCEEDED` instead. | `itemId` |
| `SYNC_STARTED` | A flush cycle begins. | -- |
| `SYNC_PROGRESS` | After each item in a cycle. **iOS only.** | `progress` (0..1) |
| `SYNC_SUCCEEDED` | A cycle finishes with no permanent failure. That includes a 0/0 cycle with nothing eligible, and a cycle whose items all went back to wait for a retry. | -- |
| `SYNC_FAILED` | A cycle finishes with one or more permanent failures, or dies. | `errorCode`, only when the cycle died |
| `ITEM_SUCCEEDED` | A single item dispatched successfully. | `itemId`, `statusCode`, `attempt` |
| `ITEM_FAILED` | A single item failed permanently: a non-retryable failure, or a retriable one with no attempts left. | `itemId`, `errorCode`, `statusCode`, `attempt` |
| `ITEM_RETRYING` | A single item failed and went back to wait, including a [credential hold](../guides/background-sync.md#cold-start-configuration). | `itemId`, `errorCode`, `statusCode`, `attempt` |
| `QUEUE_CLEARED` | After `clearQueue`. | -- |
| `CONNECTION_CHANGED` | The native monitor reported a transition. | `connectionStatus` |
| `PAUSED` | After `pauseSync`. | -- |
| `RESUMED` | After `resumeSync`. iOS also emits it at launch when recovery put interrupted items back in the queue. A pause cleared by a launch emits nothing. | -- |
| `BACKGROUND_SYNC_STARTED` | A background window starts: a BGTask on iOS, the periodic worker on Android. | -- |
| `BACKGROUND_SYNC_COMPLETED` | The same window finishes. | -- |

Events reach only listeners in a running JS engine. A worker or BGTask with no JS still dispatches, but nobody receives its events.

The `metadata` field on these events is never the item's `metadata`. See [`SyncEvent`](./types.md#syncevent).

---

## `NetworkQuality`

:::note Forward type for v0.3
`NetworkQuality` is the network-quality classification slated for v0.3. It is introduced early so consumer types can depend on its final shape, but in v0.2 it is surfaced **only** as the optional [`UseSyncSnapshotResult.networkQuality`](./types.md#usesyncsnapshotresult) field and is **ALWAYS `undefined`** until the v0.3 classifier ships.
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
