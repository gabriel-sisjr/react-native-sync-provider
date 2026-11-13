---
sidebar_position: 4
title: Publishing
description: How @gabriel-sisjr/react-native-sync-provider is released to npm through pull requests and CI. Covers stable releases, the version guard, pre-releases on the beta dist-tag, the package contents, the changelog and breaking-change records, and release troubleshooting.
keywords:
  - publishing
  - npm
  - release
  - versioning
  - semver
  - provenance
  - react-native
  - react-native-sync-provider
---

# Publishing

This guide explains how `@gabriel-sisjr/react-native-sync-provider` is released to npm.

---

## Automated Publishing

Releases go through pull requests, and CI does the publishing. A bump PR sets the version on `develop`. When that version reaches `main`, `publish.yml` publishes it to npm, tags it and creates the GitHub release. Pre-releases come from the `prerelease.yml` workflow. For the workflows themselves, their setup and their troubleshooting, see the [CI/CD Pipeline guide](./ci-cd.md).

> **Warning — Publish only through CI:** Do not run `npm publish` yourself. All publishing happens in CI so that every release carries provenance metadata.
>
> `yarn release` and `yarn release:beta` are not part of this flow either. They run release-it, which proposes the next version from the commit history, then commits, tags and pushes straight to the branch, and publishes nothing to npm (`npm.publish` is `false`). The branch ruleset rejects that push; with a bypass, an unreviewed commit and tag land, and `publish.yml` then fails at `git tag -a` because the tag already exists.

### Quick Overview

Three workflows take part in a release:

1. **CI** -- Validates every push and pull request to `develop` and `main`: lint, type check, unit tests, the package build, both example apps and both native test suites
2. **Publish to NPM** -- Publishes the version from `main` when it changed
3. **Pre-release to NPM** -- Publishes a pre-release under the `beta` dist-tag, on a manual trigger

**Deploy Documentation** also runs on the release push when it changes `website/**`.

### Branch Strategy

| Branch | npm Tag | Usage |
| --- | --- | --- |
| `main` | `latest` | Stable releases, published by `publish.yml` on a push that changes the version |
| `develop` | `beta` | Pre-releases, published by `prerelease.yml` on a manual run (`develop` by default, any branch on request) |

### Required Setup

1. **npm token** -- A granular access token with `Read and write` permission for the package
2. **GitHub secret** -- `NPM_TOKEN` in the repository's Actions secrets; `CODECOV_TOKEN` is optional
3. **GitHub Pages** -- Source set to **GitHub Actions**, for the docs deploy

See [CI/CD Pipeline > Setup Guide](./ci-cd.md#setup-guide) for step-by-step instructions.

---

## Stable Release

1. **Bump PR.** On a branch from `develop`:

   - set `version` in `package.json`;
   - in `CHANGELOG.md`, turn `## [Unreleased]` into `## [x.y.z] - YYYY-MM-DD`;
   - in `BREAKING_CHANGES.md`, when it has an `## [Unreleased]` entry, turn that heading into the entry heading from the template at the top of the file, `## [vX.Y.0] - YYYY-MM-DD - One-line summary`;
   - regenerate `example/ios/Podfile.lock`, because the podspec reads its version from `package.json`.

   ```bash
   yarn nitrogen
   cd example
   bundle install
   RCT_USE_RN_DEP=1 RCT_USE_PREBUILT_RNCORE=1 bundle exec pod install --project-directory=ios
   ```

   Commit as `chore(version): bump vx.y.z` and open the PR to `develop`.

2. **Release PR.** Open a PR from `develop` to `main` titled `Release - vx.y.z`, and merge it with a merge commit through the ruleset bypass, so each commit stays individually revertible.

3. **Publish.** Merging it pushes to `main`, which runs `publish.yml`. The workflow:

   1. compares the `package.json` version with the `latest` dist-tag and stops if they are equal (see [The Version Guard](#the-version-guard));
   2. runs `yarn nitrogen`, `yarn lint`, `yarn typecheck`, `yarn prepare` and `yarn test`, then asserts the `npm pack` contents;
   3. runs `npm publish --access public --provenance`, which moves `latest`. The `--provenance` flag attaches a signed attestation linking the tarball to this commit and workflow run;
   4. creates and pushes the annotated tag `v<version>` as `github-actions[bot]`;
   5. creates the GitHub release;
   6. comments on PRs merged into `main` in the last five minutes.

   On the same push, `deploy-docs.yml` redeploys the docs site if `website/**` changed.

> **Note:** `publish.yml` creates the `v<version>` tag itself. Do not create or push that tag by hand: the **Create Git Tag** step fails when the tag already exists.

---

## The Version Guard

The **Check if version changed** step of `publish.yml` decides whether anything is published:

```yaml
# .github/workflows/publish.yml
- name: Check if version changed
  id: version-check
  run: |
    CURRENT_VERSION=$(node -p "require('./package.json').version")

    # Get the last published version from npm
    PUBLISHED_VERSION=$(npm view @gabriel-sisjr/react-native-sync-provider version 2>/dev/null || echo "0.0.0")

    echo "Current version: $CURRENT_VERSION"
    echo "Published version: $PUBLISHED_VERSION"

    if [ "$CURRENT_VERSION" = "$PUBLISHED_VERSION" ]; then
      echo "version_changed=false" >> $GITHUB_OUTPUT
      echo "Version has not changed. Skipping publish."
    else
      echo "version_changed=true" >> $GITHUB_OUTPUT
      echo "New version detected. Proceeding with publish."
    fi
```

`npm view <package> version` returns the `latest` dist-tag, not the highest published version, and the test is string inequality, not "greater than": any version that differs from `latest`, even a lower one, is published. The bump PR is what decides the version. When `npm view` fails, the step falls back to `0.0.0` and the run continues; if that version is in fact already published, `npm publish` refuses it.

Every later step runs only when `version_changed == 'true'`, except the coverage upload, Codecov and the coverage summary, which run `if: always()`.

- **Do not push to `main` again until the run finishes.** `publish.yml` sets `cancel-in-progress: true`, so a second push can cancel a run that is mid-publish.
- **A re-run after a successful publish skips the tag and the release.** There is no `workflow_dispatch` trigger, but a finished run can be re-run from the **Actions** tab. If the run failed after **Publish to NPM**, the version is already `latest`, so the guard skips the remaining steps: create the tag and the GitHub release by hand (see [Troubleshooting](#troubleshooting)).
- **The GitHub release notes can be nearly empty.** They list the commits between the two highest tags. `CHANGELOG.md` is the record of what changed.

---

## Pre-Releases

Pre-releases come only from `prerelease.yml`: **Actions → Pre-release to NPM → Run workflow**. Its inputs are `branch` (default `develop`), an optional `version_suffix`, and an optional `description` for the GitHub pre-release. The [CI/CD Pipeline guide](./ci-cd.md) lists every step of the run.

1. **Bump first when needed.** A pre-release of a version that already shipped sorts below it (`0.2.0-rc1 < 0.2.0`). Bump the version first, then pre-release it.
2. **Run the workflow.**
   - Under **Use workflow from**, choose the branch to release.
   - Leave **Branch to release from** at `develop`, or set it to that same branch. The checkout uses this input, but the commit hash in a generated version, the `Commit:` line of the GitHub pre-release and the npm provenance come from the branch chosen in **Use workflow from**.
   - Optional: a version suffix, for example `rc1`, and a description.
3. **Install it.**

   ```bash
   yarn add @gabriel-sisjr/react-native-sync-provider@beta
   # or
   npm install @gabriel-sisjr/react-native-sync-provider@beta
   ```

What the run does:

- The version is `<base>-<suffix>`, where `<base>` is the `package.json` version without any pre-release part. With `0.2.0` and `rc1` it gives `0.2.0-rc1`. An empty suffix gives `<base>-beta.<UTC timestamp>.<sha7>`.
- It publishes with `npm publish --access public --tag beta --provenance`, so `rc` versions land on `beta` too.
- It runs `yarn nitrogen`, `yarn prepare`, `yarn test` and the `npm pack` assertion, but not `yarn lint` or `yarn typecheck`.
- It commits `package.json` inside the runner but pushes only the tag `v<version>`, so the tag points at a commit that is on no branch. It then creates a GitHub pre-release.
- npm refuses a version that already exists, so a custom suffix must be new on every run. A generated suffix changes on every run.

> **Note — Dist-tags:** Consumers opt in to a pre-release with `yarn add @gabriel-sisjr/react-native-sync-provider@beta`; a plain `yarn add` follows `latest`. In this flow only `publish.yml` moves `latest`. The one exception is a package's first publish: npm sets `latest` then, whatever `--tag` says, which is how `0.1.0-beta` became `latest`.

---

## What Gets Published

The `files` field in `package.json` controls what goes into the npm package:

| Entry | Contents |
| --- | --- |
| `src` | The TypeScript sources, which the `source` export condition points at |
| `lib` | The ESM build (`lib/module`) and the type declarations (`lib/typescript`), from `yarn prepare` |
| `android` | The Android library: sources and the Gradle and CMake build files |
| `ios` | The iOS sources and the Core Data model |
| `nitrogen` | The generated Nitro bindings. `SyncProvider.podspec` and `android/build.gradle` load them, so an app builds without running Nitrogen |
| `nitro.json` | The Nitro configuration |
| `*.podspec` | `SyncProvider.podspec` |
| `cpp`, `react-native.config.js` | Named in `files`, but the repository has neither, so nothing is packed from them |

Excluded: `ios/build`, `android/build`, `android/gradle`, `android/gradlew`, `android/gradlew.bat`, `android/local.properties`, the native test sources (`ios/Tests`, `android/src/test`, `android/src/androidTest`), every `__tests__`, `__fixtures__` and `__mocks__` folder, and every dotfile (`!**/.*`). One dotfile is added back: `ios/Database/SyncProvider.xcdatamodeld/.xccurrentversion`, which names the current Core Data model version.

### Verify Package Contents

`build-library`, `publish.yml` and `prerelease.yml` run the same check before anything is published. This is the command they run; run it the same way from the repository root after `yarn prepare`:

```bash
npm pack --dry-run --ignore-scripts --json | node .github/scripts/assert-pack-contents.mjs
```

The script fails when:

- `.xccurrentversion`, or the `contents` file of any model version, is missing from the tarball. Without the marker, `momc` only warns and exits 0, and the `.momd` it compiles declares no current version, so a consumer's build would not catch the defect;
- `.xccurrentversion` does not name the newest model version;
- the tarball would ship native tests (`ios/Tests/`, `android/src/test/`, `android/src/androidTest/`).

On success it prints the file count, the current model and the number of model versions. `build-library` also writes the packed size, unpacked size and file count of the tarball to its job summary.

---

## Pre-Publish Checklist

- [ ] The JS gate passes: `yarn typecheck && yarn lint && yarn test`
- [ ] The Android gate passes when `android/**` or `src/SyncProvider.nitro.ts` changed: `yarn nitrogen`, `yarn turbo run build:android` and the Gradle unit tests
- [ ] The iOS gate passes when `ios/**` or `src/SyncProvider.nitro.ts` changed: `pod install`, then `xcodebuild test` with the scheme `SyncProvider-Unit-Tests`
- [ ] The `version` in `package.json` is higher than the `latest` dist-tag: the version guard publishes any version that differs from it, even a lower one
- [ ] `CHANGELOG.md` has `## [x.y.z] - YYYY-MM-DD` in place of `## [Unreleased]`
- [ ] `BREAKING_CHANGES.md` has the template heading in place of its `[Unreleased]` entry, when it has one
- [ ] Every breaking or behavior change has a migration page under `website/docs/migration/`
- [ ] `example/ios/Podfile.lock` is regenerated
- [ ] The bump commit reads `chore(version): bump vx.y.z`
- [ ] CI is green on the release PR

---

## Post-Publish Verification

1. **The run published.** In the `publish.yml` run, **Publish to NPM** ran instead of being skipped.
2. **npm lists the version under `latest`:**

   ```bash
   yarn npm info @gabriel-sisjr/react-native-sync-provider --fields dist-tags
   ```

3. **The tag and the release exist.** Look for the tag `v<version>` and the GitHub release `Release v<version>`. If the run failed after **Publish to NPM**, create both by hand (see [Troubleshooting](#troubleshooting)).
4. **The docs deployed**, when the release changed `website/**`: the **Deploy Documentation** run finished.

---

## Versioning Guidelines

The project follows [Semantic Versioning](https://semver.org/). While the library is on `0.x`, minor version bumps (`0.x.0`) may include breaking changes.

| Change | Bump | Example |
| --- | --- | --- |
| Bug fixes, no API change | Patch | `0.2.0` to `0.2.1` |
| New features | Minor | `0.2.1` to `0.3.0` |
| Breaking changes, while on `0.x` | Minor | `0.1.1` to `0.2.0` |

The bump PR decides the version. Nothing derives it from the commits.

### Conventional Commits

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/), checked on every commit by the `commit-msg` hook (lefthook running commitlint with `@commitlint/config-conventional`). They still shape PR titles, but they no longer decide the version or the changelog. See [Contributing](./contributing.md) for the commit types.

The release flow fixes one message, the bump commit's:

```bash
git commit -m "chore(version): bump v0.3.0"
```

---

## Changelog and Breaking Changes

[`CHANGELOG.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/CHANGELOG.md) is curated by hand in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format; no tool writes to it.

- Add your entry under `## [Unreleased]` at the top, in the PR that makes the change, and create that section if there is none.
- The bump PR turns it into `## [x.y.z] - YYYY-MM-DD`.
- A breaking or behavior change also needs an entry in [`BREAKING_CHANGES.md`](https://github.com/gabriel-sisjr/react-native-sync-provider/blob/develop/BREAKING_CHANGES.md), following the template at its top, and a migration page under `website/docs/migration/` (see [Migration Guides](../migration/index.md)).

The GitHub release does not read `CHANGELOG.md`: its notes list commit subjects. The changelog is the record of what changed.

---

## Troubleshooting

### `npm publish --provenance` fails with an OIDC or `id-token` error

**Cause.** The `publish` job is missing `permissions: id-token: write`, which `--provenance` requires.

**Fix.**

1. Restore the `permissions:` block at the top of the job in `.github/workflows/publish.yml`. The `prerelease` job in `prerelease.yml` needs the same block.

### `npm publish` returns `403 Forbidden`

**Cause.** `NPM_TOKEN` expired, was revoked, or cannot write to the package.

**Fix.**

1. Create a granular access token with `Read and write` permission for the package.
2. Update the `NPM_TOKEN` repository secret.
3. Re-run the failed run.

### `publish.yml` runs but skips the publish step (`version_changed=false`)

**Cause.** The version in `package.json` equals the `latest` dist-tag, so the version guard short-circuits on purpose.

**Fix.**

1. Open a bump PR. Do not patch the workflow to bypass the guard.

### A re-run of a failed `publish.yml` skips the tag and the release

**Cause.** The failed run had already published to npm, so the version is `latest` and the guard skips the remaining steps.

**Fix.**

1. Create the annotated tag on the published commit and push it, the way the workflow does:

   ```bash
   git tag -a vX.Y.Z <published-commit-sha> -m "Release vX.Y.Z"
   git push origin vX.Y.Z
   ```

2. Create the GitHub release `Release vX.Y.Z` for that tag by hand.

### `publish.yml` fails at "Create Git Tag" with `tag 'v<version>' already exists`

**Cause.** The tag was pushed outside the workflow, for example by release-it. The package is already on npm.

**Fix.**

1. Check that the tag points at the published commit.
2. Create the GitHub release by hand if there is none.

### `prerelease.yml` cannot find the branch

**Cause.** The `branch` input does not exist on the remote, or the workflow is dispatched from a fork.

**Fix.**

1. Push the branch to the canonical remote. Releases never come from forks.
2. Re-run the dispatch.

### Other workflow failures

For failed CI jobs, a failed docs deploy and link check failures, see [CI/CD Pipeline > Monitoring and Troubleshooting](./ci-cd.md#monitoring-and-troubleshooting).
