---
sidebar_position: 3
title: SyncProvider Context
description: Optional React context provider that owns configureSync and background sync lifecycle, exposing the active SyncOptions to descendants.
keywords:
  - react-native
  - sync-provider
  - context
  - SyncProvider
  - configureSync
  - provider
---

# `SyncProvider` Context

Optional React context provider. Mounting it lets descendant hooks read the active [`SyncOptions`](./types.md#syncoptions) without an extra native call, and centralizes the `configureSync` / `enableBackgroundSync` lifecycle in a single place.

```tsx
import { SyncProvider } from '@gabriel-sisjr/react-native-sync-provider';
```

The provider is **optional**: every hook works without it. Use it when you have multiple consumers in the tree that all need the config, or when you want a Provider-driven pattern for swapping configs at runtime (e.g. dev vs. prod).

---

## Props

| Prop | Type | Required | Description |
|------|------|----------|-------------|
| `options` | [`SyncOptions`](./types.md#syncoptions) | Yes | Configuration applied via `configureSync` on mount and on every prop change. |
| `backgroundSync` | [`BackgroundSyncOptions`](./types.md#backgroundsyncoptions) | No | When provided, the provider calls `enableBackgroundSync` on mount and `disableBackgroundSync` on unmount. Tracked via an internal ref so duplicate enable/disable calls are skipped. |
| `children` | `ReactNode` | Yes | Subtree that receives the context. |

---

## Context Value

```ts
interface SyncProviderContextValue {
  config: SyncOptions | undefined;
}
```

| Property | Type | Description |
|----------|------|-------------|
| `config` | `SyncOptions \| undefined` | The most recent `options` prop, after `configureSync` has resolved. `undefined` between mount and first ack. |

[`useSyncConfig`](./hooks/useSyncConfig.md) reads from this context when it is available.

---

## Lifecycle

| Phase | Behavior |
|-------|----------|
| Mount | Calls `configureSync(options)`. If `backgroundSync` is set, calls `enableBackgroundSync(backgroundSync)`. |
| `options` prop change | Calls `configureSync(newOptions)`. |
| `backgroundSync` prop change | If a previous `backgroundSync` was active, the provider re-runs `enableBackgroundSync` with the new options. The internal ref tracks whether ownership has been claimed so unmount cleanup runs exactly once. |
| Unmount | If the provider claimed background-sync ownership, calls `disableBackgroundSync()`. Does **not** revert `configureSync` -- the engine keeps running with the last applied config. |

---

## Example

### Single-tree configuration

```tsx
import {
  SyncProvider,
  SyncStrategy,
  BackoffStrategy,
} from '@gabriel-sisjr/react-native-sync-provider';

const SYNC_OPTIONS = {
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy: {
    maxAttempts: 5,
    backoff: BackoffStrategy.EXPONENTIAL,
    baseDelayMs: 1000,
    maxDelayMs: 60_000,
    jitter: true,
    retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
  },
  batchSize: 10,
};

const BACKGROUND_OPTIONS = {
  minimumIntervalMs: 15 * 60 * 1000,
  requiresUnmeteredNetwork: false,
};

export default function App() {
  return (
    <SyncProvider options={SYNC_OPTIONS} backgroundSync={BACKGROUND_OPTIONS}>
      <RootNavigator />
    </SyncProvider>
  );
}
```

### Runtime config swap

```tsx
function ConfiguredApp({ env }: { env: 'dev' | 'prod' }) {
  const options = env === 'prod' ? PROD_OPTIONS : DEV_OPTIONS;
  return (
    <SyncProvider options={options}>
      <App />
    </SyncProvider>
  );
}
```

The provider re-applies `configureSync` whenever `options` changes identity, so memoize the value if it is constructed inline.

---

## See also

- [`configureSync`](./functions.md#configuresync), [`enableBackgroundSync`](./functions.md#enablebackgroundsync) -- the underlying facade calls.
- [`useSyncConfig`](./hooks/useSyncConfig.md) -- consumes the context.
- [Background sync guide](../guides/background-sync.md).
