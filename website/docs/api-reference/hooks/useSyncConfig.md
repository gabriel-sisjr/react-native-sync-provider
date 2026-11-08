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

Reactive view of the active [`SyncOptions`](../types.md#syncoptions) plus a setter that round-trips through [`configureSync`](../functions.md#configuresync). Mirrors the `SyncProvider` context if one is mounted. Otherwise it reads `getSyncConfig()` from native on mount and again on every `RESUMED` event.

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
| `config` | [`SyncOptions`](../types.md#syncoptions) `\| null` | Active configuration. `null` until the first read resolves. It can lack the stored `defaultHeaders`: see the warning below. |
| `isLoading` | `boolean` | `true` while a read of the native config is in flight. |
| `error` | [`SyncError`](../errors.md) `\| null` | Last error reading the config. `setConfig` rejects instead of setting it, if `configureSync` fails. |

### Methods

| Method | Signature | Description |
|--------|-----------|-------------|
| `setConfig` | `(next: SyncOptions) => Promise<void>` | Apply new options via `configureSync`, then re-read them. It replaces the whole configuration, so set `defaultHeaders` explicitly instead of copying them from `config`. Only the calling hook updates: see below. |
| `refresh` | `() => Promise<void>` | Re-read from native. Useful when other code has called `configureSync`. |

---

## Behavior

- **With a Provider** -- if a [`SyncProvider`](../context.md) is mounted higher in the tree, the hook mirrors the context `config` and makes no probe of its own, even while the Provider is still loading. A context value that carries a `config` counts as a Provider, so a test wrapper rendering `SyncProviderContext.Provider` directly works without setting `hasProvider`.
- **Without a Provider** -- the hook calls [`getSyncConfig`](../functions.md#getsyncconfig) on mount and on every `RESUMED` event.
- **`setConfig`** -- always calls `configureSync`, then re-reads the config. Only the calling hook updates: other `useSyncConfig` instances and the Provider's context value catch up on their next read. It is a full replace, like `configureSync`: spread `config` for the other fields, and set `defaultHeaders` from your app's own session instead of copying them from `config`.
- **External changes** -- `configureSync` fires no sync event, so the hook does not see changes made elsewhere. Call `refresh()` if needed.

:::warning `setConfig({ ...config })` can log the user out
`config` comes from [`getSyncConfig`](../functions.md#getsyncconfig), so it can lack the stored headers: while they cannot be read, `defaultHeaders` is `undefined` on Android and `{}` on iOS. A config passed without them deletes the stored copy, which acts as a logout. Later requests go out without them, and a 401 or 403 fails those items permanently. See [Troubleshooting](../../troubleshooting.md#symptom-items-stay-pending-and-flush-returns-00).
:::

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
import { useSyncConfig } from '@gabriel-sisjr/react-native-sync-provider';
import { Button } from 'react-native';
import { useSession } from './session'; // your app's auth state

function BatchSizeControl() {
  const { config, setConfig } = useSyncConfig();
  const session = useSession();

  const setBatch = async (n: number) => {
    if (!config) return;
    // `config` can lack the stored headers: pass them from the app's session.
    await setConfig({ ...config, batchSize: n, defaultHeaders: session.headers });
  };

  return <Button title="Use small batches" onPress={() => setBatch(5)} />;
}
```

---

## See also

- [`configureSync`](../functions.md#configuresync), [`getSyncConfig`](../functions.md#getsyncconfig) -- direct facade calls.
- [`SyncProvider` context](../context.md) -- when to mount a provider.
- [Retry policy guide](../../guides/retry-policy.md).
