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

    // MARK: - A budget spent before the request

    /// `maxAttempts` lowered below the attempts an item already made: the next
    /// cycle dead-letters it without sending it again, with the attempts it
    /// made and its last attempt's time.
    func test_dlq_budgetSpentBeforeTheRequest_isDeadLetteredWithoutSending() async throws {
        let engine = await engine(maxAttempts: 5)
        let id = try await enqueue(in: engine)
        URLProtocolStub.setHandler(stubResponse(503))
        _ = try await engine.dispatcher.flush()
        let firstAttemptAt = fixture.clock.nowMs()
        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: 1, baseDelayMs: 50)))
        fixture.clock.advance(ms: 1_000)
        URLProtocolStub.setHandler(stubResponse(200))
        emitter.clear()

        let result = try await engine.dispatcher.flush()

        XCTAssertTrue(URLProtocolStub.capturedRequests.isEmpty, "No request once the budget is spent")
        let queued = try await engine.storage.isQueued(id)
        XCTAssertFalse(queued)
        let letterValue = try await engine.storage.deadLetter(id)
        let letter = try XCTUnwrap(letterValue)
        XCTAssertEqual(letter.attempts, 1, "The attempts made, none added")
        XCTAssertEqual(letter.lastAttemptAt, firstAttemptAt)
        XCTAssertEqual(letter.deadLetteredAt, fixture.clock.nowMs())
        XCTAssertEqual(letter.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
        XCTAssertEqual(letter.lastErrorMessage, "Retry budget exhausted")
        XCTAssertEqual(emitter.emittedEvents.filter { $0.itemId == id }.map(\.type),
                       ["ITEM_FAILED", "ITEM_DEAD_LETTERED"])
        let failed = try XCTUnwrap(emitter.events(ofType: "ITEM_FAILED").first)
        XCTAssertEqual(failed.errorCode, "MAX_ATTEMPTS_EXCEEDED")
        XCTAssertEqual(failed.attempt, 1)
        XCTAssertNil(failed.statusCode)
        XCTAssertEqual(emitter.events(ofType: "ITEM_DEAD_LETTERED").first?.metadata,
                       ["lastErrorCode": "MAX_ATTEMPTS_EXCEEDED"])
        XCTAssertEqual(emitter.events(ofType: "SYNC_PROGRESS").last?.progress, 1)
        XCTAssertEqual(result.itemsFailed, 1)
        XCTAssertEqual(result.errorMessage, "Retry budget exhausted")
    }

    /// A spent budget sends no request, so an item purged since the batch was
    /// read has nothing to end: no event, no dead letter, not attempted, not
    /// failed. The cycle's progress still runs to the end.
    func test_dlq_budgetSpentItemPurgedBeforeItsTurn_staysSilent() async throws {
        let engine = await engine(maxAttempts: 5)
        let spent = try await engine.storage.enqueue(input: .sample(url: "https://api.test/spent", priority: "LOW"))
        URLProtocolStub.setHandler(stubResponse(503))
        _ = try await engine.dispatcher.flush()
        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: 1, baseDelayMs: 50)))
        fixture.clock.advance(ms: 1_000)
        let fresh = try await engine.storage.enqueue(input: .sample(url: "https://api.test/fresh", priority: "HIGH"))
        URLProtocolStub.setHandler { request in
            // A logout-style purge lands while the fresh item's request is
            // out, before the spent item's turn.
            let cleared = DispatchSemaphore(value: 0)
            Task {
                try? await engine.storage.clear()
                cleared.signal()
            }
            cleared.wait()
            return (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, Data())
        }
        emitter.clear()

        let result = try await engine.dispatcher.flush()

        XCTAssertEqual(URLProtocolStub.capturedRequests.map { $0.url?.absoluteString }, ["https://api.test/fresh"])
        XCTAssertEqual(emitter.events(ofType: "ITEM_SUCCEEDED").map(\.itemId), [fresh])
        XCTAssertTrue(emitter.emittedEvents.filter { $0.itemId == spent }.isEmpty,
                      "\(emitter.emittedEvents.filter { $0.itemId == spent }.map(\.type))")
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty)
        XCTAssertEqual(emitter.events(ofType: "SYNC_PROGRESS").compactMap { $0.metadata?["index"] }, ["1", "2"])
        XCTAssertEqual(result.itemsAttempted, 1, "The purged item was not attempted")
        XCTAssertEqual(result.itemsSucceeded, 1)
        XCTAssertEqual(result.itemsFailed, 0)
        XCTAssertNil(result.errorMessage)
    }

    /// A spent budget with no attempt time on record, the shape of a row
    /// migrated from an early store, is dated by the cycle that ends it.
    func test_dlq_budgetSpentWithoutAnAttemptTime_isDatedByTheCheck() async throws {
        let engine = await engine(maxAttempts: 2)
        let dao = SyncItemDao(stack: fixture.stack, clock: fixture.clock)
        try await dao.insert(StoredSyncItem(id: "01SPENT", method: "POST", url: "https://api.test/x", headers: nil,
                                            body: "{}", contentType: "application/json", priority: "NORMAL",
                                            createdAt: 1, attempts: 3, lastAttemptAt: nil, nextRetryAt: 0,
                                            lastErrorCode: nil, status: "PENDING", metadata: nil))
        fixture.clock.advance(ms: 5_000)
        URLProtocolStub.setHandler(stubResponse(200))

        _ = try await engine.dispatcher.flush()

        XCTAssertTrue(URLProtocolStub.capturedRequests.isEmpty)
        let letterValue = try await engine.storage.deadLetter("01SPENT")
        let letter = try XCTUnwrap(letterValue)
        XCTAssertEqual(letter.attempts, 3)
        XCTAssertEqual(letter.lastAttemptAt, fixture.clock.nowMs())
        XCTAssertEqual(letter.deadLetteredAt, fixture.clock.nowMs())
    }

    /// A `maxAttempts` below 1 still sends a fresh item once, as on Android.
    func test_dlq_maxAttemptsBelowOne_stillSendsAFreshItemOnce() async throws {
        let engine = await engine(maxAttempts: 0)
        let id = try await enqueue(in: engine)
        URLProtocolStub.setHandler(stubResponse(503))

        _ = try await engine.dispatcher.flush()

        XCTAssertEqual(URLProtocolStub.capturedRequests.count, 1)
        let letter = try await engine.storage.deadLetter(id)
        XCTAssertEqual(letter?.attempts, 1)
        XCTAssertEqual(letter?.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
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

    func test_dlq_queueClearedWhileInFlight_isNotDeadLettered() async throws {
        let engine = await engine(maxAttempts: 1)
        let id = try await enqueue(in: engine)
        URLProtocolStub.setHandler { request in
            // A logout-style purge lands while the request is out.
            let cleared = DispatchSemaphore(value: 0)
            Task {
                try? await engine.storage.clear()
                cleared.signal()
            }
            cleared.wait()
            return (HTTPURLResponse(url: request.url!, statusCode: 404, httpVersion: nil, headerFields: nil)!, Data())
        }
        emitter.clear()

        _ = try await engine.dispatcher.flush()

        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty, "A purged item must not come back as a dead letter")
        let queue = try await engine.storage.getAll()
        XCTAssertTrue(queue.isEmpty)
        XCTAssertEqual(emitter.events(ofType: "ITEM_FAILED").map(\.itemId), [id], "The attempt was made and failed")
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

    // MARK: - Purges racing a copy between the stores

    // A move or a replay reads its row, then saves the copy. Every store
    // operation has its own context, and the merge policy accepts a save over
    // a row deleted meanwhile, so a purge landing between the read and the
    // save would be undone: the copy, headers and body included, would be
    // written for a row that is gone. Each test holds the copy at its save and
    // gives the purge time to land in that gap.

    func test_dlq_queueClearedDuringTheMove_leavesNoDeadLetter() async throws {
        let engine = await engine()
        let id = try await enqueue(in: engine)
        let hold = InsertingSaveHold(stack: fixture.stack, entity: "SyncDeadLetterEntity", id: id)
        defer { hold.release() }

        let move = Task {
            try await engine.storage.moveToDeadLetters(id: id, errorCode: "SERVER_ERROR", errorMessage: "HTTP 404 Not Found")
        }
        await fulfillment(of: [hold.reached], timeout: 5)
        let purge = Task { try await engine.storage.clear() }
        try await letPurgeLand()
        hold.release()
        _ = try await move.value
        try await purge.value

        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty, "The purge must not be undone by the move")
        let queued = try await engine.storage.isQueued(id)
        XCTAssertFalse(queued)
    }

    func test_dlq_itemRemovedDuringTheMove_leavesNoDeadLetter() async throws {
        let engine = await engine()
        let id = try await enqueue(in: engine)
        let hold = InsertingSaveHold(stack: fixture.stack, entity: "SyncDeadLetterEntity", id: id)
        defer { hold.release() }

        let move = Task {
            try await engine.storage.moveToDeadLetters(id: id, errorCode: "SERVER_ERROR", errorMessage: "HTTP 404 Not Found")
        }
        await fulfillment(of: [hold.reached], timeout: 5)
        let remove = Task { try await engine.storage.remove(id: id) }
        try await letPurgeLand()
        hold.release()
        _ = try await move.value
        let removed = try await remove.value

        XCTAssertTrue(removed, "The row went, from one store or the other")
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty, "The removal must not be undone by the move")
        let queued = try await engine.storage.isQueued(id)
        XCTAssertFalse(queued)
    }

    func test_dlq_queueClearedDuringAReplay_leavesNothingQueued() async throws {
        let engine = await engine()
        let id = try await deadLettered(in: engine)
        let hold = InsertingSaveHold(stack: fixture.stack, entity: "SyncItemEntity", id: id)
        defer { hold.release() }

        let replay = Task { try await engine.storage.replayDeadLetter(id: id) }
        await fulfillment(of: [hold.reached], timeout: 5)
        let purge = Task { try await engine.storage.clear() }
        try await letPurgeLand()
        hold.release()
        _ = try await replay.value
        try await purge.value

        let queued = try await engine.storage.isQueued(id)
        XCTAssertFalse(queued, "The purge must not be undone by the replay")
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty)
    }

    func test_dlq_discardDuringAReplay_isNotUndoneByIt() async throws {
        let engine = await engine()
        let id = try await deadLettered(in: engine)
        let hold = InsertingSaveHold(stack: fixture.stack, entity: "SyncItemEntity", id: id)
        defer { hold.release() }

        let replay = Task { try await engine.storage.replayDeadLetter(id: id) }
        await fulfillment(of: [hold.reached], timeout: 5)
        let discard = Task { try await engine.storage.discardDeadLetter(id: id) }
        try await letPurgeLand()
        hold.release()
        let replayed = try await replay.value
        let discarded = try await discard.value

        XCTAssertTrue(replayed)
        XCTAssertFalse(discarded, "Replayed first: nothing was left to discard")
        let queued = try await engine.storage.isQueued(id)
        XCTAssertTrue(queued)
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty)
    }

    func test_dlq_overlappingReplays_requeueOnce() async throws {
        let engine = await engine()
        let id = try await deadLettered(in: engine)
        let hold = InsertingSaveHold(stack: fixture.stack, entity: "SyncItemEntity", id: id)
        defer { hold.release() }

        let first = Task { try await engine.storage.replayDeadLetter(id: id) }
        await fulfillment(of: [hold.reached], timeout: 5)
        let second = Task { try await engine.storage.replayDeadLetter(id: id) }
        try await letPurgeLand()
        hold.release()
        let firstReplayed = try await first.value
        let secondReplayed = try await second.value

        XCTAssertEqual([firstReplayed, secondReplayed], [true, false], "Only one replay may report the item requeued")
        let queue = try await engine.storage.getAll()
        XCTAssertEqual(queue.map(\.id), [id])
    }

    /// An item moved to the dead letters, with nothing left in the queue.
    private func deadLettered(in engine: SyncEngine) async throws -> String {
        let id = try await enqueue(in: engine)
        let moved = try await engine.storage.moveToDeadLetters(id: id,
                                                               errorCode: "SERVER_ERROR",
                                                               errorMessage: "HTTP 404 Not Found")
        XCTAssertTrue(moved)
        return id
    }

    /// Long enough for a purge that nothing makes wait to commit.
    private func letPurgeLand() async throws {
        try await Task.sleep(nanoseconds: 300_000_000)
    }
}

/// Holds the first save, on `stack`, of a transaction that inserts a row of
/// `entity` with `id`: after it has read and changed what it needs, before the
/// store sees any of it. Every store operation has its own context, so other
/// operations carry on meanwhile unless something makes them wait.
private final class InsertingSaveHold: @unchecked Sendable {
    /// Fulfilled once the transaction is blocked on its save.
    let reached = XCTestExpectation(description: "the transaction reached its save")

    private let released = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var isSpent = false
    private var observer: NSObjectProtocol?

    init(stack: CoreDataStack, entity: String, id: String) {
        let coordinator = stack.persistentContainer.persistentStoreCoordinator
        observer = NotificationCenter.default.addObserver(forName: .NSManagedObjectContextWillSave,
                                                          object: nil,
                                                          queue: nil) { [weak self] notification in
            guard let context = notification.object as? NSManagedObjectContext,
                  context.persistentStoreCoordinator === coordinator,
                  context.insertedObjects.contains(where: {
                      $0.entity.name == entity && $0.value(forKey: "id") as? String == id
                  })
            else { return }
            self?.holdOnce()
        }
    }

    deinit {
        if let observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    /// Lets the held save through. Safe to call more than once.
    func release() {
        released.signal()
    }

    private func holdOnce() {
        let isFirst = lock.withLock { () -> Bool in
            defer { isSpent = true }
            return !isSpent
        }
        guard isFirst else { return }
        reached.fulfill()
        released.wait()
    }
}
