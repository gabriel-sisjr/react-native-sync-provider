---
sidebar_position: 3
title: Quick Start
description: Get an offline-first sync queue running in your React Native app in five minutes with hooks and a complete working example.
keywords:
  - react-native
  - quick-start
  - offline-first
  - sync-queue
  - hooks
  - useSyncQueue
  - useSyncStatus
  - useConnection
---

# Quick Start

Get an offline-first HTTP sync queue running in five minutes. This guide uses the hooks-first approach for the simplest integration, then breaks the setup down step by step, including the direct facade API for callers that prefer it.

## Prerequisites

Before starting, make sure you have:

- Completed the [Installation](./installation.md) steps (package installed, peer dependencies linked).
- Completed the [iOS Setup](./ios-setup.md) if targeting iOS (Background Modes, Info.plist).
- Completed the [Android Setup](./android-setup.md) if targeting Android (manifest permissions are auto-merged, but verify Gradle versions).
- The New Architecture enabled (look for `"fabric":true` in Metro logs).

## Minimal Example

A screen that enqueues a POST when the user taps a button, shows the queue size, and reflects connectivity in real time:

```tsx
import React from 'react';
import { View, Text, Button, StyleSheet } from 'react-native';
import {
  useConnection,
  useSyncQueue,
  useSyncStatus,
  HttpMethod,
  SyncPriority,
} from '@gabriel-sisjr/react-native-sync-provider';

export default function SyncScreen() {
  const { isOnline, isMetered } = useConnection();
  const { size, items, enqueue, error: queueError } = useSyncQueue();
  const { isSyncing, lastResult, flush } = useSyncStatus();

  const handleEnqueue = async () => {
    await enqueue({
      method: HttpMethod.POST,
      url: 'https://api.example.com/events',
      contentType: 'application/json',
      body: JSON.stringify({ kind: 'button_tap', ts: Date.now() }),
      priority: SyncPriority.NORMAL,
    });
  };

  return (
    <View style={styles.container}>
      <Text style={styles.badge}>
        {isOnline ? (isMetered ? 'Online (metered)' : 'Online') : 'Offline'}
      </Text>

      <Text style={styles.title}>Pending: {size}</Text>
      {items.slice(0, 3).map((it) => (
        <Text key={it.id} style={styles.item}>
          {it.method} {it.url}
        </Text>
      ))}

      <Button title="Enqueue event" onPress={handleEnqueue} />
      <Button
        title={isSyncing ? 'Flushing...' : 'Flush now'}
        onPress={flush}
        disabled={isSyncing}
      />

      {lastResult && (
        <Text style={styles.info}>
          Last flush: {lastResult.successCount} ok / {lastResult.failureCount} failed
        </Text>
      )}

      {queueError && <Text style={styles.error}>{queueError.message}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 20, gap: 12 },
  title: { fontSize: 22, fontWeight: 'bold', textAlign: 'center' },
  item: { fontSize: 12, textAlign: 'center', color: '#666' },
  badge: { fontSize: 12, textAlign: 'center', color: '#888' },
  info: { fontSize: 14, textAlign: 'center' },
  error: { fontSize: 12, textAlign: 'center', color: '#c00' },
});
```

The hooks subscribe to native events and re-render whenever the queue, sync engine, or connection state changes.

Enqueueing stores an item, and a flush sends it. Here the button does that. To flush without one, see [Configure the Sync Engine](#configure-the-sync-engine) below.

## Step-by-Step Breakdown

### 1. Configure the Sync Engine {#configure-the-sync-engine}

Most apps configure the sync engine once at startup, and mount `useAutoSync` so items go out without a button. Wrap your tree with `<SyncProvider>` to centralize the lifecycle:

```tsx
import { useMemo } from 'react';
import {
  SyncProvider,
  SyncStrategy,
  BackoffStrategy,
  useAutoSync,
} from '@gabriel-sisjr/react-native-sync-provider';

const BACKGROUND_OPTIONS = {
  minimumIntervalMs: 15 * 60 * 1000,
  requiresUnmeteredNetwork: true,
};

function AutoSync() {
  useAutoSync({ enabled: true, flushOnAppForeground: true });
  return null;
}

export default function App({ token }: { token: string }) {
  const options = useMemo(
    () => ({
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
    }),
    [token]
  );

  return (
    <SyncProvider options={options} backgroundSync={BACKGROUND_OPTIONS}>
      <AutoSync />
      <Root />
    </SyncProvider>
  );
}
```

The Provider applies `configureSync(options)` on mount and on every change to `options`, and pairs `enableBackgroundSync(backgroundSync)` with a matching `disableBackgroundSync()` on unmount. The options are persisted, so background workers use them too. Keep them stable with `useMemo`, and rebuild them when the token changes. Hooks work without a Provider as well -- it is purely a convenience.

> **Warning — Omitting `defaultHeaders` is a logout:** Each `configureSync` replaces the whole configuration, and omitting `defaultHeaders` deletes the stored copy. See [Cold-start configuration](../guides/background-sync.md#cold-start-configuration).

### 2. Enqueue a Request

In a component, take `enqueue` from `useSyncQueue()`, as the minimal example does. If you prefer to call the facade directly (for example, from a non-React module or a background callback), use the top-level function:

```tsx
import { enqueue, HttpMethod } from '@gabriel-sisjr/react-native-sync-provider';

const id = await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  contentType: 'application/json',
  body: JSON.stringify({ kind: 'app_opened' }),
});
console.log('Enqueued', id);
```

The item is stored. It is not sent until a flush runs.

### 3. Flush the Queue

In a component, take `flush` and `isSyncing` from `useSyncStatus()`. Elsewhere, call `flush()`:

```tsx
import { flush } from '@gabriel-sisjr/react-native-sync-provider';

// One cycle dispatches up to `batchSize` eligible items.
const result = await flush();
console.log(`${result.successCount} ok / ${result.failureCount} failed`);
```

### 4. Inspect the Queue

`useSyncQueue()` keeps `size` and `items` current in a component. For a one-off read, call `getQueueSize()`:

```tsx
import { getQueueSize } from '@gabriel-sisjr/react-native-sync-provider';

console.log('Pending:', await getQueueSize());
```

It counts `PENDING` items, including items waiting out a retry cooldown.

## Quick Tips

### Pick the Right Strategy

| Strategy | When to use |
|---|---|
| `AUTOMATIC` | Most apps. The engine retries failed items on its own when their cooldown ends. |
| `MANUAL` | Bulk-upload screens. The engine schedules nothing on its own, but background windows still run if you enabled them, and `useAutoSync` keeps flushing while it is enabled. |
| `OPPORTUNISTIC` | Reserved. It currently behaves like `AUTOMATIC`. |

No strategy flushes on enqueue. That takes `useAutoSync`, `flush()`, or a background window.

### Send an `Idempotency-Key`

Delivery is at least once, so a request can reach your server twice after a crash. Send a key the server can dedupe on. Headers persist with the item, so every retry carries the same key:

```tsx
import {
  enqueue,
  generateId,
  HttpMethod,
} from '@gabriel-sisjr/react-native-sync-provider';

await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/orders',
  headers: { 'Idempotency-Key': generateId() },
  body: JSON.stringify({ productId: 'sku-42' }),
});
```

See the [Idempotency guide](../guides/idempotency.md) for the server side.

### Use `useOfflineQueue` for Offline-First UIs

```tsx
import { useOfflineQueue } from '@gabriel-sisjr/react-native-sync-provider';

const { size, isWaitingForConnection, enqueue, flush } = useOfflineQueue();

// `isWaitingForConnection === true` => show a "Will sync when online" badge.
```

## Testing

A real device is recommended: emulators are fine for queue and retry logic, but background scheduling cannot be reproduced reliably on them.

### On Android Device

- **Simulate offline** -- toggle Airplane Mode to see `CONNECTION_CHANGED` events fire. Over adb: `adb shell svc wifi disable && adb shell svc data disable`.
- **Force a background fire** -- after `enableBackgroundSync`, find the `WorkManager` job id and run it:

  ```bash
  adb shell dumpsys jobscheduler | grep com.your.app
  adb shell cmd jobscheduler run -f com.your.app <jobid>
  ```

### On iOS Device

- **Simulate offline** -- toggle Airplane Mode, or use Network Link Conditioner, to see `CONNECTION_CHANGED` events fire.
- **Force a background fire** -- use the LLDB command in [iOS Setup](./ios-setup.md#7-test-the-background-handler), on a physical device. **Debug -> Simulate Background Fetch** does not exercise the BGTask handlers.

### On Emulator / Simulator

- Queue and retry logic can be tested here.
- The iOS Simulator does not accept `BGTaskScheduler` requests, so `enableBackgroundSync` rejects there with `BACKGROUND_TASK_REGISTRATION_FAILED`.

See [Debugging](../development/debugging.md) for log filters and for inspecting the persisted queue.

## Common Issues

| Problem | Cause | Fix |
|---|---|---|
| "Native module unavailable" | New Architecture off, or web/SSR | Enable Fabric, rebuild native app |
| Items never flush | Nothing starts a flush: no strategy flushes on enqueue | Mount `useAutoSync`, or call `flush()` |
| Background sync never fires | Identifiers missing in `Info.plist`, or the OS has not granted a window | See [iOS Setup](./ios-setup.md) |
| `MAX_ATTEMPTS_EXCEEDED` after one try | `RetryPolicy.maxAttempts` too low | Raise `maxAttempts` or expand `retryOnStatusCodes` |

## Next Steps

- [useSyncQueue](../api-reference/hooks/useSyncQueue.md) -- The hook that mirrors the pending queue, with its state, methods and re-render behavior
- [Background Sync](../guides/background-sync.md) -- Deep dive into BGTaskScheduler and WorkManager constraints
- [Retry Policy](../guides/retry-policy.md) -- Backoff curves, jitter, and status-code allowlists
- [Production Checklist](../production/production-checklist.md) -- Pre-launch checklist
