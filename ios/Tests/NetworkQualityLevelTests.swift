import XCTest
@testable import SyncProvider

/// The engine's mirror of `NetworkQuality`, and what a gate at a given minimum
/// lets through.
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

    /// No minimum, or `OFFLINE`, is no gate at all.
    func test_unsetOrOfflineMinimum_letsEveryGradeThrough() {
        for grade in NetworkQualityLevel.allCases {
            XCTAssertTrue(grade.satisfies(minimum: nil), "\(grade)")
            XCTAssertTrue(grade.satisfies(minimum: .offline), "\(grade)")
        }
    }

    /// `POOR` is the most permissive gate: it stops only `OFFLINE`.
    func test_minimum_letsThroughExactlyTheGradesAtOrAboveIt() {
        let levels = NetworkQualityLevel.allCases
        for minimum in levels where minimum > .offline {
            for grade in levels {
                XCTAssertEqual(grade.satisfies(minimum: minimum), grade >= minimum, "\(grade) vs \(minimum)")
            }
        }
        XCTAssertFalse(NetworkQualityLevel.offline.satisfies(minimum: .poor))
        XCTAssertTrue(NetworkQualityLevel.poor.satisfies(minimum: .poor))
    }
}
