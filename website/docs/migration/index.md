---
sidebar_position: 1
title: Migration Guides
description: Index of upgrade guides for breaking changes between versions of @gabriel-sisjr/react-native-sync-provider.
keywords:
  - migration
  - upgrade
  - breaking changes
  - versioning
  - semver
---

# Migration Guides

No breaking changes have shipped yet — the library is pre-1.0. Every public symbol added in v0.x may evolve up to and including the v1.0 release, at which point this page becomes the home of every per-version upgrade guide.

:::note
The canonical, machine-readable list of breaking changes lives in [`BREAKING_CHANGES.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/BREAKING_CHANGES.md). This page mirrors that file with longer-form prose, code samples, and codemods.
:::

## Versioning policy

The library follows [Semantic Versioning 2.0.0](https://semver.org/):

- **Major** (`x.0.0`) — breaking changes to the public TypeScript API, native HybridObject contract, or behavior consumers depend on.
- **Minor** (`0.x.0`) — new features, additive type fields, new error codes, new hook return fields. Always backwards-compatible.
- **Patch** (`0.0.x`) — bug fixes only. No new types, no new functions.

Pre-1.0, minor bumps may carry deliberate breaking changes — read the changelog before upgrading.

## Format of future entries

When the first breaking change ships, a per-version page lands here following the same template the sibling library uses. Each entry includes:

- **TL;DR** — one-paragraph summary of what changed.
- **Codemods** — `sed` / `node` snippets that automate the find-and-replace work where possible.
- **Step-by-Step Migration** — before/after code blocks for every affected symbol.
- **Why** — the rationale (consistency, tree-shaking, native-side requirement, etc.) so consumers can evaluate whether to upgrade now or pin.
- **Common Pitfalls** — things to watch for after the codemod (IDE caches, stale lockfiles, regenerated Nitro bindings).
- **Files affected** — table of every symbol or file touched, so reviewers can spot incomplete migrations in a PR diff.

## How to find the right page for your upgrade

Once entries land, they are listed below in chronological order (newest first). Pick the page matching the version you are upgrading **to** — it covers the full set of breaking changes from any earlier version up to that one.

For now: there is nothing to migrate. Track [`BREAKING_CHANGES.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/BREAKING_CHANGES.md) on the `develop` branch to see proposed changes before they ship.
