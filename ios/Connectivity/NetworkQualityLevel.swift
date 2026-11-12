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

    /// `true` when this grade lets a flush through a gate at `minimum`. No
    /// minimum, or `offline` (which every grade meets), means no gate.
    func satisfies(minimum: NetworkQualityLevel?) -> Bool {
        guard let minimum, minimum > .offline else { return true }
        return self >= minimum
    }
}
