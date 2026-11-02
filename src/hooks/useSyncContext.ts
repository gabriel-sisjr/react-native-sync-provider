import { useContext } from 'react';

import { SyncProviderContext } from '../contexts/SyncProvider';
import type { SyncProviderContextValue } from '../contexts/SyncProvider';

/**
 * Read the {@link SyncProviderContext} value.
 *
 * A thin convenience wrapper over `useContext(SyncProviderContext)` that gives
 * consumers a typed view of the surrounding {@link SyncProvider} without
 * importing the context object directly.
 *
 * The returned `hasProvider` flag distinguishes the two ways `config` can be
 * `undefined`:
 *
 * - **No Provider mounted** → `{ config: undefined, hasProvider: false }`.
 * - **Provider mounted, config still loading** →
 *   `{ config: undefined, hasProvider: true }` until the initial
 *   `getSyncConfig()` round-trip resolves.
 *
 * Like the rest of the library's hooks, it **never throws** when used outside a
 * `<SyncProvider>` — it simply returns the default context value. Gate on
 * `hasProvider` when you specifically need a Provider to be present.
 *
 * @returns the current {@link SyncProviderContextValue}.
 *
 * @example
 * const { config, hasProvider } = useSyncContext();
 * if (!hasProvider) {
 *   // Rendered outside <SyncProvider> — fall back to direct facade calls.
 * } else if (config === undefined) {
 *   // Provider mounted, initial config still loading.
 * }
 *
 * @public
 */
export function useSyncContext(): SyncProviderContextValue {
  return useContext(SyncProviderContext);
}
