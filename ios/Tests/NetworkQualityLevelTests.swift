import XCTest
@testable import SyncProvider

/// The engine's mirror of `NetworkQuality`, and the provisional grade
/// `getConnectionStatus()` reports until quality is measured.
final class NetworkQualityLevelTests: XCTestCase {
    func test_rawValues_areTheWireStrings_worstToBest() {
        XCTAssertEqual(NetworkQualityLevel.allCases.map(\.rawValue),
                       ["OFFLINE", "POOR", "MODERATE", "GOOD", "EXCELLENT"])
    }

    func test_levels_areOrderedWorstToBest() {
        let levels = NetworkQualityLevel.allCases
        for (index, level) in levels.enumerated() {
            for (otherIndex, other) in levels.enumerated() {
                XCTAssertEqual(level < other, index < otherIndex, "\(level) < \(other)")
            }
        }
        XCTAssertEqual(levels.sorted(), levels)
        XCTAssertEqual(levels.max(), .excellent)
        XCTAssertEqual(levels.min(), .offline)
    }

    func test_provisionalGrade_followsTheLinkStatus() {
        XCTAssertEqual(NetworkQualityLevel.provisional(for: snapshot(.connected)), .good)
        XCTAssertEqual(NetworkQualityLevel.provisional(for: snapshot(.metered)), .good)
        XCTAssertEqual(NetworkQualityLevel.provisional(for: snapshot(.disconnected)), .offline)
        XCTAssertEqual(NetworkQualityLevel.provisional(for: .unknown), .offline,
                       "Before the first path update the link is not known to be usable")
    }

    /// The spec's invariant: `OFFLINE` exactly when the status is
    /// `DISCONNECTED` or `UNKNOWN`, whatever else the snapshot says.
    func test_provisionalGrade_isOfflineExactlyWhenDisconnectedOrUnknown() {
        let statuses: [ConnectionSnapshot.Status] = [.connected, .disconnected, .metered, .unknown]
        let kinds: [ConnectionSnapshot.Kind] = [.unknown, .wifi, .cellular, .ethernet, .other, .none]
        for status in statuses {
            for kind in kinds {
                for flag in [nil, false, true] as [Bool?] {
                    let snapshot = ConnectionSnapshot(status: status, type: kind,
                                                      isInternetReachable: flag, isExpensive: flag)

                    let grade = NetworkQualityLevel.provisional(for: snapshot)

                    let mustBeOffline = status == .disconnected || status == .unknown
                    XCTAssertEqual(grade == .offline, mustBeOffline, "\(status) \(kind) \(String(describing: flag))")
                    if !mustBeOffline {
                        XCTAssertEqual(grade, .good, "Provisional: link status only, no measurement")
                    }
                }
            }
        }
    }

    private func snapshot(_ status: ConnectionSnapshot.Status) -> ConnectionSnapshot {
        ConnectionSnapshot(status: status,
                           type: status == .disconnected ? .none : .wifi,
                           isInternetReachable: status != .disconnected,
                           isExpensive: status == .metered)
    }
}
