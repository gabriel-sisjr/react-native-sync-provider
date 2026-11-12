import XCTest
import Security
@testable import SyncProvider

/// Retry wakes held back by the network-quality gate, and the rules both
/// platforms share for them: a held wake stays silent, is re-checked with
/// backoff, and a stale measurement is taken again when a gate needs it.
final class HeldWakeTests: XCTestCase {
    private var fixture: EngineFixture!
    private var emitter: MockSyncEventEmitter!

    private let healthURL = "https://health.test/ping"

    override func setUp() {
        super.setUp()
        fixture = EngineFixture()
        emitter = MockSyncEventEmitter()
        URLProtocolStub.setHandler(stubResponse(200))
    }

    override func tearDown() {
        URLProtocolStub.setHandler(nil)
        fixture.tearDown()
        fixture = nil
        super.tearDown()
    }

    // MARK: - Helpers

    private var requestCount: Int {
        URLProtocolStub.capturedRequests.count
    }

    private func options(minimum: NetworkQualityLevel?, healthCheckUrl: String? = nil) -> SyncOptionsSnapshot {
        var options = SyncOptionsSnapshot.sample(retryPolicy: .exact(maxAttempts: 5, baseDelayMs: 50))
        options.minNetworkQuality = minimum
        options.healthCheckUrl = healthCheckUrl
        return options
    }

    private func configure(_ engine: SyncEngine, minimum: NetworkQualityLevel?, healthCheckUrl: String? = nil) async {
        await engine.configure(options(minimum: minimum, healthCheckUrl: healthCheckUrl))
        await engine.qualityMonitor.waitForProbe()
    }

    /// Queues an item already attempted once and due now, then lets the retry
    /// timer fire for it.
    private func fireAWakeForADueItem(in engine: SyncEngine) async throws {
        let id = try await engine.storage.enqueue(input: .sample())
        try await engine.storage.markInFlight(id: id)
        try await engine.storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 0)
        await engine.rearmRetryTimer()
        await fixture.timer.fire()
    }

    /// An engine holding a wake below a GOOD minimum on a metered link, with
    /// no measurement: graded MODERATE from the link alone.
    private func engineHoldingAWake() async throws -> SyncEngine {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configure(engine, minimum: .good)
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))
        try await fireAWakeForADueItem(in: engine)
        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)
        return engine
    }

    // MARK: - Silent

    /// Only explicit flushes, workers and BGTasks reach the gate's event. A
    /// held timer wake and its re-checks emit nothing and dispatch nothing.
    func test_heldWake_andItsRechecks_areSilent() async throws {
        let engine = try await engineHoldingAWake()
        emitter.clear()

        for _ in 0..<3 {
            await fixture.recheckTimer.fire()
        }

        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)
        XCTAssertTrue(emitter.emittedEvents.isEmpty, "\(emitter.emittedEvents.map(\.type))")
        XCTAssertEqual(requestCount, 0)
    }

    // MARK: - Re-check backoff

    func test_heldWake_recheckBacksOff_from30sDoublingTo15Minutes() async throws {
        _ = try await engineHoldingAWake()

        var delays: [Int64?] = []
        for _ in 0..<7 {
            delays.append(fixture.recheckTimer.delayMs)
            await fixture.recheckTimer.fire()
        }

        XCTAssertEqual(delays, [30_000, 60_000, 120_000, 240_000, 480_000, 900_000, 900_000])
    }

    func test_heldWake_recheckBackoffResets_onAPathChange() async throws {
        let engine = try await engineHoldingAWake()
        await fixture.recheckTimer.fire()
        await fixture.recheckTimer.fire()
        XCTAssertEqual(fixture.recheckTimer.delayMs, 120_000)

        fixture.connectivity.set(snapshot: .link(.other, expensive: true))

        XCTAssertTrue(engine.hasWakeAwaitingConnectivity, "Still MODERATE: still held")
        XCTAssertEqual(fixture.recheckTimer.delayMs, 30_000)
    }

    func test_heldWake_recheckBackoffResets_onConfigureSync() async throws {
        let engine = try await engineHoldingAWake()
        await fixture.recheckTimer.fire()
        await fixture.recheckTimer.fire()
        XCTAssertEqual(fixture.recheckTimer.delayMs, 120_000)

        await engine.configure(options(minimum: .good))
        XCTAssertFalse(fixture.recheckTimer.isArmed, "The re-arm re-evaluates the wake instead")
        await fixture.timer.fire()

        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)
        XCTAssertEqual(fixture.recheckTimer.delayMs, 30_000)
    }

    func test_heldWake_recheckBackoffResets_onAURLChange() async throws {
        let engine = try await engineHoldingAWake()
        await fixture.recheckTimer.fire()
        XCTAssertEqual(fixture.recheckTimer.delayMs, 60_000)
        fixture.probe.answer(latencyMs: 2_000)

        await configure(engine, minimum: .good, healthCheckUrl: healthURL)
        await fixture.timer.fire()

        XCTAssertTrue(engine.hasWakeAwaitingConnectivity, "POOR now, measured: still held")
        XCTAssertEqual(fixture.recheckTimer.delayMs, 30_000)
    }

    /// Any new arm of the retry timer, as at the end of a cycle or when a
    /// hybrid attaches, takes over from the re-check and restarts its backoff.
    func test_heldWake_recheckBackoffResets_onAnyNewArm() async throws {
        let engine = try await engineHoldingAWake()
        await fixture.recheckTimer.fire()
        await fixture.recheckTimer.fire()
        XCTAssertEqual(fixture.recheckTimer.delayMs, 120_000)

        await engine.rearmRetryTimer()

        XCTAssertFalse(fixture.recheckTimer.isArmed)
        await fixture.timer.fire()
        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)
        XCTAssertEqual(fixture.recheckTimer.delayMs, 30_000)
    }

    func test_heldWake_recheckBackoffResets_whenTheGatePasses() async throws {
        let engine = try await engineHoldingAWake()
        await fixture.recheckTimer.fire()
        await fixture.recheckTimer.fire()

        fixture.connectivity.set(snapshot: .link())
        await engine.waitForReconnectWake()
        XCTAssertEqual(requestCount, 1, "GOOD meets the minimum: the wake ran")
        XCTAssertFalse(fixture.recheckTimer.isArmed)

        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))
        try await fireAWakeForADueItem(in: engine)
        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)
        XCTAssertEqual(fixture.recheckTimer.delayMs, 30_000, "A new hold starts the backoff over")
    }

    /// Offline, there is nothing to re-check: reconnecting runs the wake.
    func test_heldWake_offline_isNotRechecked_andRunsOnReconnect() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configure(engine, minimum: .good)
        fixture.connectivity.set(.disconnected)

        try await fireAWakeForADueItem(in: engine)

        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)
        XCTAssertFalse(fixture.recheckTimer.isArmed)
        fixture.connectivity.set(snapshot: .link())
        await engine.waitForReconnectWake()
        XCTAssertEqual(requestCount, 1)
    }

    /// Accepted on both platforms: when the grade drops between the check that
    /// releases a held wake and the flush it starts, that flush is a real
    /// attempt, the gate reports it, and the wake is held again.
    func test_heldWake_gradeDroppingBeforeItsFlush_isReportedByTheGate() async throws {
        let engine = try await engineHoldingAWake()
        // What the gate reads from now on: lower than what the release check reads.
        engine.dispatcher.hooks.networkQuality = { .moderate }
        emitter.clear()

        fixture.connectivity.set(snapshot: .link())
        await engine.waitForReconnectWake()

        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").map(\.errorCode), ["NETWORK_QUALITY_TOO_LOW"])
        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)
        XCTAssertEqual(requestCount, 0)
    }

    func test_pausing_dropsTheHeldWakeAndItsRecheck() async throws {
        let engine = try await engineHoldingAWake()

        await engine.pause()

        XCTAssertFalse(engine.hasWakeAwaitingConnectivity)
        XCTAssertFalse(fixture.recheckTimer.isArmed)
    }

    // MARK: - Stale measurements

    /// A measurement never expires on its own: with no gate evaluation, no
    /// new measurement is taken and the old grade stands.
    func test_measurement_neverDecaysToLinkSignals() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.probe.answer(latencyMs: 150)
        await configure(engine, minimum: nil, healthCheckUrl: healthURL)

        fixture.clock.advance(ms: 3_600_000)

        XCTAssertEqual(engine.currentNetwork().quality, .excellent)
        XCTAssertEqual(fixture.probe.callCount, 3)
    }

    func test_gateEvaluation_withAFreshMeasurement_doesNotMeasureAgain() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.probe.answer(latencyMs: 150)
        await configure(engine, minimum: .good, healthCheckUrl: healthURL)
        fixture.clock.advance(ms: kQualityMeasurementCacheMs - 1)

        _ = try await engine.dispatcher.flushOutcome()
        await engine.qualityMonitor.waitForProbe()

        XCTAssertEqual(fixture.probe.callCount, 3)
    }

    /// The gate uses the grade as it is and asks for a new one in the
    /// background, one set at a time however many evaluations ask.
    func test_staleMeasurement_gateEvaluation_triggersOneBackgroundMeasurement() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.probe.answer(latencyMs: 800)
        await configure(engine, minimum: .good, healthCheckUrl: healthURL)
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.clock.advance(ms: kQualityMeasurementCacheMs)
        fixture.probe.hold()

        let first = try await engine.dispatcher.flushOutcome()
        let second = try await engine.dispatcher.flushOutcome()

        guard case .skipped(_, .networkQualityTooLow) = first,
              case .skipped(_, .networkQualityTooLow) = second else {
            return XCTFail("Both judged on the stale MODERATE: \(first), \(second)")
        }
        let held = await fixture.probe.waitUntilHeld()
        XCTAssertTrue(held)
        fixture.probe.answer(latencyMs: 100)
        fixture.probe.release()
        await engine.qualityMonitor.waitForProbe()
        XCTAssertEqual(fixture.probe.callCount, 6, "One new set of three")
        XCTAssertEqual(engine.currentNetwork().quality, .excellent)
    }

    /// The case the rule exists for: a stable path, so no update ever comes.
    /// The re-check finds the measurement stale, takes it again, and the new
    /// grade runs the wake.
    func test_staleMeasurement_heldWakeRecheck_measuresAgainAndRunsTheWake() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.probe.answer(latencyMs: 800)
        await configure(engine, minimum: .excellent, healthCheckUrl: healthURL)
        try await fireAWakeForADueItem(in: engine)
        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)
        XCTAssertEqual(fixture.probe.callCount, 3, "Fresh measurement: no new one yet")

        fixture.clock.advance(ms: kQualityMeasurementCacheMs)
        fixture.probe.answer(latencyMs: 100)
        await fixture.recheckTimer.fire()
        await engine.qualityMonitor.waitForProbe()
        await engine.waitForReconnectWake()

        XCTAssertEqual(fixture.probe.callCount, 6)
        XCTAssertEqual(requestCount, 1)
        XCTAssertFalse(engine.hasWakeAwaitingConnectivity)
    }

    // MARK: - Reporting and the flush head

    func test_connection_initialValue_isNotReported() async {
        _ = await fixture.makeEngine(emitter: emitter)

        XCTAssertTrue(emitter.events(ofType: "CONNECTION_CHANGED").isEmpty)
    }

    /// While the first measurement is pending, the gate compares the
    /// link-signal grade: no forced pass, no forced block.
    func test_gate_firstProbePending_comparesTheLinkGrade() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.probe.hold()
        await engine.configure(options(minimum: .good, healthCheckUrl: healthURL))
        // The health check shares the held probe: answer it from its cache.
        engine.healthCheck.recordHealthy(url: URL(string: healthURL)!)
        _ = try await engine.storage.enqueue(input: .sample())

        let atGood = try await engine.dispatcher.flushOutcome()
        await engine.configure(options(minimum: .excellent, healthCheckUrl: healthURL))
        let atExcellent = try await engine.dispatcher.flushOutcome()

        guard case .ran = atGood else { return XCTFail("GOOD from the link meets GOOD: \(atGood)") }
        guard case .skipped(_, .networkQualityTooLow) = atExcellent else {
            return XCTFail("The link alone never grades EXCELLENT: \(atExcellent)")
        }
        fixture.probe.release()
        await engine.qualityMonitor.waitForProbe()
    }

    func test_flushHead_unreadableCredentials_comeBeforeTheGate() async throws {
        let configured = await fixture.makeEngine()
        var withHeaders = options(minimum: .excellent)
        withHeaders.defaultHeaders = ["Authorization": "Bearer t"]
        await configured.configure(withHeaders)
        fixture.secrets.failReads(with: errSecInteractionNotAllowed)
        let cold = await fixture.makeColdEngine(emitter: emitter)
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))
        emitter.clear()

        let outcome = try await cold.dispatcher.flushOutcome()

        guard case .skipped(_, .credentialsUnavailable) = outcome else {
            return XCTFail("Expected the credentials check, got \(outcome)")
        }
        XCTAssertTrue(emitter.emittedEvents.isEmpty)
    }

    /// iOS only: a flush that finds a cycle running joins it, before the gate,
    /// so the running cycle's listeners never see a gate failure inside it.
    func test_flushHead_aRunningCycleIsJoinedBeforeTheGate() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configure(engine, minimum: .good)
        _ = try await engine.storage.enqueue(input: .sample())
        let started = DispatchSemaphore(value: 0)
        let finish = DispatchSemaphore(value: 0)
        URLProtocolStub.setHandler { request in
            started.signal()
            finish.wait()
            return (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, Data())
        }
        let running = Task { try await engine.dispatcher.flushOutcome() }
        await wait(for: started)
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))
        emitter.clear()

        let joined = try await engine.dispatcher.flushOutcome()

        guard case .ran = joined else { return XCTFail("Expected to join the running cycle, got \(joined)") }
        XCTAssertFalse(emitter.emittedEvents.contains { $0.errorCode == "NETWORK_QUALITY_TOO_LOW" })
        finish.signal()
        _ = try await running.value
    }

    private func wait(for semaphore: DispatchSemaphore) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            DispatchQueue.global().async {
                semaphore.wait()
                continuation.resume()
            }
        }
    }
}
