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

No breaking changes yet -- the library is pre-1.0 and the public API surface is still being shaped. Upcoming breaking changes will be cataloged here before each release and moved into a dated section on publish.
