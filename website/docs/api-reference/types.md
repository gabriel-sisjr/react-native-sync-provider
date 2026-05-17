---
sidebar_position: 4
title: Types & Interfaces
description: Complete TypeScript type and interface reference for @gabriel-sisjr/react-native-sync-provider — items, options, results, events, connection state, and hook result shapes.
keywords:
  - react-native
  - sync-provider
  - typescript
  - types
  - SyncItem
  - SyncOptions
  - SyncResult
  - RetryPolicy
---

# Types & Interfaces

All TypeScript types exported by the library, grouped by purpose.

```ts
import type {
  SyncItemInput,
  SyncItem,
  RetryPolicy,
  SyncOptions,
  SyncResult,
  SyncEvent,
  BackgroundSyncOptions,
  ConnectionState,
  // Hook result types
  UseConnectionResult,
  UseSyncQueueResult,
  UseSyncStatusResult,
  UseOfflineQueueResult,
  UseSyncConfigResult,
} from '@gabriel-sisjr/react-native-sync-provider';
```

---

## Queue Types

### `SyncItemInput`

The shape consumers pass to [`enqueue`](./functions.md#enqueue). The `id` and `createdAt` are added by the native layer.

```ts
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

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `method` | [`HttpMethod`](./enums.md#httpmethod) | Yes | HTTP verb. |
| `url` | `string` | Yes | Absolute URL. |
| `headers` | `Record<string, string>` | No | Per-item headers, merged on top of `SyncOptions.defaultHeaders`. |
| `body` | `string` | No | Request body. Strings only -- encode binary as Base64. |
| `contentType` | `string` | No | Convenience shortcut for `Content-Type` header. |
| `priority` | [`SyncPriority`](./enums.md#syncpriority) | No | Dispatch lane. Defaults to `NORMAL`. |
| `metadata` | `Record<string, string>` | No | Opaque tags persisted with the item and echoed in [`SyncEvent.metadata`](#syncevent). Strings only. |

---

### `SyncItem`

The shape stored natively and returned by [`getPendingItems`](./functions.md#getpendingitems).

```ts
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

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `id` | `string` | Yes | ULID assigned by the native layer at persistence time. |
| `method` | [`HttpMethod`](./enums.md#httpmethod) | Yes | HTTP verb. |
| `url` | `string` | Yes | Absolute URL. |
| `headers` | `Record<string, string>` | No | Per-item headers. |
| `body` | `string` | No | Request body. |
| `contentType` | `string` | No | `Content-Type` shortcut. |
| `priority` | [`SyncPriority`](./enums.md#syncpriority) | No | Dispatch lane. |
| `createdAt` | `number` | Yes | Unix milliseconds when the item was persisted. |
| `metadata` | `Record<string, string>` | No | Opaque tags. |

---

## Configuration Types

### `RetryPolicy`

Backoff strategy for failed dispatches.

```ts
interface RetryPolicy {
  maxAttempts: number;
  backoff: BackoffStrategy;
  baseDelayMs: number;
  maxDelayMs: number;
  jitter: boolean;
  retryOnStatusCodes: number[];
}
```

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `maxAttempts` | `number` | Yes | `5` | Total attempts before the item is moved to a terminal failed state. |
| `backoff` | [`BackoffStrategy`](./enums.md#backoffstrategy) | Yes | `EXPONENTIAL` | Curve used to compute the next delay. |
| `baseDelayMs` | `number` | Yes | `1000` | Base delay -- input to the curve. |
| `maxDelayMs` | `number` | Yes | `60000` | Upper cap on the computed delay. |
| `jitter` | `boolean` | Yes | `true` | When `true`, a uniform random factor is applied to the computed delay to avoid thundering-herd retries. |
| `retryOnStatusCodes` | `number[]` | Yes | `[408, 425, 429, 500, 502, 503, 504]` | Response statuses that trigger a retry. Statuses outside the list are terminal failures. |

See [Retry policy guide](../guides/retry-policy.md) for backoff math and tuning.

---

### `SyncOptions`

The full configuration applied via [`configureSync`](./functions.md#configuresync).

```ts
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

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `strategy` | [`SyncStrategy`](./enums.md#syncstrategy) | Yes | When the engine flushes: `AUTOMATIC`, `MANUAL`, or `OPPORTUNISTIC`. |
| `retryPolicy` | [`RetryPolicy`](#retrypolicy) | Yes | Backoff policy. |
| `batchSize` | `number` | No | Items dispatched per batch. Default `10`. |
| `requestTimeoutMs` | `number` | No | Per-request timeout. |
| `maxQueueSize` | `number` | No | Cap on pending items. `enqueue` throws `QUEUE_FULL` past this. |
| `persistQueue` | `boolean` | No | When `false`, the queue lives in memory only and is lost on app death. |
| `defaultHeaders` | `Record<string, string>` | No | Headers merged into every dispatched item. Per-item `headers` win on conflict. |

---

### `BackgroundSyncOptions`

Constraints for [`enableBackgroundSync`](./functions.md#enablebackgroundsync).

```ts
interface BackgroundSyncOptions {
  minimumIntervalMs: number;
  requiresCharging?: boolean;
  requiresUnmeteredNetwork?: boolean;
  requiresDeviceIdle?: boolean;
  taskIdentifier?: string;
}
```

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `minimumIntervalMs` | `number` | Yes | Minimum milliseconds between background runs. Android floor is 15 minutes; iOS treats this as a soft hint. |
| `requiresCharging` | `boolean` | No | When `true`, only run while plugged in. |
| `requiresUnmeteredNetwork` | `boolean` | No | When `true`, only run on unmetered networks. |
| `requiresDeviceIdle` | `boolean` | No | When `true`, only run while the device is idle. |
| `taskIdentifier` | `string` | No | Override the default identifier (`com.gabriel-sisjr.syncprovider.background`). Must match the `BGTaskSchedulerPermittedIdentifiers` entry in iOS `Info.plist`. |

---

## Result Types

### `SyncResult`

Returned by [`flush`](./functions.md#flush) and [`getLastSyncResult`](./functions.md#getlastsyncresult).

```ts
interface SyncResult {
  startedAt: number;
  finishedAt: number;
  successCount: number;
  failureCount: number;
  succeededIds: string[];
  failedIds: string[];
  errors: Record<string, SyncErrorCode>;
}
```

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `startedAt` | `number` | Yes | Unix ms when the run started. |
| `finishedAt` | `number` | Yes | Unix ms when the run finished. |
| `successCount` | `number` | Yes | Items dispatched successfully. |
| `failureCount` | `number` | Yes | Items that exhausted retries or hit a terminal status code. |
| `succeededIds` | `string[]` | Yes | ULIDs of successful items. |
| `failedIds` | `string[]` | Yes | ULIDs of failed items. |
| `errors` | `Record<string, SyncErrorCode>` | Yes | Map of failed-item id to terminal [`SyncErrorCode`](./errors.md#syncerrorcode). |

---

## Event Types

### `SyncEvent`

Payload emitted on the [`SYNC_EVENT_CHANNEL`](./listeners.md#sync_event_channel).

```ts
interface SyncEvent {
  type: SyncEventType;
  timestamp: number;
  itemId?: string;
  progress?: number;
  errorCode?: SyncErrorCode;
  statusCode?: number;
  attempt?: number;
  connectionStatus?: ConnectionStatus;
  metadata?: Record<string, string>;
}
```

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `type` | [`SyncEventType`](./enums.md#synceventtype) | Yes | What kind of event this is. |
| `timestamp` | `number` | Yes | Unix ms when the event was emitted. |
| `itemId` | `string` | No | Present on item-scoped events (`ITEM_*`). |
| `progress` | `number` | No | `[0, 1]`, present on `SYNC_PROGRESS`. |
| `errorCode` | [`SyncErrorCode`](./errors.md#syncerrorcode) | No | Present on `*_FAILED` events. |
| `statusCode` | `number` | No | HTTP status code, present on dispatch-related events when available. |
| `attempt` | `number` | No | 1-indexed attempt count, present on `ITEM_RETRYING` and `ITEM_FAILED`. |
| `connectionStatus` | [`ConnectionStatus`](./enums.md#connectionstatus) | No | Present on `CONNECTION_CHANGED`. |
| `metadata` | `Record<string, string>` | No | Echo of [`SyncItem.metadata`](#syncitem) for item-scoped events. |

---

## Connection Types

### `ConnectionState`

Returned by [`getConnectionStatus`](./functions.md#getconnectionstatus) and exposed via [`useConnection`](./hooks/useConnection.md).

```ts
interface ConnectionState {
  status: ConnectionStatus;
  type: ConnectionType;
  isInternetReachable?: boolean;
  isExpensive?: boolean;
}
```

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `status` | [`ConnectionStatus`](./enums.md#connectionstatus) | Yes | High-level status. |
| `type` | [`ConnectionType`](./enums.md#connectiontype) | Yes | Network family. |
| `isInternetReachable` | `boolean` | No | Optional reachability probe (iOS only on some setups). |
| `isExpensive` | `boolean` | No | Mirrors `NWPath.isExpensive` (iOS) / metered status (Android). |

---

## Hook Result Types

The shapes returned by each hook. See each hook's reference page for behavior.

### `UseConnectionResult`

```ts
interface UseConnectionResult {
  status: ConnectionStatus;
  type: ConnectionType;
  isOnline: boolean;
  isMetered: boolean;
}
```

See [`useConnection`](./hooks/useConnection.md).

### `UseSyncQueueResult`

```ts
interface UseSyncQueueResult {
  size: number;
  items: SyncItem[];
  isLoading: boolean;
  error: SyncError | null;
  enqueue: (item: SyncItemInput) => Promise<string | null>;
  enqueueBatch: (items: SyncItemInput[]) => Promise<string[] | null>;
  removeItem: (id: string) => Promise<boolean>;
  clearQueue: () => Promise<void>;
  refresh: () => Promise<void>;
}
```

See [`useSyncQueue`](./hooks/useSyncQueue.md).

### `UseSyncStatusResult`

```ts
interface UseSyncStatusResult {
  isSyncing: boolean;
  isPaused: boolean;
  progress: number;
  lastResult: SyncResult | null;
  error: SyncError | null;
  flush: () => Promise<SyncResult | null>;
  pause: () => Promise<void>;
  resume: () => Promise<void>;
}
```

See [`useSyncStatus`](./hooks/useSyncStatus.md).

### `UseOfflineQueueResult`

```ts
interface UseOfflineQueueResult {
  connection: ConnectionState;
  size: number;
  items: SyncItem[];
  isSyncing: boolean;
  isWaitingForConnection: boolean;
  error: SyncError | null;
  enqueue: (item: SyncItemInput) => Promise<string | null>;
  flush: () => Promise<SyncResult | null>;
}
```

See [`useOfflineQueue`](./hooks/useOfflineQueue.md).

### `UseSyncConfigResult`

```ts
interface UseSyncConfigResult {
  config: SyncOptions | undefined;
  isLoading: boolean;
  error: SyncError | null;
  setConfig: (options: SyncOptions) => Promise<void>;
  refresh: () => Promise<void>;
}
```

See [`useSyncConfig`](./hooks/useSyncConfig.md).

---

## See also

- [Enums](./enums.md), [Errors](./errors.md).
- [Functions reference](./functions.md).
