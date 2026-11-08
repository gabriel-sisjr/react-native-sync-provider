import XCTest
@testable import SyncProvider

/// The HEAD to `healthCheckUrl` before a cycle: when it is sent, what it
/// decides, the cache that spares it and the backoff after a failure. The
/// engine tests are named after the parity scenarios the Android suite runs.
final class HealthCheckTests: XCTestCase {
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

    private func options(healthCheckUrl: String?,
                         ttlMs: Double? = nil,
                         backoffMs: Double? = nil,
                         minimum: NetworkQualityLevel? = nil,
                         defaultHeaders: [String: String] = [:]) -> SyncOptionsSnapshot {
        var options = SyncOptionsSnapshot.sample(retryPolicy: .exact(maxAttempts: 5, baseDelayMs: 50),
                                                 defaultHeaders: defaultHeaders)
        options.healthCheckUrl = healthCheckUrl
        options.healthCheckTtlMs = ttlMs
        options.healthCheckBackoffMs = backoffMs
        options.minNetworkQuality = minimum
        return options
    }

    /// Configures the health check after a measurement whose samples answered
    /// 404: they grade the link but refresh no "last healthy". The next HEAD
    /// is the health check's own.
    private func configureWithAnUnrefreshedCache(_ engine: SyncEngine,
                                                 in fixture: EngineFixture,
                                                 ttlMs: Double? = nil,
                                                 backoffMs: Double? = nil,
                                                 minimum: NetworkQualityLevel? = nil) async {
        fixture.probe.answer(latencyMs: 100, statusCode: 404)
        await engine.configure(options(healthCheckUrl: healthURL, ttlMs: ttlMs, backoffMs: backoffMs, minimum: minimum))
        await engine.qualityMonitor.waitForProbe()
        XCTAssertEqual(fixture.probe.callCount, 3, "The measurement's three HEADs")
    }

    private func assertSkippedByTheHealthCheck(_ outcome: FlushOutcome,
                                               file: StaticString = #filePath,
                                               line: UInt = #line) {
        guard case let .skipped(result, .healthCheckFailed) = outcome else {
            return XCTFail("Expected a health-check skip, got \(outcome)", file: file, line: line)
        }
        XCTAssertEqual(result.itemsAttempted, 0, file: file, line: line)
        XCTAssertEqual(result.startedAt, result.finishedAt, "The no-cycle shape", file: file, line: line)
    }

    // MARK: - When it is sent

    func test_health_unset_neverProbes() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await engine.configure(options(healthCheckUrl: nil))
        _ = try await engine.storage.enqueue(input: .sample())

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case .ran = outcome else { return XCTFail("Expected a cycle, got \(outcome)") }
        XCTAssertEqual(fixture.probe.callCount, 0)
        XCTAssertEqual(requestCount, 1)
    }

    func test_health_2xx_proceeds_andIsCachedForTtl() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configureWithAnUnrefreshedCache(engine, in: fixture)
        fixture.probe.answer(latencyMs: 100, statusCode: 204)

        _ = try await engine.storage.enqueue(input: .sample())
        let first = try await engine.dispatcher.flushOutcome()
        guard case .ran = first else { return XCTFail("Expected a cycle, got \(first)") }
        XCTAssertEqual(fixture.probe.callCount, 4, "One HEAD before the first cycle")
        XCTAssertEqual(requestCount, 1)

        fixture.clock.advance(ms: Int64(SyncOptionsSnapshot.defaultHealthCheckTtlMs) - 1)
        _ = try await engine.storage.enqueue(input: .sample())
        _ = try await engine.dispatcher.flushOutcome()
        XCTAssertEqual(fixture.probe.callCount, 4, "No second HEAD within the TTL")
        XCTAssertEqual(requestCount, 2)

        fixture.clock.advance(ms: 1)
        _ = try await engine.storage.enqueue(input: .sample())
        _ = try await engine.dispatcher.flushOutcome()
        XCTAssertEqual(fixture.probe.callCount, 5, "The TTL is over: checked again")
    }

    func test_health_qualitySample2xx_refreshesLastHealthy() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.probe.answer(latencyMs: 100, statusCode: 200)
        await engine.configure(options(healthCheckUrl: healthURL))
        await engine.qualityMonitor.waitForProbe()
        _ = try await engine.storage.enqueue(input: .sample())

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case .ran = outcome else { return XCTFail("Expected a cycle, got \(outcome)") }
        XCTAssertEqual(fixture.probe.callCount, 3, "The measurement's 2xx spared the health-check HEAD")
    }

    /// Order at the head of a flush: the quality gate, then the health check.
    func test_health_comesAfterTheQualityGate() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        fixture.connectivity.set(snapshot: .link(.cellular, expensive: true))
        await configureWithAnUnrefreshedCache(engine, in: fixture, minimum: .excellent)
        _ = try await engine.storage.enqueue(input: .sample())
        emitter.clear()

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case .skipped(_, .networkQualityTooLow) = outcome else { return XCTFail("Expected the gate, got \(outcome)") }
        XCTAssertEqual(fixture.probe.callCount, 3, "Gated: no health-check HEAD")
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").map(\.errorCode), ["NETWORK_QUALITY_TOO_LOW"])
    }

    // MARK: - Failure and backoff

    func test_health_failure_skipsCycle_andStartsBackoff() async throws {
        let failures: [(HeadProbeResult, String)] = [
            (.response(statusCode: 404, latencyMs: 50), "404"),
            (.response(statusCode: 500, latencyMs: 50), "500"),
            (.failure(kind: .timeout, message: "The probe got no response within 2000 ms"), "timeout"),
        ]
        for (failure, label) in failures {
            let fixture = EngineFixture()
            defer { fixture.tearDown() }
            let emitter = MockSyncEventEmitter()
            URLProtocolStub.setHandler(stubResponse(200))
            let engine = await fixture.makeEngine(emitter: emitter)
            await configureWithAnUnrefreshedCache(engine, in: fixture)
            _ = try await engine.storage.enqueue(input: .sample())
            let before = try await engine.storage.getAll()
            fixture.probe.script([failure], then: .response(statusCode: 200, latencyMs: 50))
            emitter.clear()

            let outcome = try await engine.dispatcher.flushOutcome()

            assertSkippedByTheHealthCheck(outcome)
            XCTAssertEqual(emitter.emittedEvents.map(\.type), ["SYNC_FAILED"], "\(label): no SYNC_STARTED")
            XCTAssertEqual(emitter.emittedEvents.first?.errorCode, "HEALTH_CHECK_FAILED", label)
            XCTAssertEqual(fixture.probe.callCount, 4, label)
            XCTAssertEqual(requestCount, 0, label)
            let history = try await engine.storage.getHistory(limit: 10)
            XCTAssertTrue(history.isEmpty, "\(label): no history row")
            let after = try await engine.storage.getAll()
            XCTAssertEqual(after, before, "\(label): no item changes")
            XCTAssertEqual(fixture.timer.delayMs, 30_000, "\(label): the next wake waits for the backoff end")

            emitter.clear()
            let inBackoff = try await engine.dispatcher.flushOutcome()
            assertSkippedByTheHealthCheck(inBackoff)
            XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").map(\.errorCode), ["HEALTH_CHECK_FAILED"], label)
            XCTAssertEqual(fixture.probe.callCount, 4, "\(label): inside the backoff, no HEAD")

            fixture.clock.advance(ms: 30_000)
            let afterBackoff = try await engine.dispatcher.flushOutcome()
            guard case .ran = afterBackoff else { return XCTFail("\(label): expected a cycle, got \(afterBackoff)") }
            XCTAssertEqual(fixture.probe.callCount, 5, label)
            XCTAssertEqual(requestCount, 1, label)
        }
    }

    func test_health_backoffAndTtlOptions_areHonored() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configureWithAnUnrefreshedCache(engine, in: fixture, ttlMs: 5_000, backoffMs: 1_000)
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.probe.script([.response(statusCode: 503, latencyMs: 50)], then: .response(statusCode: 200, latencyMs: 50))

        assertSkippedByTheHealthCheck(try await engine.dispatcher.flushOutcome())
        XCTAssertEqual(fixture.timer.delayMs, 1_000)
        fixture.clock.advance(ms: 1_000)
        _ = try await engine.dispatcher.flushOutcome()
        XCTAssertEqual(fixture.probe.callCount, 5, "The 1 s backoff is over")

        fixture.clock.advance(ms: 4_999)
        _ = try await engine.storage.enqueue(input: .sample())
        _ = try await engine.dispatcher.flushOutcome()
        XCTAssertEqual(fixture.probe.callCount, 5, "Inside the 5 s TTL")
        fixture.clock.advance(ms: 1)
        _ = try await engine.storage.enqueue(input: .sample())
        _ = try await engine.dispatcher.flushOutcome()
        XCTAssertEqual(fixture.probe.callCount, 6)
    }

    func test_health_success_and_configure_clearBackoff() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configureWithAnUnrefreshedCache(engine, in: fixture)
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.probe.script([.response(statusCode: 500, latencyMs: 50)], then: .response(statusCode: 200, latencyMs: 50))
        assertSkippedByTheHealthCheck(try await engine.dispatcher.flushOutcome())
        XCTAssertNotNil(engine.healthCheck.backoffUntilMs)

        await engine.configure(options(healthCheckUrl: healthURL))

        XCTAssertNil(engine.healthCheck.backoffUntilMs, "Every configureSync ends the backoff")
        let afterConfigure = try await engine.dispatcher.flushOutcome()
        guard case .ran = afterConfigure else { return XCTFail("Expected a cycle, got \(afterConfigure)") }
        XCTAssertEqual(fixture.probe.callCount, 5, "Checked at once, not after the backoff")
        XCTAssertNil(engine.healthCheck.backoffUntilMs, "A success leaves no backoff")
    }

    /// A timer wake during a backoff is held like a gated one: silent, no
    /// HEAD, and back at the end of the backoff, where it runs.
    func test_health_timerWakeDuringBackoff_isSilent_andRunsAtTheEnd() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configureWithAnUnrefreshedCache(engine, in: fixture)
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.probe.script([.response(statusCode: 500, latencyMs: 50)], then: .response(statusCode: 200, latencyMs: 50))
        assertSkippedByTheHealthCheck(try await engine.dispatcher.flushOutcome())
        emitter.clear()

        await fixture.timer.fire()

        XCTAssertTrue(emitter.emittedEvents.isEmpty, "\(emitter.emittedEvents.map(\.type))")
        XCTAssertEqual(fixture.probe.callCount, 4)
        XCTAssertEqual(requestCount, 0)
        XCTAssertEqual(fixture.timer.delayMs, 30_000, "Back at the end of the backoff")

        fixture.clock.advance(ms: 30_000)
        await fixture.timer.fire()

        XCTAssertEqual(fixture.probe.callCount, 5)
        XCTAssertEqual(requestCount, 1)
    }

    /// A backoff of 0 must not let a check that keeps failing drive automatic
    /// retries back to back: each waits at least a second after the failure,
    /// so they send at most one HEAD a second. An explicit flush still checks
    /// at once.
    func test_health_zeroBackoff_automaticRetriesWaitASecond() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        await configureWithAnUnrefreshedCache(engine, in: fixture, backoffMs: 0)
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.probe.script([], then: .response(statusCode: 503, latencyMs: 50))

        assertSkippedByTheHealthCheck(try await engine.dispatcher.flushOutcome())
        XCTAssertEqual(fixture.probe.callCount, 4)
        XCTAssertEqual(fixture.timer.delayMs, 1_000, "The re-arm waits a second, not 0")

        emitter.clear()
        fixture.clock.advance(ms: 999)
        await fixture.timer.fire()

        XCTAssertEqual(fixture.probe.callCount, 4, "No HEAD within the second")
        XCTAssertTrue(emitter.emittedEvents.isEmpty, "\(emitter.emittedEvents.map(\.type))")
        XCTAssertEqual(fixture.timer.delayMs, 1, "Back at the end of the second")

        fixture.clock.advance(ms: 1)
        await fixture.timer.fire()

        XCTAssertEqual(fixture.probe.callCount, 5, "One HEAD for the second")
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").map(\.errorCode), ["HEALTH_CHECK_FAILED"])
        XCTAssertEqual(fixture.timer.delayMs, 1_000, "And the next a second after it")
        XCTAssertEqual(requestCount, 0)

        assertSkippedByTheHealthCheck(try await engine.dispatcher.flushOutcome())
        XCTAssertEqual(fixture.probe.callCount, 6, "An explicit flush is not held")
    }

    func test_health_backgroundTask_failedCheck_completesTheTask() async throws {
        let engine = await fixture.makeColdEngine(emitter: emitter)
        await configureWithAnUnrefreshedCache(engine, in: fixture)
        try engine.background.enable(options: BackgroundSyncOptionsValue(minimumIntervalMs: 900_000,
                                                                         requiresCharging: nil,
                                                                         requiresUnmeteredNetwork: nil,
                                                                         requiresDeviceIdle: nil,
                                                                         foregroundOnly: false,
                                                                         taskIdentifier: nil))
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.probe.script([.response(statusCode: 500, latencyMs: 50)])
        emitter.clear()
        let task = FakeBackgroundTask()

        engine.background.handleAppRefresh(task: task)
        await task.waitForCompletion()

        XCTAssertEqual(task.completedResults, [true])
        XCTAssertEqual(emitter.emittedEvents.map(\.type),
                       ["BACKGROUND_SYNC_STARTED", "SYNC_FAILED", "BACKGROUND_SYNC_COMPLETED"])
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "HEALTH_CHECK_FAILED")
        XCTAssertEqual(requestCount, 0)
    }

    // MARK: - The request

    /// The real probe, through the stub: every HEAD to the URL, the
    /// measurement's and the health check's, goes out bare, while the item
    /// carries the default headers.
    func test_health_noAuthHeaders_and_noCache() async throws {
        let fixture = EngineFixture()
        defer { fixture.tearDown() }
        fixture.probeOverride = HeadProbe(protocolClasses: [URLProtocolStub.self])
        URLProtocolStub.setHandler { request in
            let heads = URLProtocolStub.capturedRequests.filter { $0.httpMethod == "HEAD" }.count
            // The measurement's three HEADs refresh nothing; the fourth is the check's.
            let status = request.httpMethod == "HEAD" && heads <= 3 ? 404 : 200
            return (HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!,
                    Data())
        }
        let engine = await fixture.makeEngine(emitter: emitter)
        await engine.configure(options(healthCheckUrl: healthURL, defaultHeaders: ["Authorization": "Bearer secret"]))
        await engine.qualityMonitor.waitForProbe()
        _ = try await engine.storage.enqueue(input: .sample())

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case .ran = outcome else { return XCTFail("Expected a cycle, got \(outcome)") }
        let requests = URLProtocolStub.capturedRequests
        let heads = requests.filter { $0.httpMethod == "HEAD" }
        XCTAssertEqual(heads.count, 4, "Three for the measurement, one for the check")
        for head in heads {
            XCTAssertEqual(head.url?.absoluteString, healthURL)
            XCTAssertEqual(head.allHTTPHeaderFields ?? [:], [:], "No auth, default or cookie headers")
            XCTAssertEqual(head.cachePolicy, .reloadIgnoringLocalAndRemoteCacheData)
        }
        let item = try XCTUnwrap(requests.first { $0.httpMethod == "POST" })
        XCTAssertEqual(item.value(forHTTPHeaderField: "Authorization"), "Bearer secret")
    }

    // MARK: - The cache and the backoff, directly

    func test_overlappingChecks_shareOneHead() async throws {
        let probe = FakeHeadProbe()
        let check = HealthCheck(probe: probe, clock: ManualClock())
        let url = URL(string: healthURL)!
        probe.hold()

        let first = Task { await check.check(url: url, ttlMs: 60_000, backoffMs: 30_000) }
        let held = await probe.waitUntilHeld()
        XCTAssertTrue(held)
        let second = Task { await check.check(url: url, ttlMs: 60_000, backoffMs: 30_000) }
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(probe.callCount, 1, "The second check waits for the first one's HEAD")
        probe.release()

        let verdicts = await [first.value, second.value]
        XCTAssertEqual(verdicts, [.healthy, .healthy])
        XCTAssertEqual(probe.callCount, 1)
    }

    func test_cache_isPerURL() async {
        let probe = FakeHeadProbe()
        let check = HealthCheck(probe: probe, clock: ManualClock())
        let url = URL(string: healthURL)!
        check.recordHealthy(url: url)

        _ = await check.check(url: url, ttlMs: 60_000, backoffMs: 30_000)
        XCTAssertEqual(probe.callCount, 0)
        _ = await check.check(url: URL(string: "https://other.test/health")!, ttlMs: 60_000, backoffMs: 30_000)
        XCTAssertEqual(probe.callCount, 1, "A 2xx for one URL says nothing about another")
    }

    func test_zeroTtl_alwaysProbes_andZeroBackoff_neverHolds() async {
        let probe = FakeHeadProbe()
        let check = HealthCheck(probe: probe, clock: ManualClock())
        let url = URL(string: healthURL)!

        _ = await check.check(url: url, ttlMs: 0, backoffMs: 0)
        _ = await check.check(url: url, ttlMs: 0, backoffMs: 0)
        XCTAssertEqual(probe.callCount, 2)

        probe.script([.response(statusCode: 500, latencyMs: 50)])
        let failed = await check.check(url: url, ttlMs: 0, backoffMs: 0)
        XCTAssertNotEqual(failed, .healthy)
        XCTAssertNil(check.backoffUntilMs)
        let next = await check.check(url: url, ttlMs: 0, backoffMs: 0)
        XCTAssertEqual(next, .healthy)
        XCTAssertEqual(probe.callCount, 4)
    }

    /// Automatic retries wait for the backoff, but never less than the floor
    /// after a failure; a success ends the wait.
    func test_retryBackoff_isTheBackoff_flooredAfterAFailure() async {
        let probe = FakeHeadProbe()
        let clock = ManualClock()
        let check = HealthCheck(probe: probe, clock: clock)
        let url = URL(string: healthURL)!
        XCTAssertNil(check.retryBackoffUntilMs(floorMs: 1_000), "No failure yet")

        probe.script([.response(statusCode: 500, latencyMs: 50)])
        let failedAt = clock.nowMs()
        _ = await check.check(url: url, ttlMs: 0, backoffMs: 0)
        XCTAssertNil(check.backoffUntilMs, "Checks are not held")
        XCTAssertEqual(check.retryBackoffUntilMs(floorMs: 1_000), failedAt + 1_000)
        clock.advance(ms: 1_000)
        XCTAssertNil(check.retryBackoffUntilMs(floorMs: 1_000))

        probe.script([.response(statusCode: 500, latencyMs: 50)])
        let longFailedAt = clock.nowMs()
        _ = await check.check(url: url, ttlMs: 0, backoffMs: 30_000)
        XCTAssertEqual(check.retryBackoffUntilMs(floorMs: 1_000), longFailedAt + 30_000, "A longer backoff stands")

        check.clearBackoff()
        XCTAssertNil(check.retryBackoffUntilMs(floorMs: 1_000), "configureSync ends the wait")
    }

    /// Ages run on the wall clock, which can move back: a cached 2xx with a
    /// negative age is stale, and a backoff with a negative elapsed time is
    /// over.
    func test_clockMovedBack_neitherSparesAHeadNorHoldsABackoff() async {
        let probe = FakeHeadProbe()
        let clock = ManualClock()
        let check = HealthCheck(probe: probe, clock: clock)
        let url = URL(string: healthURL)!
        check.recordHealthy(url: url)

        clock.advance(ms: -1)
        _ = await check.check(url: url, ttlMs: 60_000, backoffMs: 30_000)
        XCTAssertEqual(probe.callCount, 1)

        probe.script([.response(statusCode: 500, latencyMs: 50)])
        clock.advance(ms: 120_000)
        _ = await check.check(url: url, ttlMs: 60_000, backoffMs: 30_000)
        XCTAssertNotNil(check.backoffUntilMs)
        clock.advance(ms: -1)
        XCTAssertNil(check.backoffUntilMs)
        let afterTheJump = await check.check(url: url, ttlMs: 60_000, backoffMs: 30_000)
        XCTAssertEqual(afterTheJump, .healthy)
        XCTAssertEqual(probe.callCount, 3)
    }
}
