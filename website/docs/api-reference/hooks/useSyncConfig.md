---
sidebar_position: 6
title: useSyncConfig
description: React hook that reads the current SyncOptions — from the SyncProvider context if mounted, otherwise from the native singleton.
keywords:
  - react-native
  - sync-provider
  - hook
  - useSyncConfig
  - configureSync
  - SyncOptions
  - context
---

# `useSyncConfig`

Reactive view of the active [`SyncOptions`](../types.md#syncoptions) plus a setter that round-trips through [`configureSync`](../functions.md#configuresync). Reads from the `SyncProvider` context if one is mounted; otherwise falls back to a one-shot `getSyncConfig()` on the native singleton.

```ts
import { useSyncConfig } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS.

---

## Signature

```ts
function useSyncConfig(): UseSyncConfigResult
```

---

## Return Value

### State

| Property | Type | Description |
|----------|------|-------------|
| `config` | [`SyncOptions`](../types.md#syncoptions) `\| undefined` | Active configuration. `undefined` until the first read resolves. |
| `isLoading` | `boolean` | `true` while the initial fetch is in flight. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last error from `setConfig` or the initial read. |

### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `setConfig` | `(options: SyncOptions) => Promise<void>` | Apply new options via `configureSync`. Updates local state on success. |
| `refresh` | `() => Promise<void>` | Re-read from native. Useful when external code (another tab, a background task) has called `configureSync`. |

---

## Behavior

- **Source of truth** -- if a [`SyncProvider`](../context.md) is mounted higher in the tree, the hook reads `config` directly from `SyncProviderContext` and skips the native call.
- **Fallback** -- without a provider, the hook calls [`getSyncConfig`](../functions.md#getsyncconfig) once on mount.
- **`setConfig`** -- always calls `configureSync`. The native layer is the source of truth; local state mirrors it.
- **No event subscription** -- config changes do not fire a sync event, so the hook does not auto-refresh on external mutations. Call `refresh()` explicitly if needed.

---

## Example

### Read + display retry policy

```tsx
import { useSyncConfig } from '@gabriel-sisjr/react-native-sync-provider';
import { Text, View } from 'react-native';

function RetryPolicyView() {
  const { config, isLoading } = useSyncConfig();

  if (isLoading || !config) return <Text>Loading config...</Text>;

  return (
    <View>
      <Text>Max attempts: {config.retryPolicy.maxAttempts}</Text>
      <Text>Backoff: {config.retryPolicy.backoff}</Text>
      <Text>Base delay: {config.retryPolicy.baseDelayMs}ms</Text>
    </View>
  );
}
```

### Update batch size at runtime

```tsx
import {
  useSyncConfig,
  SyncStrategy,
  BackoffStrategy,
} from '@gabriel-sisjr/react-native-sync-provider';

function BatchSizeControl() {
  const { config, setConfig } = useSyncConfig();

  const setBatch = async (n: number) => {
    if (!config) return;
    await setConfig({ ...config, batchSize: n });
  };

  return <Button title="Use small batches" onPress={() => setBatch(5)} />;
}
```

---

## See also

- [`configureSync`](../functions.md#configuresync), [`getSyncConfig`](../functions.md#getsyncconfig) -- direct facade calls.
- [`SyncProvider` context](../context.md) -- when to mount a provider.
- [Retry policy guide](../../guides/retry-policy.md).
