//
//  SyncOptionsSnapshot.swift
//  SyncProvider
//
//  Sendable mirror of the Nitro `SyncOptions` struct, so the engine and the
//  actors below it never touch generated C++ types.
//

import Foundation

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

    func toDispatcherConfig() -> DispatcherConfig {
        DispatcherConfig(retryPolicy: retryPolicy,
                         requestTimeoutMs: Int(requestTimeoutMs),
                         batchSize: Int(batchSize),
                         defaultHeaders: defaultHeaders)
    }
}
