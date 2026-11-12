---
sidebar_position: 4
title: Connectivity Detection
description: How react-native-sync-provider detects connectivity with NWPathMonitor (iOS) and ConnectivityManager (Android), classifies network quality, gates flushes on it, and checks backend health before a batch.
keywords:
  - connectivity
  - nwpathmonitor
  - connectivitymanager
  - useConnection
  - online-offline
  - metered-network
  - connection-changed
  - network-quality
  - health-check
---

# Connectivity Detection

The library tracks connectivity natively and exposes both a snapshot reader (`getConnectionStatus`) and a reactive hook (`useConnection`). Connection changes also surface as `CONNECTION_CHANGED` events on the `'sync-event'` channel.

On top of the link state, the engine grades the link with a [network quality](#network-quality) level. Two opt-in options act on it: a [quality gate](#the-quality-gate) that skips flushes on a poor link, and a [health check](#health-check) that probes your backend before a batch.

Reporting connectivity is not the same as acting on it: see [What Acts on Connectivity](#what-acts-on-connectivity).

## Native Sources

| Platform | Source |
|---|---|
| iOS | `NWPathMonitor` (Network framework) |
| Android | `ConnectivityManager` default-network callback + `NetworkCapabilities` |

Both APIs deliver low-latency callbacks the moment the OS detects a transport change. The library translates them into the cross-platform [`ConnectionState`](../api-reference/types.md#connectionstate) shape.

Android follows only the default network, the one the app's traffic uses. Losing a secondary network, such as cellular alongside Wi-Fi or a VPN that does not apply to the app, does not report `DISCONNECTED`, and a VPN that was the default network hands over without a false disconnect.

## Snapshot Reader

```ts
import { getConnectionStatus } from '@gabriel-sisjr/react-native-sync-provider';

const conn = await getConnectionStatus();
// { status: 'CONNECTED', type: 'WIFI', networkQuality: 'GOOD',
//   isInternetReachable: true, isExpensive: false }

if (conn.status === 'CONNECTED') {
  // safe to flush
}
```

Use this for one-off checks (e.g., before kicking off an explicit upload screen).

## Reactive Hook

`useConnection` flattens the snapshot into the most-used render-friendly fields and re-renders whenever connectivity or its quality changes:

```tsx
import { useConnection } from '@gabriel-sisjr/react-native-sync-provider';

function ConnectionBadge() {
  const { isOnline, isMetered, type, networkQuality } = useConnection();

  if (!isOnline) return <Text style={styles.offline}>Offline</Text>;
  if (networkQuality === 'POOR') return <Text style={styles.metered}>Slow network</Text>;
  if (isMetered) return <Text style={styles.metered}>Online (metered)</Text>;
  return <Text style={styles.online}>Online ({type})</Text>;
}
```

| Field | Truthy when |
|---|---|
| `isOnline` | `status === 'CONNECTED'` or `'METERED'` |
| `isMetered` | `status === 'METERED'` |

`networkQuality` is `OFFLINE` whenever `isOnline` is `false`.

## `CONNECTION_CHANGED` Events

Subscribe directly to the underlying events when you need to trigger side effects:

```tsx
import {
  getConnectionStatus,
  useSyncEvents,
} from '@gabriel-sisjr/react-native-sync-provider';

useSyncEvents({
  types: ['CONNECTION_CHANGED'],
  onEvent: async (e) => {
    console.log('connection -> ', e.connectionStatus);
    const { networkQuality } = await getConnectionStatus();
    console.log('quality -> ', networkQuality);
  },
});
```

The `e.connectionStatus` field carries the coarse [`ConnectionStatus`](../api-reference/enums.md#connectionstatus). For the full snapshot, including the quality, call `getConnectionStatus()` from the handler.

- **Only real changes.** The event fires when a reported field changes: `status`, `type`, `isInternetReachable`, `isExpensive` or `networkQuality`. It does not fire for a duplicate. Android never emits it for the state it starts in. iOS starts at `UNKNOWN`, so its first path report, which replaces that, emits one. Before v0.3, iOS emitted it on every path update.
- **Quality-only changes.** A change of `networkQuality` alone emits it too, with an unchanged `connectionStatus`.
- **Delivery.** iOS delivers it synchronously, Android asynchronously.

## Network Quality {#network-quality}

[`NetworkQuality`](../api-reference/enums.md#networkquality) grades the current link, worst to best: `OFFLINE < POOR < MODERATE < GOOD < EXCELLENT`. It is always present on `ConnectionState`, and `OFFLINE` exactly when the status is `DISCONNECTED` or `UNKNOWN`.

### How it is classified

Both platforms use the same fixed table.

**Measured**, only when [`healthCheckUrl`](../api-reference/types.md#syncoptions) is set. The engine sends three back-to-back `HEAD` requests to that URL, each with a 2 s timeout, and counts a failure or a timeout as infinitely slow. The median picks the level:

| Median | Level |
|---|---|
| Under 200 ms | `EXCELLENT` |
| 200–499 ms | `GOOD` |
| 500–999 ms | `MODERATE` |
| 1000 ms or more, including two or more failures | `POOR` |

Any HTTP status counts as a latency sample. A `5xx` is the [health check](#health-check)'s concern, not the classifier's.

**Not measured**, because there is no `healthCheckUrl` or the first measurement of this network is still pending: an unmetered link is `GOOD` and a metered one `MODERATE`.

**No link**, or a link the OS has not reported yet: `OFFLINE`.

**Caps**, applied last. The lowest cap wins:

| Condition | At most |
|---|---|
| Android link without `NET_CAPABILITY_VALIDATED` | `POOR` |
| Metered link (Android) or expensive link (iOS) | `GOOD` |
| iOS Low Data Mode | `MODERATE` |

Without `healthCheckUrl` nothing is measured, so the level never reaches `EXCELLENT`.

### When it is measured

Nothing is ever measured without `healthCheckUrl`, and nothing is sent while the device is offline. The probes go to that URL only, never to a third-party host. They carry no `defaultHeaders` and no item header, and send `Cache-Control: no-cache`.

- **Network change.** On a move to another network, a measurement starts after a 1000 ms debounce. Until it completes, the link-signal value is reported.
- **Reuse.** A measurement belongs to one network. It does not expire, and it survives an offline blip: if the same network comes back within 30 s, the measurement is reused instead of taken again.
- **Stale reads.** When the [quality gate](#the-quality-gate), at a flush or at a held-wake re-check, reads a measurement 30 s old or older, it starts one background re-measurement, at most one at a time. The gate decides on the current value, and held wakes are re-checked when the new result arrives. Only the gate reads it, and only with `minNetworkQuality` set above `OFFLINE`. Without a gate, a network change or a new URL still measures, but flushes and retry wakes do not, so on a network the device stays on, the level stays at its first measurement.
- **URL change.** A new `healthCheckUrl` starts a new measurement.

How a platform tells networks apart:

- **Android** keys a measurement by the OS network handle, its validation state and the URL.
- **iOS** has no network identifier, so it uses the interface type, the expensive and constrained flags, the gateway addresses and the URL. Two networks with the same gateway address can therefore share a measurement for up to 30 s.

## The Quality Gate {#the-quality-gate}

Set [`SyncOptions.minNetworkQuality`](../api-reference/types.md#syncoptions) to skip flush cycles on a link below that level. It is off by default: unset, no flush checks the quality, so a manual offline `flush()` behaves as before v0.3.

```ts
import {
  configureSync,
  NetworkQuality,
} from '@gabriel-sisjr/react-native-sync-provider';

await configureSync({
  ...SYNC_OPTIONS,
  minNetworkQuality: NetworkQuality.MODERATE,
  // Optional: measure the link, and probe the backend before a batch.
  healthCheckUrl: 'https://api.example.com/health',
});
```

| `minNetworkQuality` | Cycles run on |
|---|---|
| unset | Any link, offline included: no gate. |
| `OFFLINE` | Same as unset. |
| `POOR` | Any online link. It blocks only `OFFLINE`, which includes a link the OS has not reported yet. |
| `MODERATE` | `MODERATE`, `GOOD` and `EXCELLENT` links. |
| `GOOD` | `GOOD` and `EXCELLENT` links. |
| `EXCELLENT` | Only a measured median under 200 ms on a link no cap lowers. Without `healthCheckUrl` it blocks every flush. |

> **Tip — `GOOD` without a URL means unmetered:** With no measurement, a metered link is `MODERATE`. So without `healthCheckUrl`, `minNetworkQuality: GOOD` lets cycles run only on unmetered links, outside iOS Low Data Mode and, on Android, on validated networks.

### What the gate checks

Every flush, whoever starts it: `flush()`, [`useAutoSync`](../api-reference/hooks/useAutoSync.md), [`drainUntilEmpty`](../api-reference/functions.md#drainuntilempty), the Android retry kick and periodic worker, and the iOS BGTasks. It runs after the pause and stored-credentials checks and before the health check (see [`flush`](../api-reference/functions.md#checks-before-a-cycle)). The in-process retry timer checks it before it starts a flush, and [holds the wake silently](#held-wakes) instead.

Below the minimum, the flush runs no cycle: `SYNC_FAILED` is emitted with `errorCode: 'NETWORK_QUALITY_TOO_LOW'`, with no `SYNC_STARTED` before it and no history row, no item changes, no attempt is spent, and `flush()` resolves with the [no-cycle result](../api-reference/types.md#syncresult). A `flush()` call is not repeated: call it again later.

While the first measurement of a network is pending, the gate compares the link-signal level like any other. On iOS, a gated flush that finds the path not reported yet waits up to 1.5 s for the first path update, then grades the link `OFFLINE`, so a background launch is not spent on an unknown path.

### Held wakes

The in-process [retry timer](./retry-policy.md#who-wakes-the-queue-and-when) checks the gate before it dispatches, the same way it checks for an offline device, and holds the wake silently: no event, no `SYNC_FAILED`. It re-runs the wake once the device is online and the quality reaches the minimum.

While it holds, it re-checks on a backoff: 30, 60, 120, 240 and 480 seconds, then every 15 minutes. The backoff restarts when a wake passes, when the network changes, and on any new arm of the timer, which includes every `configureSync` call. A held wake therefore does not wait forever on a stable network: each re-check that finds a measurement 30 s old starts a re-measurement.

If the quality drops between that silent check and the flush it starts, the flush's own gate stops it, and that attempt emits `SYNC_FAILED`.

Workers and BGTasks are not held: they reach the gate and complete. See [Background Sync](./background-sync.md#when-the-gate-or-the-health-check-skips-a-window).

## Health Check {#health-check}

Set [`SyncOptions.healthCheckUrl`](../api-reference/types.md#syncoptions) to check an endpoint of yours before a cycle claims its first item. It is off by default.

| Option | Default | Meaning |
|---|---|---|
| `healthCheckUrl` | unset | Absolute `http(s)` URL. A final `2xx`, after redirects, is healthy. Any other status, a transport error or a timeout is not. |
| `healthCheckTtlMs` | `60000` | How long a healthy result is reused. `0` checks before every cycle. |
| `healthCheckBackoffMs` | `30000` | How long cycles are skipped after a failed check. `0` means no backoff, but automatic retry wakes still wait 1000 ms after a failure. |

How a cycle uses it:

1. **Cached result.** If a healthy result younger than `healthCheckTtlMs` exists, no request is sent. A `2xx` sample from a kept [quality measurement](#when-it-is-measured) counts as a healthy result too, so the endpoint is not hit twice. The samples of a measurement cancelled midway, for example by an offline update, are discarded.
2. **Probe.** Otherwise the engine sends one `HEAD`, with a 2 s timeout, no `defaultHeaders` or item headers, and `Cache-Control: no-cache`.
3. **Failure.** The cycle is skipped like a gated one: `SYNC_FAILED` with `errorCode: 'HEALTH_CHECK_FAILED'`, no `SYNC_STARTED`, no history row, no item change. A backoff then runs until `healthCheckBackoffMs` has passed since the failed `HEAD` finished, and every cycle in it is skipped the same way without sending a request. The in-process retry timer holds silently during the backoff.
4. **Next wake.** The next retry wake is the latest of the item's own deadline, a [credential hold](./background-sync.md#when-a-stored-token-expires), the end of the backoff and 1000 ms after the failed check. That floor holds even with `healthCheckBackoffMs: 0`, for every automatic wake: the in-process retry timer and its held-wake re-checks, the wakes after `resumeSync()` and on reconnect, and the Android retry kick. A check that keeps failing therefore cannot make them spin. It does not apply to an explicit `flush()`: with a backoff of `0`, a `flush()` right after a failure sends a new `HEAD`.
5. **Recovery.** A healthy check clears the backoff, and so does every `configureSync` call, which ends it early. The backoff is kept in memory only, so a new process starts without one.

> **Warning — Point it at an endpoint that answers without authentication:** The probes carry no credentials, so an endpoint that requires them answers `401` and every cycle is skipped. Prefer `https://`: a captive portal can answer a plain `http://` probe with a `2xx`. Only Android's `NET_CAPABILITY_VALIDATED` cap covers that case, and only for the quality level.

`configureSync` rejects a `healthCheckUrl` that is not an absolute `http(s)` URL with `INVALID_URL`, and a negative or non-finite duration with `INVALID_PAYLOAD`. The options are persisted for background workers. A stored value that cannot be used, which only a damaged record can hold, loads as unset.

On iOS, a healthy result is cached per URL, so a new `healthCheckUrl` is checked afresh, and overlapping checks share one `HEAD`. A `2xx` measurement sample refreshes the cache but does not end a running backoff. The backoff starts when the failed `HEAD` finishes. Ages use the wall clock, and a negative age, after the clock moved back, makes the cache stale and ends the backoff.

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

The interval tick and the foreground trigger skip while the status is `DISCONNECTED`, so leave `enabled` on while offline: see [Skip flushes while offline](../api-reference/hooks/useAutoSync.md#skip-the-interval-while-offline). The reconnect trigger reads the starting status when the hook is enabled and compares each event's status with the last one it knows, so it flushes only on a real change to `CONNECTED`, or to `METERED` with `flushOnMetered`. A quality-only `CONNECTION_CHANGED` never flushes.

## What Acts on Connectivity

Without `minNetworkQuality`, neither native dispatcher checks connectivity, so a flush while offline fails its requests and spends retry attempts. What protects you depends on who starts the flush:

| Flush started by | Offline behavior | Below `minNetworkQuality` |
|---|---|---|
| [Retry timer](./retry-policy.md#who-wakes-the-queue-and-when) | Does not flush. It remembers the wake and flushes when connectivity returns. On iOS, `UNKNOWN` counts as offline here; on Android, only `DISCONNECTED` does. | Holds the wake silently and re-checks on a backoff. See [Held wakes](#held-wakes). |
| [`drainUntilEmpty`](../api-reference/functions.md#drainuntilempty) | Does not flush while `DISCONNECTED`, and waits for the network until a bound stops it. `UNKNOWN` and `METERED` still flush. | Each skipped flush uses up an iteration, so the loop ends with `MAX_ITERATIONS` or `TIMEOUT`. |
| `useAutoSync` reconnect trigger | Fires only on the transition to online. | Its flush is skipped with `SYNC_FAILED`. |
| `useAutoSync` interval tick and foreground trigger | Do not flush while `DISCONNECTED`, or if the status cannot be read. `UNKNOWN` and `METERED` still flush. | Their flushes are skipped with `SYNC_FAILED`. |
| [`flush()`](../api-reference/functions.md#flush) | **Flushes anyway**, unless `minNetworkQuality` is set to anything but `OFFLINE`. | Skipped with `SYNC_FAILED` and a 0/0 result. |
| Android workers | WorkManager runs them only when their network constraint is met. | Skipped. The worker still reports success. |
| iOS BGTasks | The processing task requires a network. | Skipped. The task completes normally. |

There is no native flush on reconnect by itself. The retry timer's owed wake is the only one.

## Metered Network Caveats

`ConnectionStatus.METERED` and `ConnectionState.isExpensive` are derived from OS hints. They may disagree on:

- Wi-Fi captive portals that the OS flags as metered after probing.
- Paid roaming where iOS reports `isExpensive: true` but the type is still `WIFI`.
- Android tethered connections (mobile hotspot from a phone) that report as `WIFI` + `isMetered`.

On Android 11 (API 30) and later, a temporarily unmetered link, as some 5G plans provide, reads as `CONNECTED` with `isExpensive: false`, and as `METERED` again when the OS withdraws the exemption.

When in doubt, check **both** `status === 'METERED'` and `isExpensive` before deciding to dispatch large payloads.

## What Counts as "Online"

| `ConnectionStatus` | `isOnline` | Notes |
|---|---|---|
| `CONNECTED` | `true` | Active path, unmetered (typically Wi-Fi or ethernet). |
| `METERED` | `true` | Active path, metered (cellular or mobile hotspot). |
| `DISCONNECTED` | `false` | No usable path. |
| `UNKNOWN` | `false` | Initial state before the OS reports a path. Treat as offline. iOS only: Android reads a network it has not described yet as `DISCONNECTED`. |

The library does **not** probe `https://detectportal.firefox.com` or any other third-party reachability endpoint. It trusts the OS report, and only sends requests of its own to your `healthCheckUrl`, when you set one.

## Platform Notes

- **Android probes.** The probe client falls back to a host's other addresses (IPv4 / IPv6) when a connection fails. A sample can occasionally cost two round trips, because OkHttp repeats a `408` once and silently retries on a stale pooled connection. A process with no JS engine may send a measurement when a retry kick runs on a stale one, and only with `healthCheckUrl` set and `minNetworkQuality` above `OFFLINE`.
- **Android dispatch.** The dispatcher's own client does not fall back to a host's other addresses: a request whose first address fails is not retried on the next one. This is unchanged in v0.3.
- **Android validation.** A network without `NET_CAPABILITY_VALIDATED`, such as a captive portal before sign-in, is at most `POOR`.
- **iOS first path.** Before the first `NWPathMonitor` update the status is `UNKNOWN` and the quality `OFFLINE`. A gated flush waits up to 1.5 s for that update.
- **iOS Low Data Mode.** A constrained path is at most `MODERATE`.

## Next Steps

- [`useConnection` hook reference](../api-reference/hooks/useConnection.md)
- [`useAutoSync` hook reference](../api-reference/hooks/useAutoSync.md)
- [Background Sync](./background-sync.md) -- Connectivity-aware OS scheduling, and what a gated window does.
- [Troubleshooting](../troubleshooting.md#symptom-nothing-syncs-with-minnetworkquality-or-healthcheckurl-set) -- when nothing syncs with the gate or the health check on.
