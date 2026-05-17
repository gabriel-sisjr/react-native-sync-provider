---
sidebar_position: 7
title: useAutoSync
description: Orchestrator hook that triggers flushes on interval, reconnect, AppState foreground, and (optionally) registers background sync.
keywords:
  - react-native
  - sync-provider
  - hook
  - useAutoSync
  - auto-sync
  - flush
  - background-sync
---

# `useAutoSync`

Orchestrator hook that wires up the most common auto-flush triggers in one place: periodic interval, connectivity reconnect, metered-network gating, AppState foreground, and (optionally) registering background sync. Inert until `enabled` is `true`. Every trigger is wrapped in an `isSyncing()` guard so concurrent flushes do not stack.

```ts
import { useAutoSync } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS.

---

## Signature

```ts
function useAutoSync(options: {
  enabled?: boolean;
  flushOnReconnect?: boolean;
  flushOnMetered?: boolean;
  flushOnForeground?: boolean;
  backgroundSync?: BackgroundSyncOptions;
  intervalMs?: number;
  flushOnAppForeground?: boolean;
}): void
```

---

## Parameters

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `enabled` | `boolean` | No | `false` | Master switch. When `false`, no triggers fire. |
| `intervalMs` | `number` | No | `60_000` | Periodic interval between auto-flushes. Set to `0` to disable. |
| `flushOnReconnect` | `boolean` | No | `true` | Flush when `CONNECTION_CHANGED` reports a transition to `CONNECTED`. |
| `flushOnMetered` | `boolean` | No | `false` | Allow flushes triggered by reconnect / interval to run when the only available connection is `METERED`. |
| `flushOnForeground` | `boolean` | No | `false` | **Reserved for a future event-based foreground hook.** No-op today. Use `flushOnAppForeground` instead. |
| `flushOnAppForeground` | `boolean` | No | `false` | When `true`, the hook subscribes to React Native's `AppState` and flushes on every `active` transition. JS-only extension. |
| `backgroundSync` | [`BackgroundSyncOptions`](../types.md#backgroundsyncoptions) | No | -- | When provided, calls [`enableBackgroundSync`](../functions.md#enablebackgroundsync) on mount and [`disableBackgroundSync`](../functions.md#disablebackgroundsync) on unmount. |

:::warning Conservative defaults
By default the hook is inert (`enabled: false`). Once enabled, it will not flush on metered networks unless you opt in with `flushOnMetered: true`. If your traffic is small (telemetry, analytics, control-plane events), turning it on is safe; if your queue carries large payloads, leave it off to avoid burning user data.
:::

---

## Return Value

`void`. The hook performs side effects only.

---

## Behavior

- **`enabled` toggle** -- when `enabled` flips to `false`, all subscriptions tear down. Flipping back recreates them.
- **Interval** -- a `setInterval(intervalMs)` calls `flush()` if the queue is non-empty and not already syncing.
- **Reconnect** -- subscribes to `CONNECTION_CHANGED` events. On transition to `CONNECTED` (or to `METERED` when `flushOnMetered` is `true`), fires a guarded flush.
- **AppState foreground** -- when `flushOnAppForeground` is `true`, subscribes to React Native `AppState` and flushes on every `'background' -> 'active'` transition.
- **Background sync** -- if `backgroundSync` is provided, the hook owns the lifecycle: `enableBackgroundSync(options)` on mount, `disableBackgroundSync()` on unmount.
- **Idempotent flushes** -- every trigger calls `isSyncing()` first; concurrent flush calls are skipped.

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

### Full orchestration with background sync

```tsx
import { useAutoSync } from '@gabriel-sisjr/react-native-sync-provider';

function App() {
  useAutoSync({
    enabled: true,
    intervalMs: 30_000,
    flushOnReconnect: true,
    flushOnMetered: false,
    flushOnAppForeground: true,
    backgroundSync: {
      minimumIntervalMs: 15 * 60 * 1000,
      requiresUnmeteredNetwork: true,
    },
  });
  return <RootNavigator />;
}
```

---

## See also

- [`flush`](../functions.md#flush), [`enableBackgroundSync`](../functions.md#enablebackgroundsync), [`disableBackgroundSync`](../functions.md#disablebackgroundsync).
- [`useConnection`](./useConnection.md) -- the underlying connection signal.
- [Background sync guide](../../guides/background-sync.md), [Connectivity detection guide](../../guides/connectivity-detection.md).
