---
sidebar_position: 2
title: Data Flow
description: Step-by-step traces for enqueue, foreground flush, background flush, connectivity changes, and recovery in react-native-sync-provider.
keywords:
  - data flow
  - enqueue
  - flush
  - background-sync
  - connectivity
  - recovery
  - nitro
---

# Data Flow

Every major operation in `react-native-sync-provider` follows a predictable path through the three layers. This page traces the flows you'll need when debugging, building integrations, or contributing.

## High-Level Flow

```mermaid
sequenceDiagram
    participant App as App Code
    participant JS as JS Facade (src/)
    participant Spec as Nitro Spec
    participant Native as Native Impl (Swift / Kotlin)
    participant Storage as Core Data / Room
    participant Net as Network
    participant Bus as SyncEventEmitter

    App->>JS: enqueue(item)
    JS->>JS: validateSyncItem(item)
    JS->>Spec: SyncProvider.enqueue(...)
    Spec->>Native: dispatch
    Native->>Storage: insert SyncItem (ULID)
    Native->>Bus: emit ITEM_ENQUEUED
    Bus->>App: useSyncQueue re-renders
    Native-->>JS: id
    JS-->>App: id

    Note over Native,Net: ...sometime later, on flush trigger...
    Native->>Storage: read pending items (priority desc)
    Native->>Net: HTTP request
    Net-->>Native: 200 OK
    Native->>Storage: delete row
    Native->>Bus: emit ITEM_SUCCEEDED, then SYNC_SUCCEEDED
```

## Enqueue Flow

```mermaid
flowchart LR
    A["enqueue(input)"] --> B[validateSyncItem]
    B --> C{valid?}
    C -- no --> D[throw SyncError INVALID_PAYLOAD or INVALID_URL]
    C -- yes --> E[Nitro HybridObject.enqueue]
    E --> F[native: generate ULID]
    F --> G[native: persist row]
    G --> H{persisted?}
    H -- no --> I[throw SyncError QUEUE_FULL]
    H -- yes --> J[emit ITEM_ENQUEUED on SYNC_EVENT_CHANNEL]
    J --> K[return id to JS]
```

Steps:

1. **Validate (JS)**. `validateSyncItem` ensures `method` is in [`HttpMethod`](../api-reference/enums.md#httpmethod) and `url` parses. Failures throw `SyncError` synchronously inside the awaited call.
2. **Cross JSI**. Nitro dispatches the `enqueue` call. The item object is passed through as a typed JS object -- no JSON marshalling.
3. **Generate ULID (native)**. The native `Ulid` / `ULID` utility produces a 26-character monotonic id.
4. **Persist (native)**. The item is inserted into Core Data (iOS) or Room (Android) inside a transaction. If the queue is at `maxQueueSize`, the insert fails and the dispatcher throws `QUEUE_FULL`.
5. **Emit (native)**. `SyncEventEmitter` fires `ITEM_ENQUEUED` with the id and metadata.
6. **Resolve (JS)**. The promise resolves to the id.

`enqueueBatch` follows the same flow but inside a single native transaction, so the entire batch is atomic.

## Foreground Flush Flow

```mermaid
flowchart TD
    A["flush()"] --> B[native: claim 'in flight' lock]
    B --> C[emit SYNC_STARTED]
    C --> D[read up to batchSize pending items]
    D --> E{items?}
    E -- no --> F[emit SYNC_SUCCEEDED, persist SyncResult]
    E -- yes --> G[for each item: HTTP request]
    G --> H{response?}
    H -- 2xx --> I[delete row, emit ITEM_SUCCEEDED]
    H -- retry status --> J[increment attempt, emit ITEM_RETRYING]
    H -- terminal status --> K[delete row, emit ITEM_FAILED]
    I --> L[emit SYNC_PROGRESS]
    J --> L
    K --> L
    L --> D
    F --> M[release lock, return SyncResult]
```

Notes:

- The flush is **single-flight** per process. Concurrent `flush()` calls reuse the same in-flight run; the second `Promise` resolves with the same `SyncResult`.
- HTTP requests run on the native dispatcher's executor (`URLSession` on iOS, OkHttp + `Dispatchers.IO` on Android). The JS thread is never blocked.
- The `SyncResult` is persisted to history before the promise resolves.

## Background Flush Flow

```mermaid
sequenceDiagram
    participant OS as iOS BGTaskScheduler / Android WorkManager
    participant Native as BackgroundSyncManager
    participant Disp as SyncDispatcher
    participant Storage as Core Data / Room

    OS->>Native: launch background task
    Native->>Native: check constraints (network, charging)
    Native->>Disp: flush()
    Disp->>Storage: read pending items
    Disp->>Native: HTTP requests in batches
    Native->>OS: setTaskCompleted(success: true)
    Native->>Native: schedule next BGTaskRequest (iOS only)
```

Per-platform specifics:

- **iOS**. `BGTaskScheduler` wakes a registered handler. The handler must complete (or call `setTaskCompleted(success:)`) before the OS-imposed deadline (typically 30s for `BGAppRefreshTaskRequest`, longer for `BGProcessingTaskRequest`). After completion, the handler reschedules itself.
- **Android**. WorkManager's `CoroutineWorker` is dispatched at most every 15 minutes (the platform floor). The worker calls `flush()` and returns `Result.success()` / `Result.retry()`. WorkManager handles re-attempt scheduling.

## Connectivity Change Flow

```mermaid
flowchart LR
    A[OS network event] --> B[ConnectivityMonitor]
    B --> C[emit CONNECTION_CHANGED]
    C --> D{useConnection}
    C --> E[useAutoSync flushOnReconnect]
    E --> F["isSyncing()? skip : flush()"]
    D --> G[React re-render]
```

The native monitor is a singleton per process. It debounces transient flaps (a brief loss + restore) and emits at most one event per real transition.

## Recovery Flow (App Launch)

```mermaid
flowchart TD
    A[App launches] --> B[native module init]
    B --> C[scan SyncItem table]
    C --> D[items in 'in_flight' state]
    D --> E[atomic UPDATE state = pending, attempt unchanged]
    E --> F[no event emitted]
    F --> G[next flush trigger picks them up]
```

Recovery handles the "process killed mid-dispatch" case. Without it, items would be stuck `in_flight` forever. The reset is **transactional and idempotent** -- safe to run on every launch.

## See also

- [Architecture overview](./overview.md).
- [iOS native architecture](./ios-native.md), [Android native architecture](./android-native.md).
- [Background sync guide](../guides/background-sync.md), [Connectivity detection guide](../guides/connectivity-detection.md).
