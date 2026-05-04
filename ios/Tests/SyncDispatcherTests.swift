import XCTest
@testable import SyncProvider

final class SyncDispatcherTests: XCTestCase {
    private var stack: CoreDataStack!
    private var dao: SyncItemDao!
    private var storage: SyncQueueStorage!
    private var emitter: MockSyncEventEmitter!
    private var foregroundSession: URLSession!
    private var backgroundSession: URLSession!
    private var dispatcher: SyncDispatcher!

    override func setUp() {
        super.setUp()
        stack = InMemoryCoreDataStack.make()
        dao = SyncItemDao(stack: stack)
        storage = SyncQueueStorage(dao: dao, capacity: 100)
        emitter = MockSyncEventEmitter()
        URLProtocolStub.setHandler(nil)
        foregroundSession = URLSession.stubbed()
        // Background session is unused in foreground tests — give it a
        // separate ephemeral session so the dispatcher init succeeds.
        backgroundSession = URLSession(configuration: .ephemeral)
    }

    override func tearDown() {
        URLProtocolStub.setHandler(nil)
        foregroundSession.invalidateAndCancel()
        backgroundSession.invalidateAndCancel()
        dispatcher = nil
        emitter = nil
        storage = nil
        dao = nil
        stack = nil
        super.tearDown()
    }

    // MARK: - Helpers

    private func makeDispatcher(retryPolicy: RetryPolicyValue = .default,
                                requestTimeoutMs: Int = 30_000,
                                batchSize: Int = 25,
                                defaultHeaders: [String: String] = [:]) -> SyncDispatcher {
        let config = DispatcherConfig(retryPolicy: retryPolicy,
                                      requestTimeoutMs: requestTimeoutMs,
                                      batchSize: batchSize,
                                      defaultHeaders: defaultHeaders)
        return SyncDispatcher(storage: storage,
                              emitter: emitter,
                              connectivity: ConnectivityMonitor.shared,
                              config: config,
                              foregroundSession: foregroundSession,
                              backgroundSession: backgroundSession)
    }

    private func enqueue(url: String = "https://api.test/v1/echo",
                        body: String? = "{\"hello\":\"world\"}",
                        headers: [String: String]? = ["X-Item": "yes"],
                        contentType: String? = "application/json",
                        priority: String = "NORMAL") async throws -> String {
        let input = SyncQueueStorage.SyncItemInputData(
            method: "POST",
            url: url,
            headers: headers,
            body: body,
            contentType: contentType,
            priority: priority,
            metadata: nil
        )
        return try await storage.enqueue(input: input)
    }

    private func httpResponse(_ url: String, status: Int) -> HTTPURLResponse {
        return HTTPURLResponse(url: URL(string: url)!,
                               statusCode: status,
                               httpVersion: "HTTP/1.1",
                               headerFields: ["Content-Type": "application/json"])!
    }

    // MARK: - Success path

    func test_flush_success_removesItem_andEmitsItemSucceeded() async throws {
        let id = try await enqueue()

        URLProtocolStub.setHandler { request in
            return (self.httpResponse(request.url!.absoluteString, status: 200), Data("{}".utf8))
        }

        dispatcher = makeDispatcher()
        let result = try await dispatcher.flush()

        XCTAssertEqual(result.itemsAttempted, 1)
        XCTAssertEqual(result.itemsSucceeded, 1)
        XCTAssertEqual(result.itemsFailed, 0)

        let remaining = try await storage.getAll()
        XCTAssertTrue(remaining.isEmpty, "Successful items must be removed from the queue")

        let succeeded = emitter.events(ofType: "ITEM_SUCCEEDED")
        XCTAssertEqual(succeeded.count, 1)
        XCTAssertEqual(succeeded.first?.itemId, id)
        XCTAssertEqual(succeeded.first?.statusCode, 200)
    }

    // MARK: - Retryable failure (5xx)

    func test_flush_500_marksRetry_andKeepsItemPending() async throws {
        let id = try await enqueue()

        URLProtocolStub.setHandler { request in
            return (self.httpResponse(request.url!.absoluteString, status: 500), Data())
        }

        dispatcher = makeDispatcher()
        let result = try await dispatcher.flush()

        XCTAssertEqual(result.itemsSucceeded, 0)
        // First failure with retries available → not counted as failed yet.
        XCTAssertEqual(result.itemsFailed, 0)

        let items = try await storage.getAll()
        let item = try XCTUnwrap(items.first(where: { $0.id == id }))
        XCTAssertEqual(item.status, "PENDING")
        XCTAssertEqual(item.attempts, 1)
        XCTAssertEqual(item.lastErrorCode, "SERVER_ERROR")

        let retrying = emitter.events(ofType: "ITEM_RETRYING")
        XCTAssertEqual(retrying.count, 1)
        XCTAssertEqual(retrying.first?.statusCode, 500)
    }

    func test_flush_408_isRetryable() async throws {
        _ = try await enqueue()

        URLProtocolStub.setHandler { request in
            return (self.httpResponse(request.url!.absoluteString, status: 408), Data())
        }

        dispatcher = makeDispatcher()
        _ = try await dispatcher.flush()

        let retrying = emitter.events(ofType: "ITEM_RETRYING")
        XCTAssertEqual(retrying.count, 1)
        XCTAssertEqual(retrying.first?.statusCode, 408)
    }

    func test_flush_429_isRetryable() async throws {
        _ = try await enqueue()

        URLProtocolStub.setHandler { request in
            return (self.httpResponse(request.url!.absoluteString, status: 429), Data())
        }

        dispatcher = makeDispatcher()
        _ = try await dispatcher.flush()

        let retrying = emitter.events(ofType: "ITEM_RETRYING")
        XCTAssertEqual(retrying.count, 1)
        XCTAssertEqual(retrying.first?.statusCode, 429)
    }

    // MARK: - Non-retryable failures

    func test_flush_400_marksFailed_immediately() async throws {
        let id = try await enqueue()

        URLProtocolStub.setHandler { request in
            return (self.httpResponse(request.url!.absoluteString, status: 400), Data())
        }

        dispatcher = makeDispatcher()
        let result = try await dispatcher.flush()

        XCTAssertEqual(result.itemsSucceeded, 0)
        XCTAssertEqual(result.itemsFailed, 1)

        let items = try await storage.getAll()
        let item = try XCTUnwrap(items.first(where: { $0.id == id }))
        XCTAssertEqual(item.status, "FAILED")
        XCTAssertEqual(item.lastErrorCode, "SERVER_ERROR")

        let failed = emitter.events(ofType: "ITEM_FAILED")
        XCTAssertEqual(failed.count, 1)
        XCTAssertEqual(failed.first?.statusCode, 400)
    }

    func test_flush_401_marksUnauthorized_andDoesNotRetry() async throws {
        let id = try await enqueue()

        URLProtocolStub.setHandler { request in
            return (self.httpResponse(request.url!.absoluteString, status: 401), Data())
        }

        dispatcher = makeDispatcher()
        let result = try await dispatcher.flush()

        XCTAssertEqual(result.itemsFailed, 1)

        let items = try await storage.getAll()
        let item = try XCTUnwrap(items.first(where: { $0.id == id }))
        XCTAssertEqual(item.status, "FAILED")
        XCTAssertEqual(item.lastErrorCode, "UNAUTHORIZED")

        let failed = emitter.events(ofType: "ITEM_FAILED")
        XCTAssertEqual(failed.first?.errorCode, "UNAUTHORIZED")
        XCTAssertEqual(failed.first?.statusCode, 401)
    }

    // MARK: - Transport errors

    func test_flush_timeout_isRetryable_andSurfacesTimeoutCode() async throws {
        _ = try await enqueue()

        URLProtocolStub.setHandler { _ in
            throw URLError(.timedOut)
        }

        dispatcher = makeDispatcher()
        _ = try await dispatcher.flush()

        let retrying = emitter.events(ofType: "ITEM_RETRYING")
        XCTAssertEqual(retrying.count, 1)
        XCTAssertEqual(retrying.first?.errorCode, "TIMEOUT")
    }

    func test_flush_networkLost_isRetryable_andSurfacesNetworkErrorCode() async throws {
        _ = try await enqueue()

        URLProtocolStub.setHandler { _ in
            throw URLError(.notConnectedToInternet)
        }

        dispatcher = makeDispatcher()
        _ = try await dispatcher.flush()

        let retrying = emitter.events(ofType: "ITEM_RETRYING")
        XCTAssertEqual(retrying.count, 1)
        XCTAssertEqual(retrying.first?.errorCode, "NETWORK_ERROR")
    }

    // MARK: - Max attempts

    func test_flush_exhaustsRetries_marksMaxAttemptsExceeded() async throws {
        let id = try await enqueue()
        let policy = RetryPolicyValue(
            maxAttempts: 2,
            backoff: .exponential,
            baseDelayMs: 10,
            maxDelayMs: 100,
            jitter: false,
            retryOnStatusCodes: [500, 502, 503]
        )
        // Drive attempts to maxAttempts - 1 so the next dispatch is the final try
        // and the 503 below should mark the item FAILED with MAX_ATTEMPTS_EXCEEDED.
        try await storage.markInFlight(id: id)
        try await storage.markPendingForRetry(id: id, errorCode: "NETWORK_ERROR")

        URLProtocolStub.setHandler { request in
            return (self.httpResponse(request.url!.absoluteString, status: 503), Data())
        }

        dispatcher = makeDispatcher(retryPolicy: policy)
        let result = try await dispatcher.flush()

        XCTAssertEqual(result.itemsFailed, 1)

        let items = try await storage.getAll()
        let item = try XCTUnwrap(items.first(where: { $0.id == id }))
        XCTAssertEqual(item.status, "FAILED")
        XCTAssertEqual(item.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
    }

    // MARK: - Headers + body capture

    func test_flush_sendsDefaultAndPerItemHeaders_andItemBody() async throws {
        let url = "https://api.test/v1/headers"
        _ = try await enqueue(url: url,
                              body: "{\"hello\":\"world\"}",
                              headers: ["X-Item": "yes"],
                              contentType: "application/json")

        URLProtocolStub.setHandler { request in
            return (self.httpResponse(request.url!.absoluteString, status: 200), Data())
        }

        dispatcher = makeDispatcher(defaultHeaders: ["X-Default": "global",
                                                     "Authorization": "Bearer token"])
        _ = try await dispatcher.flush()

        let captured = URLProtocolStub.capturedRequests
        XCTAssertEqual(captured.count, 1)
        let request = try XCTUnwrap(captured.first)
        XCTAssertEqual(request.url?.absoluteString, url)
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Default"), "global")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Item"), "yes")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")

        let body = URLProtocolStub.bodyData(for: request)
        XCTAssertNotNil(body)
        XCTAssertEqual(body, Data("{\"hello\":\"world\"}".utf8))
    }

    func test_flush_perItemHeaderOverridesDefault() async throws {
        _ = try await enqueue(headers: ["Authorization": "Bearer per-item"])

        URLProtocolStub.setHandler { request in
            return (self.httpResponse(request.url!.absoluteString, status: 200), Data())
        }

        dispatcher = makeDispatcher(defaultHeaders: ["Authorization": "Bearer default"])
        _ = try await dispatcher.flush()

        let captured = try XCTUnwrap(URLProtocolStub.capturedRequests.first)
        XCTAssertEqual(captured.value(forHTTPHeaderField: "Authorization"), "Bearer per-item")
    }

    // MARK: - Empty queue + paused

    func test_flush_onEmptyQueue_emitsSyncStarted_andSucceededWithZeros() async throws {
        URLProtocolStub.setHandler { _ in
            XCTFail("No request should be issued for an empty queue")
            return (HTTPURLResponse(), Data())
        }
        dispatcher = makeDispatcher()
        let result = try await dispatcher.flush()

        XCTAssertEqual(result.itemsAttempted, 0)
        XCTAssertEqual(result.itemsSucceeded, 0)
        XCTAssertEqual(result.itemsFailed, 0)

        let started = emitter.events(ofType: "SYNC_STARTED")
        XCTAssertEqual(started.count, 1)
        let succeeded = emitter.events(ofType: "SYNC_SUCCEEDED")
        XCTAssertEqual(succeeded.count, 1)
    }

    func test_setPaused_skipsDispatch() async throws {
        _ = try await enqueue()
        URLProtocolStub.setHandler { _ in
            XCTFail("Paused dispatcher must not perform requests")
            return (HTTPURLResponse(), Data())
        }
        dispatcher = makeDispatcher()
        await dispatcher.setPaused(true)
        let result = try await dispatcher.flush()
        XCTAssertEqual(result.itemsAttempted, 0)
        XCTAssertEqual(result.errorMessage, "paused")
    }

    // MARK: - classifyResponseSync (nonisolated)

    func test_classifyResponseSync_2xx_succeeds() {
        dispatcher = makeDispatcher()
        let outcome = dispatcher.classifyResponseSync(itemId: "x",
                                                       statusCode: 204,
                                                       transportError: nil)
        XCTAssertTrue(outcome.succeeded)
        XCTAssertEqual(outcome.statusCode, 204)
        XCTAssertNil(outcome.errorCode)
    }

    func test_classifyResponseSync_401_unauthorized() {
        dispatcher = makeDispatcher()
        let outcome = dispatcher.classifyResponseSync(itemId: "x",
                                                       statusCode: 401,
                                                       transportError: nil)
        XCTAssertFalse(outcome.succeeded)
        XCTAssertEqual(outcome.errorCode, "UNAUTHORIZED")
    }

    func test_classifyResponseSync_5xx_serverError() {
        dispatcher = makeDispatcher()
        let outcome = dispatcher.classifyResponseSync(itemId: "x",
                                                       statusCode: 503,
                                                       transportError: nil)
        XCTAssertFalse(outcome.succeeded)
        XCTAssertEqual(outcome.errorCode, "SERVER_ERROR")
        XCTAssertEqual(outcome.statusCode, 503)
    }

    func test_classifyResponseSync_transportError_timeout() {
        dispatcher = makeDispatcher()
        let outcome = dispatcher.classifyResponseSync(itemId: "x",
                                                       statusCode: nil,
                                                       transportError: URLError(.timedOut))
        XCTAssertFalse(outcome.succeeded)
        XCTAssertEqual(outcome.errorCode, "TIMEOUT")
    }
}
