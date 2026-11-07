import CoreData
import NitroModules
import XCTest
@testable import SyncProvider

/// Items that fail for good leave the queue for the dead-letter store, in one
/// transaction, with their request and their failure. Named after the parity
/// scenarios the Android suite runs.
final class DeadLetterStoreTests: XCTestCase {
    private var fixture: EngineFixture!
    private var emitter: MockSyncEventEmitter!

    override func setUp() {
        super.setUp()
        fixture = EngineFixture()
        emitter = MockSyncEventEmitter()
    }

    override func tearDown() {
        URLProtocolStub.setHandler(nil)
        fixture.tearDown()
        fixture = nil
        super.tearDown()
    }

    private func engine(maxAttempts: Int = 5, defaultHeaders: [String: String] = [:]) async -> SyncEngine {
        let engine = await fixture.makeEngine(emitter: emitter)
        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: maxAttempts, baseDelayMs: 50),
                                       defaultHeaders: defaultHeaders))
        return engine
    }

    private func enqueue(in engine: SyncEngine) async throws -> String {
        try await engine.storage.enqueue(input: SyncQueueStorage.SyncItemInputData(
            method: "PUT",
            url: "https://api.test/orders/7",
            headers: ["X-Secret": "header-secret"],
            body: "{\"card\":\"body-secret\"}",
            contentType: "application/json",
            priority: "HIGH",
            metadata: ["origin": "test"]
        ))
    }

    private func phrase(_ status: Int) -> String {
        HTTPURLResponse.localizedString(forStatusCode: status).capitalized
    }

    // MARK: - Terminal failure

    func test_dlq_terminalFailure_movesRow_andEmitsFailedThenDeadLettered() async throws {
        let engine = await engine()
        let id = try await enqueue(in: engine)
        let queuedAtValue = try await engine.storage.getAll().first
        let queuedAt = try XCTUnwrap(queuedAtValue)
        URLProtocolStub.setHandler(stubResponse(400))
        emitter.clear()

        _ = try await engine.dispatcher.flush()

        let queued = try await engine.storage.isQueued(id)
        XCTAssertFalse(queued, "Never a copy left in the queue")
        let letterValue = try await engine.storage.deadLetter(id)
        let letter = try XCTUnwrap(letterValue)
        XCTAssertEqual(letter.method, "PUT")
        XCTAssertEqual(letter.url, "https://api.test/orders/7")
        XCTAssertEqual(letter.headers, ["X-Secret": "header-secret"])
        XCTAssertEqual(letter.body, "{\"card\":\"body-secret\"}")
        XCTAssertEqual(letter.contentType, "application/json")
        XCTAssertEqual(letter.priority, "HIGH")
        XCTAssertEqual(letter.metadata, ["origin": "test"])
        XCTAssertEqual(letter.createdAt, queuedAt.createdAt, "The original creation time")
        XCTAssertEqual(letter.attempts, 1)
        XCTAssertEqual(letter.lastAttemptAt, fixture.clock.nowMs())
        XCTAssertEqual(letter.deadLetteredAt, fixture.clock.nowMs())
        XCTAssertEqual(letter.lastErrorCode, "SERVER_ERROR")
        XCTAssertEqual(letter.lastErrorMessage, "HTTP 400 \(phrase(400))")

        let itemEvents = emitter.emittedEvents.filter { $0.itemId == id }
        let failedIndex = try XCTUnwrap(itemEvents.firstIndex { $0.type == "ITEM_FAILED" })
        XCTAssertEqual(itemEvents[failedIndex].errorCode, "SERVER_ERROR", "The real code, as before")
        let next = itemEvents[itemEvents.index(after: failedIndex)]
        XCTAssertEqual(next.type, "ITEM_DEAD_LETTERED", "Right after ITEM_FAILED")
        XCTAssertEqual(next.errorCode, "DEAD_LETTERED")
        XCTAssertEqual(next.metadata, ["lastErrorCode": "SERVER_ERROR"])
    }

    func test_dlq_everyTerminalFailure_isDeadLettered() async throws {
        let engine = await engine(maxAttempts: 1, defaultHeaders: ["Authorization": "Bearer live"])
        let cases: [(status: Int, code: String)] = [(503, "MAX_ATTEMPTS_EXCEEDED"),
                                                     (404, "SERVER_ERROR"),
                                                     (401, "UNAUTHORIZED"),
                                                     (403, "UNAUTHORIZED")]
        for (status, code) in cases {
            let id = try await enqueue(in: engine)
            URLProtocolStub.setHandler(stubResponse(status))

            _ = try await engine.dispatcher.flush()

            let letter = try await engine.storage.deadLetter(id)
            XCTAssertEqual(letter?.lastErrorCode, code, "HTTP \(status)")
            XCTAssertEqual(letter?.lastErrorMessage, "HTTP \(status) \(phrase(status))", "HTTP \(status)")
            let queued = try await engine.storage.isQueued(id)
            XCTAssertFalse(queued, "HTTP \(status)")
        }
    }

    /// The failure's own words, bounded, and nothing of the request: not a
    /// header, not the body.
    func test_dlq_lastErrorMessage_capturedBoundedAndRedacted() async throws {
        let engine = await engine(maxAttempts: 1)
        let id = try await enqueue(in: engine)
        let long = String(repeating: "connection refused by the far end; ", count: 200)
        URLProtocolStub.setHandler { _ in
            throw URLError(.cannotConnectToHost, userInfo: [NSLocalizedDescriptionKey: long])
        }

        _ = try await engine.dispatcher.flush()

        let letterValue = try await engine.storage.deadLetter(id)

        let letter = try XCTUnwrap(letterValue)
        let message = try XCTUnwrap(letter.lastErrorMessage)
        XCTAssertEqual(letter.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
        XCTAssertEqual(message.count, kDeadLetterMessageMaxLength)
        XCTAssertTrue(message.hasPrefix("Network error: connection refused"))
        XCTAssertFalse(message.contains("header-secret"))
        XCTAssertFalse(message.contains("body-secret"))
    }

    func test_dlq_httpFailure_keepsTheStatusLine_notTheResponseBody() async throws {
        let engine = await engine(maxAttempts: 1)
        let id = try await enqueue(in: engine)
        URLProtocolStub.setHandler { request in
            (HTTPURLResponse(url: request.url!, statusCode: 500, httpVersion: "HTTP/1.1", headerFields: nil)!,
             Data("stack trace with body-secret".utf8))
        }

        _ = try await engine.dispatcher.flush()

        let letter = try await engine.storage.deadLetter(id)
        XCTAssertEqual(letter?.lastErrorMessage, "HTTP 500 \(phrase(500))")
    }

    /// A replayed item that fails again replaces its earlier dead letter.
    func test_dlq_sameIdDeadLetteredAgain_replacesTheOlderOne() async throws {
        let stack = InMemoryCoreDataStack.make()
        let clock = ManualClock()
        let dao = SyncItemDao(stack: stack, clock: clock)
        let item = StoredSyncItem(id: "01REPLAYED", method: "POST", url: "https://api.test/x", headers: nil,
                                  body: nil, contentType: nil, priority: "NORMAL", createdAt: 1, attempts: 1,
                                  lastAttemptAt: 5, nextRetryAt: 0, lastErrorCode: nil, status: "PENDING",
                                  metadata: nil)
        try await dao.insert(item)
        try await dao.moveToDeadLetters(id: item.id, errorCode: "SERVER_ERROR", errorMessage: "HTTP 500")
        clock.advance(ms: 1_000)
        try await dao.insert(item)

        try await dao.moveToDeadLetters(id: item.id, errorCode: "TIMEOUT", errorMessage: "Request timed out")

        let letters = try await dao.fetchDeadLetters()
        XCTAssertEqual(letters.map(\.id), ["01REPLAYED"])
        XCTAssertEqual(letters.first?.lastErrorCode, "TIMEOUT")
        XCTAssertEqual(letters.first?.deadLetteredAt, clock.nowMs())
    }

    func test_dlq_damagedCode_listsAsMaxAttemptsExceeded() async throws {
        let stack = InMemoryCoreDataStack.make()
        try await stack.performBackgroundTask { context in
            let letter = SyncDeadLetterEntity(context: context)
            letter.id = "01DAMAGED"
            letter.method = "POST"
            letter.url = "https://api.test/x"
            letter.priority = "NORMAL"
            letter.lastErrorCode = ""
        }

        let letters = try await SyncItemDao(stack: stack).fetchDeadLetters()

        XCTAssertEqual(letters.first?.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
    }

    func test_dlq_itemRemovedWhileInFlight_isNotDeadLettered() async throws {
        let engine = await engine(maxAttempts: 1)
        let id = try await enqueue(in: engine)
        URLProtocolStub.setHandler { request in
            // The row goes while its request is out.
            let removed = DispatchSemaphore(value: 0)
            Task {
                _ = try? await engine.storage.remove(id: id)
                removed.signal()
            }
            removed.wait()
            return (HTTPURLResponse(url: request.url!, statusCode: 404, httpVersion: nil, headerFields: nil)!, Data())
        }
        emitter.clear()

        _ = try await engine.dispatcher.flush()

        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty)
        XCTAssertEqual(emitter.events(ofType: "ITEM_FAILED").count, 1)
        XCTAssertTrue(emitter.events(ofType: "ITEM_DEAD_LETTERED").isEmpty, "Nothing was moved")
    }

    // MARK: - Purges reach dead letters

    func test_dlq_clearQueue_clearsDeadLetters() async throws {
        let engine = await engine(maxAttempts: 1)
        _ = try await enqueue(in: engine)
        URLProtocolStub.setHandler(stubResponse(404))
        _ = try await engine.dispatcher.flush()
        _ = try await enqueue(in: engine)
        let hybrid = HybridSyncProvider(engine: engine)
        emitter.clear()

        let outcome = await settle(try hybrid.clearQueue())

        XCTAssertNotNil(try outcome?.get())
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty)
        let queue = try await engine.storage.getAll()
        XCTAssertTrue(queue.isEmpty)
        XCTAssertEqual(emitter.events(ofType: "QUEUE_CLEARED").count, 1)
    }

    func test_dlq_removeItem_fallsThroughToDeadLetters() async throws {
        let engine = await engine(maxAttempts: 1)
        let dead = try await enqueue(in: engine)
        URLProtocolStub.setHandler(stubResponse(404))
        _ = try await engine.dispatcher.flush()
        let queued = try await enqueue(in: engine)
        let hybrid = HybridSyncProvider(engine: engine)
        emitter.clear()

        let removedDead = try await settle(try hybrid.removeItem(id: dead))?.get()
        let removedAgain = try await settle(try hybrid.removeItem(id: dead))?.get()
        let removedQueued = try await settle(try hybrid.removeItem(id: queued))?.get()

        XCTAssertEqual(removedDead, true, "Not queued: the dead letter goes")
        XCTAssertEqual(removedAgain, false)
        XCTAssertEqual(removedQueued, true)
        XCTAssertEqual(emitter.events(ofType: "ITEM_REMOVED").map(\.itemId), [dead, queued])
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty)
    }
}
