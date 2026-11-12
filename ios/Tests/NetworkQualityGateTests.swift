import XCTest
import NitroModules
@testable import SyncProvider

/// The network-quality gate at the head of every flush, the wakes it holds
/// back, and what `CONNECTION_CHANGED` and `getConnectionStatus()` report.
/// Each test is named after the parity scenario the Android suite runs.
final class NetworkQualityGateTests: XCTestCase {
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

    private func configure(_ engine: SyncEngine,
                           minimum: NetworkQualityLevel?,
                           healthCheckUrl: String? = nil) async {
        var options = SyncOptionsSnapshot.sample(retryPolicy: .exact(maxAttempts: 5, baseDelayMs: 50))
        options.minNetworkQuality = minimum
        options.healthCheckUrl = healthCheckUrl
        await engine.configure(options)
        await engine.qualityMonitor.waitForProbe()
    }

    /// Queues an item already attempted once, due now: the next retry wake
    /// will want it.
    private func enqueueDue(in engine: SyncEngine) async throws -> String {
        let id = try await engine.storage.enqueue(input: .sample())
        try await engine.storage.markInFlight(id: id)
        try await engine.storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 0)
        return id
    }

    private func connectionState(of engine: SyncEngine) throws -> ConnectionState {
        let hybrid = HybridSyncProvider(engine: engine)
        return try XCTUnwrap(try attachLikeTheBridge(try hybrid.getConnectionStatus()).value?.get())
    }

    // MARK: - No gate

    /// A manual flush offline still dispatches, as before this option existed.
    func test_gate_unset_neverGates() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configure(engine, minimum: nil)
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.connectivity.set(.disconnected)

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case .ran = outcome else { return XCTFail("Expected a cycle, got \(outcome)") }
        XCTAssertEqual(requestCount, 1)
        XCTAssertFalse(emitter.emittedEvents.contains { $0.errorCode == "NETWORK_QUALITY_TOO_LOW" })
    }

    func test_gate_offlineMinimum_neverGates() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configure(engine, minimum: .offline)
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.connectivity.set(.disconnected)

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case .ran = outcome else { return XCTFail("Expected a cycle, got \(outcome)") }
        XCTAssertEqual(requestCount, 1)
    }

    // MARK: - Below the minimum

    func test_gate_belowMinimum_skipsCycle() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configure(engine, minimum: .good)
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))
        let before = try await engine.storage.getAll()
        emitter.clear()

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case let .skipped(result, reason) = outcome else { return XCTFail("Expected a skip, got \(outcome)") }
        XCTAssertEqual(reason, .networkQualityTooLow)
        XCTAssertEqual(result.itemsAttempted, 0)
        XCTAssertEqual(result.itemsSucceeded, 0)
        XCTAssertEqual(result.itemsFailed, 0)
        XCTAssertEqual(result.startedAt, result.finishedAt, "The no-cycle shape of a paused flush")
        XCTAssertEqual(emitter.emittedEvents.map(\.type), ["SYNC_FAILED"], "No SYNC_STARTED, nothing else")
        XCTAssertEqual(emitter.emittedEvents.first?.errorCode, "NETWORK_QUALITY_TOO_LOW")
        XCTAssertNil(emitter.emittedEvents.first?.metadata)
        XCTAssertEqual(requestCount, 0)
        let history = try await engine.storage.getHistory(limit: 10)
        XCTAssertTrue(history.isEmpty, "No history row")
        let after = try await engine.storage.getAll()
        XCTAssertEqual(after, before, "No item changes")
    }

    func test_gate_atTheMinimum_runs() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configure(engine, minimum: .moderate)
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case .ran = outcome else { return XCTFail("Expected a cycle, got \(outcome)") }
        XCTAssertEqual(requestCount, 1)
    }

    func test_gate_followsTheMeasuredGrade() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.probe.answer(latencyMs: 600)
        await configure(engine, minimum: .good, healthCheckUrl: healthURL)
        _ = try await engine.storage.enqueue(input: .sample())
        XCTAssertEqual(engine.currentNetwork().quality, .moderate)

        let gated = try await engine.dispatcher.flushOutcome()

        guard case .skipped(_, .networkQualityTooLow) = gated else { return XCTFail("Expected a skip, got \(gated)") }
        fixture.probe.answer(latencyMs: 150)
        fixture.connectivity.set(snapshot: .link(.ethernet))
        await engine.qualityMonitor.waitForProbe()
        XCTAssertEqual(engine.currentNetwork().quality, .excellent)
        let allowed = try await engine.dispatcher.flushOutcome()
        guard case .ran = allowed else { return XCTFail("Expected a cycle, got \(allowed)") }
        XCTAssertEqual(requestCount, 1)
    }

    /// Pause and unreadable credentials come first and stay silent.
    func test_gate_comesAfterThePauseCheck() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configure(engine, minimum: .excellent)
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))
        await engine.pause()
        emitter.clear()

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case .skipped(_, .paused) = outcome else { return XCTFail("Expected the pause, got \(outcome)") }
        XCTAssertTrue(emitter.emittedEvents.isEmpty)
    }

    /// A process the OS launched for a BGTask reads the minimum from disk.
    func test_gate_coldStart_honorsThePersistedMinimum() async throws {
        let configured = await fixture.makeEngine()
        await configure(configured, minimum: .good)
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))
        let cold = await fixture.makeColdEngine(emitter: emitter)
        _ = try await cold.storage.enqueue(input: .sample())

        let outcome = try await cold.dispatcher.flushOutcome()

        guard case .skipped(_, .networkQualityTooLow) = outcome else { return XCTFail("Expected a skip, got \(outcome)") }
        XCTAssertEqual(requestCount, 0)
    }

    // MARK: - The first path update

    /// A BGTask launch flushes before its first path update: the gate waits
    /// for it instead of grading the unknown path offline.
    func test_gate_waitsForTheFirstPathUpdate() async throws {
        fixture.connectivity.set(.unknown)
        let engine = await fixture.makeColdEngine(emitter: emitter)
        await configure(engine, minimum: .poor)
        _ = try await engine.storage.enqueue(input: .sample())

        let flush = Task { try await engine.dispatcher.flushOutcome() }
        let deadline = Date().addingTimeInterval(2)
        while fixture.connectivity.waitTimeouts.isEmpty, Date() < deadline {
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        fixture.connectivity.set(snapshot: .link())
        let outcome = try await flush.value

        guard case .ran = outcome else { return XCTFail("Expected a cycle, got \(outcome)") }
        XCTAssertEqual(fixture.connectivity.waitTimeouts, [kFirstPathUpdateWaitMs])
        XCTAssertEqual(requestCount, 1)
    }

    func test_gate_pathStillUnknownAfterTheWait_isGatedAsOffline() async throws {
        fixture.connectivity.set(.unknown)
        let engine = await fixture.makeColdEngine(emitter: emitter)
        await configure(engine, minimum: .poor)
        _ = try await engine.storage.enqueue(input: .sample())
        emitter.clear()

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case .skipped(_, .networkQualityTooLow) = outcome else { return XCTFail("Expected a skip, got \(outcome)") }
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").map(\.errorCode), ["NETWORK_QUALITY_TOO_LOW"])
        XCTAssertEqual(requestCount, 0)
    }

    func test_gate_unset_doesNotWaitForThePath() async throws {
        fixture.connectivity.set(.unknown)
        let engine = await fixture.makeColdEngine(emitter: emitter)
        await configure(engine, minimum: nil)
        _ = try await engine.storage.enqueue(input: .sample())

        _ = try await engine.dispatcher.flushOutcome()

        XCTAssertTrue(fixture.connectivity.waitTimeouts.isEmpty)
        XCTAssertEqual(requestCount, 1)
    }

    // MARK: - Automatic wakes

    func test_gate_automaticWake_rerunsWhenQualityReachesMinimum() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.probe.answer(latencyMs: 800)
        await configure(engine, minimum: .excellent, healthCheckUrl: healthURL)
        XCTAssertEqual(engine.currentNetwork().quality, .moderate)
        _ = try await enqueueDue(in: engine)
        await engine.rearmRetryTimer()
        emitter.clear()

        await fixture.timer.fire()

        XCTAssertEqual(requestCount, 0, "Below the minimum, the wake does not dispatch")
        XCTAssertTrue(engine.hasWakeAwaitingConnectivity, "It is remembered, like an offline wake")
        XCTAssertTrue(emitter.events(ofType: "SYNC_FAILED").isEmpty, "And, like an offline wake, it is silent")

        fixture.probe.answer(latencyMs: 120)
        fixture.clock.advance(ms: kQualityMeasurementCacheMs)
        fixture.connectivity.set(snapshot: .link())
        await engine.qualityMonitor.waitForProbe()
        await engine.waitForReconnectWake()

        XCTAssertEqual(engine.currentNetwork().quality, .excellent)
        XCTAssertEqual(requestCount, 1, "The remembered wake runs once the grade reaches the minimum")
        XCTAssertFalse(engine.hasWakeAwaitingConnectivity)
    }

    func test_gate_rememberedWake_waitsWhileTheGradeStaysBelow() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configure(engine, minimum: .good)
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))
        _ = try await enqueueDue(in: engine)
        await engine.rearmRetryTimer()
        await fixture.timer.fire()
        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)

        // Still metered, now in Low Data Mode too: still MODERATE.
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true, constrained: true))
        await engine.waitForReconnectWake()
        XCTAssertEqual(requestCount, 0)

        fixture.connectivity.set(snapshot: .link())
        await engine.waitForReconnectWake()
        XCTAssertEqual(requestCount, 1)
    }

    // MARK: - Background tasks

    /// A gated BGTask flush completes the task successfully. The chain was
    /// resubmitted when the task began, so the next attempt is the next
    /// window the OS grants.
    func test_gate_backgroundTask_completesWithoutDispatching() async throws {
        let engine = await fixture.makeColdEngine(emitter: emitter)
        await configure(engine, minimum: .good)
        try engine.background.enable(options: BackgroundSyncOptionsValue(minimumIntervalMs: 900_000,
                                                                         requiresCharging: nil,
                                                                         requiresUnmeteredNetwork: nil,
                                                                         requiresDeviceIdle: nil,
                                                                         foregroundOnly: false,
                                                                         taskIdentifier: nil))
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))
        fixture.scheduler.reset()
        emitter.clear()
        let task = FakeBackgroundTask()

        engine.background.handleAppRefresh(task: task)
        await task.waitForCompletion()

        XCTAssertEqual(task.completedResults, [true])
        XCTAssertEqual(requestCount, 0)
        XCTAssertEqual(emitter.emittedEvents.map(\.type),
                       ["BACKGROUND_SYNC_STARTED", "SYNC_FAILED", "BACKGROUND_SYNC_COMPLETED"])
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "NETWORK_QUALITY_TOO_LOW")
        XCTAssertNil(emitter.events(ofType: "BACKGROUND_SYNC_COMPLETED").first?.errorCode)
        XCTAssertEqual(fixture.scheduler.submittedIdentifiers,
                       [kBackgroundRefreshIdentifier, kBackgroundProcessingIdentifier],
                       "The chain continues")
    }

    // MARK: - Connection reporting

    func test_connection_qualityOnlyChange_emitsConnectionChanged() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.probe.answer(latencyMs: 150)
        emitter.clear()

        await configure(engine, minimum: nil, healthCheckUrl: healthURL)

        XCTAssertEqual(emitter.events(ofType: "CONNECTION_CHANGED").map(\.connectionStatus), ["CONNECTED"],
                       "The link did not change; the grade went from GOOD to EXCELLENT")
        XCTAssertEqual(try connectionState(of: engine).networkQuality.stringValue, "EXCELLENT")

        emitter.clear()
        fixture.connectivity.set(snapshot: .link())
        await engine.qualityMonitor.waitForProbe()
        XCTAssertTrue(emitter.events(ofType: "CONNECTION_CHANGED").isEmpty, "Nothing changed, nothing emitted")
    }

    func test_connection_offlineInvariant() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.probe.answer(latencyMs: 50)
        await configure(engine, minimum: nil, healthCheckUrl: healthURL)
        let links: [ConnectionSnapshot] = [
            .link(), .disconnected, .link(.cellular, expensive: true), .unknown, .link(constrained: true),
        ]
        for link in links {
            fixture.connectivity.set(snapshot: link)
            await engine.qualityMonitor.waitForProbe()

            let state = try connectionState(of: engine)

            let offline = state.networkQuality.stringValue == "OFFLINE"
            let unusable = ["DISCONNECTED", "UNKNOWN"].contains(state.status.stringValue)
            XCTAssertEqual(offline, unusable, "\(link)")
        }
    }
}
