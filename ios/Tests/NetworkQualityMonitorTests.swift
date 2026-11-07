import XCTest
@testable import SyncProvider

/// When the monitor measures, what a measurement is valid for, and which
/// changes it reports.
final class NetworkQualityMonitorTests: XCTestCase {
    private var connectivity: FakeConnectivity!
    private var probe: FakeHeadProbe!
    private var clock: ManualClock!
    private var monitor: NetworkQualityMonitor!
    private var changes: ChangeLog!

    private let url = URL(string: "https://health.test/ping")!

    override func setUp() {
        super.setUp()
        connectivity = FakeConnectivity(status: .connected)
        probe = FakeHeadProbe()
        clock = ManualClock()
        changes = ChangeLog()
        monitor = NetworkQualityMonitor(connectivity: connectivity, probe: probe, clock: clock, debounceMs: 0)
        let changes = changes!
        monitor.onChange = { changes.record($0) }
        monitor.start()
    }

    override func tearDown() {
        monitor.stop()
        monitor = nil
        super.tearDown()
    }

    private var quality: NetworkQualityLevel {
        monitor.current.quality
    }

    // MARK: - When it measures

    func test_withoutAURL_neverProbes_andGradesTheLinkAlone() async {
        connectivity.set(snapshot: .link(.cellular, expensive: true))
        connectivity.set(snapshot: .link())
        await monitor.waitForProbe()

        XCTAssertEqual(probe.callCount, 0)
        XCTAssertEqual(quality, .good)
    }

    func test_offline_neverProbes() async {
        connectivity.set(.disconnected)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        XCTAssertEqual(probe.callCount, 0)
        XCTAssertEqual(quality, .offline)
    }

    func test_measurement_isThreeBackToBackHeadsWithATwoSecondTimeout() async {
        probe.answer(latencyMs: 150)

        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        XCTAssertEqual(probe.probedURLs, [url, url, url])
        XCTAssertEqual(probe.timeouts, [2_000, 2_000, 2_000])
        XCTAssertEqual(quality, .excellent)
    }

    func test_anyStatus_isALatencySample() async {
        probe.script([.response(statusCode: 503, latencyMs: 120),
                      .response(statusCode: 404, latencyMs: 130),
                      .response(statusCode: 500, latencyMs: 140)])

        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        XCTAssertEqual(quality, .excellent, "5xx is the health check's concern, not the grade's")
    }

    func test_comingOnline_triggersAMeasurement() async {
        connectivity.set(.disconnected)
        monitor.setProbeURL(url)
        probe.answer(latencyMs: 600)

        connectivity.set(snapshot: .link())
        await monitor.waitForProbe()

        XCTAssertEqual(probe.callCount, 3)
        XCTAssertEqual(quality, .moderate)
    }

    func test_whileTheFirstMeasurementIsPending_theLinkGradesAlone() async {
        probe.hold()

        monitor.setProbeURL(url)
        let held = await probe.waitUntilHeld()

        XCTAssertTrue(held)
        XCTAssertEqual(quality, .good, "an unmetered link with no measurement yet")
        probe.release()
        await monitor.waitForProbe()
    }

    // MARK: - What a measurement is valid for

    func test_measurement_isReusedOnTheSameLinkFor30Seconds() async {
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        clock.advance(ms: 29_999)
        connectivity.set(snapshot: .link())
        await monitor.waitForProbe()
        XCTAssertEqual(probe.callCount, 3, "a measurement under 30 s old is reused")

        clock.advance(ms: 1)
        probe.answer(latencyMs: 700)
        connectivity.set(snapshot: .link())
        await monitor.waitForProbe()
        XCTAssertEqual(probe.callCount, 6, "at 30 s old, the next path update measures again")
        XCTAssertEqual(quality, .moderate)
    }

    func test_aDifferentLink_dropsTheMeasurement_andIsMeasuredAfresh() async {
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()
        XCTAssertEqual(quality, .excellent)

        probe.hold()
        connectivity.set(snapshot: .link(.cellular, expensive: true))
        let held = await probe.waitUntilHeld()

        XCTAssertTrue(held, "a different link is measured at once, cache or not")
        XCTAssertEqual(quality, .moderate, "the wifi measurement does not grade cellular")
        probe.release()
        await monitor.waitForProbe()
        XCTAssertEqual(quality, .good, "150 ms, capped on a metered link")
    }

    func test_turningLowDataModeOn_isADifferentLink() async {
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        connectivity.set(snapshot: .link(constrained: true))
        await monitor.waitForProbe()

        XCTAssertEqual(probe.callCount, 6)
        XCTAssertEqual(quality, .moderate)
    }

    /// An offline blip cancels a measurement in flight but keeps the last
    /// one: the same link back within 30 s reuses it.
    func test_offlineBlip_keepsTheMeasurement_forTheSameLinkWithin30Seconds() async {
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        connectivity.set(.disconnected)
        XCTAssertEqual(quality, .offline)
        clock.advance(ms: kQualityMeasurementCacheMs - 1)
        connectivity.set(snapshot: .link())
        await monitor.waitForProbe()

        XCTAssertEqual(quality, .excellent, "The same link, back within 30 s")
        XCTAssertEqual(probe.callCount, 3, "Nothing measured again")
    }

    func test_offlineBlip_cancelsTheMeasurementInFlight_butKeepsTheLastOne() async {
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()
        clock.advance(ms: kQualityMeasurementCacheMs)
        probe.hold()
        connectivity.set(snapshot: .link())
        let held = await probe.waitUntilHeld()
        XCTAssertTrue(held, "Stale on the same link: measuring again")

        connectivity.set(.disconnected)
        probe.release()
        await monitor.waitForProbe()
        connectivity.set(snapshot: .link())

        XCTAssertEqual(quality, .excellent, "The cancelled set changed nothing; the last measurement stands")
        await monitor.waitForProbe()
    }

    func test_offlineBlip_longerThan30Seconds_measuresTheSameLinkAgain() async {
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()
        connectivity.set(.disconnected)
        clock.advance(ms: kQualityMeasurementCacheMs)
        probe.hold()

        connectivity.set(snapshot: .link())

        XCTAssertEqual(quality, .excellent, "A measurement never expires: it stands until the new one lands")
        let held = await probe.waitUntilHeld()
        XCTAssertTrue(held)
        probe.answer(latencyMs: 600)
        probe.release()
        await monitor.waitForProbe()
        XCTAssertEqual(quality, .moderate)
    }

    func test_offlineBlip_backOnAnotherNetwork_dropsTheMeasurement() async {
        connectivity.set(snapshot: .link(gateway: "192.168.1.1"))
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()
        connectivity.set(.disconnected)
        probe.hold()

        connectivity.set(snapshot: .link(gateway: "10.0.0.1"))

        XCTAssertEqual(quality, .good, "Another network: graded by its link until measured")
        probe.release()
        await monitor.waitForProbe()
    }

    // MARK: - Telling networks apart

    /// Wi-Fi to Wi-Fi of the same cost: only the gateway tells the networks
    /// apart, and the new one is measured instead of inheriting a result.
    func test_anotherWifiNetwork_ofTheSameCost_isMeasuredAfresh() async {
        connectivity.set(snapshot: .link(gateway: "192.168.1.1"))
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()
        XCTAssertEqual(quality, .excellent)
        probe.hold()

        connectivity.set(snapshot: .link(gateway: "10.0.0.1"))

        XCTAssertEqual(quality, .good, "The other network's 150 ms does not grade this one")
        let held = await probe.waitUntilHeld()
        XCTAssertTrue(held)
        probe.release()
        await monitor.waitForProbe()
        XCTAssertEqual(probe.callCount, 6)
    }

    func test_sameWifiNetwork_reusesItsMeasurement() async {
        connectivity.set(snapshot: .link(gateway: "192.168.1.1"))
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        connectivity.set(snapshot: .link(gateway: "192.168.1.1"))
        await monitor.waitForProbe()

        XCTAssertEqual(probe.callCount, 3)
        XCTAssertEqual(quality, .excellent)
    }

    // MARK: - The cache clock

    /// The age runs on the wall clock, which can move back. A negative age is
    /// stale, so a clock moved back defeats neither the reuse window nor the
    /// stale re-measure.
    func test_clockMovedBack_makesTheMeasurementStale() async {
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        clock.advance(ms: -60_000)
        connectivity.set(snapshot: .link())
        await monitor.waitForProbe()

        XCTAssertEqual(probe.callCount, 6, "Reused on a path update only when 0 <= age < 30 s")
    }

    func test_clockMovedBack_letsAGateEvaluationMeasureAgain() async {
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        clock.advance(ms: -1)
        monitor.refreshIfStale()
        await monitor.waitForProbe()

        XCTAssertEqual(probe.callCount, 6)
    }

    // MARK: - Debounce

    /// 1000 ms on both platforms. Production waits it before a set.
    func test_debounce_isOneSecond() async throws {
        XCTAssertEqual(kQualityProbeDebounceMs, 1_000)
        let debounced = NetworkQualityMonitor(connectivity: connectivity, probe: probe, clock: clock)
        debounced.start()
        defer { debounced.stop() }

        debounced.setProbeURL(url)
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertEqual(probe.callCount, 0, "Still inside the debounce")
        await debounced.waitForProbe()

        XCTAssertEqual(probe.callCount, 3)
    }

    func test_changingTheURL_dropsTheMeasurement_andMeasuresTheNewOne() async {
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()
        let other = URL(string: "https://other.test/health")!
        probe.answer(latencyMs: 800)

        monitor.setProbeURL(other)
        await monitor.waitForProbe()

        XCTAssertEqual(Array(probe.probedURLs.suffix(3)), [other, other, other])
        XCTAssertEqual(quality, .moderate)
    }

    func test_removingTheURL_gradesTheLinkAlone() async {
        probe.answer(latencyMs: 150)
        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        monitor.setProbeURL(nil)

        XCTAssertEqual(quality, .good)
        XCTAssertEqual(probe.callCount, 3)
    }

    // MARK: - Cancellation

    /// A cancelled HEAD reads as a failure; kept, it would grade the link
    /// `poor` for nothing. The whole set goes.
    func test_cancelledMeasurement_isDiscardedWhole() async {
        probe.hold()
        monitor.setProbeURL(url)
        let held = await probe.waitUntilHeld()
        XCTAssertTrue(held)

        connectivity.set(.disconnected)
        probe.release()
        await monitor.waitForProbe()

        XCTAssertEqual(probe.callCount, 1, "the set stops at the cancelled HEAD")
        XCTAssertEqual(quality, .offline)
        probe.hold()
        connectivity.set(snapshot: .link())
        XCTAssertEqual(quality, .good, "nothing of the cancelled set was kept")
        probe.release()
        await monitor.waitForProbe()
    }

    func test_measurementOverlappingAURLChange_isNotApplied() async {
        probe.hold()
        monitor.setProbeURL(url)
        _ = await probe.waitUntilHeld()
        let other = URL(string: "https://other.test/health")!

        monitor.setProbeURL(other)
        probe.script([], then: .response(statusCode: 200, latencyMs: 900))
        probe.release()
        await monitor.waitForProbe()

        XCTAssertEqual(quality, .moderate, "only the new URL's 900 ms counts")
        XCTAssertEqual(Array(probe.probedURLs.suffix(3)), [other, other, other])
    }

    // MARK: - Reported changes

    func test_connection_qualityOnlyChange_isReported() async {
        probe.answer(latencyMs: 150)

        monitor.setProbeURL(url)
        await monitor.waitForProbe()

        XCTAssertEqual(changes.qualities, [.excellent], "GOOD from the link alone, then EXCELLENT measured")
        XCTAssertEqual(changes.states.first?.link.status, .connected)
    }

    func test_unchangedUpdate_isNotReported() async {
        connectivity.set(snapshot: .link())
        connectivity.set(snapshot: .link())

        XCTAssertTrue(changes.states.isEmpty)
    }

    /// Low Data Mode is not in `ConnectionState`: turning it on reports only
    /// when the grade moves.
    func test_lowDataModeChange_isReportedOnlyWhenTheGradeMoves() async {
        connectivity.set(snapshot: .link(.cellular, expensive: true))
        XCTAssertEqual(changes.qualities, [.moderate])

        connectivity.set(snapshot: .link(.cellular, expensive: true, constrained: true))

        XCTAssertEqual(changes.qualities, [.moderate], "metered already grades MODERATE: no change to report")
        connectivity.set(snapshot: .link(.wifi, constrained: true))
        XCTAssertEqual(changes.states.count, 2, "a different type and expense are reported")
    }

    func test_connection_offlineInvariant() async {
        probe.answer(latencyMs: 50)
        monitor.setProbeURL(url)
        let links: [ConnectionSnapshot] = [
            .link(), .disconnected, .link(.cellular, expensive: true), .unknown,
            .link(constrained: true), ConnectionSnapshot(status: .metered, type: .other,
                                                         isInternetReachable: nil, isExpensive: nil),
        ]
        for link in links {
            connectivity.set(snapshot: link)
            await monitor.waitForProbe()

            let state = monitor.current
            let unusable = state.link.status == .disconnected || state.link.status == .unknown
            XCTAssertEqual(state.quality == .offline, unusable, "\(link)")
        }
    }
}

/// Every change the monitor reported, in order.
private final class ChangeLog: @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [NetworkState] = []

    var states: [NetworkState] {
        lock.withLock { recorded }
    }

    var qualities: [NetworkQualityLevel] {
        states.map(\.quality)
    }

    func record(_ state: NetworkState) {
        lock.withLock { recorded.append(state) }
    }
}
