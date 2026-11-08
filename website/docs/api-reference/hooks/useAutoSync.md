---
sidebar_position: 7
title: useAutoSync
description: Orchestrator hook that triggers JS-side flushes on an interval, on reconnect, and when the app returns to the foreground.
keywords:
  - react-native
  - sync-provider
  - hook
  - useAutoSync
  - auto-sync
  - flush
  - reconnect
---

# `useAutoSync`

Orchestrator hook that wires up the common flush triggers in one place: a periodic interval, a connectivity reconnect, and, opt-in, the app returning to the foreground. Inert until `enabled` is `true`. Every trigger is guarded by `isSyncing()`, so flushes do not stack.

This hook is what dispatches new items while the app runs. The `AUTOMATIC` strategy does not flush on enqueue or on reconnect, so without this hook, a `flush()` call, or an OS background window, a new item stays in the queue.

:::warning `useAutoSync` ignores the strategy
The hook never reads `SyncOptions.strategy`, so under `MANUAL` it keeps flushing on its interval, reconnect and foreground triggers. `MANUAL` only stops the native engine from scheduling flushes on its own: the in-process retry timer and, on Android, the retry kick. Background windows enabled through [`enableBackgroundSync`](../functions.md#enablebackgroundsync) still run too. To stop the hook's flushes, pass `enabled: false` or unmount it. See [Manual flows](#manual-flows).
:::

```ts
import { useAutoSync } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS. A no-op on web.

---

## Signature

```ts
function useAutoSync(options: UseAutoSyncExtraOptions): void
```

---

## Parameters

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `enabled` | `boolean` | No | `false` | Master switch. When `false`, no triggers fire and nothing is subscribed. The hook does not read the strategy, so this is also how to turn it off under `MANUAL`. |
| `intervalMs` | `number` | No | `60_000` | Period of the tick. A value that is not positive falls back to the default, so the tick cannot be turned off while the hook is enabled. |
| `flushOnReconnect` | `boolean` | No | `true` | Flush when `CONNECTION_CHANGED` reports a transition to `CONNECTED`. |
| `flushOnMetered` | `boolean` | No | `false` | Also flush on a transition to `METERED`. Needs `flushOnReconnect`. It gates this trigger only: the interval flushes on any connection. |
| `flushOnAppForeground` | `boolean` | No | `false` | Flush whenever React Native's `AppState` changes to `active`, unless the connection status is `DISCONNECTED`. |
| `flushOnForeground` | `boolean` | No | -- | **Reserved.** The hook ignores it. Use `flushOnAppForeground`. |
| `backgroundSync` | [`BackgroundSyncOptions`](../types.md#backgroundsyncoptions) | No | -- | **Reserved.** The hook ignores it. Call [`enableBackgroundSync`](../functions.md#enablebackgroundsync), or pass `backgroundSync` to [`<SyncProvider>`](../context.md). |

:::info Flushes are skipped while offline
The interval and the foreground trigger read the connection status first and do nothing while it is `DISCONNECTED`, because a flush while offline can spend retry attempts and mark valid items `FAILED`. `UNKNOWN` and `METERED` still flush. See [Skip flushes while offline](#skip-the-interval-while-offline).

Neither trigger checks queue size: on an empty queue each flush runs a 0/0 cycle that adds a row to the sync history.
:::

---

## Return Value

`void`. The hook performs side effects only.

---

## Behavior

- **`enabled` toggle** -- when `enabled` flips to `false`, all subscriptions tear down. Flipping back recreates them, with no immediate flush.
- **Interval** -- a `setInterval(intervalMs)` calls `flush()` unless the status is `DISCONNECTED` or a cycle is already running. If the status cannot be read, the flush is skipped. It runs in JS, so it stops while the OS suspends the app.
- **Reconnect** -- subscribes to `CONNECTION_CHANGED`. On a transition to `CONNECTED`, or to `METERED` when `flushOnMetered` is `true`, it fires a guarded flush. It follows the event, so it does not read the status.
- **AppState foreground** -- when `flushOnAppForeground` is `true`, it flushes on every change to `active`, with the same status check as the interval.
- **Guarded flushes** -- every trigger calls `isSyncing()` before it flushes, and a call made during a cycle is skipped.
- **Strategy** -- not read. Every trigger flushes under `MANUAL` as under `AUTOMATIC`. While the engine is paused, a flush runs no cycle.
- **Errors** -- a failed flush is swallowed. The hook never throws to the consumer.
- **Unmount** -- clears the interval and removes both subscriptions.

---

## Example

### Minimal opt-in

```tsx
import { useAutoSync } from '@gabriel-sisjr/react-native-sync-provider';

function App() {
  useAutoSync({
    enabled: true,
    flushOnAppForeground: true,
  });
  return <RootNavigator />;
}
```

### Skip flushes while offline {#skip-the-interval-while-offline}

The hook does this for you: keep `enabled` on while the device is offline. The interval and the foreground trigger resume by themselves, and the reconnect trigger flushes as soon as the device is back.

Do not gate `enabled` on the connection. While `enabled` is `false` the hook is not subscribed, so its reconnect trigger cannot fire.

```tsx
import { useAutoSync } from '@gabriel-sisjr/react-native-sync-provider';

function App() {
  useAutoSync({ enabled: true, intervalMs: 30_000 });
  return <RootNavigator />;
}
```

### Manual flows

`strategy: MANUAL` does not stop this hook. Turn it off yourself while a screen drives its own flushes:

```tsx
import { useAutoSync } from '@gabriel-sisjr/react-native-sync-provider';

function AutoSync({ manualMode }: { manualMode: boolean }) {
  // The hook ignores `strategy`: under MANUAL, keep it off.
  useAutoSync({ enabled: !manualMode, flushOnAppForeground: true });
  return null;
}
```

### Together with background sync

`useAutoSync` covers the running app, and [`enableBackgroundSync`](../functions.md#enablebackgroundsync) covers the windows the OS gives you while it is not running. They are set up separately.

```tsx
import {
  SyncProvider,
  useAutoSync,
} from '@gabriel-sisjr/react-native-sync-provider';

function AutoSync() {
  useAutoSync({ enabled: true, flushOnAppForeground: true });
  return null;
}

export default function App() {
  return (
    <SyncProvider options={SYNC_OPTIONS} backgroundSync={BACKGROUND_OPTIONS}>
      <AutoSync />
      <RootNavigator />
    </SyncProvider>
  );
}
```

---

## See also

- [`flush`](../functions.md#flush), [`enableBackgroundSync`](../functions.md#enablebackgroundsync).
- [`SyncStrategy`](../enums.md#syncstrategy) -- what `MANUAL` stops, and what it does not.
- [`useConnection`](./useConnection.md) -- read the connection status in your UI.
- [Background sync guide](../../guides/background-sync.md), [Connectivity detection guide](../../guides/connectivity-detection.md).
