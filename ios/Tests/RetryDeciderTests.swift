import XCTest
import NitroModules
@testable import SyncProvider

/// The JS retry decider, at the dispatcher. It is asked only where the retry
/// policy would retry: a retryable failure with budget left, whose request
/// went out, and not a stale credential's hold. It may keep the retry, with
/// its own delay or the policy's, drop the item or dead-letter it. Anything
/// but a decision leaves the policy's verdict.
///
/// Time is manual. Every request takes `requestMs`, and the decider answers
/// at once unless a test says otherwise, so a delay counts from `ended`.
final class RetryDeciderTests: XCTestCase {
    private var fixture: EngineFixture!
    private var emitter: MockSyncEventEmitter!
    private var clock: ManualClock { fixture.clock }
    private var callbackTimer: ManualCallbackTimer { fixture.callbackTimer }

    private let requestMs: Int64 = 7

    override func setUp() {
        super.setUp()
        fixture = EngineFixture()
        emitter = MockSyncEventEmitter()
        URLProtocolStub.setHandler(nil)
    }

    override func tearDown() {
        fixture.tearDown()
        URLProtocolStub.setHandler(nil)
        super.tearDown()
    }

    // MARK: - Fixture

    /// An engine the app configured in this process. The default policy waits
    /// exactly 1 s after a first failed attempt, 2 s after a second.
    private func makeEngine(policy: RetryPolicyValue = .exact(maxAttempts: 5, baseDelayMs: 1_000, maxDelayMs: 600_000),
                            callbackTimeoutMs: Double? = nil) async -> SyncEngine {
        let engine = await fixture.makeEngine(emitter: emitter)
        var options = SyncOptionsSnapshot.sample(retryPolicy: policy)
        options.callbackTimeoutMs = callbackTimeoutMs
        await engine.configure(options)
        return engine
    }

    private func enqueue(_ engine: SyncEngine,
                         url: String = "https://api.test/items",
                         method: String = "POST",
                         metadata: [String: String]? = nil) async throws -> String {
        try await engine.storage.enqueue(input: SyncQueueStorage.SyncItemInputData(method: method,
                                                                                   url: url,
                                                                                   headers: nil,
                                                                                   body: "{}",
                                                                                   contentType: "application/json",
                                                                                   priority: "NORMAL",
                                                                                   metadata: metadata))
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

    /// Runs one cycle; `events` then reads that cycle's events only.
    @discardableResult
    private func flush(_ engine: SyncEngine) async throws -> StoredSyncResult {
        emitter.clear()
        return try await engine.dispatcher.flush()
    }

    private func row(_ id: String, in engine: SyncEngine) async throws -> StoredSyncItem {
        let found = try await engine.storage.getAll().first { $0.id == id }
        return try XCTUnwrap(found, "the row is queued")
    }

    private func isQueued(_ id: String, in engine: SyncEngine) async throws -> Bool {
        try await engine.storage.getAll().contains { $0.id == id }
    }

    /// The events of the last cycle, as `TYPE` or `TYPE(errorCode)`.
    private var events: [String] {
        emitter.emittedEvents.map { event in
            event.errorCode.map { "\(event.type)(\($0))" } ?? event.type
        }
    }

    /// Asserts `id` waits as the policy's 1 s first retry leaves it: the
    /// verdict when no decision came.
    private func assertThePolicyRetried(_ id: String,
                                        in engine: SyncEngine,
                                        ended: Int64,
                                        file: StaticString = #filePath,
                                        line: UInt = #line) async throws {
        let stored = try await row(id, in: engine)
        XCTAssertEqual(stored.status, "PENDING", file: file, line: line)
        XCTAssertEqual(stored.attempts, 1, file: file, line: line)
        XCTAssertEqual(stored.lastErrorCode, "SERVER_ERROR", file: file, line: line)
        XCTAssertEqual(stored.nextRetryAt, ended + 1_000, "the policy's delay", file: file, line: line)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_RETRYING(SERVER_ERROR)", "SYNC_SUCCEEDED"],
                       file: file, line: line)
    }

    // MARK: - Not installed

    func test_decider_unset_usesPolicy() async throws {
        let engine = await makeEngine()
        let id = try await enqueue(engine)
        answer(503)
        let ended = clock.nowMs() + requestMs

        try await flush(engine)

        try await assertThePolicyRetried(id, in: engine, ended: ended)
        XCTAssertEqual(callbackTimer.delays, [], "Nothing was asked")
        XCTAssertEqual(engine.gateway.pendingCount, 0)
    }

    // MARK: - RETRY

    /// The decided delay is used as given: no jitter and no `maxDelayMs` cap,
    /// fractions truncated.
    func test_decider_retry_overridesDelay() async throws {
        let jittered = RetryPolicyValue(maxAttempts: 5, backoff: .exponential, baseDelayMs: 1_000, maxDelayMs: 1_000,
                                        jitter: true, retryOnStatusCodes: [503])
        let engine = await makeEngine(policy: jittered)
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.retry, delayMs: 5_000.9) }
        let id = try await enqueue(engine)
        answer(503)
        let ended = clock.nowMs() + requestMs

        try await flush(engine)

        let stored = try await row(id, in: engine)
        XCTAssertEqual(stored.status, "PENDING")
        XCTAssertEqual(stored.attempts, 1, "The attempt it made is spent")
        XCTAssertEqual(stored.nextRetryAt, ended + 5_000)
        XCTAssertEqual(stored.lastErrorCode, "SERVER_ERROR")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_RETRYING(SERVER_ERROR)", "SYNC_SUCCEEDED"])
        let retrying = emitter.events(ofType: "ITEM_RETRYING").first
        XCTAssertEqual(retrying?.statusCode, 503)
        XCTAssertEqual(retrying?.attempt, 1)
        XCTAssertEqual(decider.callCount, 1)
    }

    func test_decider_retry_withoutDelay_usesPolicyDelay() async throws {
        let engine = await makeEngine()
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.retry) }
        let id = try await enqueue(engine)
        answer(503)
        let ended = clock.nowMs() + requestMs

        try await flush(engine)

        try await assertThePolicyRetried(id, in: engine, ended: ended)
        XCTAssertEqual(decider.callCount, 1)
    }

    /// A delay that is not finite, or is negative, is ignored: the retry
    /// stands, with the policy's delay. It is no failed answer.
    func test_decider_retry_invalidDelay_usesPolicyDelay() async throws {
        let engine = await makeEngine()
        for unusable in [Double.nan, .infinity, -.infinity, -1, -0.5] {
            let decider = FakeDecider(engine: engine) { _, _ in .decision(.retry, delayMs: unusable) }
            let id = try await enqueue(engine)
            answer(503)
            let ended = clock.nowMs() + requestMs

            try await flush(engine)

            try await assertThePolicyRetried(id, in: engine, ended: ended)
            XCTAssertEqual(decider.callCount, 1, "\(unusable)")
        }
    }

    /// A huge delay goes through the deadline clamp, at the largest
    /// JavaScript Date.
    func test_decider_retry_hugeDelay_clampsDeadline() async throws {
        let engine = await makeEngine()
        for huge in [1e300, 9_223_372_036_854_775_808.0, 9_007_199_254_740_991] {
            _ = FakeDecider(engine: engine) { _, _ in .decision(.retry, delayMs: huge) }
            let id = try await enqueue(engine)
            answer(503)

            try await flush(engine)

            let stored = try await row(id, in: engine)
            XCTAssertEqual(stored.nextRetryAt, RetryPolicyEvaluator.maxDeadlineMs, "\(huge)")
        }
    }

    /// Whatever the delay, it counts from the decision, not from the end of
    /// the attempt: an answer that takes 2 s waits 2 s more, and so does a
    /// decider whose time runs out.
    func test_decider_delays_countFromTheDecision() async throws {
        let engine = await makeEngine()
        let clock = self.clock
        let timer = callbackTimer
        _ = FakeDecider(engine: engine) { _, _ in
            clock.advance(ms: 2_000)
            return .decision(.retry, delayMs: 5_000)
        }
        let decided = try await enqueue(engine)
        answer(503)
        let firstEnded = clock.nowMs() + requestMs
        try await flush(engine)

        _ = FakeDecider(engine: engine) { _, _ in
            clock.advance(ms: 2_000)
            timer.fireAll()
            return nil
        }
        let timedOut = try await enqueue(engine)
        let secondEnded = clock.nowMs() + requestMs
        try await flush(engine)

        let decidedRow = try await row(decided, in: engine)
        XCTAssertEqual(decidedRow.nextRetryAt, firstEnded + 2_000 + 5_000)
        let timedOutRow = try await row(timedOut, in: engine)
        XCTAssertEqual(timedOutRow.nextRetryAt, secondEnded + 2_000 + 1_000, "The policy's delay, from the timeout")
    }

    // MARK: - DROP and DEAD_LETTER

    func test_decider_drop_emitsFailedAndDeletesRow() async throws {
        let engine = await makeEngine()
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.drop, delayMs: 9_000) }
        let id = try await enqueue(engine)
        answer(503)

        let result = try await flush(engine)

        let queued = try await isQueued(id, in: engine)
        XCTAssertFalse(queued, "Deleted")
        let letter = try await engine.storage.deadLetter(id)
        XCTAssertNil(letter, "No dead letter")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_FAILED(RETRY_DECIDER_REJECTED)", "SYNC_FAILED"])
        let failed = emitter.events(ofType: "ITEM_FAILED").first
        XCTAssertEqual(failed?.itemId, id)
        XCTAssertEqual(failed?.statusCode, 503)
        XCTAssertEqual(failed?.attempt, 1)
        XCTAssertEqual(result.itemsFailed, 1, "It counts as failed")
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.metadata?["failed"], "1")
        XCTAssertEqual(decider.callCount, 1)
    }

    /// The dead letter carries the decider's code, and the real failure as
    /// its message.
    func test_decider_deadLetter_movesRowWithDeciderCode() async throws {
        let engine = await makeEngine()
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.deadLetter) }
        let rejected = try await enqueue(engine)
        let claimedAt = clock.nowMs()
        answer(503)

        let result = try await flush(engine)

        let queued = try await isQueued(rejected, in: engine)
        XCTAssertFalse(queued)
        let found = try await engine.storage.deadLetter(rejected)
        let letter = try XCTUnwrap(found)
        XCTAssertEqual(letter.lastErrorCode, "RETRY_DECIDER_REJECTED")
        XCTAssertEqual(letter.lastErrorMessage, "HTTP 503 Service Unavailable")
        XCTAssertEqual(letter.attempts, 1, "The attempt that was spent")
        XCTAssertEqual(letter.lastAttemptAt, claimedAt)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_FAILED(RETRY_DECIDER_REJECTED)",
                                "ITEM_DEAD_LETTERED(DEAD_LETTERED)", "SYNC_FAILED"])
        let failed = emitter.events(ofType: "ITEM_FAILED").first
        XCTAssertEqual(failed?.statusCode, 503)
        XCTAssertEqual(failed?.attempt, 1)
        XCTAssertEqual(emitter.events(ofType: "ITEM_DEAD_LETTERED").first?.metadata,
                       ["lastErrorCode": "RETRY_DECIDER_REJECTED"])
        XCTAssertEqual(result.itemsFailed, 1)

        let timedOut = try await enqueue(engine)
        failTheRequest(with: .timedOut)
        try await flush(engine)

        let timedOutLetter = try await engine.storage.deadLetter(timedOut)
        XCTAssertEqual(timedOutLetter?.lastErrorCode, "RETRY_DECIDER_REJECTED")
        XCTAssertEqual(timedOutLetter?.lastErrorMessage, "Request timed out", "The real failure")
        XCTAssertNil(emitter.events(ofType: "ITEM_FAILED").first?.statusCode)
        XCTAssertEqual(decider.callCount, 2)
    }

    // MARK: - Fallback

    func test_decider_failedAnswer_fallsBack() async throws {
        let engine = await makeEngine()
        let decider = FakeDecider(engine: engine) { _, _ in .failed("the decider threw") }
        let id = try await enqueue(engine)
        answer(503)
        let ended = clock.nowMs() + requestMs

        try await flush(engine)

        try await assertThePolicyRetried(id, in: engine, ended: ended)
        XCTAssertEqual(decider.callCount, 1)
        XCTAssertEqual(callbackTimer.delays, [3_000], "Armed with the default, and never fired: it fell back at once")
        XCTAssertEqual(engine.gateway.pendingCount, 0)
    }

    /// RESOLVED, but no decision: an answer of another kind, or none at all.
    func test_decider_invalidAnswer_fallsBack() async throws {
        let engine = await makeEngine()
        for unusable in [CallbackReply.resolvedEmpty, .headers(["X-A": "b"]), .before(.skip)] {
            let decider = FakeDecider(engine: engine) { _, _ in unusable }
            let id = try await enqueue(engine)
            answer(503)
            let ended = clock.nowMs() + requestMs

            try await flush(engine)

            try await assertThePolicyRetried(id, in: engine, ended: ended)
            XCTAssertEqual(decider.callCount, 1)
        }
    }

    /// JS never answers: the wait ends at `callbackTimeoutMs`, truncated.
    func test_decider_neverAnswers_timesOut() async throws {
        let engine = await makeEngine(callbackTimeoutMs: 1_234.5)
        let timer = callbackTimer
        let decider = FakeDecider(engine: engine) { _, _ in
            timer.fireAll()
            return nil
        }
        let id = try await enqueue(engine)
        answer(503)
        let ended = clock.nowMs() + requestMs

        try await flush(engine)

        try await assertThePolicyRetried(id, in: engine, ended: ended)
        XCTAssertEqual(decider.callCount, 1)
        XCTAssertEqual(callbackTimer.delays, [1_234])
        XCTAssertEqual(engine.gateway.pendingCount, 0)
    }

    /// A `configureSync` that lands while the request is out applies from the
    /// next item: the decider's wait for this one ends at the
    /// `callbackTimeoutMs` its request was sent with.
    func test_decider_neverAnswers_timesOut_aConfigureDuringTheRequestAppliesFromTheNextItem() async throws {
        let policy = RetryPolicyValue.exact(maxAttempts: 5, baseDelayMs: 1_000, maxDelayMs: 600_000)
        let engine = await makeEngine(policy: policy, callbackTimeoutMs: 1_500)
        let timer = callbackTimer
        let decider = FakeDecider(engine: engine) { _, _ in
            timer.fireAll()
            return nil
        }
        _ = try await engine.storage.enqueue(input: .sample(url: "https://api.test/first", priority: "HIGH"))
        _ = try await engine.storage.enqueue(input: .sample(url: "https://api.test/second", priority: "LOW"))
        let clock = self.clock
        let requestMs = self.requestMs
        let failure = stubResponse(503)
        URLProtocolStub.setHandler { request in
            clock.advance(ms: requestMs)
            if request.url?.lastPathComponent == "first" {
                let configured = DispatchSemaphore(value: 0)
                Task {
                    var options = SyncOptionsSnapshot.sample(retryPolicy: policy)
                    options.callbackTimeoutMs = 9_000
                    await engine.configure(options)
                    configured.signal()
                }
                configured.wait()
            }
            return try failure(request)
        }

        try await flush(engine)

        XCTAssertEqual(decider.requests.values.map(\.url), ["https://api.test/first", "https://api.test/second"])
        XCTAssertEqual(callbackTimer.delays, [1_500, 9_000], "The first wait kept the timeout its request was sent with")
        XCTAssertEqual(engine.gateway.pendingCount, 0)
    }

    func test_decider_lateAnswer_ignored() async throws {
        let engine = await makeEngine()
        let timer = callbackTimer
        let decider = FakeDecider(engine: engine) { _, _ in
            timer.fireAll()
            return nil
        }
        let id = try await enqueue(engine)
        answer(503)
        let ended = clock.nowMs() + requestMs
        try await flush(engine)
        try await assertThePolicyRetried(id, in: engine, ended: ended)

        engine.gateway.answer(try XCTUnwrap(decider.requestIds.values.first), .decision(.drop))

        let stored = try await row(id, in: engine)
        XCTAssertEqual(stored.status, "PENDING", "The late DROP changed nothing")
        XCTAssertEqual(stored.nextRetryAt, ended + 1_000)
        XCTAssertEqual(emitter.events(ofType: "ITEM_FAILED").count, 0)
        XCTAssertEqual(engine.gateway.pendingCount, 0)
    }

    /// A decider whose hybrid a newer one replaced was evicted with it: it is
    /// never asked, and nothing waits for it.
    func test_anEvictedDecider_isNeverAsked_andNothingWaits() async throws {
        let engine = await makeEngine()
        let evicted = FakeDecider(engine: engine) { _, _ in .decision(.drop) }
        _ = engine.attachCallbacks()
        let id = try await enqueue(engine)
        answer(503)
        let ended = clock.nowMs() + requestMs

        try await flush(engine)

        try await assertThePolicyRetried(id, in: engine, ended: ended)
        XCTAssertEqual(evicted.callCount, 0)
        XCTAssertEqual(callbackTimer.delays, [])
    }

    // MARK: - Not asked

    func test_decider_notConsulted_nonRetryable() async throws {
        let engine = await makeEngine()
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.retry) }
        let notFound = try await enqueue(engine)
        answer(404)
        try await flush(engine)

        let rejected = try await enqueue(engine, url: "https://api.test/private")
        answer(401)
        try await flush(engine)

        let notFoundLetter = try await engine.storage.deadLetter(notFound)
        XCTAssertEqual(notFoundLetter?.lastErrorCode, "SERVER_ERROR")
        let rejectedLetter = try await engine.storage.deadLetter(rejected)
        XCTAssertEqual(rejectedLetter?.lastErrorCode, "UNAUTHORIZED", "A 401 under a configuration set in this process")
        XCTAssertEqual(decider.callCount, 0)
    }

    func test_decider_notConsulted_budgetSpent() async throws {
        let engine = await makeEngine(policy: .exact(maxAttempts: 2, baseDelayMs: 1_000))
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.retry) }

        // The last attempt fails with a retryable error.
        let lastAttempt = try await enqueue(engine)
        try await engine.storage.markInFlight(id: lastAttempt)
        try await engine.storage.markPendingForRetry(id: lastAttempt, errorCode: "SERVER_ERROR", retryDelayMs: 0)
        clock.advance(ms: 1)
        answer(503)
        try await flush(engine)

        // A budget spent before the cycle: no request at all.
        let spent = try await enqueue(engine)
        for _ in 0..<2 {
            try await engine.storage.markInFlight(id: spent)
            try await engine.storage.markPendingForRetry(id: spent, errorCode: "SERVER_ERROR", retryDelayMs: 0)
        }
        clock.advance(ms: 1)
        answer(503)
        try await flush(engine)

        let lastAttemptLetter = try await engine.storage.deadLetter(lastAttempt)
        XCTAssertEqual(lastAttemptLetter?.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
        let spentLetter = try await engine.storage.deadLetter(spent)
        XCTAssertEqual(spentLetter?.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
        XCTAssertEqual(URLProtocolStub.capturedRequests.count, 0, "The spent item sent nothing")
        XCTAssertEqual(decider.callCount, 0)
    }

    /// A 401 to default headers read back from storage is the credential
    /// hold, which the decider never sees.
    func test_decider_notConsulted_credentialHold() async throws {
        let first = await fixture.makeEngine()
        await first.configure(.sample(retryPolicy: .exact(maxAttempts: 5, baseDelayMs: 1_000),
                                      defaultHeaders: ["Authorization": "Bearer expired"]))
        first.shutdown()
        let engine = await fixture.makeEngine(emitter: emitter)
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.drop) }
        let id = try await enqueue(engine)
        answer(401)

        try await flush(engine)

        let stored = try await row(id, in: engine)
        XCTAssertEqual(stored.status, "PENDING", "Held, not dropped")
        XCTAssertEqual(stored.attempts, 0, "The attempt is refunded")
        XCTAssertEqual(stored.lastErrorCode, "UNAUTHORIZED")
        XCTAssertEqual(decider.callCount, 0)
    }

    func test_decider_notConsulted_success() async throws {
        let engine = await makeEngine()
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.drop) }
        let id = try await enqueue(engine)
        answer(200)

        try await flush(engine)

        let queued = try await isQueued(id, in: engine)
        XCTAssertFalse(queued, "Delivered")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_SUCCEEDED", "SYNC_SUCCEEDED"])
        XCTAssertEqual(decider.callCount, 0)
    }

    // MARK: - What it is told

    func test_decider_context_fields() async throws {
        let engine = await makeEngine()
        let decider = FakeDecider(engine: engine) { _, _ in .decision(.retry) }
        let answered = try await enqueue(engine, url: "https://api.test/orders/7", method: "PUT",
                                         metadata: ["tenant": "acme"])
        // One earlier attempt: this is the second.
        try await engine.storage.markInFlight(id: answered)
        try await engine.storage.markPendingForRetry(id: answered, errorCode: "SERVER_ERROR", retryDelayMs: 0)
        clock.advance(ms: 1)
        answer(503)
        try await flush(engine)

        let timedOut = try await enqueue(engine)
        failTheRequest(with: .timedOut)
        try await flush(engine)

        XCTAssertEqual(decider.requests.values, [
            RetryDeciderRequest(itemId: answered, attempt: 2, errorCode: "SERVER_ERROR", statusCode: 503,
                                url: "https://api.test/orders/7", method: "PUT", metadata: ["tenant": "acme"]),
            RetryDeciderRequest(itemId: timedOut, attempt: 1, errorCode: "TIMEOUT", statusCode: nil,
                                url: "https://api.test/items", method: "POST", metadata: nil)
        ])
    }

    // MARK: - Installs

    /// Through the hybrid: a second set replaces the first decider, and a
    /// clear leaves the policy alone.
    func test_decider_replace_and_clear() async throws {
        let engine = await makeEngine()
        let hybrid = HybridSyncProvider(engine: engine)
        let gateway = engine.gateway
        let first = Recorder<String>()
        let second = Recorder<String>()
        _ = try hybrid.setRetryDecider { requestId, _ in
            first.record(requestId)
            gateway.answer(requestId, .decision(.drop))
        }
        _ = try hybrid.setRetryDecider { requestId, _ in
            second.record(requestId)
            gateway.answer(requestId, .decision(.retry, delayMs: 9_000))
        }
        let replaced = try await enqueue(engine)
        answer(503)
        let replacedEnded = clock.nowMs() + requestMs
        try await flush(engine)

        let replacedRow = try await row(replaced, in: engine)
        XCTAssertEqual(replacedRow.nextRetryAt, replacedEnded + 9_000, "The second decider's delay")
        XCTAssertEqual(first.values, [])
        XCTAssertEqual(second.values.count, 1)

        _ = try hybrid.clearRetryDecider()
        XCTAssertEqual(try attachLikeTheBridge(try hybrid.isRetryDeciderInstalled()).value?.get(), false)
        let cleared = try await enqueue(engine)
        let clearedEnded = clock.nowMs() + requestMs
        try await flush(engine)

        try await assertThePolicyRetried(cleared, in: engine, ended: clearedEnded)
        XCTAssertEqual(second.values.count, 1, "Not asked once cleared")
    }

    // MARK: - Purged meanwhile

    /// A row purged while its decider is asked, a logout say, stays gone: a
    /// retry writes nothing back, and neither a dead letter nor a drop leaves
    /// anything. The request went out, so its outcome is still reported.
    func test_decider_purgedDuringWait_noResurrection() async throws {
        let engine = await makeEngine()
        let storage = engine.storage
        let gateway = engine.gateway
        let cases: [(RetryDecisionValue.Action, String)] = [(.retry, "ITEM_RETRYING(SERVER_ERROR)"),
                                                             (.deadLetter, "ITEM_FAILED(RETRY_DECIDER_REJECTED)"),
                                                             (.drop, "ITEM_FAILED(RETRY_DECIDER_REJECTED)")]
        for (action, reported) in cases {
            let decider = FakeDecider(engine: engine) { requestId, request in
                Task {
                    _ = try? await storage.remove(id: request.itemId)
                    gateway.answer(requestId, .decision(action))
                }
                return nil
            }
            let id = try await enqueue(engine)
            answer(503)

            try await flush(engine)

            let queued = try await isQueued(id, in: engine)
            XCTAssertFalse(queued, "\(action): not revived")
            let letter = try await engine.storage.deadLetter(id)
            XCTAssertNil(letter, "\(action): not dead-lettered")
            XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", reported,
                                    action == .retry ? "SYNC_SUCCEEDED" : "SYNC_FAILED"], "\(action)")
            XCTAssertEqual(decider.callCount, 1)
        }
    }
}
