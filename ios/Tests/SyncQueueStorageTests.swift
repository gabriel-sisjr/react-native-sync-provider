import XCTest
@testable import SyncProvider

final class SyncQueueStorageTests: XCTestCase {
    private var stack: CoreDataStack!
    private var dao: SyncItemDao!
    private var storage: SyncQueueStorage!

    override func setUp() {
        super.setUp()
        stack = InMemoryCoreDataStack.make()
        dao = SyncItemDao(stack: stack)
        storage = SyncQueueStorage(dao: dao, capacity: 5)
    }

    override func tearDown() {
        storage = nil
        dao = nil
        stack = nil
        super.tearDown()
    }

    // MARK: - Helpers

    private func makeInput(url: String = "https://example.com/api",
                           method: String = "POST",
                           priority: String = "NORMAL",
                           body: String? = "{\"k\":\"v\"}") -> SyncQueueStorage.SyncItemInputData {
        return SyncQueueStorage.SyncItemInputData(
            method: method,
            url: url,
            headers: ["X-Test": "1"],
            body: body,
            contentType: "application/json",
            priority: priority,
            metadata: ["source": "unit-test"]
        )
    }

    // MARK: - Enqueue

    func test_enqueue_single_returnsId_andPersists() async throws {
        let id = try await storage.enqueue(input: makeInput())
        XCTAssertFalse(id.isEmpty)
        let size = try await storage.getSize()
        XCTAssertEqual(size, 1)

        let stored = try await storage.getAll()
        XCTAssertEqual(stored.count, 1)
        XCTAssertEqual(stored.first?.id, id)
        XCTAssertEqual(stored.first?.url, "https://example.com/api")
        XCTAssertEqual(stored.first?.method, "POST")
        XCTAssertEqual(stored.first?.priority, "NORMAL")
        XCTAssertEqual(stored.first?.status, "PENDING")
        XCTAssertEqual(stored.first?.attempts, 0)
        XCTAssertEqual(stored.first?.headers?["X-Test"], "1")
        XCTAssertEqual(stored.first?.metadata?["source"], "unit-test")
    }

    func test_enqueueBatch_isAtomic_andReturnsIdsInOrder() async throws {
        let inputs = [makeInput(url: "https://a.test/1"),
                      makeInput(url: "https://a.test/2"),
                      makeInput(url: "https://a.test/3")]
        let ids = try await storage.enqueueBatch(inputs: inputs)
        XCTAssertEqual(ids.count, 3)
        XCTAssertEqual(Set(ids).count, 3, "All ids must be unique")

        let size = try await storage.getSize()
        XCTAssertEqual(size, 3)
    }

    func test_enqueue_throwsQueueFull_whenCapacityReached() async throws {
        for _ in 0..<5 {
            _ = try await storage.enqueue(input: makeInput())
        }
        do {
            _ = try await storage.enqueue(input: makeInput())
            XCTFail("Expected QUEUE_FULL")
        } catch let error as SyncProviderError {
            XCTAssertEqual(error.code, .queueFull)
        } catch {
            XCTFail("Unexpected error type: \(error)")
        }
    }

    func test_enqueueBatch_throwsQueueFull_whenWholeBatchExceedsCap() async throws {
        for _ in 0..<4 {
            _ = try await storage.enqueue(input: makeInput())
        }
        do {
            _ = try await storage.enqueueBatch(inputs: [makeInput(), makeInput()])
            XCTFail("Expected QUEUE_FULL on batch insert")
        } catch let error as SyncProviderError {
            XCTAssertEqual(error.code, .queueFull)
        }

        let sizeAfter = try await storage.getSize()
        XCTAssertEqual(sizeAfter, 4, "No partial commits on batch reject")
    }

    func test_updateCapacity_clampsToOneMinimum() async throws {
        await storage.updateCapacity(0)
        let cap = await storage.capacity
        XCTAssertEqual(cap, 1)
    }

    // MARK: - Ordering

    func test_getPending_sortsByPriorityThenCreatedAt() async throws {
        let normalId = try await storage.enqueue(input: makeInput(priority: "NORMAL"))
        try await Task.sleep(nanoseconds: 2_000_000) // 2ms — guarantee createdAt diff
        let high1Id = try await storage.enqueue(input: makeInput(priority: "HIGH"))
        try await Task.sleep(nanoseconds: 2_000_000)
        let lowId = try await storage.enqueue(input: makeInput(priority: "LOW"))
        try await Task.sleep(nanoseconds: 2_000_000)
        let high2Id = try await storage.enqueue(input: makeInput(priority: "HIGH"))

        let pending = try await storage.getPending()
        let ids = pending.map(\.id)
        XCTAssertEqual(ids, [high1Id, high2Id, normalId, lowId])
    }

    // MARK: - Status mutations

    func test_markInFlight_incrementsAttempts_andSetsTimestamp() async throws {
        let id = try await storage.enqueue(input: makeInput())
        try await storage.markInFlight(id: id)

        let items = try await storage.getAll()
        let item = try XCTUnwrap(items.first(where: { $0.id == id }))
        XCTAssertEqual(item.status, "IN_FLIGHT")
        XCTAssertEqual(item.attempts, 1)
        XCTAssertNotNil(item.lastAttemptAt)
    }

    func test_markPendingForRetry_resetsStatus_andRecordsErrorCode() async throws {
        let id = try await storage.enqueue(input: makeInput())
        try await storage.markInFlight(id: id)
        try await storage.markPendingForRetry(id: id, errorCode: "NETWORK_ERROR")

        let items = try await storage.getAll()
        let item = try XCTUnwrap(items.first(where: { $0.id == id }))
        XCTAssertEqual(item.status, "PENDING")
        XCTAssertEqual(item.lastErrorCode, "NETWORK_ERROR")
    }

    func test_markFailed_persistsTerminalStatus() async throws {
        let id = try await storage.enqueue(input: makeInput())
        try await storage.markFailed(id: id, errorCode: "MAX_ATTEMPTS_EXCEEDED")

        let items = try await storage.getAll()
        let item = try XCTUnwrap(items.first(where: { $0.id == id }))
        XCTAssertEqual(item.status, "FAILED")
        XCTAssertEqual(item.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
    }

    func test_resetInFlightToPending_returnsResetCount() async throws {
        let id1 = try await storage.enqueue(input: makeInput())
        let id2 = try await storage.enqueue(input: makeInput())
        let id3 = try await storage.enqueue(input: makeInput())

        try await storage.markInFlight(id: id1)
        try await storage.markInFlight(id: id2)

        let count = try await storage.resetInFlightToPending()
        XCTAssertEqual(count, 2)

        let items = try await storage.getAll()
        XCTAssertTrue(items.allSatisfy { $0.status == "PENDING" })
        XCTAssertEqual(items.count, 3)
        XCTAssertTrue(items.contains { $0.id == id3 })
    }

    // MARK: - Remove + clear

    func test_remove_returnsTrue_onlyWhenIdMatches() async throws {
        let id = try await storage.enqueue(input: makeInput())
        let removed = try await storage.remove(id: id)
        XCTAssertTrue(removed)
        let removedAgain = try await storage.remove(id: id)
        XCTAssertFalse(removedAgain)
    }

    func test_clear_emptiesQueue() async throws {
        for _ in 0..<3 {
            _ = try await storage.enqueue(input: makeInput())
        }
        try await storage.clear()
        let size = try await storage.getSize()
        XCTAssertEqual(size, 0)
    }

    // MARK: - History

    func test_recordResult_persistsAndOrdersByFinishedAtDesc() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let oldest = StoredSyncResult(id: "r1",
                                      startedAt: now - 200,
                                      finishedAt: now - 100,
                                      itemsAttempted: 1,
                                      itemsSucceeded: 1,
                                      itemsFailed: 0,
                                      errorMessage: nil)
        let newest = StoredSyncResult(id: "r2",
                                      startedAt: now,
                                      finishedAt: now + 50,
                                      itemsAttempted: 2,
                                      itemsSucceeded: 0,
                                      itemsFailed: 2,
                                      errorMessage: "boom")
        try await storage.recordResult(oldest)
        try await storage.recordResult(newest)

        let history = try await storage.getHistory(limit: 0)
        XCTAssertEqual(history.map(\.id), ["r2", "r1"])
        XCTAssertEqual(history.first?.errorMessage, "boom")
    }

    func test_clearHistory_removesAllResults() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await storage.recordResult(StoredSyncResult(id: "r1",
                                                        startedAt: now,
                                                        finishedAt: now,
                                                        itemsAttempted: 0,
                                                        itemsSucceeded: 0,
                                                        itemsFailed: 0,
                                                        errorMessage: nil))
        try await storage.clearHistory()
        let history = try await storage.getHistory(limit: 0)
        XCTAssertTrue(history.isEmpty)
    }
}
