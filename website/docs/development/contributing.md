---
sidebar_position: 1
title: Contributing
description: How to set up the dev environment, add or change a Nitro method, follow the commit/lint/test conventions, and send a pull request.
keywords:
  - contributing
  - development
  - nitrogen
  - workflow
  - commit convention
  - pull request
---

# Contributing

Thanks for your interest in `@gabriel-sisjr/react-native-sync-provider`. This page captures the full developer workflow — toolchain, commands, conventions, and the Nitro spec lifecycle.

The repo's [`CODE_OF_CONDUCT.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/CODE_OF_CONDUCT.md) is in force in every interaction.

## Toolchain

| Tool          | Version          | How to install                              |
| ------------- | ---------------- | ------------------------------------------- |
| Node.js       | `v24.13.0` (`.nvmrc`) | `nvm install`                          |
| Yarn          | `4.11.0` (Berry)  | Pinned by `packageManager` in `package.json` |
| Xcode         | 26+              | App Store                                   |
| CocoaPods     | latest            | `bundle install` (uses the repo's Gemfile)  |
| JDK           | 17 (Zulu)         | sdkman / brew                                |
| Android Studio | latest stable    | Optional — for emulator and SDK manager     |

## Install

```bash
yarn
```

Always Yarn — never npm. The project relies on Yarn workspaces and immutable installs in CI.

## The Nitro workflow (CRITICAL)

This is the single most important convention in the repo. Read it twice.

The `nitrogen/` directory is **gitignored**. The example app and the iOS/Android native targets refer to generated headers and base classes that live there. Without them the build fails with cryptic missing-symbol errors.

```bash
yarn nitrogen
```

You MUST run `yarn nitrogen` after every edit to `src/SyncProvider.nitro.ts` — and after a fresh clone before anything else builds. The Nitro spec is the single source of truth for the JS↔native contract; both `ios/SyncProvider.swift` and `android/src/main/java/com/margelo/nitro/syncprovider/SyncProvider.kt` must implement the regenerated `HybridSyncProviderSpec`.

### Adding or changing a Nitro method

1. Edit the interface in `src/SyncProvider.nitro.ts`.
2. Run `yarn nitrogen` (regenerates the spec base classes — old method signatures will fail to compile).
3. Implement the new method in **both** `ios/SyncProvider.swift` and `android/src/main/java/com/margelo/nitro/syncprovider/SyncProvider.kt`.
4. Surface it in the JS facade: re-export from `src/index.tsx`. If the method is JSI-incompatible by itself (e.g. needs platform-specific shims), add a wrapper in `src/<name>.native.tsx` and a throwing fallback in `src/<name>.tsx`.
5. Run `yarn typecheck && yarn lint && yarn test`.
6. Rebuild the example app (`yarn example android` / `ios`) — JS-only changes hot-reload, native changes require a rebuild.

## Common commands

```bash
yarn                           # install (uses workspaces)
yarn nitrogen                  # REQUIRED: regenerate native bindings from *.nitro.ts
yarn prepare                   # full library build via bob → lib/
yarn typecheck                 # tsc (no emit)
yarn lint                      # eslint **/*.{js,ts,tsx}
yarn lint --fix                # auto-fix formatting
yarn test                      # jest
yarn test path/to/file         # run a single test file
yarn test -t "name"            # run a single test by name
yarn clean                     # remove android/build, example builds, lib/

yarn example start             # Metro for the example app
yarn example android           # run example on Android
yarn example ios               # run example on iOS
yarn example build:android     # CI-style Android build
yarn example build:ios         # CI-style iOS build

yarn docs:dev                  # Docusaurus local dev server
yarn docs:build                # Build the static docs site
yarn docs:serve                # Serve the built site
yarn docs:clear                # Clear Docusaurus cache
```

For iOS, after a clean checkout or any `*.nitro.ts` change:

```bash
cd example && bundle install && bundle exec pod install --project-directory=ios
```

## Linting, types, tests

The pre-commit hook enforces all three. Don't bypass it with `--no-verify` — fix the underlying issue.

- **TypeScript**: `yarn typecheck`. Strict mode + `verbatimModuleSyntax: true` + `noUncheckedIndexedAccess: true` are non-negotiable. The tsconfig uses `customConditions: ["react-native-strict-api"]` — symbols missing from RN's strict API surface fail typecheck even if they exist at runtime.
- **ESLint**: `yarn lint`. Config is flat (ESLint 9). Prettier runs through the eslint-plugin-prettier rule.
- **Jest**: `yarn test`. Preset is `react-native`. Tests live under `src/__tests__/`. Do NOT put tests under `lib/` or `example/node_modules/` — they're ignored.

## Commit convention

Commits MUST follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

Allowed types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `style`, `ci`, `build`.

The `commit-msg` lefthook hook runs `commitlint` against `@commitlint/config-conventional`. Bad messages are rejected.

Examples:

```
feat(android): add WorkManager backoff override
fix(ios): clear in-flight items on RecoveryManager launch
docs(api): document SyncErrorCode.MAX_ATTEMPTS_EXCEEDED retryability
test(facade): cover sentinel-translation in getLastSyncResult
```

## Lefthook hooks

`lefthook.yml` runs on every commit:

- **`pre-commit`** (parallel) — `npx eslint {staged_files}` + `npx tsc`.
- **`commit-msg`** — `npx commitlint --edit`.

Skipping these masks real bugs. If a hook fails, fix the cause and re-stage.

## Sending a pull request

- Keep PRs small and focused. One concern per PR.
- Make sure CI is green (lint, typecheck, test, build-library, build-android, build-ios, test-android, test-ios).
- Update documentation in the same PR. If you changed a public symbol, the API reference page must change too.
- For UI / behavioral changes, include before/after screenshots or a short clip.
- For native changes, mention the platforms touched in the PR title (`feat(android): ...` / `feat(ios): ...` / `feat(native): ...`).
- Cross-platform feature changes go behind a single PR — the spec change, the iOS impl, the Android impl, the JS facade, and the docs all together.

## Releases

Phase 8 of the roadmap (Release flow) wires up `release-it` + automated publishes. Until then, releases are manual: bump `package.json`, tag, and `npm publish`. Watch the changelog discipline — every release entry mirrors the Conventional Commits log.
