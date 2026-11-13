---
sidebar_position: 3
title: CI/CD Pipeline
description: GitHub Actions workflows of @gabriel-sisjr/react-native-sync-provider. Covers the seven CI jobs, npm publishing and pre-releases, the docs deploy and the Markdown link check, with triggers, toolchain versions, caching and troubleshooting.
keywords:
  - ci-cd
  - github-actions
  - continuous-integration
  - turborepo
  - lychee
  - github-pages
  - react-native
  - react-native-sync-provider
---

# CI/CD Pipeline

The library uses GitHub Actions to test, build and publish the package, deploy this documentation site and check the links in its Markdown files.

---

## Overview

Five workflows live in `.github/workflows/`:

| Workflow | File | Purpose | Trigger |
| --- | --- | --- | --- |
| **CI** | `ci.yml` | Lint, type check, unit tests, package build, example app builds and native unit tests | Push and PR to `main` or `develop`, merge queue, manual |
| **Publish to NPM** | `publish.yml` | Publish a new version to npm under `latest`, tag it and create the GitHub release | Push to `main` |
| **Pre-release to NPM** | `prerelease.yml` | Publish a pre-release under the `beta` dist-tag | Manual only |
| **Deploy Documentation** | `deploy-docs.yml` | Build this site and deploy it to GitHub Pages | Push to `main` that changes `website/**`, manual |
| **Docs Link Check** | `docs-link-check.yml` | Check the links and anchors of every `.md` file | Push and PR to `main` or `develop` that change a `.md` file, `lychee.toml` or the workflow; manual |

The bold names are each workflow's `name:`, as the **Actions** tab lists them.

### Workflow Architecture

```text
branch  --> PR to develop --> CI (+ Docs Link Check when .md files change)
                                 |
develop --> PR to main ------> CI (+ Docs Link Check when .md files change)
                                 |
merge to main --> CI
              --> Publish to NPM        (when the package.json version changed)
              --> Deploy Documentation  (when website/** changed)
              --> Docs Link Check       (when .md files changed)

Pre-releases: manual run of "Pre-release to NPM" from the Actions tab
```

---

## CI Workflow (`ci.yml`)

**Purpose:** Validate every change: lint, types, unit tests, the npm package, both example apps and both native test suites.

**Triggers:**

- Push to `main` or `develop`
- Pull requests targeting `main` or `develop`
- Merge queue checks (`merge_group`, type `checks_requested`)
- Manual run (`workflow_dispatch`)

**Permissions:** `contents: read` for the whole workflow. No job pushes, comments or publishes.

**Concurrency:** one run per workflow and ref. A new push to the same ref cancels the run in progress (`cancel-in-progress: true`).

**Jobs:** none of the seven jobs declares `needs:`, so all of them start in parallel. No job sets a `name:`, so each check is listed under its job id.

| Job | Runner | What it runs |
| --- | --- | --- |
| `lint` | `ubuntu-latest` | `yarn lint`, then `yarn typecheck` |
| `test` | `ubuntu-latest` | `yarn test --maxWorkers=2 --coverage`. The coverage report stays in the job log: `ci.yml` uploads nothing |
| `build-library` | `ubuntu-latest` | `yarn prepare` (bob runs Nitrogen, then builds `lib/module` and `lib/typescript`), then writes the packed size, unpacked size and file count of the npm tarball to the job summary and asserts the tarball contents |
| `build-android` | `ubuntu-latest` | `yarn nitrogen`, then `yarn turbo run build:android`: the example app's `react-native build-android`, for `arm64-v8a` only |
| `build-ios` | `macos-latest` | `yarn nitrogen`, CocoaPods, then `yarn turbo run build:ios`: the example app's `react-native build-ios --mode Debug` |
| `test-android` | `ubuntu-latest` | `yarn nitrogen`, then the library's Gradle unit tests |
| `test-ios` | `macos-latest` | `yarn nitrogen`, CocoaPods, then `xcodebuild test` with the scheme `SyncProvider-Unit-Tests` |

### Toolchain

| Tool | Version | Where it is set |
| --- | --- | --- |
| Node.js | `v24.13.0` | `.nvmrc`, read by `actions/setup-node` in the Setup action |
| Yarn | `4.11.0` | `packageManager` in `package.json` and `yarnPath` in `.yarnrc.yml` |
| JDK | 17 (Zulu) | `actions/setup-java` in `build-android` and `test-android` |
| Xcode | `latest-stable` | `XCODE_VERSION` in `build-ios` and `test-ios` |
| CocoaPods | 1.15.2 | `example/Gemfile.lock`, run through `bundle exec` |
| Ruby | The runner's own | No setup step; `example/Gemfile` asks for `>= 2.6.10` |

Xcode is deliberately not a version pin. `macos-latest` moves between images, and a pin stops resolving once the image no longer ships that Xcode; `latest-stable` resolves against what the image provides, betas excluded. Before building, the iOS jobs print `xcodebuild -version` and the Xcode versions on the runner (`build-ios` skips this on a Turbo cache hit).

### The Setup Action

Every job in `ci.yml`, `publish.yml` and `prerelease.yml` starts with the composite action `.github/actions/setup`:

1. `actions/setup-node` with `node-version-file: .nvmrc`.
2. Restore `**/node_modules` and `.yarn/install-state.gz` from a cache keyed on the hashes of `yarn.lock` and of every `package.json`.
3. Unless that key matched exactly, run `yarn install --immutable`, then save the cache under the key.

`--immutable` fails the install when `yarn.lock` would change, so commit the lockfile with every dependency change.

### Caching

| Cache | Path | Key | Used by |
| --- | --- | --- | --- |
| Dependencies | `**/node_modules`, `.yarn/install-state.gz` | `<os>-yarn-<yarn.lock hash>-<package.json hashes>` | The Setup action |
| Turbo, Android | `.turbo/android` | `<os>-turborepo-android-<yarn.lock hash>` | `build-android` |
| Turbo, iOS | `.turbo/ios` | `<os>-turborepo-ios-<yarn.lock hash>` | `build-ios` |
| Gradle | `~/.gradle/wrapper`, `~/.gradle/caches` | `<os>-gradle-<hash of example/android/gradle/wrapper/gradle-wrapper.properties>` | `build-android`, skipped on a Turbo hit |
| Gradle, tests | `~/.gradle/wrapper`, `~/.gradle/caches` | `<os>-gradle-tests-<same hash>`, falling back to `<os>-gradle-` | `test-android` |

CocoaPods is not cached. `build-ios` and `test-ios` run `bundle install`, `pod repo update` and `pod install` on every run; `build-ios` skips all three on a Turbo hit. `deploy-docs.yml` does not use the Setup action: it relies on `actions/setup-node` with `cache: yarn`.

#### Turbo Cache Hits

Before installing any toolchain, `build-android` and `build-ios` run a Turbo dry run of their task (`--dry=json`) and read its cache status. On `HIT` they skip the toolchain steps: the JDK, the Android SDK licenses and the Gradle cache on Android; Xcode selection and CocoaPods on iOS. The build step still runs `yarn turbo run build:android` or `yarn turbo run build:ios`, which replays the cached result. Both tasks declare `"outputs": []`, so a hit restores the log, not build artifacts.

A task's hash covers `.nvmrc`, `.yarnrc.yml` and `NODE_ENV` (global to every task) plus the inputs and variables `turbo.json` declares for it:

| Task | Inputs | Environment |
| --- | --- | --- |
| `build:android` | The example's `package.json` and `android/**`; the root `package.json`, `yarn.lock`, `nitro.json` and `android/**`; `src/*.ts`, `src/*.tsx`, `src/**/*.nitro.ts`, `src/types/**` and `src/errors/**`. The `.gradle`, `.kotlin`, `build` and `.cxx` folders and `local.properties` are excluded | `ANDROID_HOME`, `ORG_GRADLE_PROJECT_newArchEnabled` |
| `build:ios` | The example's `package.json` and `ios/**`, without `build`, `Pods`, `.xcode.env.local` and Xcode user state; the root `package.json`, `yarn.lock`, `nitro.json`, `*.podspec` and `ios/**`; the same `src/` globs | `RCT_NEW_ARCH_ENABLED`, `RCT_REMOVE_LEGACY_ARCH`, `RCT_USE_RN_DEP`, `RCT_USE_PREBUILT_RNCORE` |

A change confined to `src/hooks/`, `src/contexts/`, `src/utils/` or `src/__tests__/` therefore keeps both example builds cached.

### Environment Variables

| Variable | Jobs | Value and effect |
| --- | --- | --- |
| `TURBO_CACHE_DIR` | `build-android`, `build-ios` | `.turbo/android` or `.turbo/ios`: Turbo's cache directory, and the path of its GitHub cache |
| `turbo_cache_hit` | `build-android`, `build-ios` | Set to `1` by the cache check. The toolchain steps run only when it is not `1` |
| `JAVA_OPTS` | `build-android`, `test-android` | `-XX:MaxHeapSize=6g`, on the Gradle step only |
| `XCODE_VERSION` | `build-ios`, `test-ios` | `latest-stable` |
| `RCT_USE_RN_DEP` | `build-ios`, `test-ios` | `1`: `pod install` uses React Native's prebuilt iOS dependencies |
| `RCT_USE_PREBUILT_RNCORE` | `build-ios`, `test-ios` | `1`: `pod install` uses the precompiled React Native core |
| `IOS_TEST_SCHEME` | `test-ios` | `SyncProvider-Unit-Tests` |

`RCT_USE_RN_DEP` and `RCT_USE_PREBUILT_RNCORE` are also environment inputs of the `build:ios` Turbo task. Set both when you reproduce the iOS build locally; the release bump runs `pod install` with them too (see [Stable Release](./publishing.md#stable-release)).

### Native Unit Tests

Neither test job uses Turbo, so the suites run on every CI run.

**`test-android`** installs JDK 17 (Zulu), accepts the Android SDK licenses, restores its Gradle cache and runs `yarn nitrogen`, then:

```bash
cd example/android
./gradlew :gabriel-sisjr_react-native-sync-provider:testDebugUnitTest --stacktrace
```

It uploads `android/build/reports/tests/`, `android/build/reports/jacoco/` and `android/build/test-results/` as the `android-test-reports` artifact on every run, kept for 14 days.

**`test-ios`** selects Xcode, runs `yarn nitrogen`, installs the pods, and lists the workspace schemes and the available simulators. It then picks one simulator with `.github/scripts/pick-ios-simulator.mjs`: on the newest iOS runtime that has an iPhone, the first of `iPhone 17`, `iPhone 17 Pro` and `iPhone Air` that exists, else the first iPhone on that runtime by name. The suite runs once, so a failure is never retried on another device:

```bash
cd example/ios
xcrun xcodebuild test \
  -workspace SyncProviderExample.xcworkspace \
  -scheme "${IOS_TEST_SCHEME}" \
  -destination "platform=iOS Simulator,id=${SIMULATOR_ID}" \
  -resultBundlePath build/SyncProviderTests.xcresult \
  -enableCodeCoverage YES \
  -skipPackagePluginValidation \
  -skipMacroValidation \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO
```

The output goes through `xcbeautify --renderer github-actions --preserve-unbeautified` when the runner has it. The `.xcresult` bundle is uploaded as the `ios-test-results` artifact on every run, kept for 14 days.

To run either suite on your machine, see [Testing](./testing.md).

### Reading a Failed Job

- **Example iOS build.** `react-native build-ios` discards the stderr of `xcodebuild` and filters its stdout, so the job log can leave out the reason. On failure, `.github/scripts/report-ios-build-failure.sh` prints the diagnostics it finds in Xcode's activity log and adds the first one as an annotation. The log itself is uploaded as the `ios-build-log` artifact, only when the build failed, and kept for 14 days. It is an `.xcactivitylog`: open it in Xcode, or read it as text:

  ```bash
  gunzip -c <log> | tr '\r' '\n'
  ```

- **Package contents.** `build-library`, `publish.yml` and `prerelease.yml` pass the `npm pack` file list to `.github/scripts/assert-pack-contents.mjs`. It fails when the tarball misses a Core Data model file, when `.xccurrentversion` does not name the newest model version, or when the tarball would ship native tests. See [What Gets Published](./publishing.md#what-gets-published).
- **Native tests.** Download `android-test-reports` or `ios-test-results` from the run's summary page.

---

## Publish Workflow (`publish.yml`)

**Purpose:** Publish the `package.json` version to npm under `latest` when it differs from the published one, then tag it and create the GitHub release.

**Trigger:** Push to `main` only. There is no `workflow_dispatch`; a finished run can be re-run from the **Actions** tab.

**Permissions (job):** `contents: write`, `id-token: write`, `issues: write`, `pull-requests: write`.

**Concurrency:** a new push to `main` cancels a run in progress (`cancel-in-progress: true`).

**Process:**

1. Check out the full history, run the Setup action, configure Git as `github-actions[bot]` and write `NPM_TOKEN` to `~/.npmrc`.
2. **Check if version changed**: [the version guard](./publishing.md#the-version-guard). Every later step runs only when the version changed, except the three coverage steps, which run `if: always()`.
3. `yarn nitrogen`, `yarn lint`, `yarn typecheck`, `yarn prepare`, then `yarn test --coverage --maxWorkers=2`.
4. Coverage: the `coverage-release` artifact, a Codecov upload with the `release` flag (a failed upload never fails the run), and a coverage table in the job summary.
5. **Assert npm pack contents**, directly before the publish. A failure here skips the publish, the tag and the release.
6. `npm publish --access public --provenance`.
7. Create and push the annotated tag `v<version>`.
8. Create the GitHub release `Release v<version>`. Its notes list the commit subjects between the two highest tags; `CHANGELOG.md` is not read.
9. Comment on pull requests merged into `main` in the last five minutes, with the install commands for the new version.

For the release procedure around this workflow, see [Publishing](./publishing.md).

---

## Pre-Release Workflow (`prerelease.yml`)

**Purpose:** Publish a pre-release of a branch under the `beta` dist-tag.

**Trigger:** Manual only (`workflow_dispatch`). No push publishes a pre-release.

**Permissions (job):** the same four as `publish.yml`.

### How to Trigger

1. Open the **Actions** tab and select **Pre-release to NPM**.
2. Click **Run workflow**.
3. Under **Use workflow from**, choose the branch to release (`develop` for a normal pre-release).
4. Leave **Branch to release from** (the `branch` input) at `develop`, or set it to the same branch as in step 3.
5. Optional: fill in the version suffix (`version_suffix`, for example `rc1` or `beta1`) and the description (`description`).
6. Click **Run workflow**.

> **Important — Choose the branch in both fields:** The checkout uses the `branch` input, but the commit hash in a generated version, the `Commit:` line of the GitHub pre-release and the npm provenance all come from the run's own commit, the head of the branch chosen in **Use workflow from**. When you pre-release a branch other than `develop`, choose it in both fields.

> **Note:** Keep single quotes out of the description. The workflow pastes it into a JavaScript string literal, so a quote breaks the step that creates the GitHub pre-release, which runs after the npm publish and the tag push.

### What Happens

1. **Version.** The base is the `package.json` version without any pre-release part. With a suffix the version is `<base>-<suffix>`; without one it is `<base>-beta.<timestamp>.<sha7>`, where the timestamp is `date +%Y%m%d%H%M%S` on the runner (UTC) and `<sha7>` the first seven characters of the run's commit hash. `npm version` writes it to `package.json` with `--no-git-tag-version`.
2. `yarn nitrogen`, `yarn prepare`, then `yarn test --coverage --maxWorkers=2`. There is no `yarn lint` or `yarn typecheck` step.
3. Coverage: the `coverage-prerelease` artifact, a Codecov upload with the `prerelease` flag and the summary table, all `if: always()`.
4. **Assert npm pack contents**, directly before the publish. The script comes from the branch being released, so a branch older than the script stops here too.
5. `npm publish --access public --tag beta --provenance`. A version with an `rc` suffix lands on `beta` too.
6. Commit `package.json` inside the runner as `chore: pre-release <version> [skip ci]`, then push only the annotated tag `v<version>`. The tag points at a commit that is on no branch.
7. Create the GitHub pre-release `Pre-release v<version>`, with the description (by default "This is a beta release from the develop branch."), the install command and the commit.
8. Comment on pull requests merged into `develop` in the last five minutes.

### Pre-Release Version Format

```text
<base>-beta.<timestamp>.<sha7>    e.g. 0.3.0-beta.20261112143022.a1b2c3d
<base>-<version_suffix>           e.g. 0.3.0-rc1
```

A pre-release of a version that already shipped sorts below it (`0.2.0-rc1 < 0.2.0`), so bump the version before you pre-release it (see [Pre-Releases](./publishing.md#pre-releases)).

### Installing a Pre-Release

```bash
yarn add @gabriel-sisjr/react-native-sync-provider@beta
# or
npm install @gabriel-sisjr/react-native-sync-provider@beta
```

A plain install follows `latest`.

---

## Docs Deploy Workflow (`deploy-docs.yml`)

**Purpose:** Build this Docusaurus site and deploy it to GitHub Pages at `https://gabriel-sisjr.github.io/react-native-sync-provider/`.

**Triggers:**

- Push to `main` that changes a file under `website/**`
- Manual run (`workflow_dispatch`), useful after changing docs-only repository settings

**Permissions:** `contents: read`, `pages: write`, `id-token: write`.

**Concurrency:** group `pages` with `cancel-in-progress: false`, so a newer run never cancels a deploy in progress.

| Job | What it runs |
| --- | --- |
| `build` | `actions/setup-node` (`.nvmrc`, `cache: yarn`), `yarn install --immutable`, `yarn docs:build`, then uploads `website/build` as the Pages artifact |
| `deploy` | Needs `build`. Deploys the artifact with `actions/deploy-pages` to the `github-pages` environment |

`yarn docs:build` runs the website's `build` script, `yarn validate:mermaid && docusaurus build`:

1. `website/scripts/validate-mermaid.mjs` parses every `mermaid` block under `website/docs/` with `mermaid.parse()`. A block that does not parse fails the deploy before Docusaurus starts.
2. Docusaurus fails on a broken link (`onBrokenLinks: 'throw'`) and warns on a broken Markdown link (`onBrokenMarkdownLinks: 'warn'`). Broken anchors only warn: `onBrokenAnchors` is not set, and its default is `warn`.

> **Note:** No workflow builds the site on pull requests, and the [link check](#link-check) reads the `.md` sources, not the build. Run `yarn docs:build` before you merge a docs change; otherwise a build failure first shows up in the deploy on `main`.

> **Note:** Pushes that touch only `src/**` do not redeploy the site. If you change a TSDoc block that is surfaced on the docs site, edit the corresponding `website/docs/**/*.md` page in the same PR.

---

## Link Check

`docs-link-check.yml` runs [lychee](https://github.com/lycheeverse/lychee) over every `**/*.md` file in the repository, configured by `lychee.toml`. Its single job is `link-check`, shown as **Lychee link check**.

**Triggers:**

- Push and pull request to `main` or `develop` that change a `**/*.md` file, `lychee.toml` or the workflow itself
- Manual run (`workflow_dispatch`)

There is no cron. Link rot from upstream sites surfaces the next time someone touches docs, which keeps the signal scoped to relevant changes.

The job runs lychee twice:

| Pass | Extra flags | What it checks | On failure |
| --- | --- | --- | --- |
| 1. Internal links and anchors | `--offline --include-fragments` | Relative and same-page links, with their `#anchor` fragments | Fails the workflow |
| 2. External links | None (online) | `http` and `https` links, without fragments | Warning only; the broken links are listed in the job summary |

Both passes also take `--no-progress --config ./lychee.toml` and scan `'./**/*.md'`. `lychee.toml` sets:

- `exclude_path`: dependency, build and cache folders, such as `node_modules`, `lib`, `build`, `example/ios/Pods`, `website/build`, `website/.docusaurus`, `.turbo` and `coverage`.
- For the online pass: `max_redirects = 5`, `max_retries = 2`, `timeout = 20` (seconds), and `accept = ["200..=299", "429"]`, so a rate-limited host counts as reachable.
- `exclude`: host patterns to skip. The list is empty; add a host there when it starts producing false positives.

Anchors come from the `.md` source headings, not from the Docusaurus build. Links to this site by absolute URL, which the root Markdown files use, are external links: only the online pass checks them, it checks that the page loads but not the anchor, and a failure there is a warning.

`yarn docs:build` does not reproduce this check. lychee is not a project dependency: install the CLI, then run the blocking pass from the repository root:

```bash
lychee --offline --include-fragments --no-progress --config ./lychee.toml './**/*.md'
```

---

## Setup Guide

### Prerequisites

- **Maintainer or admin rights** on the GitHub repository, to merge into `develop` and `main` and to run `prerelease.yml`. Both branches take changes only through pull requests, and the ruleset enforces one approving review, squash merges and linear history by default. Maintainers use the ruleset bypass to merge with a merge commit when a PR's commits must stay individually revertible, as on the release PR.
- **Node** matching `.nvmrc` (`v24.13.0`), **Yarn 4.11.0**, and Ruby with Bundler for `bundle exec pod install` in `example/`.
- An **npm account** with publish access to `@gabriel-sisjr/react-native-sync-provider`, to create the `NPM_TOKEN` secret.

### Step 1: Create the npm Token

On npmjs.com, under **Access Tokens**, create a **Granular Access Token** with `Read and write` permission for the package (see npm's guide to [creating access tokens](https://docs.npmjs.com/creating-and-viewing-access-tokens)). The release workflows write it to `~/.npmrc` before they publish.

Provenance needs no token scope: it comes from the job's OIDC `id-token: write` permission. The token expires after the lifetime you set on npm (see [Rotating the npm Token](#rotating-the-npm-token)).

### Step 2: Add the Repository Secrets

In **Settings → Secrets and variables → Actions**:

| Secret | Required | Value | Used by |
| --- | --- | --- | --- |
| `NPM_TOKEN` | Yes | The token from step 1 | `publish.yml`, `prerelease.yml` |
| `CODECOV_TOKEN` | No | Generated by [Codecov](https://about.codecov.io/) after adding the repository. Only the release workflows upload coverage, and a failed upload does not fail the run | `publish.yml`, `prerelease.yml` |
| `GITHUB_TOKEN` | Automatic | Provided by GitHub Actions; no setup | `publish.yml`, `prerelease.yml` (tag push, GitHub release, PR comments), `deploy-docs.yml` (Pages deploy) |

### Step 3: Deploy GitHub Pages from Actions

`deploy-docs.yml` deploys with `actions/deploy-pages`, which needs Pages backed by Actions, not by a branch. In **Settings → Pages**:

1. Under **Build and deployment → Source**, select **GitHub Actions**.
2. Leave the custom domain empty unless you have one configured.
3. Save. The first successful run of `deploy-docs.yml` publishes to `https://gabriel-sisjr.github.io/react-native-sync-provider/`.

If the source is still **Deploy from a branch**, the deploy step fails with `Error: Get Pages site failed`.

---

## Workflow Triggers Quick Reference

| Event | Branch | Workflows | Result |
| --- | --- | --- | --- |
| Push | `develop` | CI; Docs Link Check when Markdown changed | Validation |
| Push | `main` | CI; Publish to NPM; Deploy Documentation when `website/**` changed; Docs Link Check when Markdown changed | Publish when the version changed |
| Pull request | `develop` or `main` | CI; Docs Link Check when Markdown changed | Validation |
| Merge queue | The queue's target | CI | Validation |
| Manual | Any | CI, Pre-release to NPM, Deploy Documentation, Docs Link Check | Depends on the workflow |

"Markdown changed" means a `.md` file, `lychee.toml` or `docs-link-check.yml` changed. `publish.yml` has no manual trigger.

---

## Monitoring and Troubleshooting

### Check Version Status

```bash
# The versions behind the latest and beta dist-tags
yarn npm info @gabriel-sisjr/react-native-sync-provider --fields dist-tags

# Every published version
yarn npm info @gabriel-sisjr/react-native-sync-provider --fields versions
```

### Run a CI Job Locally

| Job | Command, from the repository root |
| --- | --- |
| `lint` | `yarn lint && yarn typecheck` |
| `test` | `yarn test --maxWorkers=2 --coverage` |
| `build-library` | `yarn prepare`, then the pack check in [Verify Package Contents](./publishing.md#verify-package-contents) |
| `build-android` | `yarn nitrogen && yarn turbo run build:android` (needs JDK 17 and the Android SDK) |
| `build-ios` | The commands below |
| `test-android` | `yarn nitrogen`, then the Gradle command in [Native Unit Tests](#native-unit-tests) |
| `test-ios` | `yarn nitrogen`, `pod install`, then `xcodebuild test` with the scheme `SyncProvider-Unit-Tests`, as in [Testing](./testing.md) |

`build-ios`, with the same environment as CI:

```bash
yarn nitrogen
cd example
bundle install
RCT_USE_RN_DEP=1 RCT_USE_PREBUILT_RNCORE=1 bundle exec pod install --project-directory=ios
cd ..
RCT_USE_RN_DEP=1 RCT_USE_PREBUILT_RNCORE=1 yarn turbo run build:ios
```

The pre-merge checklist in [`CONTRIBUTING.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/CONTRIBUTING.md) says which of these a change needs before it is merged.

### Common Issues

#### The docs deploy fails with `Get Pages site failed`

The `Deploy to GitHub Pages` step of `deploy-docs.yml` fails with `Error: Get Pages site failed`.

**Cause.** The Pages source is still **Deploy from a branch**.

**Fix.**

1. Switch **Settings → Pages → Source** to **GitHub Actions**.
2. Re-run the workflow.

#### The link check fails on an anchor that the docs site has

**Cause.** lychee checks anchors against the `.md` source headings, not against the Docusaurus build.

**Fix.**

1. Reproduce the failure with the command at the end of [Link Check](#link-check).
2. Link to the anchor lychee derives from the source heading.

#### The iOS example build fails with no reason in the log

**Cause.** `react-native build-ios` discards the stderr of `xcodebuild` and filters its stdout.

**Fix.**

1. Read the annotation that the **Report iOS build failure** step adds to the run.
2. Download the `ios-build-log` artifact and open it in Xcode (see [Reading a Failed Job](#reading-a-failed-job)).

#### Assert npm pack contents fails

The step fails in `build-library`, `publish.yml` or `prerelease.yml` with a `missing from the tarball`, `native test in the tarball` or `not the newest version` error.

**Cause.** The `files` list in `package.json` no longer packs a Core Data model file or packs native tests, or `.xccurrentversion` does not name the newest model version.

**Fix.**

1. Compare `files` with [What Gets Published](./publishing.md#what-gets-published).
2. After adding a Core Data model version, point `ios/Database/SyncProvider.xcdatamodeld/.xccurrentversion` at it.
3. Run the check locally (see [Verify Package Contents](./publishing.md#verify-package-contents)).

#### A publish or a pre-release fails

See [Publishing troubleshooting](./publishing.md#troubleshooting).

---

## Security

### Token Permissions

| Workflow | Permissions | Why |
| --- | --- | --- |
| `ci.yml` | `contents: read` | No job pushes, comments or publishes |
| `publish.yml`, `prerelease.yml` | `contents: write`, `id-token: write`, `issues: write`, `pull-requests: write` | Tag push and GitHub release; npm provenance; comments on merged pull requests |
| `deploy-docs.yml` | `contents: read`, `pages: write`, `id-token: write` | GitHub Pages deployment |
| `docs-link-check.yml` | Not set | The repository's default token permissions apply |

### Provenance

Both release workflows publish with `--provenance`, which attaches a signed attestation linking the tarball to the commit and workflow run that built it. It needs the job's `id-token: write` permission; the npm token needs no extra scope. A version published from a laptop would carry none, which is why all publishing goes through CI (see [Automated Publishing](./publishing.md#automated-publishing)).

### Action Pinning

`ci.yml`, `docs-link-check.yml`, the Setup action, and the checkout steps of `publish.yml` and `prerelease.yml` pin each action by commit SHA, with the version in a trailing comment:

```yaml
uses: actions/checkout@08c6903cd8c0fde910a37f88322edcfb5dd907a8 # v5.0.0
```

These still use major-version tags:

- `publish.yml` and `prerelease.yml`: `actions/upload-artifact@v4`, `codecov/codecov-action@v5` and `actions/github-script@v7`.
- `deploy-docs.yml`: `actions/checkout@v4`, `actions/setup-node@v4`, `actions/upload-pages-artifact@v3` and `actions/deploy-pages@v4`.

---

## Maintenance

### Updating GitHub Actions

Pin the new version by commit SHA and keep the version in a trailing comment, as `ci.yml` does.

### Xcode and Simulators

- `XCODE_VERSION` is `latest-stable`, so a new runner image can bring a new Xcode with no change in this repository. The **Report selected Xcode toolchain** step logs which Xcode was selected and which ones the image ships.
- `test-ios` asks for `iPhone 17`, then `iPhone 17 Pro`, then `iPhone Air`, in the **Run iOS unit tests** step. When the newest runtime has none of them, `pick-ios-simulator.mjs` takes another iPhone on that runtime, so an image rotation cannot leave the job without a device. Update the names when the runner images move to new devices.

### Rotating the npm Token

`NPM_TOKEN` expires after the lifetime you set on npm. When it does, the **Publish to NPM** step fails:

1. Create a new granular access token, as in step 1 of the [Setup Guide](#setup-guide).
2. Update the `NPM_TOKEN` repository secret.
3. Re-run the failed run. Nothing was published, so the version guard lets it through.
