# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Agent Usage

> **THIS AGENT FLOW IS MANDATORY. It is NOT optional. Follow it for EVERY task without exception.**

Always use specialized agents for all work in this repository. The agents available (defined in `.claude/agents/`) are:

| Agent                        | Role                                                                                                                                                                                                                                               |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`prompt-engineer`**        | **ONLY** refines and improves the input prompt. Must NEVER write code, create files, or implement anything. Its sole output is a refined prompt text that subsequent agents will use as input.                                                     |
| **`business-analyst`**       | Requirements analysis, impact assessment, process mapping, and solution/plan design. **Only used in Planning flows.**                                                                                                                              |
| **`ux-researcher`**          | Research, questions, and developer-experience insights to support planning. **NEVER infers or guesses answers — ALWAYS asks the developer directly in the terminal using `AskUserQuestion` with pre-defined selectable options + an open text option.** |
| **`mobile-developer`**       | All TypeScript / React Native (hybrid) work: JS facade, hooks, types, errors, utils, Nitro spec edits, example app integration, JS-side tests.                                                                                                     |
| **`dx-optimizer`**           | Developer experience best practices, build performance, code quality, tooling, monorepo / Turborepo configuration, workflow improvements.                                                                                                          |
| **`kotlin-specialist`**      | Android-specific native implementation. Kotlin 2.0+, Coroutines, Room (KSP), WorkManager, OkHttp, ConnectivityManager, BootCompletedReceiver, Gradle Kotlin DSL.                                                                                   |
| **`swift-expert`**           | iOS-specific native implementation. Swift 5.9+, Core Data, URLSession (incl. background sessions), BGTaskScheduler, NWPathMonitor, Privacy Manifest, podspec, Background Modes capability.                                                        |
| **`devops-engineer`**        | CI/CD pipelines, GitHub Actions workflows, release automation (release-it), Turborepo cache, Docusaurus deploy, infrastructure for the publishing flow.                                                                                            |
| **`git-workflow-manager`**   | Branching strategy, conflict resolution, commit conventions, PR workflows, semantic versioning, changelog management, lefthook / commitlint configuration.                                                                                         |
| **`documentation-engineer`** | Documentation of plans, decisions, architecture, public API (TSDoc), README, CHANGELOG, BREAKING_CHANGES, the Docusaurus website, migration guides.                                                                                                |
| **`Explore`**                | Codebase exploration, searching for patterns, understanding existing code, architectural questions.                                                                                                                                                |

### Planning Tasks Flow

Use this flow when the task involves creating a plan, strategy, or design (technical or non-technical).

```
Step 1: prompt-engineer
  → Refine and improve the input prompt.

Step 2: business-analyst + ux-researcher  (parallel)
  → BA elaborates the plan.
  → UX-Researcher performs research and asks clarifying questions to the developer.
     ⚠️  UX-Researcher must NEVER infer or guess — always ask the programmer directly.
     ⚠️  Questions MUST be asked via AskUserQuestion in the terminal (NOT in documents).
        Format: pre-defined selectable options + "Other (explain)" open text option.

  ┌─────────────────────────────────────────────────┐
  │  Is the plan technical?                         │
  ├────────── YES ──────────┬──────── NO ───────────┤
  │                         │                       │
  │  Step 3:                │  Step 3:              │
  │  mobile-developer       │  documentation-       │
  │  + dx-optimizer         │  engineer             │
  │  (parallel — verify     │  (document the plan)  │
  │   codebase & apply      │                       │
  │   best practices;       │                       │
  │   add kotlin-specialist │                       │
  │   and/or swift-expert   │                       │
  │   if the plan touches   │                       │
  │   native layers)        │                       │
  │                         │                       │
  │  Step 4:                │                       │
  │  documentation-engineer │                       │
  │  (document the plan)    │                       │
  └─────────────────────────┴───────────────────────┘
```

### Execution Tasks Flow

Use this flow when the task involves implementing, fixing, or building something.

```
Step 1: prompt-engineer
  → Refine and improve the input prompt.

Step 2: ux-researcher
  → Research and ask clarifying questions if needed.
     ⚠️  NEVER infer or guess — always ask the programmer directly.
     ⚠️  Questions MUST be asked via AskUserQuestion in the terminal (NOT in documents).
        Format: pre-defined selectable options + "Other (explain)" open text option.

Step 3: Implementation (parallel with dx-optimizer)
  → Choose agent(s) by technology:
     • TypeScript / React Native (JS facade, hooks, types, errors, utils, spec edits) → mobile-developer + dx-optimizer
     • Kotlin / Android native                                                         → kotlin-specialist + dx-optimizer
     • Swift / iOS native                                                              → swift-expert + dx-optimizer
     • Git / branching / release plumbing                                              → git-workflow-manager
     • CI / GitHub Actions / release automation                                        → devops-engineer
  → If the task spans multiple technologies, run the relevant agents in parallel.
     e.g., spec change + Kotlin + Swift → mobile-developer + kotlin-specialist + swift-expert + dx-optimizer (all parallel)

Step 4: documentation-engineer
  → Document changes and decisions (API reference, TSDoc, README, CHANGELOG, BREAKING_CHANGES, migration guides as applicable).
```

### Rules

- **Always start with `prompt-engineer`** — no exceptions.
- **`prompt-engineer` must ONLY refine the prompt.** It must NEVER write code, create/edit files, or perform implementation work. Its sole job is to return a clearer, more focused prompt so that subsequent specialist agents have better direction. When launching it, explicitly instruct: "Return ONLY the refined prompt text. Do NOT read, write, or modify any files beyond what is needed to understand the prompt context."
- **Run agents in parallel** when they are in the same step (e.g., `business-analyst` + `ux-researcher`, `kotlin-specialist` + `swift-expert` + `dx-optimizer`).
- **`ux-researcher` must NEVER infer or guess answers.** When uncertain, it MUST ask the developer directly **in the terminal using `AskUserQuestion`** — never in documents or files. Questions must include pre-defined selectable options for the developer to choose with arrow keys, plus an "Other (explain in your own words)" open text option as the last choice.
- **`business-analyst` is only used in Planning flows**, never in Execution flows.
- **`mobile-developer` handles only TypeScript / React Native code (the JS facade, hooks, types, errors, utils, the Nitro spec, and the example app).** It must NOT implement Kotlin or Swift native code.
- **`kotlin-specialist` is the sole agent for Kotlin / Android native work.** Never delegate Android native code to `mobile-developer`.
- **`swift-expert` is the sole agent for Swift / iOS native work.** Never delegate iOS native code to `mobile-developer`.
- **Spec changes (`src/SyncProvider.nitro.ts`) are coordinated events.** When the spec changes, run `yarn nitrogen` (via the Bash tool or via the implementation agents) and then update both native specialists in parallel.
- **`dx-optimizer` always runs in parallel** with whichever implementation agent(s) are active in Step 3.
- **Never skip agents or steps.** The full pipeline must be followed for every task.
- **Never perform work inline** — always delegate to the appropriate agent.

### UX-Researcher Question Protocol

The `ux-researcher` agent must **always** use the `AskUserQuestion` tool to ask questions directly in the terminal. It must **never** write questions in documents, files, or inline text.

Every question must follow this format:

1. A clear, concise question.
2. Pre-defined selectable options (the developer picks with arrow keys).
3. A final option: **"Other (explain in your own words)"** — for free-text input when none of the options fit.

Example prompt the `ux-researcher` must use when launched:

> "When you need to ask the developer a question, use the `AskUserQuestion` tool. Provide a clear question and a list of suggested options. Always include 'Other (explain in your own words)' as the last option. NEVER write questions in documents or files — the terminal is the only valid channel."

## Project

`react-native-sync-provider` — a React Native library exposing native sync functionality through [Nitro Modules](https://nitro.margelo.com/). Scaffolded with `create-react-native-library` as a `nitro-module` (Kotlin + Swift, TypeScript JS layer). Currently exports a single `multiply(a, b)` placeholder method as the working bridge skeleton.

## Toolchain & Hard Requirements

- **Node**: pinned via `.nvmrc` to **v24.13.0**. The example workspace declares `engines.node >= 22.11.0`.
- **Package manager**: **Yarn 4.11.0** (Berry, declared in `packageManager`). Do **not** use npm — the project relies on Yarn workspaces and immutable installs in CI (`yarn install --immutable`).
- **Workspaces**: root library + `example/` (the test/demo app, name `react-native-sync-provider-example`).
- **TypeScript**: 6.0.2, strict, `verbatimModuleSyntax: true`, `noUncheckedIndexedAccess: true`, target ESNext, module ESNext, `customConditions: ["react-native-strict-api"]`. Path alias `react-native-sync-provider` → `./src/index` so source resolves like a published package.

## Common Commands (from repo root)

```sh
yarn                       # install (uses workspaces)
yarn nitrogen              # REQUIRED: regenerate native bindings from *.nitro.ts
yarn prepare               # full library build via bob → lib/ (runs nitrogen + module + typescript)
yarn typecheck             # tsc (no emit)
yarn lint                  # eslint **/*.{js,ts,tsx}
yarn lint --fix            # auto-fix formatting (Prettier via eslint)
yarn test                  # jest (preset: @react-native/jest-preset)
yarn test path/to/file     # run a single test file
yarn test -t "name"        # run a single test by name pattern
yarn clean                 # remove android/build, example builds, lib/

yarn example start         # Metro for the example app
yarn example android       # run example on Android
yarn example ios           # run example on iOS
yarn example build:android # CI-style Android build (used by turbo)
yarn example build:ios     # CI-style iOS build (used by turbo)
```

iOS-specific (run from `example/` after `yarn` and `yarn nitrogen`):

```sh
cd example && bundle install && bundle exec pod install --project-directory=ios
```

### Critical: run `yarn nitrogen` first

The `nitrogen/` directory is **not committed** (it's listed in `bob`'s `clean` for the custom target). The example app **will not build** without it. Re-run `yarn nitrogen` whenever you edit any `*.nitro.ts` file.

## Architecture

This is a **Nitro Module**, which means the JS↔native bridge is generated, not hand-written. Three layers cooperate:

### 1. Spec (TypeScript) — `src/SyncProvider.nitro.ts`

Declares a `HybridObject<{ ios: 'swift'; android: 'kotlin' }>` interface. This is the source of truth for the bridge contract. Method signatures here drive code generation in step 2.

### 2. Generated bindings — `nitrogen/` (gitignored)

`yarn nitrogen` reads `*.nitro.ts` + `nitro.json` and produces:
- A C++ spec header per platform (e.g., `HybridSyncProviderSpec`) included via the autolinking files.
- `nitrogen/generated/ios/SyncProvider+autolinking.rb` — pulled into `SyncProvider.podspec`.
- `nitrogen/generated/android/syncprovider+autolinking.gradle` and `.cmake` — applied from `android/build.gradle` and `android/CMakeLists.txt`.
- `syncproviderOnLoad.hpp` referenced by `android/src/main/cpp/cpp-adapter.cpp` (`registerAllNatives()` on JNI load).

`nitro.json` configures namespaces and which native classes implement the spec:

```json
{
  "cxxNamespace": ["syncprovider"],
  "android": { "androidNamespace": ["syncprovider"], "androidCxxLibName": "syncprovider" },
  "autolinking": {
    "SyncProvider": {
      "ios":     { "language": "swift",  "implementationClassName": "SyncProvider" },
      "android": { "language": "kotlin", "implementationClassName": "SyncProvider" }
    }
  }
}
```

### 3. Native implementations

- **iOS**: `ios/SyncProvider.swift` — `class SyncProvider: HybridSyncProviderSpec`. The base class is generated.
- **Android**: `android/src/main/java/com/margelo/nitro/syncprovider/SyncProvider.kt` — `class SyncProvider : HybridSyncProviderSpec()`, marked `@DoNotStrip`. `SyncProviderPackage.kt` does `System.loadLibrary("syncprovider")`. Android namespace: `com.margelo.nitro.syncprovider`.
- **C++ adapter**: `android/src/main/cpp/cpp-adapter.cpp` provides `JNI_OnLoad` and calls `registerAllNatives()`.

### 4. JS facade — `src/`

- `src/index.tsx` re-exports from `./multiply`.
- `src/multiply.native.tsx` — picked by Metro on iOS/Android. Creates the Nitro hybrid: `NitroModules.createHybridObject<SyncProvider>('SyncProvider')` and calls into it.
- `src/multiply.tsx` — the non-native fallback that **throws** (keeps web/SSR bundles importable but unusable). This `.native.tsx` vs `.tsx` split is the standard React Native platform-extension mechanism — preserve it when adding new methods.

### Build pipeline (`react-native-builder-bob`)

`yarn prepare` runs three bob targets defined in `package.json`:
1. `custom` → `yarn nitrogen` (with `clean: nitrogen/`)
2. `module` (`esm: true`) → `lib/module/`
3. `typescript` (using `tsconfig.build.json`, which `exclude`s `example/` and `lib/`) → `lib/typescript/`

Published `exports` map: `source` → `src/index.tsx`, `types` → `lib/typescript/src/index.d.ts`, `default` → `lib/module/index.js`.

### Example app

`example/` is a normal RN 0.85 app on the new architecture (Fabric + concurrent root). It consumes the library through `react-native-monorepo-config` (`metro.config.js` adds the repo root) and `react-native.config.js` (registers the local library by name with both platforms). To verify the new arch is active, look in Metro logs for `"fabric":true,"concurrentRoot":true`.

## Adding / changing a Nitro method

1. Edit the interface in `src/SyncProvider.nitro.ts`.
2. Run `yarn nitrogen` (regenerates the spec base classes — old method signatures will fail to compile).
3. Implement the new method in **both** `ios/SyncProvider.swift` and `android/src/main/java/com/margelo/nitro/syncprovider/SyncProvider.kt`.
4. Surface it in the JS layer: add a wrapper in `src/<name>.native.tsx` (calls `SyncProviderHybridObject.<method>`) and a throwing fallback in `src/<name>.tsx`. Re-export from `src/index.tsx`.
5. `yarn typecheck && yarn lint && yarn test`. For native changes, rebuild the example app (`yarn example android` / `ios`) — JS-only changes hot-reload.

## Hooks & commit conventions

- **lefthook** (`lefthook.yml`) runs on every commit:
  - `pre-commit`: `npx eslint {staged_files}` + `npx tsc` (parallel).
  - `commit-msg`: `npx commitlint --edit` against `@commitlint/config-conventional`.
- Commits **must** be conventional: `fix`, `feat`, `refactor`, `docs`, `test`, `chore` (also `perf`, `style`).
- Don't bypass hooks with `--no-verify`; fix the underlying lint/type issue instead.

## CI (`.github/workflows/ci.yml`)

Five parallel jobs run on push/PR/merge_group to `main`:
- **lint** — `yarn lint && yarn typecheck`
- **test** — `yarn test --maxWorkers=2 --coverage`
- **build-library** — `yarn prepare`
- **build-android** — `yarn nitrogen` then `yarn turbo run build:android` (cached via `.turbo/android`, JDK 17 / Zulu)
- **build-ios** — `yarn nitrogen` then `yarn turbo run build:ios` (Xcode 26, env `RCT_USE_RN_DEP=1`, `RCT_USE_PREBUILT_RNCORE=1`)

Turbo task inputs (see `turbo.json`) intentionally exclude `*/build` and `example/ios/Pods` so cache keys are stable across local builds. Match these env vars locally if reproducing CI builds.

## Gotchas

- The library name in `package.json` is `react-native-sync-provider`; the iOS pod / module name is **`SyncProvider`** (see `nitro.json` → `iosModuleName`, `SyncProvider.podspec`). Don't confuse the two when writing native code.
- Android `minSdkVersion` is **24**, `compileSdkVersion`/`targetSdkVersion` **36**, Kotlin **2.0.21**, AGP **8.7.2**.
- The `customConditions: ["react-native-strict-api"]` in `tsconfig.json` means stricter RN type resolution — symbols missing from RN's strict API surface will fail to typecheck even if they exist at runtime.
- `noUncheckedIndexedAccess` is on: array/object index accesses are typed as `T | undefined`. Write defensive code or narrow before use.
- Jest ignores `<rootDir>/example/node_modules` and `<rootDir>/lib/`. Don't put tests in those paths.
