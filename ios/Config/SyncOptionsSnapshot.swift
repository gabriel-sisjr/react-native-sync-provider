//
//  SyncOptionsSnapshot.swift
//  SyncProvider
//
//  Sendable mirror of the Nitro `SyncOptions` struct, so the engine and the
//  actors below it never touch generated C++ types.
//

import Foundation

/// Where the active configuration came from.
enum ConfigSource: String, Sendable {
    /// Nothing was ever configured on this install.
    case defaults = "DEFAULTS"
    /// Read back from disk when the engine started. The app has not spoken in
    /// this process, so a persisted credential may be stale.
    case persisted = "PERSISTED"
    /// Set by `configureSync` in this process.
    case live = "LIVE"
}

/// Sendable mirror of `SyncOptions`.
struct SyncOptionsSnapshot: Sendable, Equatable {
    var strategy: String
    var retryPolicy: RetryPolicyValue
    var batchSize: Double
    var requestTimeoutMs: Double
    var maxQueueSize: Double
    var persistQueue: Bool
    var defaultHeaders: [String: String]

    static let `default` = SyncOptionsSnapshot(
        strategy: "AUTOMATIC",
        retryPolicy: .default,
        batchSize: 25,
        requestTimeoutMs: 30_000,
        maxQueueSize: Double(kDefaultQueueCapacity),
        persistQueue: true,
        defaultHeaders: [:]
    )

    /// `true` when cooldowns must not wake the engine on their own.
    var isManual: Bool {
        strategy == "MANUAL"
    }

    func toDispatcherConfig(source: ConfigSource = .live,
                            credentialsUnavailable: Bool = false) -> DispatcherConfig {
        DispatcherConfig(retryPolicy: retryPolicy,
                         requestTimeoutMs: Int(requestTimeoutMs),
                         batchSize: Int(batchSize),
                         defaultHeaders: defaultHeaders,
                         source: source,
                         hasDefaultHeaders: !defaultHeaders.isEmpty || credentialsUnavailable,
                         credentialsUnavailable: credentialsUnavailable)
    }
}
