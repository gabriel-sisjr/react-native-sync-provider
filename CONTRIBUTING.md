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

### Releasing

Releases go through pull requests, and CI does the publishing. A bump PR sets the version on `develop`. When that version reaches `main`, `publish.yml` publishes it to npm, tags it and creates the GitHub release. Pre-releases come from the `prerelease.yml` workflow.

> **`yarn release` and `yarn release:beta` are not part of this flow.** They run release-it, which picks the next version on its own (from `0.2.0` it proposes `0.3.0`), commits, tags and pushes straight to the branch, and publishes nothing to npm. The branch ruleset rejects that push; with a bypass, an unreviewed commit and tag land, and `publish.yml` then fails at `git tag -a` because the tag already exists.

#### Prerequisites

Before cutting a release, make sure you have:

- **Maintainer or admin rights** on the GitHub repository, to merge into `develop` and `main` and to run `prerelease.yml`. Both branches take changes only through pull requests, and the ruleset enforces one approving review, squash merges and linear history by default. Maintainers use the ruleset bypass to merge with a merge commit when a PR's commits must stay individually revertible, as on the release PR.
- **Node** matching `.nvmrc` (v24.13.0), **Yarn 4.11.0** (Berry), and Ruby with Bundler for `bundle exec pod install` in `example/`.
- An **npm account** with publish access to `@gabriel-sisjr/react-native-sync-provider`, to create the `NPM_TOKEN` secret.

#### Repository secrets

The release workflows require the following secrets to be configured in **Settings → Secrets and variables → Actions**:

| Secret          | Required | How to create                                                                                                                                                                                                                                                                                                                                          | Used by                                                                                                   |
| --------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------- |
| `NPM_TOKEN`     | yes      | On [npmjs.com → Access Tokens](https://docs.npmjs.com/creating-and-viewing-access-tokens), create a **Granular Access Token** with `Read and write` permission for the package, and paste it into the GitHub secret. The workflows write it to `~/.npmrc`. Provenance needs no token scope: it comes from the job's OIDC `id-token: write` permission. | `publish.yml`, `prerelease.yml`                                                                           |
| `CODECOV_TOKEN` | optional | Generated by [Codecov](https://about.codecov.io/) after adding the repo. Only the release workflows upload coverage to Codecov; `ci.yml` uploads nothing. A failed upload does not fail the run.                                                                                                                                                       | `publish.yml`, `prerelease.yml` (coverage)                                                                |
| `GITHUB_TOKEN`  | auto     | Provided automatically by GitHub Actions. No manual setup required.                                                                                                                                                                                                                                                                                    | `publish.yml`, `prerelease.yml` (tag push, GitHub release, PR comments), `deploy-docs.yml` (Pages deploy) |

> **Token rotation:** `NPM_TOKEN` expires after the lifetime you set on npm. When it does, the "Publish to NPM" step fails: regenerate the token, update the secret, and re-run the failed run.

#### GitHub Pages prerequisite

`deploy-docs.yml` deploys the Docusaurus site using the official [`actions/deploy-pages@v4`](https://github.com/actions/deploy-pages) action. This requires the repository to be configured with Pages backed by Actions (not by a branch).

In **Settings → Pages**:

1. Under **Build and deployment → Source**, select **GitHub Actions**.
2. Leave the custom domain empty unless you have one configured.
3. Save. The first successful run of `deploy-docs.yml` will publish to `https://gabriel-sisjr.github.io/react-native-sync-provider/`.

> If Pages is still set to "Deploy from a branch", `deploy-docs.yml` will fail at the deploy step with `Error: Get Pages site failed`. Switching the source to **GitHub Actions** fixes it.

#### Stable release

1. **Bump PR.** On a branch from `develop`:
   - set `version` in `package.json`;
   - in `CHANGELOG.md`, turn `## [Unreleased]` into `## [x.y.z] - YYYY-MM-DD`;
   - in `BREAKING_CHANGES.md`, turn `## [Unreleased]` into the entry heading from the template at the top of that file;
   - regenerate `example/ios/Podfile.lock`, because the podspec reads its version from `package.json`.

   ```sh
   yarn nitrogen
   cd example
   bundle install
   RCT_USE_RN_DEP=1 RCT_USE_PREBUILT_RNCORE=1 bundle exec pod install --project-directory=ios
   ```

   Commit as `chore(version): bump vx.y.z` and open the PR to `develop`.

2. **Release PR.** Open a PR from `develop` to `main` titled `Release - vx.y.z`, and merge it with a merge commit through the ruleset bypass, so each commit stays individually revertible.
3. **Publish.** Merging it pushes to `main`, which runs `publish.yml`. The workflow:
   1. compares the `package.json` version with the `latest` dist-tag and stops if they are equal (see [The version guard](#the-version-guard));
   2. runs `yarn nitrogen`, `yarn lint`, `yarn typecheck`, `yarn prepare` and `yarn test`, then asserts the `npm pack` contents;
   3. runs `npm publish --access public --provenance`, which moves `latest`. The `--provenance` flag attaches a signed attestation linking the tarball to this commit and workflow run;
   4. creates and pushes the annotated tag `v<version>` as `github-actions[bot]`;
   5. creates the GitHub release;
   6. comments on PRs merged into `main` in the last five minutes.

   On the same push, `deploy-docs.yml` redeploys the docs site if `website/**` changed.

> **Do not run `npm publish` yourself.** All publishing happens in CI so that every release carries provenance metadata.

#### The version guard

The `Check if version changed` step of `publish.yml` decides whether anything is published:

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

`npm view <package> version` returns the `latest` dist-tag, not the highest published version, and the test is string inequality, not "greater than": any version that differs from `latest`, even a lower one, is published. The bump PR is what decides the version. Every later step runs only when `version_changed == 'true'`, except the coverage upload, Codecov and the coverage summary, which run `if: always()`.

- **Do not push to `main` again until the run finishes.** `publish.yml` sets `cancel-in-progress: true`, so a second push can cancel a run that is mid-publish.
- **A re-run after a successful publish skips the tag and the release.** There is no `workflow_dispatch` trigger, but a finished run can be re-run from the Actions tab. If the run failed after "Publish to NPM", the version is already `latest`, so the guard skips the remaining steps: create the tag and the GitHub release by hand.
- **The GitHub release notes can be nearly empty.** They list the commits between the two highest tags. `CHANGELOG.md` is the record of what changed.

#### Pre-releases

Pre-releases come only from `prerelease.yml`: **Actions → Pre-release to NPM → Run workflow**. Its inputs are `branch` (default `develop`), an optional `version_suffix`, and an optional `description` for the GitHub pre-release.

- The version is `<base>-<suffix>`, where `<base>` is the `package.json` version without any pre-release part. With `0.2.0` and `rc1` it gives `0.2.0-rc1`. An empty suffix gives `<base>-beta.<UTC timestamp>.<sha7>`.
- It publishes with `npm publish --access public --tag beta --provenance`, so `rc` versions land on `beta` too.
- It runs `yarn nitrogen`, `yarn prepare`, `yarn test` and the `npm pack` assertion, but not `yarn lint` or `yarn typecheck`.
- It commits `package.json` inside the runner but pushes only the tag `v<version>`, so the tag points at a commit that is on no branch. It then creates a GitHub pre-release.
- A pre-release of a version that already shipped sorts below it (`0.2.0-rc1 < 0.2.0`). Bump the version first, then pre-release it.

> **Dist-tags.** Consumers opt in to a pre-release with `yarn add @gabriel-sisjr/react-native-sync-provider@beta`; a plain `yarn add` follows `latest`. In this flow only `publish.yml` moves `latest`. The one exception is a package's first publish: npm sets `latest` then, whatever `--tag` says, which is how `0.1.0-beta` became `latest`.

#### Changelog

`CHANGELOG.md` is curated by hand in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) format; no tool writes to it. Add your entry under `## [Unreleased]` at the top, in the PR that makes the change, and create that section if there is none. The bump PR turns it into `## [x.y.z] - YYYY-MM-DD`. A breaking or behavior change also needs an entry in [`BREAKING_CHANGES.md`](./BREAKING_CHANGES.md), following the template at its top, and a section in the migration guide under `website/docs/migration/`.

Conventional commits are still enforced on every commit by the `commit-msg` hook (lefthook running commitlint) and still shape PR titles, but they no longer decide the version or the changelog.

#### Docs deploy

`deploy-docs.yml` runs on:

- push to `main` that touches `website/**` (production deploy)
- `workflow_dispatch` (manual rebuild — useful when changing docs-only repo settings)

The build runs `yarn docs:build` and uploads the `website/build/` directory as a Pages artifact. The deploy job consumes the artifact and publishes to GitHub Pages.

> Pushes that touch only `src/**` do **not** redeploy the site. If you change a TSDoc block that is surfaced on the docs site, edit the corresponding `website/docs/**/*.md` page in the same PR.

#### Link check

`docs-link-check.yml` runs on push and PR to `main` and `develop` when a `**/*.md` file, `lychee.toml` or the workflow itself changes, and on `workflow_dispatch`. It uses [lychee](https://github.com/lycheeverse/lychee) in two passes:

1. **Internal links and anchors** — fail-blocking. A broken relative link or missing heading anchor fails the workflow.
2. **External links** — warning only. Flaky third-party sites do not block PRs but are reported in the run summary.

It checks the `.md` sources, not the built site, so `yarn docs:build` does not reproduce it. To run the blocking pass locally:

```sh
lychee --offline --include-fragments --no-progress --config ./lychee.toml './**/*.md'
```

> There is no cron. Link rot from upstream sites surfaces the next time someone touches docs, which keeps the signal scoped to relevant changes.

#### Troubleshooting

| Symptom                                                                          | Likely cause                                                                                                     | Fix                                                                                                                                                   |
| -------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| `npm publish --provenance` fails with an OIDC or `id-token` error                | The `publish` job is missing `permissions: id-token: write`, which `--provenance` requires.                      | Restore the `permissions:` block at the top of the job in `.github/workflows/publish.yml`.                                                            |
| `npm publish` returns `403 Forbidden`                                            | `NPM_TOKEN` expired, was revoked, or cannot write to the package.                                                | Create a granular access token with `Read and write` permission for the package, update the `NPM_TOKEN` repository secret, and re-run the failed run. |
| `publish.yml` runs but skips the publish step (`version_changed=false`)          | The version in `package.json` equals the `latest` dist-tag, so the version guard short-circuits on purpose.      | Open a bump PR. Do not patch the workflow to bypass the guard.                                                                                        |
| A re-run of a failed `publish.yml` skips the tag and the release                 | The failed run had already published to npm, so the version is `latest` and the guard skips the remaining steps. | Create the annotated tag `v<version>` on the published commit, and the GitHub release, by hand.                                                       |
| `publish.yml` fails at "Create Git Tag" with `tag 'v<version>' already exists`   | The tag was pushed outside the workflow, for example by release-it. The package is already on npm.               | Check that the tag points at the published commit, then create the GitHub release by hand if there is none.                                           |
| `deploy-docs.yml` fails at `Deploy to GitHub Pages` with `Get Pages site failed` | Pages source is still set to "Deploy from a branch".                                                             | Switch **Settings → Pages → Source** to **GitHub Actions** and rerun the workflow.                                                                    |
| `docs-link-check.yml` fails on an anchor that the docs site has                  | lychee checks anchors against the `.md` source headings, not the Docusaurus build.                               | Reproduce it with the command under [Link check](#link-check), and link to the anchor lychee derives from the source heading.                         |
| `prerelease.yml` cannot find the branch                                          | The `branch` input does not exist on the remote, or the workflow is dispatched from a fork.                      | Push the branch to the canonical remote (no forks for releases) and re-run the dispatch.                                                              |

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
