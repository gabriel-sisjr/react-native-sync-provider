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

    // The network-health options keep `nil` for "not set": `getSyncConfig()`
    // echoes them only when they were set, so defaults are filled in at use.

    /// The lowest quality a flush may run at. `nil`: no quality gate.
    var minNetworkQuality: NetworkQualityLevel? = nil
    /// Probed before a batch, and the target of the latency probe.
    var healthCheckUrl: String? = nil
    /// As set. Read ``effectiveHealthCheckTtlMs`` to use it.
    var healthCheckTtlMs: Double? = nil
    /// As set. Read ``effectiveHealthCheckBackoffMs`` to use it.
    var healthCheckBackoffMs: Double? = nil

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
                         credentialsUnavailable: credentialsUnavailable,
                         minNetworkQuality: minNetworkQuality,
                         healthCheckUrl: healthCheckUrl.flatMap { URL(string: $0) },
                         healthCheckTtlMs: effectiveHealthCheckTtlMs,
                         healthCheckBackoffMs: effectiveHealthCheckBackoffMs)
    }
}

// MARK: - Network-health options

extension SyncOptionsSnapshot {
    /// `healthCheckTtlMs` when unset: how long a healthy check is trusted.
    static let defaultHealthCheckTtlMs: Int64 = 60_000
    /// `healthCheckBackoffMs` when unset: how long cycles are skipped after a
    /// failed check.
    static let defaultHealthCheckBackoffMs: Int64 = 30_000
    /// The largest millisecond value an option converts to: 2^53 - 1, the
    /// largest integer a JS number holds exactly. Far enough from `Int64.max`
    /// that adding it to an epoch-ms timestamp cannot overflow.
    static let maxOptionMs: Double = 9_007_199_254_740_991

    /// `healthCheckTtlMs` in whole milliseconds, with the default filled in.
    var effectiveHealthCheckTtlMs: Int64 {
        Self.wholeMs(healthCheckTtlMs, default: Self.defaultHealthCheckTtlMs)
    }

    /// `healthCheckBackoffMs` in whole milliseconds, with the default filled in.
    var effectiveHealthCheckBackoffMs: Int64 {
        Self.wholeMs(healthCheckBackoffMs, default: Self.defaultHealthCheckBackoffMs)
    }

    /// `value` in whole milliseconds, clamped to `0...maxOptionMs` before the
    /// conversion: `Int64(_:)` traps on a value it cannot hold, and a value
    /// read back from disk at every launch would make that a crash loop.
    static func wholeMs(_ value: Double?, default fallback: Int64) -> Int64 {
        guard let value, !value.isNaN else { return fallback }
        return Int64(min(max(value, 0), maxOptionMs).rounded(.down))
    }

    /// Rejects the network-health values `configureSync` must not accept, in
    /// the facade's order. `minNetworkQuality` needs no check: it is typed.
    /// The messages leave the URL out, because its query can carry a token.
    ///
    /// - Throws: `INVALID_URL` when `healthCheckUrl` is not an absolute http(s)
    ///   URL with a host. `INVALID_PAYLOAD` when `healthCheckTtlMs` or
    ///   `healthCheckBackoffMs` is not a finite number of at least 0.
    func validateNetworkHealth() throws {
        if let healthCheckUrl, !Self.isUsableHealthCheckURL(healthCheckUrl) {
            throw SyncProviderError(code: .invalidUrl,
                                    message: "SyncOptions.healthCheckUrl must be an absolute http(s) URL.")
        }
        try Self.requireNonNegativeFinite("healthCheckTtlMs", healthCheckTtlMs)
        try Self.requireNonNegativeFinite("healthCheckBackoffMs", healthCheckBackoffMs)
    }

    /// The options with every network-health value ``validateNetworkHealth()``
    /// would reject dropped, as if it had never been set. A configuration
    /// read back from disk goes through this: a damaged record must not stop
    /// the engine, nor hand the health check a URL it cannot probe.
    func droppingInvalidNetworkHealth() -> SyncOptionsSnapshot {
        var options = self
        if let url = options.healthCheckUrl, !Self.isUsableHealthCheckURL(url) {
            SyncLogger.error("Stored healthCheckUrl is unusable; ignoring it", category: "general")
            options.healthCheckUrl = nil
        }
        if let ttl = options.healthCheckTtlMs, !Self.isNonNegativeFinite(ttl) {
            SyncLogger.error("Stored healthCheckTtlMs is unusable; ignoring it", category: "general")
            options.healthCheckTtlMs = nil
        }
        if let backoff = options.healthCheckBackoffMs, !Self.isNonNegativeFinite(backoff) {
            SyncLogger.error("Stored healthCheckBackoffMs is unusable; ignoring it", category: "general")
            options.healthCheckBackoffMs = nil
        }
        return options
    }

    /// The rule the probe applies to every URL it sends to: absolute http or
    /// https with a non-empty host. Stricter than "parses with a host" for
    /// strings like `https://:8080`, which the probe could never reach.
    static func isUsableHealthCheckURL(_ value: String) -> Bool {
        guard let url = URL(string: value) else { return false }
        return HeadProbe.isProbeable(url)
    }

    private static func isNonNegativeFinite(_ value: Double) -> Bool {
        value.isFinite && value >= 0
    }

    private static func requireNonNegativeFinite(_ field: String, _ value: Double?) throws {
        guard let value, !isNonNegativeFinite(value) else { return }
        throw SyncProviderError(code: .invalidPayload,
                                message: "SyncOptions.\(field) must be a finite number of at least 0, was \(value).")
    }
}
