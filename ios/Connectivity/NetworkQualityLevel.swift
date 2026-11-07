//
//  NetworkQualityLevel.swift
//  SyncProvider
//
//  The engine's network-quality grades. The bridge maps them to and from the
//  generated `NetworkQuality`, which the layers below it never touch.
//

import Foundation

/// How usable the network is, worst to best. The raw values are the wire
/// strings of the generated `NetworkQuality`.
enum NetworkQualityLevel: String, Sendable, CaseIterable, Comparable {
    case offline = "OFFLINE"
    case poor = "POOR"
    case moderate = "MODERATE"
    case good = "GOOD"
    case excellent = "EXCELLENT"

    static func < (lhs: NetworkQualityLevel, rhs: NetworkQualityLevel) -> Bool {
        lhs.rank < rhs.rank
    }

    private var rank: Int {
        switch self {
        case .offline: return 0
        case .poor: return 1
        case .moderate: return 2
        case .good: return 3
        case .excellent: return 4
        }
    }

    /// The grade reported while quality is not measured: read from the link
    /// status alone. `offline` exactly when the link is not known to be usable
    /// (disconnected, or unknown before the first path update), `good`
    /// otherwise. Provisional: it never reports `poor`, `moderate` or
    /// `excellent`.
    static func provisional(for snapshot: ConnectionSnapshot) -> NetworkQualityLevel {
        snapshot.isOnline ? .good : .offline
    }
}
