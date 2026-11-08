import XCTest
@testable import SyncProvider

/// Dispatcher-level coverage for the retry cooldown. The storage tests prove
/// the column works; these prove `flush()` actually uses it, and fail if the
/// delay never reaches the row. Time is a ``ManualClock``.
final class SyncDispatcherCooldownTests: XCTestCase {
    private var clock: ManualClock!
    private var stack: CoreDataStack!
    private var storage: SyncQueueStorage!
    private var emitter: MockSyncEventEmitter!
    private var foregroundSession: URLSession!
    private var backgroundSession: URLSession!

    override func setUp() {
        super.setUp()
        clock = ManualClock()
        stack = InMemoryCoreDataStack.make()
        storage = SyncQueueStorage(dao: SyncItemDao(stack: stack, clock: clock), capacity: 100)
        emitter = MockSyncEventEmitter()
        URLProtocolStub.setHandler(nil)
        foregroundSession = URLSession.stubbed()
        backgroundSession = URLSession(configuration: .ephemeral)
    }

    override func tearDown() {
        URLProtocolStub.setHandler(nil)
        foregroundSession.invalidateAndCancel()
        backgroundSession.invalidateAndCancel()
        super.tearDown()
    }

    private func makeDispatcher(_ policy: RetryPolicyValue) -> SyncDispatcher {
        SyncDispatcher(storage: storage,
                       emitter: emitter,
                       connectivity: ConnectivityMonitor.shared,
                       config: DispatcherConfig(retryPolicy: policy,
                                                requestTimeoutMs: 30_000,
                                                batchSize: 25,
                                                defaultHeaders: [:]),
                       foregroundSession: foregroundSession,
                       backgroundSession: backgroundSession)
    }

    private func item(_ id: String) async throws -> StoredSyncItem {
        let all = try await storage.getAll()
        return try XCTUnwrap(all.first { $0.id == id })
    }

    // MARK: - Hot retry loop

    /// A degraded server, with something flushing every 20 ms. Without the
    /// cooldown this is the hot loop: a dispatch on every flush.
    func test_degradedServer_hammeredFlushes_areSpacedByTheBackoffCurve() async throws {
        let id = try await storage.enqueue(input: .sample())
        let log = RequestLog()
        let clock = self.clock!
        URLProtocolStub.setHandler { request in
            log.record(at: clock.nowMs())
            return try stubResponse(503)(request)
        }
        let dispatcher = makeDispatcher(.exact(maxAttempts: 4, baseDelayMs: 300, maxDelayMs: 5_000))

        var flushes = 0
        while try await item(id).status != "FAILED", flushes < 500 {
            _ = try await dispatcher.flush()
            flushes += 1
            clock.advance(ms: 20)
        }

        XCTAssertEqual(log.times.count, 4, "exactly maxAttempts dispatches")
        XCTAssertGreaterThan(flushes, log.times.count, "the loop must outpace the cooldown")
        // 300, 600, 1200 ms, seen through a 20 ms flush interval.
        XCTAssertEqual(log.gaps, [300, 600, 1_200])
        let last = try await item(id)
        XCTAssertEqual(last.status, "FAILED")
        XCTAssertEqual(last.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
    }

    // MARK: - Cooldown write

    /// The clock must be read after the attempt finished: a slow response must
    /// not eat into the cooldown.
    func test_retriableFailure_writesTheDelay_fromTheClockAfterTheAttempt() async throws {
        let id = try await storage.enqueue(input: .sample())
        let clock = self.clock!
        URLProtocolStub.setHandler { request in
            clock.advance(ms: 2_500)
            return try stubResponse(503)(request)
        }
        let before = clock.nowMs()

        _ = try await makeDispatcher(.exact(maxAttempts: 5, baseDelayMs: 5_000)).flush()

        let stored = try await item(id)
        XCTAssertEqual(stored.status, "PENDING")
        XCTAssertEqual(stored.nextRetryAt, before + 2_500 + 5_000)
        XCTAssertEqual(stored.lastAttemptAt, before + 2_500)
    }

    func test_backoffCurveAdvances_acrossCycles() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        let dispatcher = makeDispatcher(.exact(maxAttempts: 5, baseDelayMs: 100))
        let start = clock.nowMs()

        _ = try await dispatcher.flush()
        let first = try await item(id)
        XCTAssertEqual(first.nextRetryAt, start + 100)

        clock.advance(ms: 100)
        _ = try await dispatcher.flush()
        let second = try await item(id)

        XCTAssertEqual(second.attempts, 2)
        XCTAssertEqual(second.nextRetryAt, start + 100 + 200)
    }

    /// `nextRetryAt > 0` means "a cooldown is pending", which the retry timer
    /// watches. A policy with no delay must still write a positive deadline,
    /// not `0` ("eligible now"), which `earliestRetryAt` skips.
    func test_retriableFailure_withZeroDelay_stillWritesAPositiveTimestamp() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        let now = clock.nowMs()

        _ = try await makeDispatcher(.exact(maxAttempts: 5, baseDelayMs: 0)).flush()

        let stored = try await item(id)
        XCTAssertEqual(stored.nextRetryAt, now)
        let wake = try await storage.earliestRetryAt()
        XCTAssertEqual(wake, now, "a zero-delay retry must stay visible to the retry timer")
    }

    func test_coolingItem_isSkipped_untilItsDeadlineElapses() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        let dispatcher = makeDispatcher(.exact(maxAttempts: 5, baseDelayMs: 500))

        _ = try await dispatcher.flush()
        let deadline = try await item(id).nextRetryAt

        clock.advance(ms: deadline - clock.nowMs() - 1)
        _ = try await dispatcher.flush()
        XCTAssertEqual(URLProtocolStub.capturedRequests.count, 1, "one millisecond early is still cooling")

        clock.advance(ms: 1)
        _ = try await dispatcher.flush()
        XCTAssertEqual(URLProtocolStub.capturedRequests.count, 2, "the item is due exactly at its deadline")
    }

    // MARK: - Terminal codes

    func test_nonRetryableFailure_onTheLastAttempt_keepsItsRealCode() async throws {
        let expectations: [(status: Int, code: String)] = [(401, "UNAUTHORIZED"), (403, "UNAUTHORIZED"), (400, "SERVER_ERROR")]
        for (status, code) in expectations {
            let id = try await storage.enqueue(input: .sample(url: "https://api.test/\(status)"))
            URLProtocolStub.setHandler(stubResponse(status))

            _ = try await makeDispatcher(.exact(maxAttempts: 1, baseDelayMs: 1)).flush()

            let stored = try await item(id)
            XCTAssertEqual(stored.status, "FAILED")
            XCTAssertEqual(stored.lastErrorCode, code, "HTTP \(status)")
            try await storage.remove(id: id)
        }
    }

    func test_retriableFailure_withBudgetExhausted_isMaxAttemptsExceeded() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))

        _ = try await makeDispatcher(.exact(maxAttempts: 1, baseDelayMs: 1)).flush()

        let stored = try await item(id)
        XCTAssertEqual(stored.status, "FAILED")
        XCTAssertEqual(stored.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
        XCTAssertEqual(emitter.events(ofType: "ITEM_FAILED").first?.attempt, 1)
    }

    // MARK: - Cycle reporting

    func test_cycleWithOnlyCoolingItems_endsAsAnEmptySuccess() async throws {
        _ = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        let dispatcher = makeDispatcher(.exact(maxAttempts: 5, baseDelayMs: 60_000))
        _ = try await dispatcher.flush()
        emitter.clear()
        let historyBefore = try await storage.getHistory().count

        let result = try await dispatcher.flush()

        XCTAssertEqual(result.itemsAttempted, 0)
        XCTAssertEqual(result.itemsFailed, 0)
        XCTAssertEqual(emitter.emittedEvents.map(\.type), ["SYNC_STARTED", "SYNC_SUCCEEDED"])
        let historyAfter = try await storage.getHistory().count
        XCTAssertEqual(historyAfter, historyBefore + 1)
        let size = try await storage.getSize()
        XCTAssertEqual(size, 1, "the cooling item stays queued")
    }

    func test_cycleEnd_notifiesTheObserver_onEveryOutcome_beforeFlushReturns() async throws {
        let cycles = RequestLog()
        let dispatcher = makeDispatcher(.exact(maxAttempts: 5, baseDelayMs: 60_000))
        dispatcher.hooks.onCycleFinished = { _ in cycles.record() }

        _ = try await dispatcher.flush() // empty
        XCTAssertEqual(cycles.times.count, 1)

        _ = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        _ = try await dispatcher.flush() // failed
        XCTAssertEqual(cycles.times.count, 2)

        await dispatcher.setPaused(true)
        _ = try await dispatcher.flush() // no cycle ran
        XCTAssertEqual(cycles.times.count, 2, "a paused flush is not a cycle")
    }

    func test_cycleStaysMarkedRunning_untilItEnds_andNotDuringTheObserver() async throws {
        let dispatcher = makeDispatcher(.default)
        let runningDuringObserver = RequestLog()
        dispatcher.hooks.onCycleFinished = { [weak dispatcher] _ in
            if await dispatcher?.cycleRunning() == true {
                runningDuringObserver.record()
            }
        }

        _ = try await dispatcher.flush()

        XCTAssertTrue(runningDuringObserver.times.isEmpty,
                      "a wake armed by the observer must be able to start the next cycle")
    }
}
