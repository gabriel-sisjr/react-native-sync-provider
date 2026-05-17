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

`useAutoSync` wires the common patterns for you:

```tsx
import { useAutoSync } from '@gabriel-sisjr/react-native-sync-provider';

useAutoSync({
  flushOnReconnect: true,
  flushOnMetered: false,
  flushOnForeground: true,
});
```

| Option | Default | Behavior |
|---|---|---|
| `flushOnReconnect` | `true` | Flush when the device transitions to `CONNECTED`. |
| `flushOnMetered` | `false` | Also flush on `METERED`. Disabled to avoid surprise cellular usage. |
| `flushOnForeground` | `true` | Flush when the app returns to the foreground. |

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
