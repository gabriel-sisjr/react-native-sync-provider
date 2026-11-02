---
sidebar_position: 9
title: useSyncContext
description: Typed convenience hook for reading the SyncProvider context — config plus a hasProvider flag that distinguishes "no provider" from "provider mounted, config loading".
keywords:
  - react-native
  - sync-provider
  - hook
  - useSyncContext
  - context
  - SyncProvider
  - hasProvider
---

# `useSyncContext`

Typed convenience wrapper over `useContext(SyncProviderContext)`. It gives consumers a one-line, typed view of the surrounding [`<SyncProvider>`](../context.md) without importing the context object directly.

```ts
import { useSyncContext } from '@gabriel-sisjr/react-native-sync-provider';
```

**Platform:** Android and iOS (inert on web).

**Added in:** v0.2.

---

## Signature

```ts
function useSyncContext(): SyncProviderContextValue;
```

Takes no arguments and returns the current [`SyncProviderContextValue`](../context.md#context-value).

---

## Return Value

| Property      | Type                                                    | Description                                                                                                                                      |
| ------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `config`      | [`SyncOptions`](../types.md#syncoptions) `\| undefined` | The native config as of the Provider's last read. `undefined` while loading **and** when no Provider is mounted.                                 |
| `hasProvider` | `boolean`                                               | `true` only when a `<SyncProvider>` is mounted above the consumer; `false` for the default (no-Provider) context value.                          |

### What it owns vs. mirrors

The hook owns **no** state of its own — it is a pure `useContext` read. All state (`config`, `hasProvider`) is owned by the nearest [`<SyncProvider>`](../context.md), or is the static default value when none is mounted.

### Re-render triggers

Re-renders whenever the `<SyncProvider>` above it publishes a new context value — i.e. when its `config` changes identity: after a read on mount, on an `options` or `backgroundSync` prop change, or on a `RESUMED` event. A `configureSync` or `setConfig` call made elsewhere does not trigger one. With no Provider mounted, the value is a stable constant and never causes a re-render.

### Subscription cleanup

None to manage. `useContext` carries no subscription, so there is nothing to tear down on unmount.

---

## `hasProvider`: telling the two `undefined` cases apart

`config` can be `undefined` for two very different reasons. `hasProvider` disambiguates them:

| `hasProvider` | `config`      | Meaning                                                                                   |
| ------------- | ------------- | ----------------------------------------------------------------------------------------- |
| `false`       | `undefined`   | No `<SyncProvider>` is mounted above this component.                                      |
| `true`        | `undefined`   | A Provider is mounted, but its initial `getSyncConfig()` round-trip has not resolved yet. |
| `true`        | `SyncOptions` | A Provider is mounted and config has loaded.                                              |

```tsx
import { useSyncContext } from '@gabriel-sisjr/react-native-sync-provider';

function ConfigBadge() {
  const { config, hasProvider } = useSyncContext();

  if (!hasProvider) {
    // Rendered outside <SyncProvider> — fall back to direct facade calls
    // (e.g. getSyncConfig()) or render nothing.
    return null;
  }

  if (config === undefined) {
    return <Text>Loading sync config…</Text>;
  }

  return <Text>Strategy: {config.strategy}</Text>;
}
```

---

## Behavior

- **Never throws outside a Provider.** Like every hook in the library, calling `useSyncContext()` without a surrounding `<SyncProvider>` returns the default value `{ config: undefined, hasProvider: false }` instead of throwing.
- **Read-only.** It exposes no setters; to change config, call [`configureSync`](../functions.md#configuresync) or update the Provider's `options` prop.
- **Web parity.** On web the hook returns the inert context value; with the web `<SyncProvider>` mounted that is `{ config: undefined, hasProvider: true }`.

:::warning Hooks rules apply
Call `useSyncContext()` unconditionally at the top level of your component — never inside a condition, loop, or callback (the [Rules of Hooks](https://react.dev/reference/rules/rules-of-hooks)).
:::

---

## Example

### Gate a Provider-only feature

```tsx
import { useSyncContext } from '@gabriel-sisjr/react-native-sync-provider';
import { Text } from 'react-native';

function SyncSettingsLink() {
  const { hasProvider } = useSyncContext();

  // Only render the settings entry point when a Provider centralizes config.
  if (!hasProvider) return null;

  return <Text>Open sync settings →</Text>;
}
```

---

## See also

- [`SyncProvider` Context](../context.md) -- the Provider and the full `SyncProviderContextValue` shape.
- [`useSyncConfig`](./useSyncConfig.md) -- reads config from this context, with a native-singleton fallback.
- [`configureSync`](../functions.md#configuresync) -- the underlying facade call that updates config.
