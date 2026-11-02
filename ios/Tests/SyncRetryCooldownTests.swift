import XCTest
@testable import SyncProvider

/// Storage / DAO-level coverage for the retry cooldown:
///   * a retriable failure persists `nextRetryAt = now + delay`, always positive;
///   * `fetchPending` excludes items still in cooldown and includes them once
///     the deadline elapses;
///   * `countPending` keeps counting cooling items (capacity is unchanged);
///   * `resetInFlightToPending` / `markInFlight` leave `nextRetryAt` (and
///     `attempts`) untouched;
///   * the retry timer's next-wake queries.
final class SyncRetryCooldownTests: XCTestCase {
    private var clock: ManualClock!
    private var stack: CoreDataStack!
    private var dao: SyncItemDao!
    private var storage: SyncQueueStorage!

    override func setUp() {
        super.setUp()
        clock = ManualClock()
        stack = InMemoryCoreDataStack.make()
        dao = SyncItemDao(stack: stack, clock: clock)
        storage = SyncQueueStorage(dao: dao, capacity: 100)
    }

    override func tearDown() {
        storage = nil
        dao = nil
        stack = nil
        super.tearDown()
    }

    // MARK: - Helpers

    private func makeInput(url: String = "https://example.com/api",
                           priority: String = "NORMAL") -> SyncQueueStorage.SyncItemInputData {
        SyncQueueStorage.SyncItemInputData(
            method: "POST",
            url: url,
            headers: ["X-Test": "1"],
            body: "{\"k\":\"v\"}",
            contentType: "application/json",
            priority: priority,
            metadata: nil
        )
    }

    private func item(_ id: String) async throws -> StoredSyncItem {
        let all = try await storage.getAll()
        return try XCTUnwrap(all.first(where: { $0.id == id }))
    }

    // MARK: - Cooldown persistence

    func test_markPendingForRetry_persistsCooldown_asNowPlusDelay() async throws {
        let id = try await storage.enqueue(input: makeInput())
        try await storage.markInFlight(id: id)

        clock.advance(ms: 250)
        try await storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 5_000)

        let stored = try await item(id)
        XCTAssertEqual(stored.status, "PENDING")
        XCTAssertEqual(stored.lastErrorCode, "SERVER_ERROR")
        XCTAssertEqual(stored.nextRetryAt, clock.nowMs() + 5_000)
    }

    func test_markPendingForRetry_zeroDelay_writesAPositiveTimestamp() async throws {
        let id = try await storage.enqueue(input: makeInput())
        try await storage.markInFlight(id: id)

        try await storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 0)

        // `0` is reserved for "never attempted".
        let written = try await item(id).nextRetryAt
        XCTAssertEqual(written, clock.nowMs())

        let dispatchable = try await storage.getPending()
        XCTAssertEqual(dispatchable.map(\.id), [id], "a zero delay is eligible at once")
    }

    // MARK: - Fetch eligibility

    func test_fetchPending_excludesCoolingItems_includesOnceElapsed() async throws {
        let eligibleId = try await storage.enqueue(input: makeInput(url: "https://a.test/eligible"))
        let coolingId = try await storage.enqueue(input: makeInput(url: "https://a.test/cooling"))

        // Park the second item behind a cooldown.
        try await storage.markInFlight(id: coolingId)
        try await storage.markPendingForRetry(id: coolingId, errorCode: "SERVER_ERROR", retryDelayMs: 500)

        // Dispatch fetch sees only the eligible item ...
        let pending = try await storage.getPending()
        XCTAssertEqual(pending.map(\.id), [eligibleId])

        // ... but both rows still exist and the cooling one is still PENDING.
        let all = try await storage.getAll()
        XCTAssertEqual(all.count, 2)
        let coolingStatus = try await item(coolingId).status
        XCTAssertEqual(coolingStatus, "PENDING")

        clock.advance(ms: 499)
        let oneMsEarly = try await storage.getPending()
        XCTAssertEqual(oneMsEarly.map(\.id), [eligibleId])

        // Once the deadline elapses, it reappears.
        clock.advance(ms: 1)
        let pendingAfter = try await storage.getPending()
        XCTAssertEqual(Set(pendingAfter.map(\.id)), [eligibleId, coolingId])
    }

    // MARK: - Capacity accounting (unchanged)

    func test_countPending_countsCoolingItems_capacityUnaffected() async throws {
        let dispatchable = try await storage.enqueue(input: makeInput(url: "https://a.test/now"))
        let cooling = try await storage.enqueue(input: makeInput(url: "https://a.test/later"))

        try await storage.markInFlight(id: cooling)
        try await storage.markPendingForRetry(id: cooling, errorCode: "SERVER_ERROR", retryDelayMs: 60_000)

        // getSize() -> countPending() counts BOTH (capacity is about queue
        // contents, not dispatch eligibility).
        let size = try await storage.getSize()
        XCTAssertEqual(size, 2, "cooling items must still count toward the cap")

        // But only the eligible one is dispatchable.
        let pending = try await storage.getPending()
        XCTAssertEqual(pending.map(\.id), [dispatchable])
    }

    // MARK: - Recovery invariants

    func test_markInFlight_preservesNextRetryAt() async throws {
        let id = try await storage.enqueue(input: makeInput())
        try await storage.markInFlight(id: id)
        try await storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 60_000)
        let cooldown = try await item(id).nextRetryAt
        XCTAssertGreaterThan(cooldown, 0)

        // Picking the item up again must not clobber its cooldown column.
        try await storage.markInFlight(id: id)
        let reFetched = try await item(id)
        XCTAssertEqual(reFetched.status, "IN_FLIGHT")
        XCTAssertEqual(reFetched.attempts, 2, "attempts keeps counting across dispatches")
        XCTAssertEqual(reFetched.nextRetryAt, cooldown, "markInFlight must preserve nextRetryAt")
    }

    func test_resetInFlightToPending_preservesNextRetryAt() async throws {
        let id = try await storage.enqueue(input: makeInput())
        try await storage.markInFlight(id: id)
        try await storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 60_000)
        let cooldown = try await item(id).nextRetryAt
        XCTAssertGreaterThan(cooldown, 0)

        // Simulate a crash mid-dispatch: the item is IN_FLIGHT when recovery runs.
        try await storage.markInFlight(id: id)
        let reset = try await storage.resetInFlightToPending()
        XCTAssertEqual(reset, 1)

        let recovered = try await item(id)
        XCTAssertEqual(recovered.status, "PENDING")
        XCTAssertEqual(recovered.attempts, 2, "recovery must not reset the attempt count")
        XCTAssertEqual(recovered.nextRetryAt, cooldown,
                       "recovery must neither extend nor clear the retry cooldown")
    }

    // MARK: - Queue contents

    func test_getQueued_returnsPendingRowsOnly_includingCoolingOnes() async throws {
        let fresh = try await storage.enqueue(input: makeInput(url: "https://a.test/fresh"))
        let cooling = try await storage.enqueue(input: makeInput(url: "https://a.test/cooling"))
        let inFlight = try await storage.enqueue(input: makeInput(url: "https://a.test/in-flight"))
        let failed = try await storage.enqueue(input: makeInput(url: "https://a.test/failed"))

        try await storage.markInFlight(id: cooling)
        try await storage.markPendingForRetry(id: cooling, errorCode: "SERVER_ERROR", retryDelayMs: 60_000)
        try await storage.markInFlight(id: inFlight)
        try await storage.markFailed(id: failed, errorCode: "SERVER_ERROR")

        let queued = try await storage.getQueued()
        XCTAssertEqual(Set(queued.map(\.id)), [fresh, cooling])
        let size = try await storage.getSize()
        XCTAssertEqual(size, queued.count, "getQueueSize and getPendingItems must agree")
    }

    func test_getQueued_followsDispatchOrder() async throws {
        let low = try await storage.enqueue(input: makeInput(priority: "LOW"))
        let normal = try await storage.enqueue(input: makeInput(priority: "NORMAL"))
        let high = try await storage.enqueue(input: makeInput(priority: "HIGH"))

        let queued = try await storage.getQueued()

        XCTAssertEqual(queued.map(\.id), [high, normal, low])
    }

    // MARK: - Next wake

    func test_earliestRetryAt_ignoresNeverAttemptedRows() async throws {
        _ = try await storage.enqueue(input: makeInput(url: "https://a.test/fresh"))
        let noRow = try await storage.earliestRetryAt()
        XCTAssertNil(noRow, "a batch overflow must not wake the timer")

        let soon = try await storage.enqueue(input: makeInput(url: "https://a.test/soon"))
        let later = try await storage.enqueue(input: makeInput(url: "https://a.test/later"))
        for (id, delay) in [(later, 60_000), (soon, 30_000)] {
            try await storage.markInFlight(id: id)
            try await storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: delay)
        }

        let wake = try await storage.earliestRetryAt()
        let expected = try await item(soon).nextRetryAt
        XCTAssertEqual(wake, expected)
    }

    func test_earliestRetryAt_ignoresRowsThatAreNotPending() async throws {
        let id = try await storage.enqueue(input: makeInput())
        try await storage.markInFlight(id: id)
        try await storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 60_000)
        try await storage.markFailed(id: id, errorCode: "SERVER_ERROR")

        let wake = try await storage.earliestRetryAt()
        XCTAssertNil(wake)
    }

    func test_earliestRetryAt_returnsAnElapsedDeadline() async throws {
        let id = try await storage.enqueue(input: makeInput())
        try await storage.markInFlight(id: id)
        try await storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 0)
        let deadline = clock.nowMs()
        clock.advance(ms: 5_000)

        let wake = try await storage.earliestRetryAt()
        XCTAssertEqual(wake, deadline, "an elapsed cooldown must not be stranded")
    }

    func test_earliestPendingAt_coversEveryPendingRow() async throws {
        let empty = try await storage.earliestPendingAt()
        XCTAssertNil(empty)

        let cooling = try await storage.enqueue(input: makeInput(url: "https://a.test/cooling"))
        try await storage.markInFlight(id: cooling)
        try await storage.markPendingForRetry(id: cooling, errorCode: "SERVER_ERROR", retryDelayMs: 60_000)
        let onlyCooling = try await storage.earliestPendingAt()
        let deadline = try await item(cooling).nextRetryAt
        XCTAssertEqual(onlyCooling, deadline)

        _ = try await storage.enqueue(input: makeInput(url: "https://a.test/fresh"))
        let withFresh = try await storage.earliestPendingAt()
        XCTAssertEqual(withFresh, 0, "a never-attempted row is due now")
    }

    // MARK: - Stale credential

    func test_markCredentialRejected_refundsTheAttempt_andCoolsTheItem() async throws {
        let id = try await storage.enqueue(input: makeInput())
        let before = try await item(id).attempts
        try await storage.markInFlight(id: id)

        let heldUntil = try await storage.markCredentialRejected(id: id,
                                                                 errorCode: "UNAUTHORIZED",
                                                                 retryDelayMs: 5_000,
                                                                 attempts: before)

        let stored = try await item(id)
        XCTAssertEqual(stored.status, "PENDING")
        XCTAssertEqual(stored.attempts, before)
        XCTAssertEqual(stored.lastErrorCode, "UNAUTHORIZED")
        XCTAssertEqual(stored.nextRetryAt, clock.nowMs() + 5_000)
        XCTAssertEqual(heldUntil, stored.nextRetryAt)
    }

    /// The row can go while its request is out. The credential was still
    /// rejected, so the hold must still reach the timer.
    func test_markCredentialRejected_forARemovedRow_stillReportsTheHold() async throws {
        let heldUntil = try await storage.markCredentialRejected(id: "gone",
                                                                 errorCode: "UNAUTHORIZED",
                                                                 retryDelayMs: 5_000,
                                                                 attempts: 0)

        XCTAssertEqual(heldUntil, clock.nowMs() + 5_000)
    }
}
