# Sponsor this project

## Support `@gabriel-sisjr/react-native-sync-provider`

**react-native-sync-provider** is an open-source React Native library that provides a native, offline-first HTTP sync queue with persistent retry and background dispatch -- the queue keeps working even after the app is force-quit or the device reboots. Built on [Nitro Modules](https://nitro.margelo.com/) for the New Architecture, it's designed for production apps that cannot afford to drop user actions on flaky networks.

## About the project

This library offers:

- **Offline-first HTTP queue** -- enqueue requests once, the native layer persists, retries, and dispatches them
- **Survives app death** -- queue items are persisted to disk and resumed on next launch
- **Background dispatch** -- iOS `BGTaskScheduler` + `URLSession.background`, Android `WorkManager`, even when the app is killed
- **Smart retry policies** -- exponential / linear / fixed backoff with jitter, status-code allowlists, and configurable max attempts
- **Connectivity awareness** -- pauses on disconnect, resumes on reconnect, optional metered-network gating
- **Idempotency by default** -- ULID per item to make at-least-once delivery safe
- **First-class TypeScript** -- full TSDoc, strict types, discriminated `SyncErrorCode` enum
- **New Architecture only** -- Nitro Modules + Fabric, no legacy bridge

## Why sponsor?

By sponsoring this project, you are:

- Supporting continuous development of an offline-sync primitive that the React Native community is missing
- Accelerating native parity (iOS Swift + Android Kotlin) and ecosystem integrations
- Enabling improvements such as conflict resolution helpers, multipart uploads, and observability hooks
- Ensuring quick bug fixes and responsive community support
- Improving documentation and creating more guides, examples, and migration playbooks
- Maintaining high quality through comprehensive tests and CI/CD across both platforms

## How sponsorships are used

- **Active development** -- new features, performance work, and platform parity
- **Maintenance** -- bug fixes and React Native / Nitro compatibility updates
- **Documentation** -- Docusaurus site, API reference, advanced guides, migration trail
- **Infrastructure** -- CI/CD costs, release tooling, Docusaurus hosting
- **Support** -- triaging issues and helping integrators in production

## How to become a sponsor

You can become a sponsor through [GitHub Sponsors](https://github.com/sponsors/gabriel-sisjr).

Click the **Sponsor** button at the top of the repo or visit:

**<https://github.com/sponsors/gabriel-sisjr>**

Other sponsorship channels (Open Collective, direct invoicing, etc.) can be added on request -- open a discussion if your organization needs an alternative.

## Other ways to contribute

Can't sponsor financially? You can still help:

- **Star** the repository
- **Report bugs** with reproducible examples
- **Contribute code** -- pull requests are welcome (see [`CONTRIBUTING.md`](./CONTRIBUTING.md))
- **Improve documentation** -- fixes, examples, and translations
- **Share** the project with other React Native developers
- **Test** prereleases and provide feedback on the API surface

## Acknowledgments

Thank you to every sponsor and contributor who makes maintaining offline-sync infrastructure for the React Native community possible. Your support is what keeps this project moving.

---

_For more information about the project, see the [README](README.md) and the [full documentation](https://gabriel-sisjr.github.io/react-native-sync-provider)._
