import CoreData
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
        makeDispatcher(config: DispatcherConfig(retryPolicy: policy,
                                                requestTimeoutMs: 30_000,
                                                batchSize: 25,
                                                defaultHeaders: [:]))
    }

    private func makeDispatcher(config: DispatcherConfig,
                                isAppActive: (@Sendable () async -> Bool)? = nil) -> SyncDispatcher {
        SyncDispatcher(storage: storage,
                       emitter: emitter,
                       connectivity: ConnectivityMonitor.shared,
                       config: config,
                       foregroundSession: foregroundSession,
                       backgroundSession: backgroundSession,
                       isAppActive: isAppActive)
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

    private var eventTypes: [String] {
        emitter.emittedEvents.map(\.type).filter { $0 != "SYNC_PROGRESS" }
    }

    /// The server has the item: its row is removed as delivered, on its last
    /// attempt too, and nothing is sent again.
    func test_aFailedRemovalAfterA2xx_onTheLastAttempt_deliversTheItem() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(200))
        let dispatcher = makeDispatcher(.exact(maxAttempts: 1, baseDelayMs: 300))
        faults.fail(1, after: 1)

        await flushExpectingItToDie(dispatcher)

        let queued = try await storage.isQueued(id)
        XCTAssertFalse(queued, "delivered")
        let letters = try await storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0, "a delivered item is no dead letter")
        XCTAssertEqual(eventTypes, ["SYNC_STARTED", "ITEM_SUCCEEDED", "SYNC_FAILED"])
        let succeeded = emitter.events(ofType: "ITEM_SUCCEEDED").first
        XCTAssertEqual(succeeded?.itemId, id)
        XCTAssertEqual(succeeded?.statusCode, 200)
        XCTAssertEqual(succeeded?.attempt, 1)
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "NETWORK_ERROR",
                       "the cycle still died of the write")

        _ = try await dispatcher.flush()
        XCTAssertEqual(URLProtocolStub.capturedRequests.count, 1, "not sent again")
    }

    func test_aFailedWriteAfterA503_retriesWithItsCode_andTheBackoff() async throws {
        try await assertRetriedAfterAFailedOutcomeWrite(stubResponse(503), code: "SERVER_ERROR", statusCode: 503)
    }

    /// A failure during the send is a transport error, which the classifier
    /// turns into an outcome: there is no request that went out without one.
    func test_aFailedWriteAfterALostConnection_countsTheAttempt_withNetworkError() async throws {
        try await assertRetriedAfterAFailedOutcomeWrite({ _ in throw URLError(.networkConnectionLost) },
                                                        code: "NETWORK_ERROR", statusCode: nil)
    }

    func test_aFailedWriteAfterATimeout_keepsTimeout() async throws {
        try await assertRetriedAfterAFailedOutcomeWrite({ _ in throw URLError(.timedOut) },
                                                        code: "TIMEOUT", statusCode: nil)
    }

    private func assertRetriedAfterAFailedOutcomeWrite(_ answer: @escaping URLProtocolHandler,
                                                       code: String,
                                                       statusCode: Double?,
                                                       file: StaticString = #filePath,
                                                       line: UInt = #line) async throws {
        let id = try await storage.enqueue(input: .sample())
        let log = RequestLog()
        let clock = self.clock!
        URLProtocolStub.setHandler { request in
            log.record(at: clock.nowMs())
            return try answer(request)
        }
        let dispatcher = makeDispatcher(.exact(maxAttempts: 3, baseDelayMs: 300))
        faults.fail(1, after: 1)
        let now = clock.nowMs()

        await flushExpectingItToDie(dispatcher, file: file, line: line)

        let stored = try await item(id)
        XCTAssertEqual(stored.status, "PENDING", "not left IN_FLIGHT", file: file, line: line)
        XCTAssertEqual(stored.attempts, 1, "the attempt counts", file: file, line: line)
        XCTAssertEqual(stored.nextRetryAt, now + 300, "the retry policy's first delay", file: file, line: line)
        XCTAssertEqual(stored.lastErrorCode, code, "the outcome's own code", file: file, line: line)
        let letters = try await storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0, file: file, line: line)
        XCTAssertEqual(eventTypes, ["SYNC_STARTED", "ITEM_RETRYING", "SYNC_FAILED"], file: file, line: line)
        let retrying = emitter.events(ofType: "ITEM_RETRYING").first
        XCTAssertEqual(retrying?.errorCode, code, file: file, line: line)
        XCTAssertEqual(retrying?.statusCode, statusCode, file: file, line: line)
        XCTAssertEqual(retrying?.attempt, 1, file: file, line: line)
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "NETWORK_ERROR",
                       "the cycle died of the write", file: file, line: line)

        _ = try await dispatcher.flush()
        XCTAssertEqual(log.times.count, 1, "nothing is sent again before the cooldown ends", file: file, line: line)
    }

    /// A permanent answer ends in the dead-letter store with its own code.
    func test_aFailedDeadLetterWriteAfterA404_deadLettersTheItem_withItsCode() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(404))
        faults.fail(1, after: 1)

        await flushExpectingItToDie(makeDispatcher(.exact(maxAttempts: 3, baseDelayMs: 300)))

        let queued = try await storage.isQueued(id)
        XCTAssertFalse(queued)
        let letter = try await storage.deadLetter(id)
        XCTAssertEqual(letter?.lastErrorCode, "SERVER_ERROR")
        XCTAssertEqual(letter?.lastErrorMessage, "HTTP 404 Not Found")
        XCTAssertEqual(letter?.attempts, 1)
        XCTAssertEqual(eventTypes, ["SYNC_STARTED", "ITEM_FAILED", "ITEM_DEAD_LETTERED", "SYNC_FAILED"])
        let failed = emitter.events(ofType: "ITEM_FAILED").first
        XCTAssertEqual(failed?.errorCode, "SERVER_ERROR")
        XCTAssertEqual(failed?.statusCode, 404)
        XCTAssertEqual(failed?.attempt, 1)
        XCTAssertEqual(emitter.events(ofType: "ITEM_DEAD_LETTERED").first?.metadata?["lastErrorCode"], "SERVER_ERROR")
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "NETWORK_ERROR")
    }

    /// A 401 to default headers read back from storage holds the item as on
    /// the normal path: its attempt refunded, the row held for at least a
    /// minute, and the hold handed to the cycle's observer.
    func test_aFailedWriteAfterAStaleCredentialRejection_holdsTheItem() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(401))
        var config = DispatcherConfig(retryPolicy: .exact(maxAttempts: 3, baseDelayMs: 300),
                                      requestTimeoutMs: 30_000,
                                      batchSize: 25,
                                      defaultHeaders: ["Authorization": "Bearer expired"])
        config.source = .persisted
        config.hasDefaultHeaders = true
        let dispatcher = makeDispatcher(config: config)
        let ends = CycleEnds()
        dispatcher.hooks.onCycleFinished = { notBefore, end in ends.record(end, notBefore: notBefore) }
        faults.fail(1, after: 1)
        let now = clock.nowMs()

        await flushExpectingItToDie(dispatcher)

        let stored = try await item(id)
        XCTAssertEqual(stored.status, "PENDING")
        XCTAssertEqual(stored.attempts, 0, "an expired token says nothing about the item")
        XCTAssertEqual(stored.lastErrorCode, "UNAUTHORIZED")
        XCTAssertEqual(stored.nextRetryAt, now + 60_000)
        XCTAssertEqual(eventTypes, ["SYNC_STARTED", "ITEM_RETRYING", "SYNC_FAILED"])
        let retrying = emitter.events(ofType: "ITEM_RETRYING").first
        XCTAssertEqual(retrying?.errorCode, "UNAUTHORIZED")
        XCTAssertEqual(retrying?.statusCode, 401)
        XCTAssertEqual(retrying?.attempt, 1)
        XCTAssertEqual(ends.all, [.aborted])
        XCTAssertEqual(ends.holds, [now + 60_000], "the hold reaches the wake")
    }

    /// The outcome's write fails in every cycle. Each one counts its attempt
    /// and moves the deadline along the curve, so no wake is due at once, and
    /// the last attempt ends in the dead-letter store.
    func test_anOutcomeWriteThatKeepsFailing_backsOff_andEndsInTheDeadLetter() async throws {
        let id = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
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
        XCTAssertEqual(emitter.events(ofType: "ITEM_RETRYING").map(\.errorCode), ["SERVER_ERROR", "SERVER_ERROR"])
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
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "NETWORK_ERROR")
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
        XCTAssertEqual(emitter.events(ofType: "ITEM_SUCCEEDED").count, 0)
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "NETWORK_ERROR")
        let recovered = try await storage.resetInFlightToPending()
        XCTAssertEqual(recovered, 1)
    }

    /// A purge between the failed outcome write and the settle, a logout say:
    /// the settle changes only a row still IN_FLIGHT, so the purged item stays
    /// gone and gets no event of its own, whatever its answer was.
    func test_aRowPurgedBeforeTheSettle_isLeftAlone_withNoEvent() async throws {
        let container = stack.persistentContainer
        for status in [200, 503, 404] {
            let id = try await storage.enqueue(input: .sample())
            URLProtocolStub.setHandler(stubResponse(status))
            emitter.clear()
            faults.intercept(after: 1) { // the outcome's write
                Self.purge(id, in: container)
                throw NSError(domain: NSCocoaErrorDomain,
                              code: NSPersistentStoreSaveError,
                              userInfo: [NSLocalizedDescriptionKey: "Purged, then failed"])
            }

            await flushExpectingItToDie(makeDispatcher(.exact(maxAttempts: 3, baseDelayMs: 300)))

            let queued = try await storage.isQueued(id)
            XCTAssertFalse(queued, "HTTP \(status): the purge stands")
            let letter = try await storage.deadLetter(id)
            XCTAssertNil(letter, "HTTP \(status): nothing is resurrected")
            XCTAssertEqual(eventTypes, ["SYNC_STARTED", "SYNC_FAILED"], "HTTP \(status)")
        }
    }

    // MARK: - A cancellation before the request reaches URLSession

    /// A row never attempted keeps no attempt time.
    func test_aCancellationBeforeTheSend_leavesANewRowWithNoAttemptTime() async throws {
        let id = try await storage.enqueue(input: .sample())
        try await assertCancelledBeforeTheSend(id)
    }

    /// A row attempted before keeps the time of that attempt, not its claim's.
    func test_aCancellationBeforeTheSend_keepsTheEarlierAttemptTime() async throws {
        let id = try await storage.enqueue(input: .sample())
        try await storage.markInFlight(id: id)
        try await storage.releaseClaim(id: id, attempts: 1, lastAttemptAt: nil)
        clock.advance(ms: 5_000)
        try await assertCancelledBeforeTheSend(id)
    }

    /// The flush is cancelled after the claim and before URLSession gets the
    /// request: nothing went out, so the row comes back as it was before its
    /// claim, its `lastAttemptAt` included.
    private func assertCancelledBeforeTheSend(_ id: String,
                                              file: StaticString = #filePath,
                                              line: UInt = #line) async throws {
        let before = try await item(id)
        URLProtocolStub.setHandler(stubResponse(200))
        let holder = FlushTask()
        let dispatcher = makeDispatcher(config: DispatcherConfig(retryPolicy: .exact(maxAttempts: 3,
                                                                                     baseDelayMs: 300),
                                                                 requestTimeoutMs: 30_000,
                                                                 batchSize: 25,
                                                                 defaultHeaders: [:]),
                                        isAppActive: { holder.cancel(); return true })
        var open: AsyncStream<Void>.Continuation?
        let gate = AsyncStream<Void> { open = $0 }
        let flush = Task { () -> Error? in
            for await _ in gate { break }
            do {
                _ = try await dispatcher.flush()
                return nil
            } catch {
                return error
            }
        }
        holder.set(flush)
        open?.yield(())
        let thrown = await flush.value

        XCTAssertTrue(thrown is CancellationError, file: file, line: line)
        XCTAssertEqual(URLProtocolStub.capturedRequests.count, 0, "nothing went out", file: file, line: line)
        let stored = try await item(id)
        XCTAssertEqual(stored.status, "PENDING", file: file, line: line)
        XCTAssertEqual(stored.attempts, before.attempts, file: file, line: line)
        XCTAssertEqual(stored.lastAttemptAt, before.lastAttemptAt, "the time from before the claim",
                       file: file, line: line)
    }

    /// Deletes the queue row `id` through a context of its own, as a purge by
    /// another caller would.
    private static func purge(_ id: String, in container: NSPersistentContainer) {
        let context = container.newBackgroundContext()
        context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SyncItemEntity")
            request.predicate = NSPredicate(format: "id == %@", id)
            if let row = try? context.fetch(request).first {
                context.delete(row)
                try? context.save()
            }
        }
    }
}

/// A flush running in a task of its own, which a hook can cancel.
private final class FlushTask: @unchecked Sendable {
    private let lock = NSLock()
    private var task: Task<Error?, Never>?

    func set(_ task: Task<Error?, Never>) {
        lock.withLock { self.task = task }
    }

    func cancel() {
        lock.withLock { task }?.cancel()
    }
}

/// The ``CycleEnd`` values an observer received, in order, with the
/// credential hold each one carried.
private final class CycleEnds: @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [(end: CycleEnd, notBefore: Int64?)] = []

    func record(_ end: CycleEnd, notBefore: Int64? = nil) {
        lock.withLock { recorded.append((end, notBefore)) }
    }

    var all: [CycleEnd] {
        lock.withLock { recorded.map(\.end) }
    }

    var holds: [Int64?] {
        lock.withLock { recorded.map(\.notBefore) }
    }
}
