import XCTest
import NitroModules
@testable import SyncProvider

/// Listing, replaying and discarding dead letters through the bridge, as JS
/// calls them. Named after the parity scenarios the Android suite runs.
///
/// Every promise is observed the way the generated bridge observes it, with a
/// listener attached right after the call returns, and must settle within a
/// deadline: one that never calls its listener fails the test instead of
/// hanging the suite.
final class DeadLetterBridgeTests: XCTestCase {
    private var fixture: EngineFixture!
    private var emitter: MockSyncEventEmitter!
    private var engine: SyncEngine!
    private var hybrid: HybridSyncProvider!

    private let unknownId = "01HZZZZZZZZZZZZZZZZZZZZZZZ"

    override func setUp() async throws {
        try await super.setUp()
        fixture = EngineFixture()
        emitter = MockSyncEventEmitter()
        engine = await fixture.makeEngine(emitter: emitter)
        hybrid = HybridSyncProvider(engine: engine)
        await configure(maxQueueSize: 100)
    }

    override func tearDown() async throws {
        URLProtocolStub.setHandler(nil)
        hybrid = nil
        engine = nil
        fixture.tearDown()
        fixture = nil
        emitter = nil
        try await super.tearDown()
    }

    // MARK: - List

    func test_dlq_list_isEmptyWithoutDeadLetters() async throws {
        _ = try await enqueue()

        let listed = try await settle(hybrid.listDeadLetter())?.get()

        XCTAssertEqual(listed?.count, 0, "A queued item is not a dead letter")
    }

    func test_dlq_list_orderedByDeadLetteredAtThenId() async throws {
        let dao = SyncItemDao(stack: fixture.stack, clock: fixture.clock)
        for id in ["01C", "01B", "01A"] {
            try await dao.insert(Self.row(id: id))
        }
        try await dao.moveToDeadLetters(id: "01C", errorCode: "SERVER_ERROR", errorMessage: "HTTP 500")
        fixture.clock.advance(ms: 10)
        try await dao.moveToDeadLetters(id: "01B", errorCode: "SERVER_ERROR", errorMessage: "HTTP 500")
        try await dao.moveToDeadLetters(id: "01A", errorCode: "SERVER_ERROR", errorMessage: "HTTP 500")

        let listed = try await settle(hybrid.listDeadLetter())?.get()

        XCTAssertEqual(listed?.map(\.id), ["01C", "01A", "01B"], "Oldest first, then by id")
    }

    func test_dlq_list_translatesEveryField() async throws {
        let id = try await enqueue()
        let queuedValue = try await engine.storage.getAll().first
        let queued = try XCTUnwrap(queuedValue)
        URLProtocolStub.setHandler(stubResponse(404))
        _ = try await engine.dispatcher.flush()

        let listed = try await settle(hybrid.listDeadLetter())?.get()

        XCTAssertEqual(listed?.count, 1)
        let letter = try XCTUnwrap(listed?.first)
        XCTAssertEqual(letter.id, id)
        XCTAssertEqual(letter.method, .put)
        XCTAssertEqual(letter.url, "https://api.test/orders/7")
        XCTAssertEqual(letter.headers, ["X-Request": "7"])
        XCTAssertEqual(letter.body, "{\"total\":7}")
        XCTAssertEqual(letter.contentType, "application/json")
        XCTAssertEqual(letter.priority, .high)
        XCTAssertEqual(letter.createdAt, Double(queued.createdAt))
        XCTAssertEqual(letter.metadata, ["origin": "test"])
        XCTAssertEqual(letter.attempts, 1)
        XCTAssertEqual(letter.lastAttemptAt, Double(fixture.clock.nowMs()))
        XCTAssertEqual(letter.lastErrorCode, .serverError)
        XCTAssertEqual(letter.lastErrorMessage,
                       "HTTP 404 \(HTTPURLResponse.localizedString(forStatusCode: 404).capitalized)")
        XCTAssertEqual(letter.deadLetteredAt, Double(fixture.clock.nowMs()))
    }

    /// An empty code, which only a damaged row can have, and a code this build
    /// does not know, from a newer one say, both read as the code of a dead
    /// letter that has none.
    func test_dlq_list_emptyOrUnknownCode_readsAsMaxAttemptsExceeded() async throws {
        try await fixture.stack.performBackgroundTask { context in
            for (id, code) in [("01EMPTY", ""), ("01FUTURE", "A_CODE_FROM_A_NEWER_BUILD")] {
                let letter = SyncDeadLetterEntity(context: context)
                letter.id = id
                letter.method = "POST"
                letter.url = "https://api.test/x"
                letter.priority = "NORMAL"
                letter.lastErrorCode = code
            }
        }

        let listed = try await settle(hybrid.listDeadLetter())?.get()

        XCTAssertEqual(listed?.map(\.id), ["01EMPTY", "01FUTURE"])
        XCTAssertEqual(listed?.map(\.lastErrorCode), [.maxAttemptsExceeded, .maxAttemptsExceeded])
    }

    // MARK: - Replay

    /// Back in the queue as a fresh item, under its id and its creation time,
    /// with `ITEM_ENQUEUED` and nothing sent until the next flush, which
    /// sends it: failed for good, dead-lettered, replayed, delivered.
    func test_dlq_replay_requeuesFresh() async throws {
        let id = try await enqueue()
        let createdValue = try await engine.storage.getAll().first?.createdAt
        let createdAt = try XCTUnwrap(createdValue)
        URLProtocolStub.setHandler(stubResponse(404))
        _ = try await engine.dispatcher.flush()
        fixture.clock.advance(ms: 1_000)
        URLProtocolStub.setHandler(stubResponse(200))
        emitter.clear()

        let outcome = try await settle(hybrid.replayDeadLetter(id: id))

        XCTAssertNotNil(try outcome?.get())
        let rows = try await engine.storage.getAll()
        XCTAssertEqual(rows.count, 1)
        let row = try XCTUnwrap(rows.first)
        XCTAssertEqual(row.id, id, "The same id")
        XCTAssertEqual(row.createdAt, createdAt, "The original creation time")
        XCTAssertEqual(row.status, "PENDING")
        XCTAssertEqual(row.attempts, 0)
        XCTAssertEqual(row.nextRetryAt, 0)
        XCTAssertNil(row.lastAttemptAt)
        XCTAssertNil(row.lastErrorCode)
        XCTAssertEqual(row.method, "PUT")
        XCTAssertEqual(row.url, "https://api.test/orders/7")
        XCTAssertEqual(row.headers, ["X-Request": "7"])
        XCTAssertEqual(row.body, "{\"total\":7}")
        XCTAssertEqual(row.contentType, "application/json")
        XCTAssertEqual(row.priority, "HIGH")
        XCTAssertEqual(row.metadata, ["origin": "test"])
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty, "The dead letter goes")
        XCTAssertEqual(emitter.emittedEvents.filter { $0.itemId == id }.map(\.type), ["ITEM_ENQUEUED"])
        XCTAssertTrue(emitter.events(ofType: "SYNC_STARTED").isEmpty, "No flush by itself")
        XCTAssertTrue(URLProtocolStub.capturedRequests.isEmpty)

        _ = try await engine.dispatcher.flush()

        XCTAssertEqual(URLProtocolStub.capturedRequests.map { $0.url?.absoluteString },
                       ["https://api.test/orders/7"], "The next flush sends it")
        XCTAssertEqual(emitter.events(ofType: "ITEM_SUCCEEDED").map(\.itemId), [id])
        let queued = try await engine.storage.isQueued(id)
        XCTAssertFalse(queued)
        let after = try await engine.storage.getDeadLetters()
        XCTAssertTrue(after.isEmpty)
    }

    /// A replayed item has a whole budget again; failing for good once more
    /// makes it a dead letter again, the only one with its id.
    func test_dlq_replayedItemFailingAgain_isDeadLetteredAgain() async throws {
        let id = try await deadLetter(status: 404)
        _ = try await settle(hybrid.replayDeadLetter(id: id))
        fixture.clock.advance(ms: 1_000)
        URLProtocolStub.setHandler(stubResponse(500))
        emitter.clear()

        _ = try await engine.dispatcher.flush()

        XCTAssertEqual(URLProtocolStub.capturedRequests.count, 1, "Sent again")
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertEqual(letters.map(\.id), [id])
        XCTAssertEqual(letters.first?.attempts, 1, "Counted from a fresh budget")
        XCTAssertEqual(letters.first?.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
        XCTAssertEqual(letters.first?.deadLetteredAt, fixture.clock.nowMs())
        XCTAssertEqual(emitter.events(ofType: "ITEM_DEAD_LETTERED").map(\.itemId), [id])
    }

    func test_dlq_replay_queueFull_rejectsAndKeepsRow() async throws {
        await configure(maxQueueSize: 1)
        let dead = try await deadLetter()
        let queued = try await enqueue()
        emitter.clear()

        let outcome = try await settle(hybrid.replayDeadLetter(id: dead))

        guard case let .failure(error)? = outcome else {
            return XCTFail("Expected QUEUE_FULL, got \(String(describing: outcome))")
        }
        XCTAssertEqual((error as? SyncProviderError)?.codeName, "QUEUE_FULL")
        let letter = try await engine.storage.deadLetter(dead)
        XCTAssertNotNil(letter, "The dead letter stays")
        let rows = try await engine.storage.getAll()
        XCTAssertEqual(rows.map(\.id), [queued])
        XCTAssertTrue(emitter.events(ofType: "ITEM_ENQUEUED").isEmpty)
    }

    func test_dlq_replayTwice_secondChangesNothing() async throws {
        let id = try await deadLetter()
        emitter.clear()

        _ = try await settle(hybrid.replayDeadLetter(id: id))
        let again = try await settle(hybrid.replayDeadLetter(id: id))

        XCTAssertNotNil(try again?.get(), "No dead letter left: resolves")
        let rows = try await engine.storage.getAll()
        XCTAssertEqual(rows.map(\.id), [id])
        XCTAssertEqual(emitter.events(ofType: "ITEM_ENQUEUED").map(\.itemId), [id], "One replay, one event")
    }

    /// A queue row with the dead letter's id, which only a racing replay or a
    /// damaged store can leave, is not overwritten, and the dead letter stays.
    func test_dlq_replay_overAQueuedId_changesNothing() async throws {
        let dao = SyncItemDao(stack: fixture.stack, clock: fixture.clock)
        try await dao.insert(Self.row(id: "01TWICE"))
        try await dao.moveToDeadLetters(id: "01TWICE", errorCode: "SERVER_ERROR", errorMessage: "HTTP 404")
        try await dao.insert(Self.row(id: "01TWICE", attempts: 2, status: "IN_FLIGHT"))
        let before = try await engine.storage.getAll()
        emitter.clear()

        let outcome = try await settle(hybrid.replayDeadLetter(id: "01TWICE"))

        XCTAssertNotNil(try outcome?.get())
        let after = try await engine.storage.getAll()
        XCTAssertEqual(after, before, "The queued row is not overwritten")
        let letter = try await engine.storage.deadLetter("01TWICE")
        XCTAssertNotNil(letter, "The dead letter stays")
        XCTAssertTrue(emitter.events(ofType: "ITEM_ENQUEUED").isEmpty)
    }

    // MARK: - Discard

    func test_dlq_discard_emitsItemRemoved() async throws {
        let id = try await deadLetter()
        emitter.clear()

        let first = try await settle(hybrid.discardDeadLetter(id: id))
        let again = try await settle(hybrid.discardDeadLetter(id: id))

        XCTAssertNotNil(try first?.get())
        XCTAssertNotNil(try again?.get(), "Already gone: resolves")
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertTrue(letters.isEmpty)
        XCTAssertEqual(emitter.events(ofType: "ITEM_REMOVED").map(\.itemId), [id], "Once, for the row deleted")
        let replayed = try await settle(hybrid.replayDeadLetter(id: id))
        XCTAssertNotNil(try replayed?.get())
        let queue = try await engine.storage.getAll()
        XCTAssertTrue(queue.isEmpty, "A discarded dead letter cannot come back")
    }

    // MARK: - Unknown ids

    func test_dlq_replay_and_discard_unknownId_resolve() async throws {
        let dead = try await deadLetter()
        let queued = try await enqueue()
        let before = try await engine.storage.getAll()
        emitter.clear()

        let replayed = try await settle(hybrid.replayDeadLetter(id: unknownId))
        let discarded = try await settle(hybrid.discardDeadLetter(id: unknownId))
        // A queued item is no dead letter: neither call touches it.
        let replayedQueued = try await settle(hybrid.replayDeadLetter(id: queued))
        let discardedQueued = try await settle(hybrid.discardDeadLetter(id: queued))

        for outcome in [replayed, discarded, replayedQueued, discardedQueued] {
            XCTAssertNotNil(try outcome?.get())
        }
        let after = try await engine.storage.getAll()
        XCTAssertEqual(after, before)
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertEqual(letters.map(\.id), [dead])
        XCTAssertTrue(emitter.emittedEvents.isEmpty)
    }

    // MARK: - Capacity

    func test_dlq_deadLetters_doNotCountTowardCapacity() async throws {
        await configure(maxQueueSize: 2)
        _ = try await deadLetter()
        _ = try await deadLetter()

        _ = try await enqueue()
        _ = try await enqueue()

        let size = try await settle(hybrid.getQueueSize())?.get()
        XCTAssertEqual(size, 2)
        let letters = try await engine.storage.getDeadLetters()
        XCTAssertEqual(letters.count, 2)
        do {
            _ = try await enqueue()
            XCTFail("The cap counts the queue alone, and the queue is full")
        } catch {
            XCTAssertEqual((error as? SyncProviderError)?.codeName, "QUEUE_FULL")
        }
    }

    // MARK: - Helpers

    /// One attempt per item, so a single failure dead-letters it.
    private func configure(maxQueueSize: Double) async {
        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: 1, baseDelayMs: 50),
                                       maxQueueSize: maxQueueSize))
    }

    private func enqueue() async throws -> String {
        try await engine.storage.enqueue(input: SyncQueueStorage.SyncItemInputData(
            method: "PUT",
            url: "https://api.test/orders/7",
            headers: ["X-Request": "7"],
            body: "{\"total\":7}",
            contentType: "application/json",
            priority: "HIGH",
            metadata: ["origin": "test"]
        ))
    }

    /// Enqueues an item and fails it for good with `status`.
    private func deadLetter(status: Int = 404,
                            file: StaticString = #filePath,
                            line: UInt = #line) async throws -> String {
        let id = try await enqueue()
        URLProtocolStub.setHandler(stubResponse(status))
        _ = try await engine.dispatcher.flush()
        let letter = try await engine.storage.deadLetter(id)
        XCTAssertNotNil(letter, "\(id) must be a dead letter by now", file: file, line: line)
        return id
    }

    private static func row(id: String, attempts: Int32 = 0, status: String = "PENDING") -> StoredSyncItem {
        StoredSyncItem(id: id, method: "POST", url: "https://api.test/x", headers: nil, body: "{}",
                       contentType: "application/json", priority: "NORMAL", createdAt: 1, attempts: attempts,
                       lastAttemptAt: attempts > 0 ? 5 : nil, nextRetryAt: 0, lastErrorCode: nil,
                       status: status, metadata: nil)
    }
}
