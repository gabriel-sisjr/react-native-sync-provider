import XCTest
@testable import SyncProvider

/// Dispatcher-level coverage for the retry cooldown. The storage tests prove
/// the column works; these prove `flush()` actually uses it, and fail if the
/// delay never reaches the row. Time is a ``ManualClock``.
final class SyncDispatcherCooldownTests: XCTestCase {
    private var clock: ManualClock!
    private var faults: SaveFaults!
    private var stack: CoreDataStack!
    private var storage: SyncQueueStorage!
    private var emitter: MockSyncEventEmitter!
    private var foregroundSession: URLSession!
    private var backgroundSession: URLSession!

    override func setUp() {
        super.setUp()
        clock = ManualClock()
        faults = SaveFaults()
        stack = InMemoryCoreDataStack.make(saveFaults: faults)
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
        while try await storage.isQueued(id), flushes < 500 {
            _ = try await dispatcher.flush()
            flushes += 1
            clock.advance(ms: 20)
        }

        XCTAssertEqual(log.times.count, 4, "exactly maxAttempts dispatches")
        XCTAssertGreaterThan(flushes, log.times.count, "the loop must outpace the cooldown")
        // 300, 600, 1200 ms, seen through a 20 ms flush interval.
        XCTAssertEqual(log.gaps, [300, 600, 1_200])
        let last = try await storage.deadLetter(id)
        XCTAssertEqual(last?.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
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

    /// No `maxDelayMs` and a huge base: the delay stops at 2^53 - 1 ms, and
    /// the deadline at the largest valid JavaScript `Date`.
    func test_aRetryDeadline_neverPassesTheLargestJavaScriptDate() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))

        _ = try await makeDispatcher(.exact(maxAttempts: 5, baseDelayMs: 9_007_199_254_740_991, maxDelayMs: 0)).flush()

        let stored = try await item(id)
        XCTAssertEqual(stored.status, "PENDING")
        XCTAssertEqual(stored.nextRetryAt, 8_640_000_000_000_000)
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

            let stored = try await storage.deadLetter(id)
            XCTAssertEqual(stored?.lastErrorCode, code, "HTTP \(status)")
            try await storage.remove(id: id)
        }
    }

    func test_retriableFailure_withBudgetExhausted_isMaxAttemptsExceeded() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))

        _ = try await makeDispatcher(.exact(maxAttempts: 1, baseDelayMs: 1)).flush()

        let stored = try await storage.deadLetter(id)
        XCTAssertEqual(stored?.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
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
        let ends = CycleEnds()
        let dispatcher = makeDispatcher(.exact(maxAttempts: 5, baseDelayMs: 60_000))
        dispatcher.hooks.onCycleFinished = { _, end in ends.record(end) }

        _ = try await dispatcher.flush() // empty
        XCTAssertEqual(ends.all, [.completed])

        _ = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        _ = try await dispatcher.flush() // failed
        XCTAssertEqual(ends.all, [.completed, .completed], "an item that failed is a cycle that completed")

        _ = try await storage.enqueue(input: .sample())
        faults.fail(1) // its claim
        await flushExpectingItToDie(dispatcher)
        XCTAssertEqual(ends.all, [.completed, .completed, .aborted])

        await dispatcher.setPaused(true)
        _ = try await dispatcher.flush() // no cycle ran
        XCTAssertEqual(ends.all.count, 3, "a paused flush is not a cycle")
    }

    func test_cycleStaysMarkedRunning_untilItEnds_andNotDuringTheObserver() async throws {
        let dispatcher = makeDispatcher(.default)
        let runningDuringObserver = RequestLog()
        dispatcher.hooks.onCycleFinished = { [weak dispatcher] _, _ in
            if await dispatcher?.cycleRunning() == true {
                runningDuringObserver.record()
            }
        }

        _ = try await dispatcher.flush()

        XCTAssertTrue(runningDuringObserver.times.isEmpty,
                      "a wake armed by the observer must be able to start the next cycle")
    }

    // MARK: - A cycle that dies before an outcome is written

    // In a one-item cycle, the claim is the first save, the outcome's write
    // the second, and the row's settling the third: `fail(1, after: 1)` makes
    // only the outcome's write fail.

    private func flushExpectingItToDie(_ dispatcher: SyncDispatcher,
                                       file: StaticString = #filePath, line: UInt = #line) async {
        do {
            _ = try await dispatcher.flush()
            XCTFail("the cycle was expected to die", file: file, line: line)
        } catch {}
    }

    func test_aFailedWriteAfterA2xx_countsTheAttempt_andCoolsTheRow() async throws {
        try await assertCooledAfterAFailedOutcomeWrite(status: 200)
    }

    func test_aFailedWriteAfterA503_countsTheAttempt_andCoolsTheRow() async throws {
        try await assertCooledAfterAFailedOutcomeWrite(status: 503)
    }

    private func assertCooledAfterAFailedOutcomeWrite(status: Int,
                                                      file: StaticString = #filePath,
                                                      line: UInt = #line) async throws {
        let id = try await storage.enqueue(input: .sample())
        let log = RequestLog()
        let clock = self.clock!
        URLProtocolStub.setHandler { request in
            log.record(at: clock.nowMs())
            return try stubResponse(status)(request)
        }
        let dispatcher = makeDispatcher(.exact(maxAttempts: 3, baseDelayMs: 300))
        faults.fail(1, after: 1)
        let now = clock.nowMs()

        await flushExpectingItToDie(dispatcher, file: file, line: line)

        let stored = try await item(id)
        XCTAssertEqual(stored.status, "PENDING", "not left IN_FLIGHT", file: file, line: line)
        XCTAssertEqual(stored.attempts, 1, "the attempt counts", file: file, line: line)
        XCTAssertEqual(stored.nextRetryAt, now + 300, "the retry policy's first delay", file: file, line: line)
        XCTAssertEqual(stored.lastErrorCode, "INVALID_PAYLOAD", file: file, line: line)
        let letters = try await storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0, file: file, line: line)
        XCTAssertEqual(emitter.emittedEvents.map(\.type).filter { $0 != "SYNC_PROGRESS" },
                       ["SYNC_STARTED", "ITEM_RETRYING", "SYNC_FAILED"], file: file, line: line)
        let retrying = emitter.events(ofType: "ITEM_RETRYING").first
        XCTAssertEqual(retrying?.errorCode, "INVALID_PAYLOAD", file: file, line: line)
        XCTAssertEqual(retrying?.attempt, 1, file: file, line: line)
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "INVALID_PAYLOAD",
                       file: file, line: line)

        _ = try await dispatcher.flush()
        XCTAssertEqual(log.times.count, 1, "nothing is sent again before the cooldown ends", file: file, line: line)
    }

    /// The outcome's write fails in every cycle. Each one counts its attempt
    /// and moves the deadline along the curve, so no wake is due at once, and
    /// the last attempt ends in the dead-letter store.
    func test_anOutcomeWriteThatKeepsFailing_backsOff_andEndsInTheDeadLetter() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(200))
        let dispatcher = makeDispatcher(.exact(maxAttempts: 3, baseDelayMs: 300))
        let start = clock.nowMs()
        var deadlines: [Int64] = []

        for _ in 0..<2 {
            faults.fail(1, after: 1)
            await flushExpectingItToDie(dispatcher)
            let stored = try await item(id)
            deadlines.append(stored.nextRetryAt)
            let wake = try await storage.earliestRetryAt()
            XCTAssertGreaterThan(wake ?? 0, clock.nowMs(), "no wake is due at once")
            clock.advance(ms: stored.nextRetryAt - clock.nowMs())
        }
        faults.fail(1, after: 1)
        await flushExpectingItToDie(dispatcher)

        XCTAssertEqual(deadlines, [start + 300, start + 300 + 600])
        let queued = try await storage.isQueued(id)
        XCTAssertFalse(queued)
        let letter = try await storage.deadLetter(id)
        XCTAssertEqual(letter?.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
        XCTAssertEqual(letter?.attempts, 3)
        XCTAssertEqual(emitter.events(ofType: "ITEM_RETRYING").map(\.attempt), [1, 2])
        XCTAssertEqual(emitter.events(ofType: "ITEM_FAILED").map(\.errorCode), ["MAX_ATTEMPTS_EXCEEDED"])
        XCTAssertEqual(emitter.events(ofType: "ITEM_DEAD_LETTERED").count, 1)
        XCTAssertEqual(emitter.events(ofType: "ITEM_SUCCEEDED").count, 0)
        XCTAssertEqual(URLProtocolStub.capturedRequests.count, 3)
    }

    /// A URL that is not http(s) never goes out. When its dead letter cannot
    /// be written, the row comes back as it was before its claim.
    func test_aFailedWriteForARequestThatNeverWentOut_givesTheRowBackAsItWas() async throws {
        let id = try await storage.enqueue(input: .sample(url: "ftp://api.test/items"))
        let before = try await item(id)
        faults.fail(1, after: 1)

        await flushExpectingItToDie(makeDispatcher(.exact(maxAttempts: 3, baseDelayMs: 300)))

        let stored = try await item(id)
        XCTAssertEqual(stored.status, "PENDING")
        XCTAssertEqual(stored.attempts, before.attempts)
        XCTAssertEqual(stored.lastAttemptAt, before.lastAttemptAt)
        XCTAssertEqual(stored.nextRetryAt, before.nextRetryAt)
        XCTAssertEqual(stored.lastErrorCode, before.lastErrorCode)
        let letters = try await storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0)
        XCTAssertEqual(emitter.emittedEvents.map(\.type).filter { $0.hasPrefix("ITEM_") }, [])
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "INVALID_PAYLOAD")
    }

    /// When settling the row fails too, it waits IN_FLIGHT for launch recovery.
    func test_aSettleThatFailsToo_leavesTheRowInFlight_forRecovery() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(200))
        faults.fail(2, after: 1)

        await flushExpectingItToDie(makeDispatcher(.exact(maxAttempts: 3, baseDelayMs: 300)))

        let stored = try await item(id)
        XCTAssertEqual(stored.status, "IN_FLIGHT")
        XCTAssertEqual(faults.failures, 2)
        XCTAssertEqual(emitter.events(ofType: "ITEM_RETRYING").count, 0)
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "INVALID_PAYLOAD")
        let recovered = try await storage.resetInFlightToPending()
        XCTAssertEqual(recovered, 1)
    }
}

/// The ``CycleEnd`` values an observer received, in order.
private final class CycleEnds: @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [CycleEnd] = []

    func record(_ end: CycleEnd) {
        lock.withLock { recorded.append(end) }
    }

    var all: [CycleEnd] {
        lock.withLock { recorded }
    }
}
