# Contributing

Contributions are always welcome, no matter how large or small!

We want this community to be friendly and respectful to each other. Please follow it in all your interactions with the project. Before contributing, please read the [code of conduct](./CODE_OF_CONDUCT.md).

The documentation site goes deeper in its development pages: [Contributing](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/development/contributing), [Testing](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/development/testing), [Debugging](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/development/debugging), [CI/CD](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/development/ci-cd) and [Publishing](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/development/publishing).

## Development workflow

This project is a monorepo managed using [Yarn workspaces](https://yarnpkg.com/features/workspaces). It contains the following packages:

- The library package in the root directory.
- An example app in the `example/` directory.
- The documentation site in the `website/` directory.

To get started with the project, make sure you have the correct version of [Node.js](https://nodejs.org/) installed. See the [`.nvmrc`](./.nvmrc) file for the version used in this project.

Run `yarn` in the root directory to install the required dependencies for each package:

```sh
yarn
```

> Since the project relies on Yarn workspaces, you cannot use [`npm`](https://github.com/npm/cli) for development without manually migrating.

This project uses Nitro Modules. If you're not familiar with how Nitro works, make sure to check the [Nitro Modules Docs](https://nitro.margelo.com/).

You need to run [Nitrogen](https://nitro.margelo.com/docs/nitrogen) to generate the boilerplate code required for this project. The example app will not build without this step.

Run **Nitrogen** in following cases:

- When you make changes to any `*.nitro.ts` files.
- When running the project for the first time (since the generated files are not committed to the repository).

To invoke **Nitrogen**, use the following command:

```sh
yarn nitrogen
```

### The Nitro workflow (CRITICAL)

`src/SyncProvider.nitro.ts` is the single source of truth for the JS↔native contract. Both `ios/SyncProvider.swift` and `android/src/main/java/com/margelo/nitro/syncprovider/SyncProvider.kt` implement the regenerated `HybridSyncProviderSpec` base class — old method signatures will fail to compile after the spec changes.

The full lifecycle for adding or changing a Nitro method:

1. Edit the interface in `src/SyncProvider.nitro.ts`.
2. Run `yarn nitrogen` (regenerates the Swift/Kotlin/C++ base classes — old impls break loudly).
3. Implement the new method in **both** `ios/SyncProvider.swift` and `android/src/main/java/com/margelo/nitro/syncprovider/SyncProvider.kt`.
4. Surface it in the JS facade by adding the wrapper directly in `src/index.tsx` (via `nativeOrThrow()`). The facade lives wholly in `src/index.tsx`; the platform split is at the entry level — `src/index.tsx` (native) vs `src/index.web.tsx` (web fallback that throws). When the new function/hook should be unusable on web, add a throwing twin in `src/index.web.tsx`. There is no per-method `.native.tsx` / `.tsx` split.
5. Run `yarn typecheck && yarn lint && yarn test`.
6. Rebuild the example app (`yarn example android` / `ios`) — JS-only changes hot-reload, native changes need a rebuild.

The [example app](./example/) demonstrates usage of the library. You need to run it to test any changes you make.

It is configured to use the local version of the library, so any changes you make to the library's source code will be reflected in the example app. Changes to the library's JavaScript code will be reflected in the example app without a rebuild, but native code changes will require a rebuild of the example app.

If you want to use Android Studio or Xcode to edit the native code, you can open the `example/android` or `example/ios` directories respectively in those editors. To edit the Objective-C or Swift files, open `example/ios/SyncProviderExample.xcworkspace` in Xcode and find the source files at `Pods > Development Pods > SyncProvider`.

To edit the Kotlin files, open `example/android` in Android Studio and find the source files at `gabriel-sisjr_react-native-sync-provider` under `Android`.

You can use various commands from the root directory to work with the project.

To start the packager:

```sh
yarn example start
```

To run the example app on Android:

```sh
yarn example android
```

To run the example app on iOS:

```sh
yarn example ios
```

To confirm that the app is running with the new architecture, you can check the Metro logs for a message like this:

```sh
Running "SyncProviderExample" with {"fabric":true,"initialProps":{"concurrentRoot":true},"rootTag":1}
```

Note the `"fabric":true` and `"concurrentRoot":true` properties.

> **Important:** When making changes that affect both platforms, test on both Android and iOS before submitting a pull request. Platform-specific behavior differences should be documented with `> **iOS:**` / `> **Android:**` callouts in the documentation.

Make sure your code passes TypeScript:

```sh
yarn typecheck
```

To check for linting errors, run the following:

```sh
yarn lint
```

To fix formatting errors, run the following:

```sh
yarn lint --fix
```

Remember to add tests for your change if possible. Run the unit tests by:

```sh
yarn test
```

### Pre-merge checklist

Run the checks that match what your change touched before opening or merging a PR. The JS gate is **always** required; the native gates and regeneration steps are conditional.

| Check                       | Command                                                                                                                                                 | When                                                                                            |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| JS gate (always)            | `yarn typecheck && yarn lint && yarn test`                                                                                                              | Every change, no exceptions.                                                                    |
| Android gate                | `yarn nitrogen` then `yarn turbo run build:android`, plus the Gradle unit tests (`gradlew :gabriel-sisjr_react-native-sync-provider:testDebugUnitTest`) | Android native (`android/**`) **or** the Nitro bridge (`src/SyncProvider.nitro.ts`) is touched. |
| iOS gate                    | `cd example && bundle exec pod install --project-directory=ios` then `xcodebuild test` with scheme `SyncProvider-Unit-Tests`                            | iOS native (`ios/**`) **or** the Nitro bridge is touched.                                       |

And these correctness gates, regardless of platform:

- Re-run `yarn nitrogen` on any change to `src/SyncProvider.nitro.ts` or to any spec-reachable type. Stale generated base classes are a silent source of build drift.
- Every new facade function or hook needs a web twin in `src/index.web.tsx` (a throwing fallback for functions, inert state for hooks). A new export with no web twin breaks the web/SSR bundle.

### Reading a failed CI job

For a failed example iOS build, the job log can leave out the reason, so CI prints the compiler diagnostics from Xcode's activity log as an annotation. It also uploads that log as the **`ios-build-log`** artifact, kept for 14 days, only when the build failed. The artifact is an `.xcactivitylog`: open it in Xcode, or read it as text with `gunzip -c <log> | tr '\r' '\n'`.

The `build-library` job and both release workflows assert the `npm pack` contents with `.github/scripts/assert-pack-contents.mjs`. If it fails, the tarball is missing a Core Data model file, or would ship native tests: check the `files` list in `package.json`.

### Commit message convention

We follow the [conventional commits specification](https://www.conventionalcommits.org/en) for our commit messages:

- `fix`: bug fixes, e.g. fix crash due to deprecated method.
- `feat`: new features, e.g. add new method to the module.
- `refactor`: code refactor, e.g. migrate from class components to hooks.
- `docs`: changes into documentation, e.g. add usage example for the module.
- `test`: adding or updating tests, e.g. add integration tests using detox.
- `chore`: tooling changes, e.g. change CI config.

Our pre-commit hooks verify that your commit message matches this format when committing.

### Linting and tests

[ESLint](https://eslint.org/), [Prettier](https://prettier.io/), [TypeScript](https://www.typescriptlang.org/)

We use [TypeScript](https://www.typescriptlang.org/) for type checking, [ESLint](https://eslint.org/) with [Prettier](https://prettier.io/) for linting and formatting the code, and [Jest](https://jestjs.io/) for testing.

Our pre-commit hooks lint the staged files with ESLint and type-check the project with TypeScript when committing. They do not run the tests: run `yarn test` yourself.

### Releasing

Releases go through pull requests, and CI does the publishing: nothing is published from a developer machine. To cut a release:

1. **Bump PR.** On a branch from `develop`, set `version` in `package.json`, turn `## [Unreleased]` into `## [x.y.z] - YYYY-MM-DD` in `CHANGELOG.md` and into the entry heading from the template at the top of `BREAKING_CHANGES.md`, and regenerate `example/ios/Podfile.lock`, because the podspec reads its version from `package.json`. Open the PR to `develop`.
2. **Release PR.** Open a PR from `develop` to `main` titled `Release - vx.y.z`. A maintainer merges it with a merge commit, so each commit stays individually revertible.
3. **Publish.** The push to `main` runs `publish.yml`. It publishes to npm only when the `package.json` version differs from the `latest` dist-tag (see [The Version Guard](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/development/publishing#the-version-guard)), then creates the `v<version>` tag and the GitHub release. Do not create or push that tag yourself, and do not run `npm publish`: the workflow fails when the tag already exists, and publishing from CI gives every release its provenance.

Pre-releases come only from the manual `prerelease.yml` workflow (**Actions → Pre-release to NPM → Run workflow**), which publishes under the `beta` dist-tag. `yarn release` and `yarn release:beta` are not part of this flow: they run release-it, which commits, tags and pushes straight to the branch and publishes nothing to npm.

`CHANGELOG.md` is curated by hand in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format. Add your entry under `## [Unreleased]` in the PR that makes the change. A breaking or behavior change also needs an entry in [`BREAKING_CHANGES.md`](./BREAKING_CHANGES.md), following the template at its top, and a page under `website/docs/migration/`.

`docs-link-check.yml` checks the links of every `.md` file with lychee, and a broken internal link or anchor fails it. See [Link Check](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/development/ci-cd#link-check) to run it locally.

See [Publishing](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/development/publishing) for the full flow, including the repository secrets, the GitHub Pages setup and troubleshooting, and [CI/CD](https://gabriel-sisjr.github.io/react-native-sync-provider/docs/development/ci-cd) for every workflow.

### Scripts

The `package.json` file contains various scripts for common tasks:

- `yarn`: setup project by installing dependencies.
- `yarn typecheck`: type-check files with TypeScript.
- `yarn lint`: lint files with [ESLint](https://eslint.org/).
- `yarn test`: run unit tests with [Jest](https://jestjs.io/).
- `yarn nitrogen`: generate the Nitro bindings in `nitrogen/` from `src/SyncProvider.nitro.ts`. Run it after a fresh clone and after every spec change.
- `yarn prepare`: build the library into `lib/` with react-native-builder-bob, running `nitrogen` first.
- `yarn clean`: delete `lib/`, the library's `android/build`, and the example app's Android and iOS build outputs.
- `yarn docs:dev` / `yarn docs:build`: run or build the documentation site. The build validates every mermaid diagram first.
- `yarn docs:serve` / `yarn docs:clear`: serve the built site, or clear Docusaurus's generated files and caches.
- `yarn example start`: start the Metro server for the example app.
- `yarn example android`: run the example app on Android.
- `yarn example ios`: run the example app on iOS.

### Sending a pull request

> **Working on your first pull request?** You can learn how from this _free_ series: [How to Contribute to an Open Source Project on GitHub](https://app.egghead.io/playlists/how-to-contribute-to-an-open-source-project-on-github).

When you're sending a pull request:

- Prefer small pull requests focused on one change.
- Verify that linters and tests are passing.
- Test on both Android and iOS when changes affect shared code or the TypeScript layer.
- Review the documentation to make sure it looks good.
- In the pull request description, say what the change does and which platforms you tested it on, link the issue where an API or implementation change was discussed, and add before/after screenshots or a short clip for a UI or behavior change.
- For pull requests that change the API or implementation, discuss with maintainers first by opening an issue.
- For platform-specific changes, clearly indicate which platform is affected in the PR title (e.g., `feat(ios): ...` or `fix(android): ...`).
