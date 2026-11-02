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

Get an offline-first HTTP sync queue running in five minutes. This guide uses the hooks-first approach for the simplest integration. The direct facade API is shown at the end for callers that prefer it.

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

## Direct API

If you prefer to call the facade directly (for example, from a non-React module or a background callback), use the top-level functions:

```tsx
import {
  enqueue,
  flush,
  getQueueSize,
  HttpMethod,
} from '@gabriel-sisjr/react-native-sync-provider';

// 1. Enqueue an item
const id = await enqueue({
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  contentType: 'application/json',
  body: JSON.stringify({ kind: 'app_opened' }),
});
console.log('Enqueued', id);

// 2. Flush. One cycle dispatches up to `batchSize` eligible items.
const result = await flush();
console.log(`${result.successCount} ok / ${result.failureCount} failed`);

// 3. Inspect the queue
console.log('Pending:', await getQueueSize());
```

## Configure the Sync Engine

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

:::warning Omitting `defaultHeaders` is a logout
Each `configureSync` replaces the whole configuration, and omitting `defaultHeaders` deletes the stored copy. See [Cold-start configuration](../guides/background-sync.md#cold-start-configuration).
:::

## Quick Tips

### Pick the right strategy

| Strategy | When to use |
|---|---|
| `AUTOMATIC` | Most apps. The engine retries failed items on its own when their cooldown ends. |
| `MANUAL` | Bulk-upload screens. The engine schedules nothing on its own, but background windows still run if you enabled them. |
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

### Use `useOfflineQueue` for offline-first UIs

```tsx
import { useOfflineQueue } from '@gabriel-sisjr/react-native-sync-provider';

const { size, isWaitingForConnection, enqueue, flush } = useOfflineQueue();

// `isWaitingForConnection === true` => show a "Will sync when online" badge.
```

## Testing

- **Real device recommended** -- emulators are fine for queue / retry logic but background scheduling cannot be reproduced reliably.
- **Simulate offline** -- toggle Airplane Mode (or Network Link Conditioner on iOS) to see `CONNECTION_CHANGED` events fire.
- **Force a background fire** -- on iOS, use the LLDB command in [iOS Setup](./ios-setup.md#7-test-the-background-handler), on a physical device. **Debug -> Simulate Background Fetch** does not exercise the BGTask handlers.

## Common Issues

| Problem | Cause | Fix |
|---|---|---|
| "Native module unavailable" | New Architecture off, or web/SSR | Enable Fabric, rebuild native app |
| Items never flush | Nothing starts a flush: no strategy flushes on enqueue | Mount `useAutoSync`, or call `flush()` |
| Background sync never fires | Identifiers missing in `Info.plist`, or the OS has not granted a window | See [iOS Setup](./ios-setup.md) |
| `MAX_ATTEMPTS_EXCEEDED` after one try | `RetryPolicy.maxAttempts` too low | Raise `maxAttempts` or expand `retryOnStatusCodes` |

## Next Steps

- [Hooks Reference](../api-reference/hooks/useSyncQueue.md) -- Every hook with options and return types.
- [Background Sync](../guides/background-sync.md) -- Deep dive into BGTaskScheduler and WorkManager constraints.
- [Retry Policy](../guides/retry-policy.md) -- Backoff curves, jitter, and status-code allowlists.
- [Production Checklist](../production/production-checklist.md) -- Pre-launch checklist.
