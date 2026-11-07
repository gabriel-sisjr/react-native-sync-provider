//
//  NetworkQualityClassifier.swift
//  SyncProvider
//
//  The shared threshold table, as one pure function. The Android classifier
//  implements the same table; the two must agree on every input.
//

import Foundation

/// Grades a network from its link signals and, when there is one, a
/// measurement: the latencies of three back-to-back HEADs to
/// `healthCheckUrl`.
///
/// The shared threshold table, in the order it applies:
///
/// 1. No usable link (disconnected, or unknown before the first path update):
///    `offline`.
/// 2. With a measurement, the median latency: below 200 ms `excellent`,
///    200-499 ms `good`, 500-999 ms `moderate`, 1000 ms or more `poor`. A
///    failed or timed-out sample counts as infinitely slow, so two failures
///    make the median infinite: `poor`.
/// 3. Without one (no URL, or the first probe still pending): `good` on an
///    unmetered link, `moderate` on a metered one.
/// 4. Caps, applied last, the lowest winning: metered (`isExpensive`) at most
///    `good`, Low Data Mode (`isConstrained`) at most `moderate`.
///
/// A usable link never grades `offline`: the worst it gets is `poor`.
enum NetworkQualityClassifier {
    /// Median latencies below this are `excellent`.
    static let excellentBelowMs = 200
    /// Median latencies below this are `good`.
    static let goodBelowMs = 500
    /// Median latencies below this are `moderate`; from here on, `poor`.
    static let moderateBelowMs = 1_000

    /// - Parameters:
    ///   - link: the current path.
    ///   - samples: the measurement, one latency per HEAD, `nil` for a failed
    ///     or timed-out one; `nil` or empty when there is no measurement.
    static func classify(link: ConnectionSnapshot, samples: [Int?]?) -> NetworkQualityLevel {
        guard link.isOnline else { return .offline }

        var level: NetworkQualityLevel
        if let samples, !samples.isEmpty {
            level = grade(forMedianMs: median(of: samples))
        } else {
            level = isMetered(link) ? .moderate : .good
        }

        if isMetered(link) {
            level = min(level, .good)
        }
        if link.isConstrained == true {
            level = min(level, .moderate)
        }
        return level
    }

    /// The grade of a median latency; `nil` is an infinite median.
    static func grade(forMedianMs medianMs: Int?) -> NetworkQualityLevel {
        guard let medianMs else { return .poor }
        if medianMs < excellentBelowMs { return .excellent }
        if medianMs < goodBelowMs { return .good }
        if medianMs < moderateBelowMs { return .moderate }
        return .poor
    }

    /// The middle sample once sorted, failures (`nil`) last as infinitely
    /// slow; for an even count, the upper of the two middle samples. `nil`
    /// when that sample is a failure, and for no samples at all.
    static func median(of samples: [Int?]) -> Int? {
        guard !samples.isEmpty else { return nil }
        let sorted = samples.sorted { lhs, rhs in
            switch (lhs, rhs) {
            case let (lhs?, rhs?): return lhs < rhs
            case (_?, nil): return true
            default: return false
            }
        }
        return sorted[sorted.count / 2]
    }

    /// Metered on either signal: `status` says `METERED` or the path is
    /// expensive.
    static func isMetered(_ link: ConnectionSnapshot) -> Bool {
        link.status == .metered || link.isExpensive == true
    }
}
