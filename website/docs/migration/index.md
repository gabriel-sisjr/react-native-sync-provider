---
sidebar_position: 1
title: Migration Guides
description: Upgrade guides for breaking and behavior changes between versions of @gabriel-sisjr/react-native-sync-provider, including the v0.2 to v0.3 and v0.1 to v0.2 upgrades.
keywords:
  - migration
  - upgrade
  - breaking changes
  - versioning
  - semver
---

# Migration Guides

The library is pre-1.0, so minor versions can carry breaking changes. Each upgrade has its own guide, listed here newest first.

> **Note:** The canonical list of breaking and behavior changes lives in [`BREAKING_CHANGES.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/BREAKING_CHANGES.md). The guides below turn it into upgrade procedures.

## v0.2 → v0.3 {#v0-2-to-v0-3}

No function, hook or error code was removed, and the quality gate and the health check are opt-in. Four things change for every app: `react-native-nitro-modules` must be 0.35.8 or later, permanently failed items move to a dead-letter queue, `networkQuality` is a required field, and connection events fire only on real changes. The stores migrate automatically, and there is no supported downgrade: see [Rolling back](./v0-3-0.md#rolling-back-from-v03).

Follow the [v0.3.0 migration guide](./v0-3-0.md) for the steps.

## v0.1 → v0.2 {#v0-1-to-v0-2}

No function, hook or error code was removed, but the upgrade needs native changes on iOS. Delete any `BGTaskScheduler.shared.register` call for a library identifier first: the library now registers its handlers at process start, and a second registration is expected to crash the app at launch. The iOS minimum is 15.0, and retries, configuration and pauses are now persisted.

Follow the [v0.2.0 migration guide](./v0-2-0.md) for the steps.

## Versioning Policy

The library follows [Semantic Versioning 2.0.0](https://semver.org/):

- **Major** (`x.0.0`) — breaking changes to the public TypeScript API, native HybridObject contract, or behavior consumers depend on.
- **Minor** (`0.x.0`) — new features, additive type fields, new error codes, new hook return fields. Pre-1.0, minor bumps may also carry deliberate breaking changes, so read the changelog before upgrading.
- **Patch** (`0.0.x`) — bug fixes only. No new types, no new functions.

## How to Find the Right Guide for Your Upgrade

Pick the guide matching the version you are upgrading **to**. When you skip a version, apply each guide in turn, oldest first. Track [`BREAKING_CHANGES.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/BREAKING_CHANGES.md) on the `develop` branch to see proposed changes before they ship.

## Need Help?

If you hit a migration issue not covered by the guides:

- File an issue at [github.com/gabriel-sisjr/react-native-sync-provider/issues](https://github.com/gabriel-sisjr/react-native-sync-provider/issues) with the **Bug Report** template
- Include the before/after of the failing code and the exact behavior observed
- Put `migration` and the version you are upgrading to in the title

Questions that are not bugs go to [GitHub Discussions](https://github.com/gabriel-sisjr/react-native-sync-provider/discussions).
