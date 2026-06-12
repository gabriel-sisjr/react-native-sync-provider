# Contributing

Contributions are always welcome, no matter how large or small!

We want this community to be friendly and respectful to each other. Please follow it in all your interactions with the project. Before contributing, please read the [code of conduct](./CODE_OF_CONDUCT.md).

## Development workflow

This project is a monorepo managed using [Yarn workspaces](https://yarnpkg.com/features/workspaces). It contains the following packages:

- The library package in the root directory.
- An example app in the `example/` directory.

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

#### The Nitro workflow (CRITICAL)

`src/SyncProvider.nitro.ts` is the single source of truth for the JS↔native contract. Both `ios/SyncProvider.swift` and `android/src/main/java/com/margelo/nitro/syncprovider/SyncProvider.kt` implement the regenerated `HybridSyncProviderSpec` base class — old method signatures will fail to compile after the spec changes.

The full lifecycle for adding or changing a Nitro method:

1. Edit the interface in `src/SyncProvider.nitro.ts`.
2. Run `yarn nitrogen` (regenerates the Swift/Kotlin/C++ base classes — old impls break loudly).
3. Implement the new method in **both** `ios/SyncProvider.swift` and `android/src/main/java/com/margelo/nitro/syncprovider/SyncProvider.kt`.
4. Surface it in the JS facade by adding the wrapper directly in `src/index.tsx` (via `nativeOrThrow()`). The facade lives wholly in `src/index.tsx`; the platform split is at the entry level — `src/index.tsx` (native) vs `src/index.web.tsx` (web fallback that throws). When the new function/hook should be unusable on web, add a throwing twin in `src/index.web.tsx`. There is no per-method `.native.tsx` / `.tsx` split.
5. Run `yarn typecheck && yarn lint && yarn test`.
6. Rebuild the example app (`yarn example android` / `ios`) — JS-only changes hot-reload, native changes need a rebuild.

The [example app](/example/) demonstrates usage of the library. You need to run it to test any changes you make.

It is configured to use the local version of the library, so any changes you make to the library's source code will be reflected in the example app. Changes to the library's JavaScript code will be reflected in the example app without a rebuild, but native code changes will require a rebuild of the example app.

If you want to use Android Studio or Xcode to edit the native code, you can open the `example/android` or `example/ios` directories respectively in those editors. To edit the Objective-C or Swift files, open `example/ios/SyncProviderExample.xcworkspace` in Xcode and find the source files at `Pods > Development Pods > react-native-sync-provider`.

To edit the Java or Kotlin files, open `example/android` in Android studio and find the source files at `react-native-sync-provider` under `Android`.

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
| **V-JS** — JS gate (always) | `yarn typecheck && yarn lint && yarn test`                                                                                                              | Every change, no exceptions.                                                                    |
| **V-AND** — Android gate    | `yarn nitrogen` then `yarn turbo run build:android`, plus the Gradle unit tests (`gradlew :gabriel-sisjr_react-native-sync-provider:testDebugUnitTest`) | Android native (`android/**`) **or** the Nitro bridge (`src/SyncProvider.nitro.ts`) is touched. |
| **V-IOS** — iOS gate        | `cd example && bundle exec pod install --project-directory=ios` then `xcodebuild test` with scheme `SyncProvider-Unit-Tests`                            | iOS native (`ios/**`) **or** the Nitro bridge is touched.                                       |

And these correctness gates, regardless of platform:

- **R-A** — Re-run `yarn nitrogen` on any change to `src/SyncProvider.nitro.ts` or to any spec-reachable type. Stale generated base classes are a silent source of build drift.
- **R-E** — Every new facade function or hook needs a web twin in `src/index.web.tsx` (a throwing fallback for functions, inert state for hooks). A new export with no web twin breaks the web/SSR bundle.

### Commit message convention

We follow the [conventional commits specification](https://www.conventionalcommits.org/en) for our commit messages:

- `fix`: bug fixes, e.g. fix crash due to deprecated method.
- `feat`: new features, e.g. add new method to the module.
- `refactor`: code refactor, e.g. migrate from class components to hooks.
- `docs`: changes into documentation, e.g. add usage example for the module.
- `test`: adding or updating tests, e.g. add integration tests using detox.
- `chore`: tooling changes, e.g. change CI config.

Our pre-commit hooks verify that your commit message matches this format when committing.

### Releasing

This project uses [release-it](https://github.com/release-it/release-it) for local version bumping plus a set of GitHub Actions workflows that handle npm publishing, GitHub releases, documentation deploys, and link checking. The flow is intentionally split so that contributors never run `npm publish` from their machines — every published artifact goes through CI.

#### Prerequisites

Before cutting a release, make sure you have:

- **Node** matching `.nvmrc` (v24.13.0) and **Yarn 4.11.0** (Berry). Older Yarn versions do not understand the workspace protocol used here.
- An **npm account** with publish access to `@gabriel-sisjr/react-native-sync-provider` and 2FA configured for "Authorization and Writes".
- **Maintainer permissions** on the GitHub repository (required to push tags, create releases, and trigger `workflow_dispatch` jobs).
- A clean working directory on the branch you want to release from (`main` for stable, `develop` for pre-release).

#### Repository secrets

The release workflows require the following secrets to be configured in **Settings → Secrets and variables → Actions**:

| Secret          | Required | How to create                                                                                                                                                                                                                                              | Used by                                      |
| --------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| `NPM_TOKEN`     | yes      | On [npmjs.com → Access Tokens](https://docs.npmjs.com/creating-and-viewing-access-tokens), create a **Granular Access Token** with `Read and write` permission for the package and the `--provenance` scope. Paste the token value into the GitHub secret. | `publish.yml`, `prerelease.yml`              |
| `CODECOV_TOKEN` | optional | Generated by [Codecov](https://about.codecov.io/) after adding the repo. Only needed if you want coverage uploaded from release runs (CI runs already upload from `ci.yml`).                                                                               | `publish.yml`, `prerelease.yml` (coverage)   |
| `GITHUB_TOKEN`  | auto     | Provided automatically by GitHub Actions. No manual setup required.                                                                                                                                                                                        | All workflows (tags, releases, Pages deploy) |

> **Token rotation:** `NPM_TOKEN` expires after the lifetime you set on npm. If `publish.yml` starts failing with `403 Forbidden` or `EUNAVAILABLE`, regenerate the token and update the secret.

#### GitHub Pages prerequisite

`deploy-docs.yml` deploys the Docusaurus site using the official [`actions/deploy-pages@v4`](https://github.com/actions/deploy-pages) action. This requires the repository to be configured with Pages backed by Actions (not by a branch).

In **Settings → Pages**:

1. Under **Build and deployment → Source**, select **GitHub Actions**.
2. Leave the custom domain empty unless you have one configured.
3. Save. The first successful run of `deploy-docs.yml` will publish to `https://gabriel-sisjr.github.io/react-native-sync-provider/`.

> If Pages is still set to "Deploy from a branch", `deploy-docs.yml` will fail at the deploy step with `Error: Get Pages site failed`. Switching the source to **GitHub Actions** fixes it.

#### Local release flow (stable)

Use this when you are ready to publish a stable version (e.g., `0.1.0`, `0.2.0`, `1.0.0`) from `main`.

```sh
# 1. Sync main and verify it is clean.
git checkout main
git pull --ff-only
git status            # must be clean — no staged or unstaged changes

# 2. Bump the version. release-it will:
#    - prompt for the next version based on conventional commits
#    - regenerate CHANGELOG.md via @release-it/conventional-changelog (angular preset)
#    - commit "chore: release v<version>"
#    - create the annotated tag "v<version>"
#    - push the commit and the tag
yarn release
```

What happens next is automatic:

1. The push to `main` triggers **`publish.yml`**.
2. The workflow re-reads `package.json`, compares the version against the last version on npm, and only proceeds if it changed (the **version-diff guard**). This is what prevents accidental re-publishes when you push unrelated commits to `main`.
3. CI runs `yarn install --immutable` → `yarn nitrogen` → `yarn lint` → `yarn typecheck` → `yarn prepare` (build) → `yarn test`. The `yarn lint` and `yarn typecheck` gates run inside `publish.yml` (right after nitrogen, before the build) so the publish path fails fast on lint/type errors, not just on test failures.
4. CI runs `npm publish --access public --provenance`. The `--provenance` flag attaches a signed attestation linking the published tarball to this exact commit and workflow run.
5. CI creates the `v<version>` GitHub release with notes generated from the commit log between tags.

> **Do not run `npm publish` yourself.** All publishing happens in CI so that every release carries provenance metadata.

#### Pre-release flow (beta)

There are two ways to publish a pre-release:

**Option A — Local (`yarn release:beta`):** for a quick beta from `develop`.

```sh
git checkout develop
git pull --ff-only
yarn release:beta
```

This runs `release-it --preRelease=beta`, which bumps the version to a `-beta.N` suffix, commits, tags, and pushes. The push triggers the same version-diff guard in `publish.yml`, which then publishes with the `beta` dist-tag thanks to the version suffix.

**Option B — CI-driven (`prerelease.yml` workflow_dispatch):** for ephemeral betas where you do not want to commit a version bump.

1. Go to **Actions → Pre-release to NPM → Run workflow**.
2. Choose the branch (default `develop`), optionally set a `version_suffix` (e.g., `rc1`) or leave empty to auto-generate `<base>-beta.<timestamp>.<sha>`.
3. The workflow updates `package.json` ephemerally (no commit), runs the full test suite, and publishes with `npm publish --access public --tag beta --provenance`.
4. A `v<version>` tag and a GitHub pre-release are created at the end.

> **Dist-tag matters.** Consumers must opt in to a beta with `yarn add @gabriel-sisjr/react-native-sync-provider@beta`. A plain `yarn add` keeps following the `latest` tag, which is only ever moved by `publish.yml`.

#### CI-driven publish details

`publish.yml` triggers on push to `main` only — there is no `workflow_dispatch` trigger, so it cannot be rerun manually from the Actions tab. The version-diff guard is the load-bearing piece — without it, every push to `main` would attempt to republish the current version and fail. Concretely:

```yaml
# .github/workflows/publish.yml (excerpt)
- name: Check if version changed
  id: version-check
  run: |
    CURRENT_VERSION=$(node -p "require('./package.json').version")
    PUBLISHED_VERSION=$(npm view @gabriel-sisjr/react-native-sync-provider version 2>/dev/null || echo "0.0.0")
    if [ "$CURRENT_VERSION" = "$PUBLISHED_VERSION" ]; then
      echo "version_changed=false" >> $GITHUB_OUTPUT
    else
      echo "version_changed=true" >> $GITHUB_OUTPUT
    fi
```

All subsequent steps (`yarn nitrogen`, `yarn prepare`, `npm publish`, tag, release) are gated on `version_changed == 'true'`.

#### Docs deploy

`deploy-docs.yml` runs on:

- push to `main` that touches `website/**` (production deploy)
- `workflow_dispatch` (manual rebuild — useful when changing docs-only repo settings)

The build runs `yarn workspace react-native-sync-provider-website docusaurus build` and uploads the `website/build/` directory as a Pages artifact. The deploy job consumes the artifact and publishes to GitHub Pages.

> Pushes that touch only `src/**` do **not** redeploy the site. If you change a TSDoc block that is surfaced on the docs site, edit the corresponding `website/docs/**/*.md` page in the same PR.

#### Link check

`docs-link-check.yml` runs on push and PR for documentation paths (`README.md`, `CONTRIBUTING.md`, `BREAKING_CHANGES.md`, `SPONSOR.md`, `website/docs/**`, `website/blog/**`) and on `workflow_dispatch`. It uses [lychee](https://github.com/lycheeverse/lychee) in two passes:

1. **Internal links and anchors** — fail-blocking. A broken relative link or missing heading anchor fails the workflow.
2. **External links** — warning only. Flaky third-party sites do not block PRs but are reported in the run summary.

> There is no cron. Link rot from upstream sites surfaces the next time someone touches docs, which keeps the signal scoped to relevant changes.

#### Conventional commits reminder

Both `yarn release` and `yarn release:beta` rely on conventional commits to compute the next version and to generate CHANGELOG entries. The `commit-msg` hook (lefthook + `@commitlint/config-conventional`) already enforces this on every commit. If you have to amend or rewrite history before releasing, keep the format intact:

```
fix(android): retry queue stops draining after WorkManager constraint expires
feat(ios): add expedited dispatch helper to SyncDispatcher
```

#### Do not manually edit CHANGELOG.md

`CHANGELOG.md` is owned by `@release-it/conventional-changelog`. The angular preset rewrites the file in place during `yarn release` / `yarn release:beta`. Manual edits will be silently overwritten on the next release. If a changelog entry is wrong, fix the underlying commit message and re-run the release, or amend the entry in a follow-up release.

#### Troubleshooting

| Symptom                                                                          | Likely cause                                                                                                            | Fix                                                                                                                                                                                                                                |
| -------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `publish.yml` fails with `EOIDCTOKEN` or `unable to read OIDC token`             | The `publish` job is missing `permissions: id-token: write`, which `--provenance` requires.                             | Restore the `permissions:` block at the top of the job in `.github/workflows/publish.yml`.                                                                                                                                         |
| `npm publish` returns `403 Forbidden`                                            | `NPM_TOKEN` expired or was rotated; or 2FA "Authorization and Writes" is enabled and the token lacks the publish scope. | Regenerate a granular access token on npm with publish permission for the package, update the `NPM_TOKEN` repository secret, and rerun the workflow.                                                                               |
| `publish.yml` runs but skips the publish step (`version_changed=false`)          | The version in `package.json` matches what is already on npm — the version-diff guard intentionally short-circuits.     | Bump the version (`yarn release`) and push again. Do not patch the workflow to bypass the guard.                                                                                                                                   |
| Lefthook rejects the release commit (`subject may not be empty`)                 | release-it's commit message (`chore: release v<version>`) was modified in flight or commitlint config was loosened.     | Keep the default `commitMessage` template in `package.json → release-it`. If you need to change it, also update the commitlint config (the `"commitlint"` key in `package.json`, which extends `@commitlint/config-conventional`). |
| `deploy-docs.yml` fails at `Deploy to GitHub Pages` with `Get Pages site failed` | Pages source is still set to "Deploy from a branch".                                                                    | Switch **Settings → Pages → Source** to **GitHub Actions** and rerun the workflow.                                                                                                                                                 |
| `docs-link-check.yml` fails on an internal anchor that exists                    | Docusaurus camelCases anchor slugs (`SyncItemInput` → `synciteminput`, not `syncitem-input`).                           | Use the literal slug emitted by Docusaurus. Verify by running `yarn docs:build` locally and checking the generated HTML.                                                                                                           |
| `prerelease.yml` cannot find the branch                                          | The `target_branch` input does not exist on the remote, or the workflow is dispatched from a fork.                      | Push the branch to the canonical remote (no forks for releases) and re-run the dispatch.                                                                                                                                           |

### Scripts

The `package.json` file contains various scripts for common tasks:

- `yarn`: setup project by installing dependencies.
- `yarn typecheck`: type-check files with TypeScript.
  - `yarn lint`: lint files with [ESLint](https://eslint.org/).
    - `yarn test`: run unit tests with [Jest](https://jestjs.io/).
  - `yarn example start`: start the Metro server for the example app.
- `yarn example android`: run the example app on Android.
- `yarn example ios`: run the example app on iOS.

### Sending a pull request

> **Working on your first pull request?** You can learn how from this _free_ series: [How to Contribute to an Open Source Project on GitHub](https://app.egghead.io/playlists/how-to-contribute-to-an-open-source-project-on-github).

When you're sending a pull request:

- Prefer small pull requests focused on one change.
- Verify that linters and tests are passing.
- Review the documentation to make sure it looks good.
- Follow the pull request template when opening a pull request.
- For pull requests that change the API or implementation, discuss with maintainers first by opening an issue.
