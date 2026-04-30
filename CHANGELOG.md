# Changelog

All notable changes to **`@gabriel-sisjr/react-native-sync-provider`** are documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> **Pre-1.0 notice:** while the library is on `0.x`, minor version bumps (`0.x.0`) may include breaking changes. Every breaking change is summarized in [`BREAKING_CHANGES.md`](./BREAKING_CHANGES.md) and ships with a dedicated migration guide under `website/docs/migration/`.

## [Unreleased]

### Added

- **Public Nitro spec frozen (Phase 1).** Rewrote `src/SyncProvider.nitro.ts` as the v0.1 source-of-truth bridge contract, exposing the full surface for queue ops (`enqueue`, `enqueueBatch`, `removeItem`, `clearQueue`, `getQueueSize`, `getPendingItems`), sync ops (`flush`, `pauseSync`, `resumeSync`, `isSyncing`), config (`configureSync`, `getSyncConfig`), history (`getLastSyncResult`, `getSyncHistory`, `clearSyncHistory`), connectivity (`getConnectionStatus`), background sync (`enableBackgroundSync`, `disableBackgroundSync`, `isBackgroundSyncEnabled`), and events (`addListener`, `removeListener`). Every method is documented with `@param`, `@returns`, `@throws`, and `@example` blocks.
- **Public type system under `src/types/`.** New modules `enums.ts`, `sync.ts`, `connection.ts`, `hooks.ts`, plus the `index.ts` barrel. All enum-like values ship as `as const` objects with companion types in `SCREAMING_SNAKE_CASE` (`ConnectionStatus`, `ConnectionType`, `SyncStrategy`, `SyncPriority`, `BackoffStrategy`, `HttpMethod`, `SyncEventType` — 15 event types). Public interfaces: `SyncItemInput`, `SyncItem`, `RetryPolicy`, `SyncOptions`, `SyncResult`, `SyncEvent`, `BackgroundSyncOptions`, `ConnectionState`. Hook return-shape contracts frozen ahead of Phase 2: `UseConnectionResult`, `UseSyncQueueResult`, `UseSyncStatusResult`, `UseOfflineQueueResult`, `UseSyncEventsOptions`, `UseSyncConfigResult`, `UseAutoSyncOptions`.
- **`SyncError` / `SyncErrorCode` surface under `src/errors/`.** New `class SyncError extends Error` carries a stable `code: SyncErrorCode` plus an optional ES2022 `cause`, with prototype chain restored for cross-bridge `instanceof` checks. `SyncErrorCode` is an 11-member `as const` enum: `NETWORK_ERROR`, `SERVER_ERROR`, `INVALID_PAYLOAD`, `QUEUE_FULL`, `NATIVE_MODULE_UNAVAILABLE`, `BACKGROUND_TASK_REGISTRATION_FAILED`, `INVALID_URL`, `UNAUTHORIZED`, `TIMEOUT`, `MAX_ATTEMPTS_EXCEEDED`, `DUPLICATE_ITEM` — each with TSDoc explaining when it is thrown.
- **Native-assigned ULIDs.** `SyncItemInput` intentionally omits `id` and `createdAt`; the native layer assigns both. `enqueue` returns `Promise<string>` (the new ULID); `enqueueBatch` returns `Promise<string[]>` parallel to the input.
- **Listener channel contract.** `addListener(event, callback)` and `removeListener(event, subscriptionId)` accept `event: string`. The only valid channel for v0.1 is `"sync-event"`; native MUST validate and throw `INVALID_PAYLOAD` on unknown channels. The relaxation from a string-literal type is required because Nitrogen rejects single-literal string params.
- **Public sync facade (19 functions) — Phase 2.** `src/index.tsx` now exposes the full public API, each function fronted by `assertNativeModuleAvailable()` and wrapping unknown native rejections into `SyncError` (preserving `cause`; existing `SyncError` instances pass through unchanged). Functions: `enqueue`, `enqueueBatch`, `removeItem`, `clearQueue`, `getQueueSize`, `getPendingItems`, `flush`, `pauseSync`, `resumeSync`, `isSyncing`, `configureSync`, `getSyncConfig`, `getLastSyncResult` (translates the native sentinel `startedAt === 0 && finishedAt === 0` back to `undefined`), `getSyncHistory(limit?)` (passes `0` to native when omitted), `clearSyncHistory`, `getConnectionStatus`, `enableBackgroundSync`, `disableBackgroundSync`, `isBackgroundSyncEnabled`.
- **Listener wrappers — Phase 2.** `addSyncEventListener(callback): Promise<string>` and `removeSyncEventListener(subscriptionId: string): Promise<void>` wrap the underlying `addListener` / `removeListener` Nitro calls and pin the channel to the new exported constant `SYNC_EVENT_CHANNEL = 'sync-event'`. The `Sync` prefix avoids collision with `EventEmitter.addListener`.
- **Public hooks (7) — Phase 2.** `useConnection`, `useSyncQueue`, `useSyncStatus`, `useOfflineQueue`, `useSyncEvents`, `useSyncConfig`, `useAutoSync`. `useAutoSync` is inert by default and only flushes when `enabled: true`; default poll interval is `60_000` ms; foreground-triggered flush is opt-in via `flushOnAppForeground`. `useAutoSync` consumes a JS-only derived type `UseAutoSyncExtraOptions` (extends the frozen `UseAutoSyncOptions` from Phase 1 with `intervalMs` and `flushOnAppForeground`) — additive, non-breaking.
- **`SyncProvider` Context — Phase 2.** New `src/contexts/SyncProvider.tsx` exports `SyncProvider`, `SyncProviderContext`, and `SyncProviderContextValue`. Context value is a `{ config: SyncOptions | undefined }` snapshot; hooks continue to talk directly to the native singleton (the provider is opt-in, only centralizes config).
- **`src/utils/` — Phase 2 (8 helper modules + barrel).** `isNativeModuleAvailable.ts`, `assertNativeModuleAvailable.ts`, `validateSyncItem.ts`, `serializeSyncItem.ts`, `deserializeSyncItem.ts`, `retryBackoff.ts`, `idGenerator.ts` (uuid v7), `errors.ts`, `index.ts`.
- **Helper re-exports.** `generateId` (uuid v7, timestamp-prefixed for natural ordering) and `isNativeModuleAvailable` are re-exported from `src/index.tsx` for consumer ergonomics.
- **Web throwing stub — Phase 2.** New `src/index.web.tsx` ships now (was tentatively v1.1) so web/SSR bundles remain importable without breaking build: the platform-extension entry throws on every facade call and hooks return inert state. No web support is promised in v0.1.
- **Runtime dep `uuid@^10`** added to `dependencies` (and `@types/uuid@^10` to `devDependencies`) to back `generateId`. `react-native-mmkv` was NOT introduced in this phase — persistence remains a native concern (Core Data / Room) for Phases 3 / 4.
- **`tsconfig.build.json`** now `exclude`s `nitrogen/` so generated bindings never leak into `lib/typescript/` declarations (`dx-optimizer` patch).
- **`package.json` `exports[".":]`** gained `react-native` and `browser` conditions so the new `src/index.web.tsx` stub resolves correctly on web/SSR while RN bundlers continue to pick the native entry (`dx-optimizer` patch).

### Changed

- **`src/index.tsx` is now the real public facade.** Replaces the temporary `multiply` placeholder re-export with the full Phase 2 surface (19 functions + 2 listener wrappers + `SYNC_EVENT_CHANNEL` + 7 hooks + `SyncProvider` Context + `SyncError` / `SyncErrorCode` re-exports + `generateId` / `isNativeModuleAvailable` re-exports + every public type / enum from `src/types/`).
- **`example/src/App.tsx`** received a minimal patch swapping the deleted `multiply` import for `isNativeModuleAvailable()` so the workspace continues to typecheck. Phase 9 owns the full example-app rewrite.

### Deferred

- **`RetryPolicy.shouldRetry(error)` callback** — postponed to v0.2. v0.1 ships the declarative-only `RetryPolicy` (`maxAttempts`, `backoff`, `baseDelayMs`, `maxDelayMs`, `jitter`, `retryOnStatusCodes`) so background dispatch does not require a JS-alive bridge round-trip per retry.

### Notes (Nitro codegen workarounds)

- `getLastSyncResult()` returns `Promise<SyncResult>` with a sentinel (`startedAt === 0 && finishedAt === 0`) instead of `Promise<SyncResult | undefined>`; the Phase 2 JS facade now translates the sentinel back to `undefined` for consumer code.
- `getSyncHistory(limit: number)` is a required parameter at the Nitro layer (not optional) — `0` means "no limit" on the native side. The Phase 2 JS facade exposes a true optional argument and passes `0` when omitted.

### Notes (Phase 2 carry-overs)

- **Native layer (iOS / Android) still ships the legacy `multiply` only.** `ios/SyncProvider.swift` and `android/src/main/java/com/margelo/nitro/syncprovider/SyncProvider.kt` will fail to build against the frozen Phase 1 spec base classes. Phases 3 and 4 own the fix; the JS facade is fully exercised against mocks until then.
- **Tests are deferred to Phase 5.** No Jest suites were added in Phase 2; the facade is verified via `yarn typecheck`, `yarn lint`, and `yarn prepare` only.
- **`useAutoSync` types.** The hook accepts a JS-only `UseAutoSyncExtraOptions` extension over the frozen `UseAutoSyncOptions` from Phase 1, adding `intervalMs` and `flushOnAppForeground`. This is additive and non-breaking; downstream consumers reading the Phase 1 type continue to compile.
- **Lint result.** `yarn lint` exits 0 with 23 informational `no-void` warnings — intentional fire-and-forget pattern in hook cleanup paths (subscription tear-down).

### Deprecated

### Removed

- **`src/multiply.tsx`** — placeholder facade fallback removed; `src/index.tsx` is the new entry.
- **`src/multiply.native.tsx`** — placeholder native shim removed; the real facade now imports the Nitro `HybridObject` directly.
- **`src/__tests__/index.test.tsx`** — empty placeholder test removed (along with the now-empty `src/__tests__/` directory). Phase 5 owns the real test suite.

### Fixed

### Security

[Unreleased]: https://github.com/gabriel-sisjr/react-native-sync-provider/compare/HEAD...HEAD
