---
sidebar_position: 1
title: useConnection
description: React hook that subscribes to the native connectivity monitor and re-renders when the status, transport or network quality changes.
keywords:
  - react-native
  - sync-provider
  - hook
  - useConnection
  - connectivity
  - online
  - metered
  - network-quality
---

# `useConnection`

Reactive view of the device's network state. Subscribes to `CONNECTION_CHANGED` events from the native [`ConnectivityMonitor`](../../architecture/overview.md) and re-renders whenever the status, type, metered flag or network quality changes.

```ts
import { useConnection } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS. On web / SSR returns `{ status: 'UNKNOWN', type: 'NONE', isOnline: false, isMetered: false, networkQuality: 'OFFLINE' }`.

---

## Signature

```ts
function useConnection(): UseConnectionResult
```

---

## Return Value

| Property | Type | Description |
|----------|------|-------------|
| `status` | [`ConnectionStatus`](../enums.md#connectionstatus) | `CONNECTED`, `DISCONNECTED`, `METERED`, or `UNKNOWN`. |
| `type` | [`ConnectionType`](../enums.md#connectiontype) | `WIFI`, `CELLULAR`, `ETHERNET`, `BLUETOOTH`, `VPN`, `OTHER`, `NONE`, or `UNKNOWN`. |
| `isOnline` | `boolean` | Convenience: `status === CONNECTED \|\| status === METERED`. |
| `isMetered` | `boolean` | Convenience: `status === METERED`. |
| `networkQuality` | [`NetworkQuality`](../enums.md#networkquality) | The engine's quality classification, mirroring [`ConnectionState.networkQuality`](../types.md#connectionstate). `OFFLINE` whenever `isOnline` is `false`, including before the first snapshot arrives. Never `EXCELLENT` without `healthCheckUrl`. |

Before the first snapshot the result is `UNKNOWN` / `UNKNOWN` / `false` / `false` / `OFFLINE`.

---

## Behavior

- **Initial value** -- on mount, the hook calls [`getConnectionStatus()`](../functions.md#getconnectionstatus) once to seed state.
- **Subscription** -- registers a listener for `SyncEventType.CONNECTION_CHANGED` events via [`addSyncEventListener`](../listeners.md#addsynceventlistener), and re-reads the snapshot on every emission, including the ones emitted when only the network quality changed, since the event carries only the status.
- **Re-renders** -- the result object is kept when a new snapshot changes none of `status`, `type` and `networkQuality`, so an event that changes nothing visible causes no re-render.
- **Failed re-read** -- if the snapshot cannot be read after an event, the hook applies the event's status and keeps the last `type`. The quality stays tied to the status, as native keeps it: `OFFLINE` for an offline status, the previous quality while online, and, for a link that came online from `OFFLINE`, `GOOD` when unmetered or `MODERATE` when metered, the values for a link with no measurement.
- **Cleanup** -- unsubscribes on unmount.
- **Web / SSR safety** -- when the native module is unavailable, the hook returns the `UNKNOWN` defaults without throwing.

---

## Example

```tsx
import {
  NetworkQuality,
  useConnection,
} from '@gabriel-sisjr/react-native-sync-provider';
import { Text } from 'react-native';

function ConnectivityBadge() {
  const { isOnline, isMetered, type, networkQuality } = useConnection();

  if (!isOnline) return <Text>Offline</Text>;
  if (networkQuality === NetworkQuality.POOR) return <Text>Slow network</Text>;
  if (isMetered) return <Text>On {type} (metered)</Text>;
  return <Text>Online via {type}</Text>;
}
```

---

## See also

- [`getConnectionStatus`](../functions.md#getconnectionstatus) -- one-shot read.
- [`useOfflineQueue`](./useOfflineQueue.md) -- composes `useConnection` + queue state.
- [Connectivity detection guide](../../guides/connectivity-detection.md) -- how the native layer detects transitions and classifies network quality.
