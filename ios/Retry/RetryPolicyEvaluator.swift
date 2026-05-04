//
//  RetryPolicyEvaluator.swift
//  SyncProvider
//
//  Pure-Swift implementation of the retry-backoff math. Mirrors the JS
//  implementation in `src/utils/retryBackoff.ts` 1:1 so behavior is consistent
//  across platforms and the JS-side `SyncOptions.retryPolicy` produces the
//  same delay sequence it would on iOS.
//

import Foundation

/// Sendable Swift mirror of `RetryPolicy` to keep this layer free of the
/// generated `margelo.nitro.syncprovider.RetryPolicy` C++ type. Constructed by
/// the bridge.
struct RetryPolicyValue: Sendable, Equatable {
    let maxAttempts: Int
    let backoff: Backoff
    let baseDelayMs: Int
    let maxDelayMs: Int
    let jitter: Bool
    let retryOnStatusCodes: [Int]

    enum Backoff: String, Sendable {
        case linear = "LINEAR"
        case exponential = "EXPONENTIAL"
        case fibonacci = "FIBONACCI"
    }

    /// Default policy matching the JS facade defaults.
    static let `default` = RetryPolicyValue(
        maxAttempts: 5,
        backoff: .exponential,
        baseDelayMs: 1000,
        maxDelayMs: 60_000,
        jitter: true,
        retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504]
    )
}

/// Stateless evaluator for ``RetryPolicyValue``.
enum RetryPolicyEvaluator {
    /// Compute the delay (in integer ms) before the next retry, given an
    /// attempt number (1-based — the first retry is `attempt == 1`) and a
    /// policy.
    ///
    /// - Important: **must** match `src/utils/retryBackoff.ts`.
    static func nextDelayMs(attempt: Int, policy: RetryPolicyValue) -> Int {
        let safeAttempt = max(1, attempt)
        let base = max(0, policy.baseDelayMs)

        var raw: Double
        switch policy.backoff {
        case .linear:
            raw = Double(base) * Double(safeAttempt)
        case .exponential:
            raw = Double(base) * pow(2.0, Double(safeAttempt - 1))
        case .fibonacci:
            raw = Double(base) * Double(fibonacci(safeAttempt))
        }

        if policy.jitter {
            let factor = 0.75 + Double.random(in: 0..<0.5)
            raw *= factor
        }

        let cap = policy.maxDelayMs > 0 ? Double(policy.maxDelayMs) : raw
        let clamped = min(max(0, raw), cap)
        return Int(clamped.rounded())
    }

    /// Decide whether an HTTP response should be retried.
    static func shouldRetry(statusCode: Int, policy: RetryPolicyValue) -> Bool {
        return policy.retryOnStatusCodes.contains(statusCode)
    }

    // MARK: - Internals

    private static func fibonacci(_ n: Int) -> Int {
        if n <= 0 { return 0 }
        if n <= 2 { return 1 }
        var prev = 1
        var curr = 1
        for _ in 3...n {
            let next = prev + curr
            prev = curr
            curr = next
        }
        return curr
    }
}
