import XCTest
@testable import SyncProvider

/// The abort/settle matrix, iOS column: what is left of a claimed row when a
/// write fails. Rows, what the attempt came to: R1 a 2xx; R2 a retryable
/// answer with attempts left; R2b one on the last attempt; R2t a timeout; R3
/// a permanent answer; R4 a 401 to default headers read back from storage;
/// R6 a request that never went out; R7 a BGTask that expires. Columns: C1,
/// the outcome's write fails, the cycle dies and the settle's own write
/// succeeds (for R7, the expiry gives the row back); C2, that write fails
/// too. Each test pins the row's state, attempts and times, the events in
/// order and the next automatic wake.
///
/// There is no R5, a request that went out with no outcome classified: an
/// iOS send ends with an answer, a transport error the classifier turns into
/// an outcome, or a cancellation, never with an error of its own.
///
/// The row under test was attempted once, at `t0`, failed with a 503 and is
/// due at `t1`, when the cycle claims it. A request that goes out takes
/// `requestMs`, and an outcome write that fails takes `writeMs`, so the
/// settle runs after the attempt ended. In a one-row cycle the claim is the
/// first save, the outcome's write the second and the settle's the third.
/// Time is manual, and `flush()` returns after the wake was armed.
final class SettleMatrixTests: XCTestCase {
    private var fixture: EngineFixture!
    private var emitter: MockSyncEventEmitter!
    private var clock: ManualClock { fixture.clock }
    private var timer: ManualWakeTimer { fixture.timer }
    private var faults: SaveFaults { fixture.saveFaults }

    private var t0: Int64 = 0
    private let seedDelayMs: Int64 = 100
    private let requestMs: Int64 = 7
    private let writeMs: Int64 = 500
    private var t1: Int64 { t0 + seedDelayMs }
    /// When an attempt that went out ended.
    private var ended: Int64 { t1 + requestMs }
    /// When the settle of an attempt that went out ran.
    private var settled: Int64 { ended + writeMs }
    /// A row that is not due during the cycle, to show the wake after it.
    private let witnessDelayMs: Int64 = 10_000
    /// The largest delay the retry curve computes, 2^53 - 1 ms.
    private let largestDelayMs = 9_007_199_254_740_991

    override func setUp() {
        super.setUp()
        fixture = EngineFixture()
        emitter = MockSyncEventEmitter()
        t0 = fixture.clock.nowMs()
        URLProtocolStub.setHandler(nil)
    }

    override func tearDown() {
        fixture.tearDown()
        URLProtocolStub.setHandler(nil)
        super.tearDown()
    }

    // MARK: - Fixture

    /// An engine the app configured in this process: a 401 is final.
    private func liveEngine(maxAttempts: Int = 5,
                            baseDelayMs: Int = 100,
                            maxDelayMs: Int = 600_000) async -> SyncEngine {
        let engine = await fixture.makeEngine(emitter: emitter)
        let policy = RetryPolicyValue.exact(maxAttempts: maxAttempts, baseDelayMs: baseDelayMs, maxDelayMs: maxDelayMs)
        await engine.configure(.sample(retryPolicy: policy))
        return engine
    }

    /// A relaunched app that has not configured yet: it sends the default
    /// headers read back from storage, so a 401 is the credential hold.
    private func persistedCredentialEngine(baseDelayMs: Int = 100, maxDelayMs: Int = 600_000) async -> SyncEngine {
        let first = await fixture.makeEngine()
        let policy = RetryPolicyValue.exact(maxAttempts: 5, baseDelayMs: baseDelayMs, maxDelayMs: maxDelayMs)
        await first.configure(.sample(retryPolicy: policy, defaultHeaders: ["Authorization": "Bearer expired"]))
        first.shutdown()
        return await fixture.makeEngine(emitter: emitter)
    }

    /// Queues a row attempted once, now, that failed with a 503 and cools for
    /// `delayMs`.
    @discardableResult
    private func seed(_ engine: SyncEngine,
                      url: String = "https://api.test/items",
                      priority: String = "HIGH",
                      delayMs: Int64 = 100) async throws -> String {
        let id = try await engine.storage.enqueue(input: .sample(url: url, priority: priority))
        try await engine.storage.markInFlight(id: id)
        try await engine.storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: Int(delayMs))
        return id
    }

    private func seedWitness(_ engine: SyncEngine) async throws {
        try await seed(engine, url: "https://api.test/witness", priority: "LOW", delayMs: witnessDelayMs)
    }

    /// Moves the clock to `t1` and arms the wake for the due row, so the
    /// wake after the cycle is the one the cycle's end armed.
    private func armForTheDueRow(_ engine: SyncEngine, file: StaticString = #filePath, line: UInt = #line) async {
        clock.advance(ms: t1 - clock.nowMs())
        await engine.rearmRetryTimer()
        XCTAssertEqual(timer.delayMs, 0, "the row is due", file: file, line: line)
    }

    /// Every request takes `requestMs`, then gets `status`.
    private func answer(_ status: Int) {
        let clock = self.clock
        let requestMs = self.requestMs
        URLProtocolStub.setHandler { request in
            clock.advance(ms: requestMs)
            return try stubResponse(status)(request)
        }
    }

    /// Every request takes `requestMs`, then fails with `code`.
    private func failTheRequest(with code: URLError.Code) {
        let clock = self.clock
        let requestMs = self.requestMs
        URLProtocolStub.setHandler { _ in
            clock.advance(ms: requestMs)
            throw URLError(code)
        }
    }

    /// Runs one cycle, which must die: the outcome's write takes `writeMs`
    /// and fails, and for C2 the settle's write fails too.
    private func runACycleThatDies(_ engine: SyncEngine,
                                   settleFails: Bool,
                                   file: StaticString = #filePath,
                                   line: UInt = #line) async {
        let count = settleFails ? 2 : 1
        let before = faults.failures
        let clock = self.clock
        let writeMs = self.writeMs
        faults.intercept(after: 1) { clock.advance(ms: writeMs) }
        faults.fail(count, after: 1)
        emitter.clear()
        do {
            _ = try await engine.dispatcher.flush()
            XCTFail("the cycle was expected to die", file: file, line: line)
        } catch {}
        XCTAssertEqual(faults.failures - before, count, "only the intended writes failed", file: file, line: line)
    }

    private func enableBackgroundSync(_ engine: SyncEngine) throws {
        try engine.background.enable(options: BackgroundSyncOptionsValue(minimumIntervalMs: 900_000,
                                                                         requiresCharging: nil,
                                                                         requiresUnmeteredNetwork: nil,
                                                                         requiresDeviceIdle: nil,
                                                                         foregroundOnly: false,
                                                                         taskIdentifier: nil))
    }

    /// Fires a processing window and returns once all of its work is done.
    /// The work waits until the handler has returned, so the task's
    /// expiration handler is set before anything is claimed.
    private func runAWindow(_ engine: SyncEngine, _ task: FakeBackgroundTask) async {
        var open: AsyncStream<Void>.Continuation?
        let gate = AsyncStream<Void> { open = $0 }
        engine.background.setPrepare {
            for await _ in gate { break }
        }
        emitter.clear()
        let work = engine.background.handleProcessing(task: task)
        open?.yield(())
        await work?.value
    }

    /// The events since the cycle started, as `TYPE` or `TYPE(errorCode)`.
    private var events: [String] {
        emitter.emittedEvents.map { event in
            event.errorCode.map { "\(event.type)(\($0))" } ?? event.type
        }
    }

    private var requestCount: Int {
        URLProtocolStub.capturedRequests.count
    }

    private func row(_ id: String, in engine: SyncEngine) async throws -> StoredSyncItem? {
        try await engine.storage.getAll().first { $0.id == id }
    }

    private func assertRow(_ id: String,
                           in engine: SyncEngine,
                           status: String,
                           attempts: Int32,
                           lastAttemptAt: Int64?,
                           nextRetryAt: Int64,
                           lastErrorCode: String?,
                           file: StaticString = #filePath,
                           line: UInt = #line) async throws {
        let found = try await row(id, in: engine)
        let stored = try XCTUnwrap(found, "the row is queued", file: file, line: line)
        XCTAssertEqual(stored.status, status, "status", file: file, line: line)
        XCTAssertEqual(stored.attempts, attempts, "attempts", file: file, line: line)
        XCTAssertEqual(stored.lastAttemptAt, lastAttemptAt, "lastAttemptAt", file: file, line: line)
        XCTAssertEqual(stored.nextRetryAt, nextRetryAt, "nextRetryAt", file: file, line: line)
        XCTAssertLessThanOrEqual(stored.nextRetryAt, RetryPolicyEvaluator.maxDeadlineMs, file: file, line: line)
        XCTAssertEqual(stored.lastErrorCode, lastErrorCode, "lastErrorCode", file: file, line: line)
    }

    /// What every C2 cell leaves: the row IN_FLIGHT for launch recovery, with
    /// the claim's attempt and time and its deadline as it was, no event of
    /// its own, and no wake for it, not even from the arm `resumeSync()`
    /// uses. A due row the cycle never saw waits for the 30 s after it.
    private func assertLeftInFlight(_ id: String,
                                    in engine: SyncEngine,
                                    file: StaticString = #filePath,
                                    line: UInt = #line) async throws {
        try await assertRow(id, in: engine, status: "IN_FLIGHT", attempts: 2, lastAttemptAt: t1,
                            nextRetryAt: t1, lastErrorCode: "SERVER_ERROR", file: file, line: line)
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0, file: file, line: line)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "SYNC_FAILED(NETWORK_ERROR)"], file: file, line: line)
        XCTAssertNil(timer.delayMs, "an IN_FLIGHT row arms no wake", file: file, line: line)
        await engine.rearmRetryTimer(scope: .allPending, waitsAfterAbort: false)
        XCTAssertNil(timer.delayMs, "not even the arm resumeSync() uses", file: file, line: line)

        try await seed(engine, url: "https://api.test/unseen", priority: "LOW", delayMs: 0)
        await engine.rearmRetryTimer()
        XCTAssertEqual(timer.delayMs, 30_000, "the next arm", file: file, line: line)
        let recovered = try await engine.storage.resetInFlightToPending()
        XCTAssertEqual(recovered, 1, "launch recovery puts it back", file: file, line: line)
    }

    // MARK: - R1: a 2xx

    /// The removal fails on the item's last attempt: the settle removes the
    /// row as delivered, with no dead letter and no resend. The witness, due
    /// sooner, waits for the 30 s that follow a dead cycle.
    func test_R1C1_a2xx_onTheLastAttempt_isDelivered_andTheWaitStarts() async throws {
        let engine = await liveEngine(maxAttempts: 2)
        let id = try await seed(engine)
        try await seedWitness(engine)
        await armForTheDueRow(engine)
        answer(200)

        await runACycleThatDies(engine, settleFails: false)

        let stored = try await row(id, in: engine)
        XCTAssertNil(stored, "delivered")
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0, "a delivered item is no dead letter")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_SUCCEEDED", "SYNC_FAILED(NETWORK_ERROR)"])
        let succeeded = emitter.events(ofType: "ITEM_SUCCEEDED").first
        XCTAssertEqual(succeeded?.itemId, id)
        XCTAssertEqual(succeeded?.statusCode, 200)
        XCTAssertEqual(succeeded?.attempt, 2)
        XCTAssertEqual(timer.delayMs, 30_000, "the witness is due sooner")

        _ = try await engine.dispatcher.flush()
        XCTAssertEqual(requestCount, 1, "not sent again")
    }

    /// The removal after the 2xx throws a CancellationError that is not the
    /// cycle's own: the settle still removes the row as delivered. The cycle
    /// ends without a code, as a cancelled one does, and counts as one that
    /// died for the wait after it.
    func test_R1C1_aForeignCancellationAfterA2xx_stillDeliversTheItem() async throws {
        let engine = await liveEngine(maxAttempts: 2)
        let id = try await seed(engine)
        try await seedWitness(engine)
        await armForTheDueRow(engine)
        answer(200)
        faults.intercept(after: 1) { throw CancellationError() } // the removal
        emitter.clear()

        do {
            _ = try await engine.dispatcher.flush()
            XCTFail("the cycle was expected to die")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }

        let stored = try await row(id, in: engine)
        XCTAssertNil(stored, "delivered")
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_SUCCEEDED", "SYNC_FAILED"])
        XCTAssertEqual(timer.delayMs, 30_000, "the witness is due sooner")

        _ = try await engine.dispatcher.flush()
        XCTAssertEqual(requestCount, 1, "not sent again")
    }

    func test_R1C2_a2xx_whoseSettleFailsToo_staysInFlight() async throws {
        let engine = await liveEngine(maxAttempts: 2)
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(200)

        await runACycleThatDies(engine, settleFails: true)

        try await assertLeftInFlight(id, in: engine)
        XCTAssertEqual(requestCount, 1)
    }

    // MARK: - R2: a retryable answer, attempts left

    /// The attempt's time and its cooldown are the settle's, as the normal
    /// path's are its write's. The backoff, 40 s for the second attempt, is
    /// later than the wait.
    func test_R2C1_a503_retriesWithItsCode_andWakesAtTheBackoff() async throws {
        let engine = await liveEngine(baseDelayMs: 20_000)
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(503)

        await runACycleThatDies(engine, settleFails: false)

        try await assertRow(id, in: engine, status: "PENDING", attempts: 2, lastAttemptAt: settled,
                            nextRetryAt: settled + 40_000, lastErrorCode: "SERVER_ERROR")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_RETRYING(SERVER_ERROR)",
                                "SYNC_FAILED(NETWORK_ERROR)"])
        let retrying = emitter.events(ofType: "ITEM_RETRYING").first
        XCTAssertEqual(retrying?.statusCode, 503)
        XCTAssertEqual(retrying?.attempt, 2)
        XCTAssertEqual(timer.delayMs, 40_000, "the later of the backoff and the 30 s wait")
    }

    /// The settle's write stops a deadline at the largest JavaScript Date.
    func test_R2C1_aBackoffPastTheLargestDate_stopsAtIt() async throws {
        let engine = await liveEngine(baseDelayMs: largestDelayMs, maxDelayMs: 0)
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(503)

        await runACycleThatDies(engine, settleFails: false)

        try await assertRow(id, in: engine, status: "PENDING", attempts: 2, lastAttemptAt: settled,
                            nextRetryAt: RetryPolicyEvaluator.maxDeadlineMs, lastErrorCode: "SERVER_ERROR")
        XCTAssertEqual(timer.delayMs, RetryPolicyEvaluator.maxDeadlineMs - settled)
    }

    /// In a BGTask window, the window ends after the cycle's SYNC_FAILED with
    /// BACKGROUND_SYNC_COMPLETED and `ok: "false"`, and the task is completed
    /// with `false`.
    func test_R2C1_inABGTaskWindow_endsTheWindowWithOkFalse() async throws {
        let engine = await liveEngine(baseDelayMs: 20_000)
        try enableBackgroundSync(engine)
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(503)
        faults.fail(1, after: 1)
        let task = FakeBackgroundTask()

        await runAWindow(engine, task)

        XCTAssertEqual(faults.failures, 1)
        XCTAssertEqual(task.completedResults, [false])
        try await assertRow(id, in: engine, status: "PENDING", attempts: 2, lastAttemptAt: ended,
                            nextRetryAt: ended + 40_000, lastErrorCode: "SERVER_ERROR")
        XCTAssertEqual(events, ["BACKGROUND_SYNC_STARTED", "SYNC_STARTED", "SYNC_PROGRESS",
                                "ITEM_RETRYING(SERVER_ERROR)", "SYNC_FAILED(NETWORK_ERROR)",
                                "BACKGROUND_SYNC_COMPLETED"])
        XCTAssertEqual(emitter.events(ofType: "BACKGROUND_SYNC_COMPLETED").first?.metadata,
                       ["kind": "processing", "ok": "false"])
        XCTAssertEqual(timer.delayMs, 40_000)
    }

    func test_R2C2_a503_whoseSettleFailsToo_staysInFlight() async throws {
        let engine = await liveEngine()
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(503)

        await runACycleThatDies(engine, settleFails: true)

        try await assertLeftInFlight(id, in: engine)
    }

    // MARK: - R2b: a retryable answer on the last attempt

    func test_R2bC1_a503OnTheLastAttempt_isAMaxAttemptsDeadLetter() async throws {
        let engine = await liveEngine(maxAttempts: 2)
        let id = try await seed(engine)
        try await seedWitness(engine)
        await armForTheDueRow(engine)
        answer(503)

        await runACycleThatDies(engine, settleFails: false)

        let queued = try await row(id, in: engine)
        XCTAssertNil(queued)
        let found = try await engine.storage.deadLetter(id)
        let letter = try XCTUnwrap(found)
        XCTAssertEqual(letter.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
        XCTAssertEqual(letter.lastErrorMessage, "HTTP 503 Service Unavailable")
        XCTAssertEqual(letter.attempts, 2)
        XCTAssertEqual(letter.lastAttemptAt, t1, "the claim's time")
        XCTAssertEqual(letter.deadLetteredAt, settled)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_FAILED(MAX_ATTEMPTS_EXCEEDED)",
                                "ITEM_DEAD_LETTERED(DEAD_LETTERED)", "SYNC_FAILED(NETWORK_ERROR)"])
        let failed = emitter.events(ofType: "ITEM_FAILED").first
        XCTAssertEqual(failed?.statusCode, 503)
        XCTAssertEqual(failed?.attempt, 2)
        XCTAssertEqual(emitter.events(ofType: "ITEM_DEAD_LETTERED").first?.metadata,
                       ["lastErrorCode": "MAX_ATTEMPTS_EXCEEDED"])
        XCTAssertEqual(timer.delayMs, 30_000, "the witness is due sooner")
    }

    func test_R2bC2_a503OnTheLastAttempt_whoseSettleFailsToo_staysInFlight() async throws {
        let engine = await liveEngine(maxAttempts: 2)
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(503)

        await runACycleThatDies(engine, settleFails: true)

        try await assertLeftInFlight(id, in: engine)
    }

    // MARK: - R2t: a timeout

    /// The item keeps TIMEOUT; the cycle, which a local write ended, reports
    /// NETWORK_ERROR. The wait is later than the 200 ms backoff.
    func test_R2tC1_aTimeout_retriesWithTimeout() async throws {
        let engine = await liveEngine()
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        failTheRequest(with: .timedOut)

        await runACycleThatDies(engine, settleFails: false)

        try await assertRow(id, in: engine, status: "PENDING", attempts: 2, lastAttemptAt: settled,
                            nextRetryAt: settled + 200, lastErrorCode: "TIMEOUT")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_RETRYING(TIMEOUT)",
                                "SYNC_FAILED(NETWORK_ERROR)"])
        let retrying = emitter.events(ofType: "ITEM_RETRYING").first
        XCTAssertNil(retrying?.statusCode)
        XCTAssertEqual(retrying?.attempt, 2)
        XCTAssertEqual(timer.delayMs, 30_000, "the later of the 200 ms backoff and the 30 s wait")
    }

    /// On the last attempt the timeout ends as MAX_ATTEMPTS_EXCEEDED, with
    /// the attempt's own message.
    func test_R2tC1_aTimeoutOnTheLastAttempt_isAMaxAttemptsDeadLetter() async throws {
        let engine = await liveEngine(maxAttempts: 2)
        let id = try await seed(engine)
        try await seedWitness(engine)
        await armForTheDueRow(engine)
        failTheRequest(with: .timedOut)

        await runACycleThatDies(engine, settleFails: false)

        let queued = try await row(id, in: engine)
        XCTAssertNil(queued)
        let found = try await engine.storage.deadLetter(id)
        let letter = try XCTUnwrap(found)
        XCTAssertEqual(letter.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
        XCTAssertEqual(letter.lastErrorMessage, "Request timed out")
        XCTAssertEqual(letter.attempts, 2)
        XCTAssertEqual(letter.lastAttemptAt, t1)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_FAILED(MAX_ATTEMPTS_EXCEEDED)",
                                "ITEM_DEAD_LETTERED(DEAD_LETTERED)", "SYNC_FAILED(NETWORK_ERROR)"])
        XCTAssertEqual(emitter.events(ofType: "ITEM_FAILED").first?.attempt, 2)
        XCTAssertEqual(timer.delayMs, 30_000, "the witness is due sooner")
    }

    func test_R2tC2_aTimeout_whoseSettleFailsToo_staysInFlight() async throws {
        let engine = await liveEngine()
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        failTheRequest(with: .timedOut)

        await runACycleThatDies(engine, settleFails: true)

        try await assertLeftInFlight(id, in: engine)
    }

    // MARK: - R3: a permanent answer

    func test_R3C1_a404_isADeadLetterWithItsCode() async throws {
        let engine = await liveEngine()
        let id = try await seed(engine)
        try await seedWitness(engine)
        await armForTheDueRow(engine)
        answer(404)

        await runACycleThatDies(engine, settleFails: false)

        let queued = try await row(id, in: engine)
        XCTAssertNil(queued)
        let found = try await engine.storage.deadLetter(id)
        let letter = try XCTUnwrap(found)
        XCTAssertEqual(letter.lastErrorCode, "SERVER_ERROR")
        XCTAssertEqual(letter.lastErrorMessage, "HTTP 404 Not Found")
        XCTAssertEqual(letter.attempts, 2)
        XCTAssertEqual(letter.lastAttemptAt, t1, "the claim's time")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_FAILED(SERVER_ERROR)",
                                "ITEM_DEAD_LETTERED(DEAD_LETTERED)", "SYNC_FAILED(NETWORK_ERROR)"])
        let failed = emitter.events(ofType: "ITEM_FAILED").first
        XCTAssertEqual(failed?.statusCode, 404)
        XCTAssertEqual(failed?.attempt, 2)
        XCTAssertEqual(emitter.events(ofType: "ITEM_DEAD_LETTERED").first?.metadata,
                       ["lastErrorCode": "SERVER_ERROR"])
        XCTAssertEqual(timer.delayMs, 30_000, "the witness is due sooner")
    }

    func test_R3C2_a404_whoseSettleFailsToo_staysInFlight() async throws {
        let engine = await liveEngine()
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(404)

        await runACycleThatDies(engine, settleFails: true)

        try await assertLeftInFlight(id, in: engine)
    }

    // MARK: - R4: a stale credential's rejection

    /// The hold the normal path applies: no attempt spent, the row held for
    /// a minute from the settle, the cycle stopped before the row behind,
    /// and the hold reaches the wake, which waits for it although that row
    /// is due.
    func test_R4C1_aStaleCredentialRejection_isHeld_andTheHoldReachesTheWake() async throws {
        let engine = await persistedCredentialEngine()
        let id = try await seed(engine)
        let behind = try await seed(engine, url: "https://api.test/behind", priority: "LOW")
        await armForTheDueRow(engine)
        answer(401)

        await runACycleThatDies(engine, settleFails: false)

        try await assertRow(id, in: engine, status: "PENDING", attempts: 1, lastAttemptAt: settled,
                            nextRetryAt: settled + 60_000, lastErrorCode: "UNAUTHORIZED")
        try await assertRow(behind, in: engine, status: "PENDING", attempts: 1, lastAttemptAt: t0,
                            nextRetryAt: t1, lastErrorCode: "SERVER_ERROR")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_RETRYING(UNAUTHORIZED)",
                                "SYNC_FAILED(NETWORK_ERROR)"])
        let retrying = emitter.events(ofType: "ITEM_RETRYING").first
        XCTAssertEqual(retrying?.statusCode, 401)
        XCTAssertEqual(retrying?.attempt, 2, "the attempt the server rejected")
        XCTAssertEqual(requestCount, 1, "the cycle stopped")
        XCTAssertEqual(timer.delayMs, 60_000, "the hold, later than the 30 s wait")
    }

    /// The settle's credential hold stops at the largest JavaScript Date.
    func test_R4C1_aHoldPastTheLargestDate_stopsAtIt() async throws {
        let engine = await persistedCredentialEngine(baseDelayMs: largestDelayMs, maxDelayMs: 0)
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(401)

        await runACycleThatDies(engine, settleFails: false)

        try await assertRow(id, in: engine, status: "PENDING", attempts: 1, lastAttemptAt: settled,
                            nextRetryAt: RetryPolicyEvaluator.maxDeadlineMs, lastErrorCode: "UNAUTHORIZED")
        XCTAssertEqual(timer.delayMs, RetryPolicyEvaluator.maxDeadlineMs - settled)
    }

    /// The hold is not written either, so it does not reach the wake: the
    /// row behind waits only for the 30 s after a dead cycle.
    func test_R4C2_aStaleCredentialRejection_whoseSettleFailsToo_staysInFlight() async throws {
        let engine = await persistedCredentialEngine()
        let id = try await seed(engine)
        let behind = try await seed(engine, url: "https://api.test/behind", priority: "LOW")
        await armForTheDueRow(engine)
        answer(401)

        await runACycleThatDies(engine, settleFails: true)

        try await assertRow(id, in: engine, status: "IN_FLIGHT", attempts: 2, lastAttemptAt: t1,
                            nextRetryAt: t1, lastErrorCode: "SERVER_ERROR")
        try await assertRow(behind, in: engine, status: "PENDING", attempts: 1, lastAttemptAt: t0,
                            nextRetryAt: t1, lastErrorCode: "SERVER_ERROR")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "SYNC_FAILED(NETWORK_ERROR)"])
        XCTAssertEqual(requestCount, 1)
        XCTAssertEqual(timer.delayMs, 30_000)
    }

    // MARK: - R8: a verdict the retry decider was asked for

    // The settle applies the verdict the failed write held, the decider's or,
    // with no decision, the policy's. It never asks the decider again.

    func test_decider_abortSettle_appliesRecordedOutcome_deadLetter() async throws {
        let engine = await liveEngine()
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.deadLetter) }
        let id = try await seed(engine)
        try await seedWitness(engine)
        await armForTheDueRow(engine)
        answer(503)

        await runACycleThatDies(engine, settleFails: false)

        let queued = try await row(id, in: engine)
        XCTAssertNil(queued)
        let found = try await engine.storage.deadLetter(id)
        let letter = try XCTUnwrap(found)
        XCTAssertEqual(letter.lastErrorCode, "RETRY_DECIDER_REJECTED")
        XCTAssertEqual(letter.lastErrorMessage, "HTTP 503 Service Unavailable")
        XCTAssertEqual(letter.attempts, 2)
        XCTAssertEqual(letter.lastAttemptAt, t1, "the claim's time")
        XCTAssertEqual(letter.deadLetteredAt, settled)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_FAILED(RETRY_DECIDER_REJECTED)",
                                "ITEM_DEAD_LETTERED(DEAD_LETTERED)", "SYNC_FAILED(NETWORK_ERROR)"])
        XCTAssertEqual(emitter.events(ofType: "ITEM_DEAD_LETTERED").first?.metadata,
                       ["lastErrorCode": "RETRY_DECIDER_REJECTED"])
        XCTAssertEqual(decider.callCount, 1, "The settle did not ask again")
        XCTAssertEqual(timer.delayMs, 30_000, "the witness is due sooner")
    }

    /// The delete fails, and the settle deletes the row: `ITEM_FAILED` goes
    /// out once, from the settle.
    func test_decider_abortSettle_appliesRecordedOutcome_drop() async throws {
        let engine = await liveEngine()
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.drop) }
        let id = try await seed(engine)
        try await seedWitness(engine)
        await armForTheDueRow(engine)
        answer(503)

        await runACycleThatDies(engine, settleFails: false)

        let queued = try await row(id, in: engine)
        XCTAssertNil(queued, "deleted")
        let letter = try await engine.storage.deadLetter(id)
        XCTAssertNil(letter, "a drop writes no dead letter")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_FAILED(RETRY_DECIDER_REJECTED)",
                                "SYNC_FAILED(NETWORK_ERROR)"])
        let failed = emitter.events(ofType: "ITEM_FAILED").first
        XCTAssertEqual(failed?.statusCode, 503)
        XCTAssertEqual(failed?.attempt, 2)
        XCTAssertEqual(decider.callCount, 1)
        XCTAssertEqual(timer.delayMs, 30_000, "the witness is due sooner")

        _ = try await engine.dispatcher.flush()
        XCTAssertEqual(requestCount, 1, "not sent again")
    }

    /// The decided delay, 45 s where the policy would wait 40 s, counts from
    /// the settle's write.
    func test_decider_abortSettle_appliesRecordedOutcome_retryDelayMs() async throws {
        let engine = await liveEngine(baseDelayMs: 20_000)
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.retry, delayMs: 45_000) }
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(503)

        await runACycleThatDies(engine, settleFails: false)

        try await assertRow(id, in: engine, status: "PENDING", attempts: 2, lastAttemptAt: settled,
                            nextRetryAt: settled + 45_000, lastErrorCode: "SERVER_ERROR")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_RETRYING(SERVER_ERROR)",
                                "SYNC_FAILED(NETWORK_ERROR)"])
        XCTAssertEqual(decider.callCount, 1)
        XCTAssertEqual(timer.delayMs, 45_000, "the decided delay, later than the 30 s wait")
    }

    /// No decision came, a FAILED answer here: the settle applies the
    /// policy's verdict, as with no decider at all.
    func test_decider_abortSettle_appliesRecordedOutcome_nothingRecorded() async throws {
        let engine = await liveEngine(baseDelayMs: 20_000)
        let decider = FakeDecider(engine: engine) { _, _ in .failed("the decider threw") }
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(503)

        await runACycleThatDies(engine, settleFails: false)

        try await assertRow(id, in: engine, status: "PENDING", attempts: 2, lastAttemptAt: settled,
                            nextRetryAt: settled + 40_000, lastErrorCode: "SERVER_ERROR")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_RETRYING(SERVER_ERROR)",
                                "SYNC_FAILED(NETWORK_ERROR)"])
        XCTAssertEqual(decider.callCount, 1)
        XCTAssertEqual(timer.delayMs, 40_000)
    }

    func test_decider_abortSettle_appliesRecordedOutcome_deadLetterWhoseSettleFailsToo() async throws {
        let engine = await liveEngine()
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.deadLetter) }
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(503)

        await runACycleThatDies(engine, settleFails: true)

        try await assertLeftInFlight(id, in: engine)
        XCTAssertEqual(decider.callCount, 1)
    }

    // MARK: - R6: a request that never went out

    /// A URL that is not http(s) is never sent. When its dead letter cannot
    /// be written, the row comes back as it was before its claim. It is due,
    /// so only the wait after a dead cycle keeps the wake from running it at
    /// once: 30 s, then 60 s while cycles keep dying.
    func test_R6C1_anUnsentRequest_comesBackAsBefore_andWaits() async throws {
        let engine = await liveEngine()
        let id = try await seed(engine, url: "ftp://api.test/items")
        await armForTheDueRow(engine)

        await runACycleThatDies(engine, settleFails: false)

        try await assertRow(id, in: engine, status: "PENDING", attempts: 1, lastAttemptAt: t0,
                            nextRetryAt: t1, lastErrorCode: "SERVER_ERROR")
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "SYNC_FAILED(NETWORK_ERROR)"])
        XCTAssertEqual(requestCount, 0)
        XCTAssertEqual(timer.delayMs, 30_000, "0 without the wait")

        clock.advance(ms: 30_000)
        await runACycleThatDies(engine, settleFails: false)
        try await assertRow(id, in: engine, status: "PENDING", attempts: 1, lastAttemptAt: t0,
                            nextRetryAt: t1, lastErrorCode: "SERVER_ERROR")
        XCTAssertEqual(timer.delayMs, 60_000)
    }

    func test_R6C2_anUnsentRequest_whoseReleaseFailsToo_staysInFlight() async throws {
        let engine = await liveEngine()
        let id = try await seed(engine, url: "ftp://api.test/items")
        await armForTheDueRow(engine)

        await runACycleThatDies(engine, settleFails: true)

        try await assertLeftInFlight(id, in: engine)
        XCTAssertEqual(requestCount, 0)
    }

    // MARK: - R7: a BGTask that expires

    /// The expiry cancels the request that is out: the claim's attempt is
    /// refunded and the claim's time kept, since the request may have
    /// reached the server. Nothing failed, so the cycle ends without a code
    /// and leaves the wait after an earlier dead cycle, and its streak, as
    /// they were.
    func test_R7C1_anExpiryDuringTheRequest_refundsTheAttempt_andLeavesTheWaitAsItWas() async throws {
        let clock = self.clock
        let requestMs = self.requestMs
        let engine = await liveEngine()
        try enableBackgroundSync(engine)
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        faults.fail(1) // the claim: a dead cycle that leaves the row as it is
        _ = try? await engine.dispatcher.flush()
        XCTAssertEqual(timer.delayMs, 30_000)
        clock.advance(ms: 10_000)
        let claimedAt = clock.nowMs()
        let task = FakeBackgroundTask()
        URLProtocolStub.setHandler { _ in
            clock.advance(ms: requestMs)
            task.expirationHandler?()
            throw URLError(.cancelled)
        }

        await runAWindow(engine, task)

        XCTAssertEqual(task.completedResults, [false])
        XCTAssertEqual(faults.failures, 1, "only the earlier claim")
        try await assertRow(id, in: engine, status: "PENDING", attempts: 1, lastAttemptAt: claimedAt,
                            nextRetryAt: t1, lastErrorCode: "SERVER_ERROR")
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0)
        XCTAssertEqual(events, ["BACKGROUND_SYNC_STARTED", "SYNC_STARTED", "SYNC_FAILED", "BACKGROUND_SYNC_COMPLETED"])
        XCTAssertNil(emitter.events(ofType: "SYNC_FAILED").first?.metadata)
        XCTAssertEqual(emitter.events(ofType: "BACKGROUND_SYNC_COMPLETED").first?.metadata,
                       ["kind": "processing", "ok": "false"])
        XCTAssertEqual(requestCount, 1)
        let left = 30_000 - 10_000 - requestMs
        XCTAssertEqual(timer.delayMs, left, "what is left of the wait")

        clock.advance(ms: left)
        faults.fail(1) // the next claim
        _ = try? await engine.dispatcher.flush()
        XCTAssertEqual(timer.delayMs, 60_000, "the streak goes on from where it was")
    }

    /// The expiry lands while the claim is written: nothing is sent and the
    /// row comes back as it was before its claim. With no wait running, the
    /// wake is the row's own deadline, already past.
    func test_R7C1_anExpiryBeforeTheSend_givesTheRowBackAsBefore() async throws {
        let engine = await liveEngine()
        try enableBackgroundSync(engine)
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        answer(200)
        let task = FakeBackgroundTask()
        faults.intercept { task.expirationHandler?() } // the claim's save, which still commits

        await runAWindow(engine, task)

        XCTAssertEqual(task.completedResults, [false])
        try await assertRow(id, in: engine, status: "PENDING", attempts: 1, lastAttemptAt: t0,
                            nextRetryAt: t1, lastErrorCode: "SERVER_ERROR")
        XCTAssertEqual(events, ["BACKGROUND_SYNC_STARTED", "SYNC_STARTED", "SYNC_FAILED", "BACKGROUND_SYNC_COMPLETED"])
        XCTAssertEqual(emitter.events(ofType: "BACKGROUND_SYNC_COMPLETED").first?.metadata,
                       ["kind": "processing", "ok": "false"])
        XCTAssertEqual(requestCount, 0)
        XCTAssertEqual(timer.delayMs, 0, "a cancelled cycle starts no wait")
    }

    /// Giving the row back fails: it waits IN_FLIGHT for launch recovery,
    /// with the claim's attempt, and no wait was started.
    func test_R7C2_anExpiryWhoseReleaseFails_staysInFlight() async throws {
        let engine = await liveEngine()
        try enableBackgroundSync(engine)
        let id = try await seed(engine)
        await armForTheDueRow(engine)
        let task = FakeBackgroundTask()
        let clock = self.clock
        let faults = self.faults
        let requestMs = self.requestMs
        URLProtocolStub.setHandler { _ in
            clock.advance(ms: requestMs)
            faults.fail(1) // the release
            task.expirationHandler?()
            throw URLError(.cancelled)
        }

        await runAWindow(engine, task)

        XCTAssertEqual(task.completedResults, [false])
        XCTAssertEqual(faults.failures, 1)
        try await assertRow(id, in: engine, status: "IN_FLIGHT", attempts: 2, lastAttemptAt: t1,
                            nextRetryAt: t1, lastErrorCode: "SERVER_ERROR")
        XCTAssertEqual(events, ["BACKGROUND_SYNC_STARTED", "SYNC_STARTED", "SYNC_FAILED", "BACKGROUND_SYNC_COMPLETED"])
        XCTAssertNil(timer.delayMs, "an IN_FLIGHT row arms no wake")

        try await seed(engine, url: "https://api.test/unseen", delayMs: 0)
        await engine.rearmRetryTimer()
        XCTAssertEqual(timer.delayMs, 0, "no wait started: the next arm runs a due row at once")
    }
}
