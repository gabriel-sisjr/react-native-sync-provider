---
sidebar_position: 4
title: Connectivity Detection
description: How react-native-sync-provider detects network connectivity using NWPathMonitor (iOS) and ConnectivityManager (Android), and how to drive flushes from connection changes.
keywords:
  - connectivity
  - nwpathmonitor
  - connectivitymanager
  - useConnection
  - online-offline
  - metered-network
  - connection-changed
---

# Connectivity Detection

The library tracks connectivity natively and exposes both a snapshot reader (`getConnectionStatus`) and a reactive hook (`useConnection`). Connection changes also surface as `CONNECTION_CHANGED` events on the `'sync-event'` channel.

Reporting connectivity is not the same as acting on it: see [What Acts on Connectivity](#what-acts-on-connectivity).

## Native Sources

| Platform | Source |
|---|---|
| iOS | `NWPathMonitor` (Network framework) |
| Android | `ConnectivityManager.NetworkCallback` + `NetworkCapabilities` |

Both APIs deliver low-latency callbacks the moment the OS detects a transport change. The library translates them into the cross-platform [`ConnectionState`](../api-reference/types.md#connectionstate) shape.

## Snapshot Reader

```ts
import { getConnectionStatus } from '@gabriel-sisjr/react-native-sync-provider';

const conn = await getConnectionStatus();
// { status: 'CONNECTED', type: 'WIFI', isInternetReachable: true, isExpensive: false }

if (conn.status === 'CONNECTED') {
  // safe to flush
}
```

Use this for one-off checks (e.g., before kicking off an explicit upload screen).

## Reactive Hook

`useConnection` flattens the snapshot into the most-used render-friendly fields and re-renders whenever connectivity changes:

```tsx
import { useConnection } from '@gabriel-sisjr/react-native-sync-provider';

function ConnectionBadge() {
  const { isOnline, isMetered, status, type } = useConnection();

  if (!isOnline) return <Text style={styles.offline}>Offline</Text>;
  if (isMetered) return <Text style={styles.metered}>Online (metered)</Text>;
  return <Text style={styles.online}>Online ({type})</Text>;
}
```

| Field | Truthy when |
|---|---|
| `isOnline` | `status === 'CONNECTED'` or `'METERED'` |
| `isMetered` | `status === 'METERED'` |

## `CONNECTION_CHANGED` Events

Subscribe directly to the underlying events when you need to trigger side effects:

```tsx
import { useSyncEvents } from '@gabriel-sisjr/react-native-sync-provider';

useSyncEvents({
  types: ['CONNECTION_CHANGED'],
  onEvent: async (e) => {
    console.log('connection -> ', e.connectionStatus);
    // Optional: trigger a flush on reconnect
  },
});
```

The `e.connectionStatus` field carries the coarse [`ConnectionStatus`](../api-reference/enums.md#connectionstatus). For the full snapshot, call `getConnectionStatus()` from the handler.

## Auto-Flush on Reconnect

`useAutoSync` wires the common patterns for you. It is inert until `enabled` is `true`:

```tsx
import { useAutoSync } from '@gabriel-sisjr/react-native-sync-provider';

useAutoSync({
  enabled: true,
  flushOnReconnect: true,
  flushOnMetered: false,
  flushOnAppForeground: true,
});
```

| Option | Default | Behavior |
|---|---|---|
| `enabled` | `false` | Master switch. |
| `flushOnReconnect` | `true` | Flush when the device transitions to `CONNECTED`. |
| `flushOnMetered` | `false` | Also flush on a transition to `METERED`. It gates this trigger only. |
| `flushOnAppForeground` | `false` | Flush when the app returns to the foreground, unless the status is `DISCONNECTED`. |

The interval tick and the foreground trigger skip while the status is `DISCONNECTED`, so leave `enabled` on while offline: see [Skip flushes while offline](../api-reference/hooks/useAutoSync.md#skip-the-interval-while-offline).

## What Acts on Connectivity

Neither native dispatcher checks connectivity, so a flush while offline fails its requests and spends retry attempts. What protects you depends on who starts the flush:

| Flush started by | Offline behavior |
|---|---|
| [Retry timer](./retry-policy.md#who-wakes-the-queue-and-when) | Does not flush. It remembers the wake and flushes when connectivity returns. On iOS, `UNKNOWN` counts as offline here; on Android, only `DISCONNECTED` does. |
| [`drainUntilEmpty`](../api-reference/functions.md#drainuntilempty) | Does not flush while `DISCONNECTED`, and waits for the network until a bound stops it. `UNKNOWN` and `METERED` still flush. |
| `useAutoSync` reconnect trigger | Fires only on the transition to online. |
| `useAutoSync` interval tick and foreground trigger | Do not flush while `DISCONNECTED`, or if the status cannot be read. `UNKNOWN` and `METERED` still flush. |
| [`flush()`](../api-reference/functions.md#flush) | **Flushes anyway.** |
| Android workers | WorkManager runs them only when their network constraint is met. |

There is no native flush on reconnect by itself. The retry timer's owed wake is the only one.

## Metered Network Caveats

`ConnectionStatus.METERED` and `ConnectionState.isExpensive` are derived from OS hints. They may disagree on:

- Wi-Fi captive portals that the OS flags as metered after probing.
- Paid roaming where iOS reports `isExpensive: true` but the type is still `WIFI`.
- Android tethered connections (mobile hotspot from a phone) that report as `WIFI` + `isMetered`.

When in doubt, check **both** `status === 'METERED'` and `isExpensive` before deciding to dispatch large payloads.

## What Counts as "Online"

| `ConnectionStatus` | `isOnline` | Notes |
|---|---|---|
| `CONNECTED` | `true` | Active path, unmetered (typically Wi-Fi or ethernet). |
| `METERED` | `true` | Active path, metered (cellular or mobile hotspot). |
| `DISCONNECTED` | `false` | No usable path. |
| `UNKNOWN` | `false` | Initial state before the OS reports a path. Treat as offline. |

The library does **not** probe `https://detectportal.firefox.com` or similar reachability endpoints -- it trusts the OS report. If your app needs deeper reachability checks, perform them in your own layer and gate `enqueue` / `flush` calls accordingly.

## Next Steps

- [`useConnection` hook reference](../api-reference/hooks/useConnection.md)
- [`useAutoSync` hook reference](../api-reference/hooks/useAutoSync.md)
- [Background Sync](./background-sync.md) -- Connectivity-aware OS scheduling.
