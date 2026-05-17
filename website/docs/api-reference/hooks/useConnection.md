---
sidebar_position: 1
title: useConnection
description: React hook that subscribes to the native connectivity monitor and re-renders on every CONNECTION_CHANGED event.
keywords:
  - react-native
  - sync-provider
  - hook
  - useConnection
  - connectivity
  - online
  - metered
---

# `useConnection`

Reactive view of the device's network state. Subscribes to `CONNECTION_CHANGED` events from the native [`ConnectivityMonitor`](../../architecture/overview.md) and re-renders whenever the status, type, or metered flag changes.

```ts
import { useConnection } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS. On web / SSR returns `{ status: 'UNKNOWN', type: 'UNKNOWN', isOnline: false, isMetered: false }`.

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

---

## Behavior

- **Initial value** -- on mount, the hook calls [`getConnectionStatus()`](../functions.md#getconnectionstatus) once to seed state.
- **Subscription** -- registers a listener for `SyncEventType.CONNECTION_CHANGED` events via [`addSyncEventListener`](../listeners.md#addsynceventlistener) and updates state on every emission.
- **Cleanup** -- unsubscribes on unmount.
- **Web / SSR safety** -- when the native module is unavailable, the hook returns the `UNKNOWN` defaults without throwing.

---

## Example

```tsx
import { useConnection } from '@gabriel-sisjr/react-native-sync-provider';
import { Text, View } from 'react-native';

function ConnectivityBadge() {
  const { isOnline, isMetered, type } = useConnection();

  if (!isOnline) return <Text>Offline</Text>;
  if (isMetered) return <Text>On {type} (metered)</Text>;
  return <Text>Online via {type}</Text>;
}
```

---

## See also

- [`getConnectionStatus`](../functions.md#getconnectionstatus) -- one-shot read.
- [`useOfflineQueue`](./useOfflineQueue.md) -- composes `useConnection` + queue state.
- [Connectivity detection guide](../../guides/connectivity-detection.md) -- how the native layer detects transitions.
