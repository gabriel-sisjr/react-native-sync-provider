//
//  SyncConfigStore.swift
//  SyncProvider
//
//  Keeps the configuration available to a process the app never spoke to: a
//  BGTask launch runs with no JS, yet must honor the retry policy, timeouts
//  and queue cap the app set, and send the headers it configured.
//

import Foundation

/// The configuration as found when the engine starts.
struct LoadedSyncConfig: Sendable {
    let options: SyncOptionsSnapshot
    let source: ConfigSource
    /// Headers are declared but could not be read. Dispatch must wait.
    let credentialsUnavailable: Bool
    let paused: Bool
}

/// The non-secret half of `SyncOptions`. `defaultHeaders` is reduced to a flag.
private struct PersistedSyncRecord: Codable {
    var strategy: String
    var maxAttempts: Int
    var backoff: String
    var baseDelayMs: Int
    var maxDelayMs: Int
    var jitter: Bool
    var retryOnStatusCodes: [Int]
    var batchSize: Double
    var requestTimeoutMs: Double
    var maxQueueSize: Double
    var persistQueue: Bool
    var hasDefaultHeaders: Bool

    init(_ options: SyncOptionsSnapshot) {
        strategy = options.strategy
        maxAttempts = options.retryPolicy.maxAttempts
        backoff = options.retryPolicy.backoff.rawValue
        baseDelayMs = options.retryPolicy.baseDelayMs
        maxDelayMs = options.retryPolicy.maxDelayMs
        jitter = options.retryPolicy.jitter
        retryOnStatusCodes = options.retryPolicy.retryOnStatusCodes
        batchSize = options.batchSize
        requestTimeoutMs = options.requestTimeoutMs
        maxQueueSize = options.maxQueueSize
        persistQueue = options.persistQueue
        hasDefaultHeaders = !options.defaultHeaders.isEmpty
    }

    var options: SyncOptionsSnapshot {
        SyncOptionsSnapshot(
            strategy: strategy,
            retryPolicy: RetryPolicyValue(
                maxAttempts: maxAttempts,
                backoff: RetryPolicyValue.Backoff(rawValue: backoff) ?? .exponential,
                baseDelayMs: baseDelayMs,
                maxDelayMs: maxDelayMs,
                jitter: jitter,
                retryOnStatusCodes: retryOnStatusCodes
            ),
            batchSize: batchSize,
            requestTimeoutMs: requestTimeoutMs,
            maxQueueSize: maxQueueSize,
            persistQueue: persistQueue,
            defaultHeaders: [:]
        )
    }
}

/// Reads and writes the configuration: UserDefaults for everything except
/// `defaultHeaders`, a ``SecretStore`` for those.
final class SyncConfigStore: @unchecked Sendable {
    private enum Key {
        static let record = "config.v1"
        static let paused = "paused"
    }

    private let defaults: UserDefaults
    private let secrets: SecretStore

    init(defaults: UserDefaults = .syncProvider, secrets: SecretStore = KeychainSecretStore()) {
        self.defaults = defaults
        self.secrets = secrets
    }

    /// Reads the configuration for a starting engine.
    func load() -> LoadedSyncConfig {
        let paused = defaults.bool(forKey: Key.paused)

        guard let data = defaults.data(forKey: Key.record) else {
            discardOrphanedSecret()
            return LoadedSyncConfig(options: .default,
                                    source: .defaults,
                                    credentialsUnavailable: false,
                                    paused: paused)
        }

        guard let record = try? JSONDecoder().decode(PersistedSyncRecord.self, from: data) else {
            SyncLogger.error("Stored configuration is unreadable; using defaults", category: "general")
            return LoadedSyncConfig(options: .default,
                                    source: .defaults,
                                    credentialsUnavailable: false,
                                    paused: paused)
        }

        var options = record.options
        var credentialsUnavailable = false
        if record.hasDefaultHeaders {
            if let headers = readDefaultHeaders() {
                options.defaultHeaders = headers
            } else {
                credentialsUnavailable = true
                SyncLogger.error("Default headers are declared but could not be read",
                                 category: "general")
            }
        }

        return LoadedSyncConfig(options: options,
                                source: .persisted,
                                credentialsUnavailable: credentialsUnavailable,
                                paused: paused)
    }

    /// Persists a configuration. Without default headers the stored secret is
    /// deleted: this is what a logout looks like from here.
    ///
    /// A Keychain failure is logged, not thrown. The configuration still
    /// applies to this process, and the record keeps declaring the headers, so
    /// a later cold start skips dispatch instead of sending without them.
    func save(_ options: SyncOptionsSnapshot) {
        do {
            if options.defaultHeaders.isEmpty {
                try secrets.deleteDefaultHeaders()
            } else {
                try secrets.writeDefaultHeaders(options.defaultHeaders)
            }
        } catch {
            SyncLogger.error("Could not update the stored default headers: \(error.localizedDescription)",
                             category: "general")
        }

        do {
            let data = try JSONEncoder().encode(PersistedSyncRecord(options))
            defaults.set(data, forKey: Key.record)
        } catch {
            SyncLogger.error("Could not persist the configuration: \(error.localizedDescription)",
                             category: "general")
        }
    }

    func setPaused(_ paused: Bool) {
        defaults.set(paused, forKey: Key.paused)
    }

    /// The stored headers, or `nil` when they are missing or unreadable.
    func readDefaultHeaders() -> [String: String]? {
        if case .found(let headers) = secrets.readDefaultHeaders() {
            return headers
        }
        return nil
    }

    /// Keychain items survive an uninstall; UserDefaults do not. Headers with
    /// no record belong to a previous install, so they are removed. A read
    /// that succeeds also proves the device is unlocked, which is what makes
    /// the missing record meaningful: before first unlock neither is readable.
    private func discardOrphanedSecret() {
        guard case .found = secrets.readDefaultHeaders() else { return }
        do {
            try secrets.deleteDefaultHeaders()
        } catch {
            SyncLogger.error("Could not remove orphaned default headers: \(error.localizedDescription)",
                             category: "general")
        }
    }
}
