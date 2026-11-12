import XCTest
@testable import SyncProvider

/// The shared threshold table. Each test is named after the parity scenario
/// the Android classifier runs under the same name.
final class NetworkQualityClassifierTests: XCTestCase {
    private func classify(_ link: ConnectionSnapshot, _ samples: [Int?]?) -> NetworkQualityLevel {
        NetworkQualityClassifier.classify(link: link, samples: samples)
    }

    // MARK: - No usable link

    func test_quality_noLink_isOffline() {
        for samples in [nil, [100, 100, 100], [nil, nil, nil]] as [[Int?]?] {
            XCTAssertEqual(classify(.disconnected, samples), .offline, "\(String(describing: samples))")
        }
    }

    /// Before the first path update the link is unknown, and unknown is not
    /// usable: a measurement taken earlier does not change that.
    func test_quality_unknownLink_isOffline() {
        XCTAssertEqual(classify(.unknown, nil), .offline)
        XCTAssertEqual(classify(.unknown, [50, 50, 50]), .offline)
    }

    // MARK: - No measurement

    func test_quality_noMeasurement_unmetered_isGood() {
        XCTAssertEqual(classify(.link(), nil), .good)
        XCTAssertEqual(classify(.link(), []), .good, "An empty set is no measurement")
        XCTAssertEqual(classify(.link(.ethernet), nil), .good)
    }

    func test_quality_noMeasurement_metered_isModerate() {
        XCTAssertEqual(classify(.link(.cellular, expensive: true), nil), .moderate)
        // Metered on either signal.
        let meteredStatusOnly = ConnectionSnapshot(status: .metered, type: .cellular,
                                                   isInternetReachable: true, isExpensive: false)
        let expensiveOnly = ConnectionSnapshot(status: .connected, type: .wifi,
                                               isInternetReachable: true, isExpensive: true)
        XCTAssertEqual(classify(meteredStatusOnly, nil), .moderate)
        XCTAssertEqual(classify(expensiveOnly, nil), .moderate)
    }

    // MARK: - Measurement

    func test_quality_median_boundaries() {
        let cases: [(medianMs: Int, expected: NetworkQualityLevel)] = [
            (0, .excellent), (150, .excellent), (199, .excellent),
            (200, .good), (499, .good),
            (500, .moderate), (999, .moderate),
            (1_000, .poor), (60_000, .poor),
        ]
        for (medianMs, expected) in cases {
            XCTAssertEqual(NetworkQualityClassifier.grade(forMedianMs: medianMs), expected, "\(medianMs) ms")
            // As the median of a real set: one faster sample, one failure.
            XCTAssertEqual(classify(.link(), [max(0, medianMs - 1), medianMs, nil]), expected, "\(medianMs) ms")
            XCTAssertEqual(classify(.link(), [medianMs, medianMs, medianMs]), expected, "\(medianMs) ms")
        }
    }

    func test_quality_median_ignoresSampleOrder() {
        for samples in [[100, 900, 300], [900, 300, 100], [300, 100, 900]] as [[Int?]] {
            XCTAssertEqual(NetworkQualityClassifier.median(of: samples), 300)
            XCTAssertEqual(classify(.link(), samples), .good)
        }
    }

    func test_quality_oneFailure_usesMedianOfRest() {
        XCTAssertEqual(NetworkQualityClassifier.median(of: [100, 120, nil]), 120)
        XCTAssertEqual(classify(.link(), [100, 120, nil]), .excellent)
        XCTAssertEqual(classify(.link(), [nil, 100, 120]), .excellent, "Wherever the failure is")
    }

    func test_quality_twoFailures_isPoor() {
        XCTAssertNil(NetworkQualityClassifier.median(of: [100, nil, nil]))
        XCTAssertEqual(classify(.link(), [100, nil, nil]), .poor)
        XCTAssertEqual(classify(.link(), [nil, nil, nil]), .poor)
        XCTAssertEqual(classify(.link(), [nil, nil, 5]), .poor, "Wherever the failures are")
    }

    // MARK: - Caps

    /// Applied last, the lowest cap winning. A cap never raises a grade.
    func test_quality_caps() {
        let metered = ConnectionSnapshot.link(.cellular, expensive: true)
        let constrained = ConnectionSnapshot.link(.wifi, constrained: true)
        let both = ConnectionSnapshot.link(.cellular, expensive: true, constrained: true)
        let cases: [(ConnectionSnapshot, [Int?]?, NetworkQualityLevel, String)] = [
            (metered, [150, 150, 150], .good, "metered + 150 ms"),
            (constrained, [150, 150, 150], .moderate, "constrained + 150 ms"),
            (both, [150, 150, 150], .moderate, "both caps: the lowest wins"),
            (constrained, nil, .moderate, "constrained, no measurement"),
            (both, nil, .moderate, "both, no measurement"),
            (metered, [300, 300, 300], .good, "metered + 300 ms: already within the cap"),
            (metered, [600, 600, 600], .moderate, "metered + 600 ms: the cap does not raise it"),
            (constrained, [1_500, 1_500, 1_500], .poor, "constrained + 1500 ms"),
            (constrained, [100, nil, nil], .poor, "constrained + two failures"),
        ]
        for (link, samples, expected, label) in cases {
            XCTAssertEqual(classify(link, samples), expected, label)
        }
    }

    // MARK: - Invariant

    /// A usable link never grades `OFFLINE`, so `OFFLINE` means exactly "no
    /// usable link".
    func test_quality_usableLink_isNeverOffline() {
        let statuses: [ConnectionSnapshot.Status] = [.connected, .metered, .disconnected, .unknown]
        let measurements: [[Int?]?] = [nil, [], [10, 10, 10], [nil, nil, nil], [5_000, 5_000, 5_000]]
        for status in statuses {
            for constrained in [false, true] {
                for expensive in [false, true] {
                    for samples in measurements {
                        let link = ConnectionSnapshot(status: status, type: .wifi, isInternetReachable: nil,
                                                      isExpensive: expensive, isConstrained: constrained)

                        let grade = classify(link, samples)

                        let usable = status == .connected || status == .metered
                        XCTAssertEqual(grade == .offline, !usable,
                                       "\(status) expensive=\(expensive) constrained=\(constrained) \(String(describing: samples))")
                    }
                }
            }
        }
    }
}
