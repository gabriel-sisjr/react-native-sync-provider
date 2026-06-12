# Breaking Changes

This document tracks consumer-facing breaking changes in **`@gabriel-sisjr/react-native-sync-provider`** across versions. Each entry summarizes the change and links to the full migration guide; the migration guides under [`website/docs/migration/`](https://github.com/gabriel-sisjr/react-native-sync-provider/tree/main/website/docs/migration) are the authoritative reference.

> **Pre-1.0 notice:** while the library is on `0.x`, minor version bumps (`0.x.0`) may include breaking changes per [SemVer 0.x semantics](https://semver.org/#spec-item-4).

## How to read this file

Every breaking-change entry follows the same shape so consumers can scan the file quickly:

````markdown
## [vX.Y.0] - YYYY-MM-DD - One-line summary

> **Upgrade path:** vX.(Y-1).z --> vX.Y.0
>
> **Full migration guide:** [`docs/migration/vX-Y-0`](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/migration/vX-Y-0)

One-paragraph overview: what changed, why, and the blast radius (compile-time only? silent runtime regression? native code?).

### <Affected symbol or area>

#### Before

```ts
// previous API
```

#### After

```ts
// new API
```

#### Migration steps

1. ...
2. ...

#### Affected symbols

| Symbol | Change |
| ------ | ------ |
| ...    | ...    |
````

Use the table at the bottom of each entry to enumerate every renamed, removed, or reshaped public symbol so consumers can grep for what they import.

## [Unreleased]

### iOS minimum deployment target `13.0` --> `15.0`

> **Category:** platform-requirement change (**not** a JS/TS API break). Pre-1.0.
>
> **Changelog:** see the [`[Unreleased]` entry in `CHANGELOG.md`](./CHANGELOG.md#unreleased).

The iOS minimum deployment target declared in `SyncProvider.podspec` is raised from `13.0` to `15.0`. This is a **platform-requirement change**: the public TypeScript surface (functions, hooks, types, enums, error codes) is unchanged, and no consumer JS code needs to be migrated. The blast radius is **install/compile-time on iOS only** — apps that pin an iOS deployment target below `15.0` must raise it.

**Rationale:** `ios/HTTP/SyncDispatcher.swift` uses the async `URLSession.data(for:)` API, which requires **iOS 15+**. The previous `13.0` floor was latent: the build only passed because the example app overrode `IPHONEOS_DEPLOYMENT_TARGET` to `>= 15.x`, masking the mismatch. Any consumer honoring the declared `13.0` floor would have hit an availability compile error in `SyncDispatcher.swift`. Raising the podspec floor to `15.0` makes the declared requirement match the code. (`BGTaskScheduler`'s iOS 13+ requirement is comfortably covered by the higher floor.)

#### Migration steps

1. In your app's `ios/Podfile`, ensure the platform line is at least `platform :ios, '15.0'`.
2. If you set `IPHONEOS_DEPLOYMENT_TARGET` anywhere (Podfile `post_install`, Xcode build settings), make sure it is `>= 15.0`.
3. Run `cd ios && bundle exec pod install`.

No JS/TS code changes are required.

#### Affected symbols

| Symbol / surface                                              | Change                      |
| ------------------------------------------------------------- | --------------------------- |
| `SyncProvider.podspec` (`s.platforms`, `test_spec.platforms`) | iOS floor `13.0` --> `15.0` |
| Public TypeScript API                                         | none (no JS break)          |
