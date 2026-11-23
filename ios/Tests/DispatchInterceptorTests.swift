import XCTest
import NitroModules
@testable import SyncProvider

/// The header resolver, `beforeDispatch` and `afterDispatch`, at the
/// dispatcher. For each item that can be sent: the resolver, the merge and
/// `beforeDispatch`, all before the claim, which is the last check that the
/// row is still queued; then the request, its outcome, and, once that is
/// written and emitted, `afterDispatch`, which nothing waits for.
///
/// A resolver or `beforeDispatch` that gives nothing to use, or a SKIP, defers
/// only that item: nothing is sent, its attempt and its last attempt's time
/// are kept, and it waits the longer of its would-be attempt's backoff and
/// 60 s, with `INTERCEPTOR_FAILED`.
///
/// Time is manual. Every request takes `requestMs`, and the callbacks answer
/// at once unless a test says otherwise.
final class DispatchInterceptorTests: XCTestCase {
    private var fixture: EngineFixture!
    private var emitter: MockSyncEventEmitter!
    private var clock: ManualClock { fixture.clock }
    private var callbackTimer: ManualCallbackTimer { fixture.callbackTimer }

    private let requestMs: Int64 = 7
    /// The least a deferred item waits.
    private let floorMs: Int64 = 60_000

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

    /// An engine the app configured in this process, and the owner its
    /// callbacks are installed under: every fake of a test shares it, since a
    /// newer owner evicts the older installs. The default policy backs off
    /// exactly 1 s before a second attempt, 2 s before a third.
    private func makeEngine(policy: RetryPolicyValue = .exact(maxAttempts: 5, baseDelayMs: 1_000, maxDelayMs: 600_000),
                            defaultHeaders: [String: String] = [:],
                            callbackTimeoutMs: Double? = nil) async -> (SyncEngine, UInt64) {
        let engine = await fixture.makeEngine(emitter: emitter)
        var options = SyncOptionsSnapshot.sample(retryPolicy: policy, defaultHeaders: defaultHeaders)
        options.callbackTimeoutMs = callbackTimeoutMs
        await engine.configure(options)
        return (engine, engine.attachCallbacks())
    }

    @discardableResult
    private func installResolver(_ engine: SyncEngine,
                                 owner: UInt64,
                                 reply: @escaping FakeCallback<HeaderResolverRequest>.Reply)
        -> FakeCallback<HeaderResolverRequest> {
        FakeCallback(owner: owner, gateway: engine.gateway, install: engine.callbacks.setHeaderResolver, reply: reply)
    }

    @discardableResult
    private func installBefore(_ engine: SyncEngine,
                               owner: UInt64,
                               reply: @escaping FakeCallback<BeforeDispatchRequest>.Reply)
        -> FakeCallback<BeforeDispatchRequest> {
        FakeCallback(owner: owner, gateway: engine.gateway, install: engine.callbacks.setBeforeDispatch, reply: reply)
    }

    @discardableResult
    private func installAfter(_ engine: SyncEngine,
                              owner: UInt64,
                              reply: @escaping FakeCallback<AfterDispatchReport>.Reply = { _, _ in .resolvedEmpty })
        -> FakeCallback<AfterDispatchReport> {
        FakeCallback(owner: owner, gateway: engine.gateway, install: engine.callbacks.setAfterDispatch, reply: reply)
    }

    private func enqueue(_ engine: SyncEngine,
                         url: String = "https://api.test/items",
                         method: String = "POST",
                         headers: [String: String]? = nil,
                         contentType: String? = "application/json",
                         priority: String = "NORMAL",
                         metadata: [String: String]? = nil) async throws -> String {
        try await engine.storage.enqueue(input: SyncQueueStorage.SyncItemInputData(method: method,
                                                                                   url: url,
                                                                                   headers: headers,
                                                                                   body: "{}",
                                                                                   contentType: contentType,
                                                                                   priority: priority,
                                                                                   metadata: metadata))
    }

    /// Queues a row attempted once, whose 503 left a cooldown that is over:
    /// a deferral that keeps its attempt keeps 1, its last attempt's time and
    /// nothing else. Returns the row as it is now.
    private func enqueueAttempted(_ engine: SyncEngine, url: String = "https://api.test/items") async throws -> StoredSyncItem {
        let id = try await enqueue(engine, url: url)
        try await engine.storage.markInFlight(id: id)
        try await engine.storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 100)
        clock.advance(ms: 100)
        return try await row(id, in: engine)
    }

    /// Every request takes `requestMs`, then gets `status`. `log` records
    /// each one as "request".
    private func answer(_ status: Int, log: Recorder<String>? = nil) {
        let clock = self.clock
        let requestMs = self.requestMs
        URLProtocolStub.setHandler { request in
            log?.record("request")
            clock.advance(ms: requestMs)
            return try stubResponse(status)(request)
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

    private var sentURLs: [String] {
        URLProtocolStub.capturedRequests.map { $0.url?.absoluteString ?? "" }
    }

    /// Asserts the row `before` was deferred as an interceptor defers it: still
    /// `PENDING`, with its attempts and its last attempt's time, waiting
    /// `delayMs` from `deferredAt`, now by default, with `INTERCEPTOR_FAILED`,
    /// and one `ITEM_RETRYING` with that code, no status and the would-be
    /// attempt.
    private func assertDeferred(_ before: StoredSyncItem,
                                in engine: SyncEngine,
                                delayMs: Int64,
                                at deferredAt: Int64? = nil,
                                file: StaticString = #filePath,
                                line: UInt = #line) async throws {
        let stored = try await row(before.id, in: engine)
        XCTAssertEqual(stored.status, "PENDING", file: file, line: line)
        XCTAssertEqual(stored.attempts, before.attempts, "no attempt was made", file: file, line: line)
        XCTAssertEqual(stored.lastAttemptAt, before.lastAttemptAt, "no request went out", file: file, line: line)
        XCTAssertEqual(stored.lastErrorCode, "INTERCEPTOR_FAILED", file: file, line: line)
        XCTAssertEqual(stored.nextRetryAt, (deferredAt ?? clock.nowMs()) + delayMs, file: file, line: line)
        let retrying = emitter.events(ofType: "ITEM_RETRYING").filter { $0.itemId == before.id }
        XCTAssertEqual(retrying.count, 1, file: file, line: line)
        XCTAssertEqual(retrying.first?.errorCode, "INTERCEPTOR_FAILED", file: file, line: line)
        XCTAssertNil(retrying.first?.statusCode, file: file, line: line)
        XCTAssertEqual(retrying.first?.attempt, Double(before.attempts + 1), "the would-be attempt", file: file, line: line)
    }

    // MARK: - Not installed

    /// With nothing installed, the static headers go out as before, and
    /// nothing is asked.
    func test_interceptor_unset_sendsStaticHeaders() async throws {
        let (engine, _) = await makeEngine(defaultHeaders: ["Authorization": "Bearer static", "X-App": "1"])
        _ = try await enqueue(engine, headers: ["X-Item": "i"])
        answer(200)

        try await flush(engine)

        let request = try XCTUnwrap(URLProtocolStub.capturedRequests.first)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer static")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-App"), "1")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Item"), "i")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_SUCCEEDED", "SYNC_SUCCEEDED"])
        XCTAssertEqual(callbackTimer.delays, [], "Nothing was asked")
        XCTAssertEqual(engine.gateway.pendingCount, 0)
    }

    // MARK: - Order and merge

    func test_interceptor_order_resolver_before_dispatch_after() async throws {
        let (engine, owner) = await makeEngine()
        let log = Recorder<String>()
        let resolver = installResolver(engine, owner: owner) { _, _ in
            log.record("resolver")
            return .headers([:])
        }
        let before = installBefore(engine, owner: owner) { _, _ in
            log.record("before")
            return .before(.proceed)
        }
        let after = installAfter(engine, owner: owner) { _, _ in
            log.record("after")
            return .resolvedEmpty
        }
        let id = try await enqueue(engine, method: "PUT", metadata: ["tenant": "acme"])
        answer(200, log: log)

        let result = try await flush(engine)

        XCTAssertEqual(log.values, ["resolver", "before", "request", "after"])
        XCTAssertEqual(resolver.requests.values, [HeaderResolverRequest(itemId: id,
                                                                        attempt: 1,
                                                                        url: "https://api.test/items",
                                                                        method: "PUT",
                                                                        metadata: ["tenant": "acme"])])
        XCTAssertEqual(before.callCount, 1)
        XCTAssertEqual(after.callCount, 1)
        XCTAssertEqual(result.itemsSucceeded, 1)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_SUCCEEDED", "SYNC_SUCCEEDED"])
        XCTAssertEqual(engine.gateway.pendingCount, 0)
    }

    /// `defaultHeaders` < resolver < the item's headers, by name whatever its
    /// case, and the winning layer's spelling is kept.
    func test_interceptor_headerMergeOrder() async throws {
        let (engine, owner) = await makeEngine(defaultHeaders: ["Authorization": "Bearer default",
                                                                "X-Default": "d",
                                                                "x-shared": "default"])
        installResolver(engine, owner: owner) { _, _ in
            .headers(["authorization": "Bearer resolved", "X-Resolver": "r", "X-SHARED": "resolver"])
        }
        let before = installBefore(engine, owner: owner) { _, _ in .before(.proceed) }
        _ = try await enqueue(engine, headers: ["X-Shared": "item", "X-Item": "i"])
        answer(200)

        try await flush(engine)

        let merged = ["authorization": "Bearer resolved",
                      "X-Default": "d",
                      "X-Resolver": "r",
                      "X-Shared": "item",
                      "X-Item": "i"]
        XCTAssertEqual(before.requests.values.first?.headers, merged)
        let request = try XCTUnwrap(URLProtocolStub.capturedRequests.first)
        for (name, value) in merged {
            XCTAssertEqual(request.value(forHTTPHeaderField: name), value, name)
        }
        let names = (request.allHTTPHeaderFields ?? [:]).keys.map { $0.lowercased() }
        XCTAssertEqual(names.filter { $0 == "authorization" }.count, 1, "One Authorization goes out")
        XCTAssertEqual(names.filter { $0 == "x-shared" }.count, 1, "One X-Shared goes out")
    }

    // MARK: - The resolver fails closed

    func test_interceptor_resolverFails_failsClosed_keepsAttempt() async throws {
        let (engine, owner) = await makeEngine()
        installResolver(engine, owner: owner) { _, _ in .failed("the token refresh failed") }
        let before = installBefore(engine, owner: owner) { _, _ in .before(.proceed) }
        let after = installAfter(engine, owner: owner)
        let attempted = try await enqueueAttempted(engine)
        answer(200)

        let result = try await flush(engine)

        // A third attempt would back off 2 s: the floor wins.
        try await assertDeferred(attempted, in: engine, delayMs: floorMs)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_RETRYING(INTERCEPTOR_FAILED)", "SYNC_SUCCEEDED"])
        XCTAssertEqual(sentURLs, [], "Nothing was sent")
        XCTAssertEqual(before.callCount, 0, "beforeDispatch is not asked after a failed resolver")
        XCTAssertEqual(after.callCount, 0)
        XCTAssertEqual(result.itemsAttempted, 0)
        XCTAssertEqual(result.itemsSucceeded, 0)
        XCTAssertEqual(result.itemsFailed, 0, "A deferral is no failure")
    }

    func test_interceptor_resolverTimeout_failsClosed() async throws {
        let (engine, owner) = await makeEngine(callbackTimeoutMs: 1_500)
        let timer = callbackTimer
        let resolver = installResolver(engine, owner: owner) { _, _ in
            timer.fireAll()
            return nil
        }
        let before = installBefore(engine, owner: owner) { _, _ in .before(.proceed) }
        let attempted = try await enqueueAttempted(engine)
        answer(200)

        try await flush(engine)

        try await assertDeferred(attempted, in: engine, delayMs: floorMs)
        XCTAssertEqual(resolver.callCount, 1)
        XCTAssertEqual(before.callCount, 0)
        XCTAssertEqual(callbackTimer.delays, [1_500])
        XCTAssertEqual(engine.gateway.pendingCount, 0)
        XCTAssertEqual(sentURLs, [])
    }

    /// A RESOLVED answer counts only with headers HTTP allows.
    func test_interceptor_resolverInvalid_failsClosed() async throws {
        let (engine, owner) = await makeEngine()
        let replies: [String: CallbackReply] = [
            "bad-name": .headers(["X Bad": "v"]),
            "bad-value": .headers(["X-Ok": "line\nbreak"]),
            "no-headers": CallbackReply(status: .resolved),
            "wrong-kind": .before(.proceed),
        ]
        installResolver(engine, owner: owner) { _, request in replies[lastPath(request.url)] ?? CallbackReply.headers([:]) }
        let before = installBefore(engine, owner: owner) { _, _ in .before(.proceed) }
        var queued: [StoredSyncItem] = []
        for name in replies.keys.sorted() {
            let id = try await enqueue(engine, url: "https://api.test/\(name)")
            let item = try await row(id, in: engine)
            queued.append(item)
        }
        answer(200)

        let result = try await flush(engine)

        for item in queued {
            try await assertDeferred(item, in: engine, delayMs: floorMs)
        }
        XCTAssertEqual(sentURLs, [])
        XCTAssertEqual(before.callCount, 0)
        XCTAssertEqual(result.itemsFailed, 0)
    }

    // MARK: - beforeDispatch

    func test_interceptor_beforeSkip_defers() async throws {
        // A third attempt would back off 200 s: past the floor, so it wins.
        let (engine, owner) = await makeEngine(policy: .exact(maxAttempts: 5, baseDelayMs: 100_000, maxDelayMs: 600_000))
        let resolver = installResolver(engine, owner: owner) { _, _ in .headers(["Authorization": "Bearer resolved"]) }
        installBefore(engine, owner: owner) { _, _ in .before(.skip) }
        let after = installAfter(engine, owner: owner)
        let attempted = try await enqueueAttempted(engine)
        answer(200)

        try await flush(engine)

        try await assertDeferred(attempted, in: engine, delayMs: 200_000)
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "ITEM_RETRYING(INTERCEPTOR_FAILED)", "SYNC_SUCCEEDED"])
        XCTAssertEqual(resolver.callCount, 1)
        XCTAssertEqual(after.callCount, 0)
        XCTAssertEqual(sentURLs, [])
    }

    /// FAILED, a RESOLVED answer without an action, and no answer at all.
    func test_interceptor_beforeFails_failsClosed() async throws {
        let (engine, owner) = await makeEngine()
        let timer = callbackTimer
        installResolver(engine, owner: owner) { _, _ in .headers([:]) }
        installBefore(engine, owner: owner) { _, request in
            switch lastPath(request.url) {
            case "failed": return .failed("the hook threw")
            case "no-action": return CallbackReply(status: .resolved)
            default:
                timer.fireAll()
                return nil
            }
        }
        var queued: [StoredSyncItem] = []
        for name in ["failed", "no-action", "silent"] {
            let id = try await enqueue(engine, url: "https://api.test/\(name)")
            let item = try await row(id, in: engine)
            queued.append(item)
        }
        answer(200)

        try await flush(engine)

        for item in queued {
            try await assertDeferred(item, in: engine, delayMs: floorMs)
        }
        XCTAssertEqual(sentURLs, [])
        XCTAssertEqual(engine.gateway.pendingCount, 0)
    }

    /// `beforeDispatch` sees the merged headers that go out, without the
    /// `Content-Type` that `contentType` supplies; an explicit one stays.
    func test_interceptor_beforeContext_hasMergedHeaders() async throws {
        let (engine, owner) = await makeEngine(defaultHeaders: ["Authorization": "Bearer default", "X-Default": "d"])
        installResolver(engine, owner: owner) { _, request in .headers(["Authorization": "Bearer \(request.itemId)"]) }
        let before = installBefore(engine, owner: owner) { _, _ in .before(.proceed) }
        let json = try await enqueue(engine,
                                     url: "https://api.test/json",
                                     method: "PUT",
                                     headers: ["X-Item": "i"],
                                     priority: "HIGH",
                                     metadata: ["tenant": "acme"])
        let explicit = try await enqueue(engine,
                                         url: "https://api.test/explicit",
                                         headers: ["content-type": "text/plain"],
                                         priority: "LOW")
        answer(200)

        try await flush(engine)

        let contexts = before.requests.values
        XCTAssertEqual(contexts.count, 2)
        XCTAssertEqual(contexts.first, BeforeDispatchRequest(itemId: json,
                                                             attempt: 1,
                                                             url: "https://api.test/json",
                                                             method: "PUT",
                                                             headers: ["Authorization": "Bearer \(json)",
                                                                       "X-Default": "d",
                                                                       "X-Item": "i"],
                                                             contentType: "application/json",
                                                             metadata: ["tenant": "acme"]))
        XCTAssertEqual(contexts.last?.headers, ["Authorization": "Bearer \(explicit)",
                                                "X-Default": "d",
                                                "content-type": "text/plain"])
        XCTAssertEqual(contexts.last?.contentType, "application/json")
        let requests = URLProtocolStub.capturedRequests
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer \(json)")
        XCTAssertEqual(requests.first?.value(forHTTPHeaderField: "X-Default"), "d")
        XCTAssertEqual(requests.first?.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(requests.last?.value(forHTTPHeaderField: "Content-Type"), "text/plain")
    }

    /// One configuration per item: a `configureSync` during the
    /// `beforeDispatch` wait applies from the next item, so the request
    /// carries what the hook saw.
    func test_interceptor_beforeContext_hasMergedHeaders_aConfigureDuringTheWaitAppliesFromTheNextItem() async throws {
        let policy = RetryPolicyValue.exact(maxAttempts: 5, baseDelayMs: 1_000, maxDelayMs: 600_000)
        let (engine, owner) = await makeEngine(policy: policy, defaultHeaders: ["Authorization": "Bearer old"])
        let gateway = engine.gateway
        let held = Recorder<String>()
        let firstAsked = expectation(description: "beforeDispatch was asked about the first item")
        let before = installBefore(engine, owner: owner) { requestId, request in
            guard lastPath(request.url) == "first" else { return .before(.proceed) }
            held.record(requestId)
            firstAsked.fulfill()
            return nil
        }
        _ = try await enqueue(engine, url: "https://api.test/first", priority: "HIGH")
        _ = try await enqueue(engine, url: "https://api.test/second", priority: "LOW")
        answer(200)
        emitter.clear()

        let flushing = Task { try await engine.dispatcher.flush() }
        await fulfillment(of: [firstAsked], timeout: livenessTimeout)
        await engine.configure(.sample(retryPolicy: policy, defaultHeaders: ["Authorization": "Bearer new"]))
        gateway.answer(try XCTUnwrap(held.values.first), .before(.proceed))
        let result = try await flushing.value

        XCTAssertEqual(result.itemsSucceeded, 2)
        XCTAssertEqual(before.requests.values.map { $0.headers["Authorization"] }, ["Bearer old", "Bearer new"])
        XCTAssertEqual(URLProtocolStub.capturedRequests.map { $0.value(forHTTPHeaderField: "Authorization") },
                       ["Bearer old", "Bearer new"])
    }

    // MARK: - Per item

    /// A deferral holds only its own item: the next one is sent in the same
    /// cycle, and no cycle-level hold delays the wake of a row that is due.
    func test_interceptor_failure_isPerItem() async throws {
        let (engine, owner) = await makeEngine()
        installResolver(engine, owner: owner) { _, request in
            lastPath(request.url) == "failing" ? .failed("no token for this tenant") : .headers([:])
        }
        let after = installAfter(engine, owner: owner)
        let failing = try await enqueueAttempted(engine, url: "https://api.test/failing")
        let sent = try await enqueue(engine, url: "https://api.test/sent")
        answer(200)
        // The first item is deferred before the second request moves the clock.
        let deferredAt = clock.nowMs()

        let result = try await flush(engine)

        try await assertDeferred(failing, in: engine, delayMs: floorMs, at: deferredAt)
        let sentQueued = try await isQueued(sent, in: engine)
        XCTAssertFalse(sentQueued, "The next item was sent")
        XCTAssertEqual(events, ["SYNC_STARTED",
                                "SYNC_PROGRESS", "ITEM_RETRYING(INTERCEPTOR_FAILED)",
                                "SYNC_PROGRESS", "ITEM_SUCCEEDED",
                                "SYNC_SUCCEEDED"])
        XCTAssertEqual(result.itemsAttempted, 1)
        XCTAssertEqual(result.itemsSucceeded, 1)
        XCTAssertEqual(result.itemsFailed, 0)
        XCTAssertEqual(after.requests.values.map(\.itemId), [sent])

        await engine.rearmRetryTimer()
        XCTAssertEqual(fixture.timer.delayMs, deferredAt + floorMs - clock.nowMs(), "The wake is the deferred row's own")
        _ = try await enqueueAttempted(engine, url: "https://api.test/due")
        await engine.rearmRetryTimer()
        XCTAssertEqual(fixture.timer.delayMs, 0, "A row that is due arms at once: no cycle-level hold")
    }

    // MARK: - A purge during a wait

    /// A row purged while the resolver is asked is not sent and gets no event
    /// of its own: not when the answer lets it go on, and not when it fails,
    /// since there is no row left to defer.
    func test_interceptor_purgedDuringResolver_notSent() async throws {
        let (engine, owner) = await makeEngine()
        let storage = engine.storage
        let gateway = engine.gateway
        let cases: [(String, CallbackReply)] = [("headers", .headers(["Authorization": "Bearer resolved"])),
                                                ("failed", .failed("the token refresh failed"))]
        for (name, reply) in cases {
            let resolver = installResolver(engine, owner: owner) { requestId, request in
                Task {
                    _ = try? await storage.remove(id: request.itemId)
                    gateway.answer(requestId, reply)
                }
                return nil
            }
            let before = installBefore(engine, owner: owner) { _, _ in .before(.proceed) }
            let after = installAfter(engine, owner: owner)
            let id = try await enqueue(engine)
            answer(200)

            try await flush(engine)

            let queued = try await isQueued(id, in: engine)
            XCTAssertFalse(queued, "\(name): not brought back")
            XCTAssertEqual(sentURLs, [], "\(name): nothing was sent")
            XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "SYNC_SUCCEEDED"], "\(name): no event of its own")
            XCTAssertEqual(resolver.callCount, 1, name)
            XCTAssertEqual(before.callCount, name == "headers" ? 1 : 0, "\(name): the claim is the check")
            XCTAssertEqual(after.callCount, 0, name)
        }
    }

    func test_interceptor_purgedDuringBefore_notSent() async throws {
        let (engine, owner) = await makeEngine()
        let storage = engine.storage
        let gateway = engine.gateway
        installResolver(engine, owner: owner) { _, _ in .headers([:]) }
        for action in [BeforeDispatchActionValue.proceed, .skip] {
            let before = installBefore(engine, owner: owner) { requestId, request in
                Task {
                    _ = try? await storage.remove(id: request.itemId)
                    gateway.answer(requestId, .before(action))
                }
                return nil
            }
            let after = installAfter(engine, owner: owner)
            let id = try await enqueue(engine)
            answer(200)

            try await flush(engine)

            let queued = try await isQueued(id, in: engine)
            XCTAssertFalse(queued, "\(action): not brought back")
            XCTAssertEqual(sentURLs, [], "\(action): nothing was sent")
            XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "SYNC_SUCCEEDED"], "\(action): no event of its own")
            XCTAssertEqual(before.callCount, 1)
            XCTAssertEqual(after.callCount, 0)
        }
    }

    // MARK: - afterDispatch

    /// One call per request that went out, once its outcome is written, with
    /// the request's own duration: the waits for the resolver,
    /// `beforeDispatch` and the decider do not count.
    func test_interceptor_after_calledOncePerSentRequest() async throws {
        let (engine, owner) = await makeEngine(policy: .exact(maxAttempts: 2, baseDelayMs: 1_000, maxDelayMs: 600_000))
        let clock = self.clock
        installResolver(engine, owner: owner) { _, _ in
            clock.advance(ms: 1_000)
            return .headers([:])
        }
        installBefore(engine, owner: owner) { _, _ in
            clock.advance(ms: 1_000)
            return .before(.proceed)
        }
        let decider = FakeDecider(registry: engine.callbacks, gateway: engine.gateway, owner: owner) { _, request in
            clock.advance(ms: 1_000)
            switch lastPath(request.url) {
            case "dropped": return .decision(.drop)
            case "lettered": return .decision(.deadLetter)
            default: return .decision(.retry)
            }
        }
        let after = installAfter(engine, owner: owner)
        // What each request gets, and how long it takes. 0 is a timeout.
        let plan: [String: (status: Int, ms: Int64)] = ["ok": (200, 3),
                                                        "retry": (503, 5),
                                                        "timeout": (0, 11),
                                                        "not-found": (404, 13),
                                                        "exhausted": (503, 17),
                                                        "dropped": (503, 19),
                                                        "lettered": (503, 23)]
        URLProtocolStub.setHandler { request in
            let step = plan[request.url?.lastPathComponent ?? ""] ?? (200, 1)
            clock.advance(ms: step.ms)
            if step.status == 0 { throw URLError(.timedOut) }
            return try stubResponse(step.status)(request)
        }
        var ids: [String: String] = [:]
        for name in ["ok", "retry", "timeout", "not-found", "dropped", "lettered"] {
            ids[name] = try await enqueue(engine,
                                          url: "https://api.test/\(name)",
                                          metadata: name == "ok" ? ["tenant": "acme"] : nil)
        }
        ids["exhausted"] = try await enqueueAttempted(engine, url: "https://api.test/exhausted").id

        try await flush(engine)

        func expected(_ name: String,
                      attempt: Int32 = 1,
                      _ outcome: DispatchOutcomeKind,
                      status: Int?,
                      code: String?) -> AfterDispatchReport {
            AfterDispatchReport(itemId: ids[name] ?? "",
                                attempt: attempt,
                                outcome: outcome,
                                statusCode: status,
                                errorCode: code,
                                url: "https://api.test/\(name)",
                                method: "POST",
                                metadata: name == "ok" ? ["tenant": "acme"] : nil,
                                durationMs: plan[name]?.ms ?? -1)
        }
        let reports = Dictionary(after.requests.values.map { (lastPath($0.url), $0) }) { first, _ in first }
        XCTAssertEqual(after.callCount, 7, "One call per request")
        XCTAssertEqual(Set(after.requestIds.values).count, 7)
        XCTAssertEqual(reports["ok"], expected("ok", .succeeded, status: 200, code: nil))
        XCTAssertEqual(reports["retry"], expected("retry", .retrying, status: 503, code: "SERVER_ERROR"))
        XCTAssertEqual(reports["timeout"], expected("timeout", .retrying, status: nil, code: "TIMEOUT"))
        XCTAssertEqual(reports["not-found"], expected("not-found", .failed, status: 404, code: "SERVER_ERROR"))
        XCTAssertEqual(reports["exhausted"],
                       expected("exhausted", attempt: 2, .failed, status: 503, code: "MAX_ATTEMPTS_EXCEEDED"))
        XCTAssertEqual(reports["dropped"], expected("dropped", .failed, status: 503, code: "RETRY_DECIDER_REJECTED"))
        XCTAssertEqual(reports["lettered"], expected("lettered", .failed, status: 503, code: "RETRY_DECIDER_REJECTED"))
        XCTAssertEqual(decider.callCount, 4, "retry, timeout, dropped and lettered")
        XCTAssertEqual(engine.gateway.pendingCount, 0)
    }

    /// A stale credential's hold stops the cycle, but the held item's request
    /// went out: it gets its call first, as RETRYING.
    func test_interceptor_after_calledOncePerSentRequest_aCredentialHoldIsRetrying() async throws {
        let first = await fixture.makeEngine()
        let policy = RetryPolicyValue.exact(maxAttempts: 5, baseDelayMs: 1_000, maxDelayMs: 600_000)
        await first.configure(.sample(retryPolicy: policy, defaultHeaders: ["Authorization": "Bearer expired"]))
        first.shutdown()
        // Relaunched, not configured yet: the stored credential goes out.
        let engine = await fixture.makeEngine(emitter: emitter)
        let after = installAfter(engine, owner: engine.attachCallbacks())
        let held = try await enqueue(engine, url: "https://api.test/held", priority: "HIGH")
        _ = try await enqueue(engine, url: "https://api.test/behind", priority: "LOW")
        answer(401)

        try await flush(engine)

        XCTAssertEqual(after.requests.values, [AfterDispatchReport(itemId: held,
                                                                   attempt: 1,
                                                                   outcome: .retrying,
                                                                   statusCode: 401,
                                                                   errorCode: "UNAUTHORIZED",
                                                                   url: "https://api.test/held",
                                                                   method: "POST",
                                                                   metadata: nil,
                                                                   durationMs: requestMs)])
        XCTAssertEqual(sentURLs, ["https://api.test/held"], "The hold stopped the cycle")
    }

    /// No request, no call: a deferral, a purge during a wait, a budget spent
    /// before the cycle, and a URL that is not http(s). Neither of the last
    /// two is even asked about.
    func test_interceptor_after_notCalledWhenNotSent() async throws {
        let (engine, owner) = await makeEngine(policy: .exact(maxAttempts: 1, baseDelayMs: 1_000, maxDelayMs: 600_000))
        let storage = engine.storage
        let resolver = installResolver(engine, owner: owner) { requestId, request in
            switch lastPath(request.url) {
            case "deferred":
                return .failed("no token")
            case "purged":
                Task { [gateway = engine.gateway] in
                    _ = try? await storage.remove(id: request.itemId)
                    gateway.answer(requestId, .headers([:]))
                }
                return nil
            default:
                return .headers([:])
            }
        }
        let after = installAfter(engine, owner: owner)
        _ = try await enqueue(engine, url: "https://api.test/deferred")
        _ = try await enqueue(engine, url: "https://api.test/purged")
        _ = try await enqueueAttempted(engine, url: "https://api.test/spent")
        _ = try await enqueue(engine, url: "ftp://api.test/invalid")
        let sent = try await enqueue(engine, url: "https://api.test/sent")
        answer(200)

        try await flush(engine)

        XCTAssertEqual(resolver.requests.values.map { lastPath($0.url) }, ["deferred", "purged", "sent"])
        XCTAssertEqual(sentURLs, ["https://api.test/sent"])
        XCTAssertEqual(after.requests.values.map(\.itemId), [sent], "Only the request that went out")
    }

    /// Nothing waits for `afterDispatch`: `flush()` returns, and the next item
    /// goes on, while both calls are still unanswered. Their timeout only
    /// drops them.
    func test_interceptor_after_neverAwaited() async throws {
        let (engine, owner) = await makeEngine(callbackTimeoutMs: 600_000)
        let after = installAfter(engine, owner: owner) { _, _ in nil }
        let first = try await enqueue(engine, url: "https://api.test/first", priority: "HIGH")
        let second = try await enqueue(engine, url: "https://api.test/second", priority: "LOW")
        answer(200)

        let result = try await flush(engine)

        XCTAssertEqual(result.itemsSucceeded, 2)
        XCTAssertEqual(after.requests.values.map(\.itemId), [first, second])
        XCTAssertEqual(engine.gateway.pendingCount, 2, "Both calls are still out")
        XCTAssertEqual(callbackTimer.delays, [600_000, 600_000])
        let cycleEvents = events

        callbackTimer.fireAll()

        XCTAssertEqual(engine.gateway.pendingCount, 0)
        XCTAssertEqual(events, cycleEvents, "Their timeout changes nothing else")
        let queue = try await engine.storage.getAll()
        XCTAssertTrue(queue.isEmpty)
    }

    /// Through the hybrid, which hops to the main queue: JS hears the item's
    /// event, which the emitter queued there first, before `afterDispatch`.
    func test_interceptor_after_followsItsItemEvent() async throws {
        let (engine, _) = await makeEngine()
        let hybrid = HybridSyncProvider(engine: engine)
        let heard = Recorder<String>()
        let called = expectation(description: "afterDispatch was called")
        _ = try engine.emitter.addListener(channel: kSyncEventChannel) { payload in
            heard.record(payload.type)
        }
        _ = try hybrid.setAfterDispatch { _, _ in
            heard.record("afterDispatch")
            called.fulfill()
        }
        _ = try await enqueue(engine)
        answer(200)

        try await flush(engine)
        await fulfillment(of: [called], timeout: livenessTimeout)

        let order = heard.values
        let itemEvent = try XCTUnwrap(order.firstIndex(of: "ITEM_SUCCEEDED"))
        let hook = try XCTUnwrap(order.firstIndex(of: "afterDispatch"))
        XCTAssertLessThan(itemEvent, hook)
        withExtendedLifetime(hybrid) {}
    }

    // MARK: - Clearing

    func test_interceptor_clear_restoresStaticHeaders() async throws {
        let (engine, owner) = await makeEngine(defaultHeaders: ["Authorization": "Bearer static"])
        let resolver = installResolver(engine, owner: owner) { _, _ in .headers(["Authorization": "Bearer resolved"]) }
        let before = installBefore(engine, owner: owner) { _, _ in .before(.proceed) }
        _ = try await enqueue(engine, url: "https://api.test/first")
        answer(200)
        try await flush(engine)
        XCTAssertEqual(URLProtocolStub.capturedRequests.first?.value(forHTTPHeaderField: "Authorization"),
                       "Bearer resolved")

        engine.callbacks.clearHeaderResolver(owner: owner)
        engine.callbacks.clearBeforeDispatch(owner: owner)
        _ = try await enqueue(engine, url: "https://api.test/second")
        answer(200)
        try await flush(engine)

        XCTAssertEqual(URLProtocolStub.capturedRequests.first?.value(forHTTPHeaderField: "Authorization"),
                       "Bearer static")
        XCTAssertEqual(resolver.callCount, 1)
        XCTAssertEqual(before.callCount, 1)
        XCTAssertEqual(callbackTimer.delays.count, 2, "Nothing was asked after the clear")
    }

    // MARK: - Probes

    /// The network-quality measurement and the health check send their HEADs
    /// without asking any callback.
    func test_interceptor_probes_neverCallCallbacks() async throws {
        let engine = await fixture.makeEngine(emitter: emitter)
        let owner = engine.attachCallbacks()
        let decider = FakeDecider(registry: engine.callbacks, gateway: engine.gateway, owner: owner) { _, _ in
            .decision(.retry)
        }
        let resolver = installResolver(engine, owner: owner) { _, _ in .headers([:]) }
        let before = installBefore(engine, owner: owner) { _, _ in .before(.proceed) }
        let after = installAfter(engine, owner: owner)
        // Configuring a health check measures the link: three HEADs. A 404
        // grades it but leaves no "last healthy", so the flush checks again.
        fixture.probe.answer(latencyMs: 100, statusCode: 404)
        var options = SyncOptionsSnapshot.sample()
        options.healthCheckUrl = "https://api.test/health"
        await engine.configure(options)
        await engine.qualityMonitor.waitForProbe()
        fixture.probe.answer(latencyMs: 100, statusCode: 204)

        let outcome = try await engine.dispatcher.flushOutcome()

        guard case .ran = outcome else { return XCTFail("Expected a cycle, got \(outcome)") }
        XCTAssertEqual(fixture.probe.callCount, 4, "The measurement's three HEADs and the health check's")
        XCTAssertEqual([decider.callCount, resolver.callCount, before.callCount, after.callCount], [0, 0, 0, 0])
        XCTAssertEqual(callbackTimer.delays, [])
        XCTAssertEqual(sentURLs, [])
    }

    // MARK: - A deferral that cannot be written

    /// The cycle dies with the write, and the row, which was never claimed, is
    /// exactly as it was: the cooldown and the code are lost for this cycle.
    func test_interceptor_deferWriteFails_rowComesBackAsItWas() async throws {
        let (engine, owner) = await makeEngine()
        let resolver = installResolver(engine, owner: owner) { _, _ in .failed("no token") }
        let attempted = try await enqueueAttempted(engine)
        answer(200)
        // No claim comes first: the deferral is the cycle's first write.
        fixture.saveFaults.fail(1)

        do {
            try await flush(engine)
            XCTFail("The cycle dies with the write")
        } catch {}

        XCTAssertEqual(fixture.saveFaults.failures, 1, "Only the deferral's write failed")
        let stored = try await row(attempted.id, in: engine)
        XCTAssertEqual(stored, attempted, "Exactly as it was")
        XCTAssertEqual(events, ["SYNC_STARTED", "SYNC_PROGRESS", "SYNC_FAILED(NETWORK_ERROR)"])
        XCTAssertEqual(resolver.callCount, 1)
        XCTAssertEqual(sentURLs, [])
    }

    // MARK: - Background uploads

    /// An upload handed to the background session carries the resolved and
    /// merged headers: they are on the request before its task is created.
    func test_ios_backgroundUpload_carriesResolvedHeaders() async throws {
        let stack = InMemoryCoreDataStack.make()
        let storage = SyncQueueStorage(dao: SyncItemDao(stack: stack), capacity: 10)
        let callbacks = CallbackRegistry()
        let gateway = CallbackGateway(timer: ManualCallbackTimer())
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [URLProtocolStub.self]
        let delegate = BackgroundURLSessionDelegate()
        let delegateQueue = OperationQueue()
        delegateQueue.maxConcurrentOperationCount = 1
        let dispatcher = SyncDispatcher(storage: storage,
                                        emitter: emitter,
                                        connectivity: FakeConnectivity(),
                                        config: DispatcherConfig(retryPolicy: .default,
                                                                 requestTimeoutMs: 30_000,
                                                                 batchSize: 25,
                                                                 defaultHeaders: ["Authorization": "Bearer static",
                                                                                  "X-Default": "d"]),
                                        // Not stubbed: a request sent through it would fail.
                                        foregroundSession: URLSession(configuration: .ephemeral),
                                        backgroundSession: URLSession(configuration: configuration,
                                                                      delegate: delegate,
                                                                      delegateQueue: delegateQueue),
                                        backgroundDelegate: delegate,
                                        isAppActive: { false },
                                        callbacks: callbacks,
                                        gateway: gateway)
        let resolver = FakeCallback(owner: callbacks.attach(),
                                    gateway: gateway,
                                    install: callbacks.setHeaderResolver) { _, _ in
            .headers(["Authorization": "Bearer resolved"])
        }
        _ = try await storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(200))

        let usesBackground = await dispatcher.shouldUseBackgroundSession()
        let result = try await dispatcher.flush()

        XCTAssertTrue(usesBackground)
        XCTAssertEqual(result.itemsSucceeded, 1)
        XCTAssertEqual(resolver.callCount, 1)
        let request = try XCTUnwrap(URLProtocolStub.capturedRequests.first)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer resolved")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Default"), "d")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
    }
}

/// The last path component of `url`, which names a test's item.
private func lastPath(_ url: String) -> String {
    URL(string: url)?.lastPathComponent ?? url
}
