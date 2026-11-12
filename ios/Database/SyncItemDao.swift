//
//  SyncItemDao.swift
//  SyncProvider
//
//  CRUD encapsulation over the Core Data `SyncItemEntity` and
//  `SyncResultEntity`. Every mutating operation runs on a private background
//  context fetched from `CoreDataStack.shared`.
//

import CoreData
import Foundation

/// Status string values stored on `SyncItemEntity.status`.
enum SyncItemStatus: String {
    case pending = "PENDING"
    case inFlight = "IN_FLIGHT"
    case failed = "FAILED"
}

/// Plain Swift representation of a queued sync item — used so callers do not
/// have to handle `NSManagedObject` directly. All values are `Sendable`.
struct StoredSyncItem: Sendable, Equatable {
    let id: String
    let method: String
    let url: String
    let headers: [String: String]?
    let body: String?
    let contentType: String?
    let priority: String
    let createdAt: Int64
    /// Recorded attempts. ``SyncItemDao/markInFlight(id:)`` counts one when the
    /// dispatch starts, so a kill mid-request still spends it. After a
    /// credential rejection under a persisted credential,
    /// ``SyncItemDao/markCredentialRejected(id:errorCode:retryDelayMs:attempts:)``
    /// restores the count from before the dispatch, so `0` does not prove the
    /// request never left the device. `lastAttemptAt` is the field that records
    /// whether a dispatch ever started.
    let attempts: Int32
    let lastAttemptAt: Int64?
    /// Epoch-ms timestamp before which the item is **not** eligible for
    /// dispatch. `0` means "eligible now": no cooldown or credential hold is
    /// pending. A positive value is a deadline written by
    /// ``SyncItemDao/markPendingForRetry(id:errorCode:retryDelayMs:)`` (retry
    /// cooldown) or by
    /// ``SyncItemDao/markCredentialRejected(id:errorCode:retryDelayMs:attempts:)``
    /// (credential hold). `0` does not mean "never attempted": a row recovered
    /// after a kill mid-dispatch, or migrated from the v1 store, can have
    /// `attempts >= 1` with `nextRetryAt == 0`.
    let nextRetryAt: Int64
    let lastErrorCode: String?
    let status: String
    let metadata: [String: String]?
}

/// Plain Swift representation of a persisted flush result.
struct StoredSyncResult: Sendable, Equatable {
    let id: String
    let startedAt: Int64
    let finishedAt: Int64
    let itemsAttempted: Int32
    let itemsSucceeded: Int32
    let itemsFailed: Int32
    let errorMessage: String?
}

/// An item that failed for good, out of the queue. All values are `Sendable`.
struct StoredDeadLetter: Sendable, Equatable {
    let id: String
    let method: String
    let url: String
    let headers: [String: String]?
    let body: String?
    let contentType: String?
    let priority: String
    /// When the item was first enqueued.
    let createdAt: Int64
    let metadata: [String: String]?
    /// Every attempt made.
    let attempts: Int32
    let lastAttemptAt: Int64
    /// The code of the failure that ended it.
    let lastErrorCode: String
    /// What that failure said, bounded; never a header or a body.
    let lastErrorMessage: String?
    let deadLetteredAt: Int64
}

/// The most a dead letter's `lastErrorMessage` keeps, in characters.
let kDeadLetterMessageMaxLength = 1_024

/// What ``SyncItemDao/replayDeadLetter(id:capacity:)`` did.
enum DeadLetterReplay: Sendable, Equatable {
    /// Back in the queue, and the dead letter gone.
    case replayed
    /// No dead letter has that id: nothing changed.
    case unknown
    /// A queue row already has that id: nothing changed.
    case alreadyQueued
    /// The queue is at capacity: nothing changed.
    case queueFull
}

/// Asynchronous CRUD wrapper around `SyncItemEntity`, `SyncDeadLetterEntity`
/// and `SyncResultEntity`.
final class SyncItemDao {
    private let stack: CoreDataStack
    private let clock: SyncClock

    init(stack: CoreDataStack = .shared, clock: SyncClock = SystemClock()) {
        self.stack = stack
        self.clock = clock
    }

    // MARK: - Inserts

    /// Insert a single item. Throws on Core Data errors.
    func insert(_ item: StoredSyncItem) async throws {
        try await stack.performBackgroundTask { context in
            try Self.insertItem(item, into: context)
        }
    }

    /// Atomically insert a batch of items. Either every row is committed or
    /// none of them are.
    func insertBatch(_ items: [StoredSyncItem]) async throws {
        try await stack.performBackgroundTask { context in
            for item in items {
                try Self.insertItem(item, into: context)
            }
        }
    }

    private static func insertItem(_ item: StoredSyncItem, into context: NSManagedObjectContext) throws {
        let entity = SyncItemEntity(context: context)
        entity.id = item.id
        entity.method = item.method
        entity.url = item.url
        entity.headersJSON = try encodeJSON(item.headers)
        entity.body = item.body
        entity.contentType = item.contentType
        entity.priority = item.priority
        entity.createdAt = item.createdAt
        entity.attempts = item.attempts
        entity.lastAttemptAt = item.lastAttemptAt ?? 0
        entity.nextRetryAt = item.nextRetryAt
        entity.lastErrorCode = item.lastErrorCode
        entity.status = item.status
        entity.metadataJSON = try encodeJSON(item.metadata)
    }

    // MARK: - Reads

    /// Total number of `PENDING` items. Used to enforce the queue cap.
    ///
    /// - Important: This deliberately counts items currently in retry
    ///   **cooldown** (`nextRetryAt` in the future) as well — capacity
    ///   accounting tracks what is *in the queue*, not what is *dispatchable
    ///   right now*. Only the dispatch path (``fetchPending(limit:)``) filters
    ///   by eligibility. Counting cooling items keeps the cap honest so
    ///   the queue cannot silently over-fill while items wait out their
    ///   backoff.
    func countPending() async throws -> Int {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<NSNumber>(entityName: "SyncItemEntity")
            request.resultType = .countResultType
            request.predicate = NSPredicate(format: "status == %@", SyncItemStatus.pending.rawValue)
            let count = try context.count(for: request)
            return count
        }
    }

    /// Fetch **dispatchable** pending items sorted by priority (HIGH, NORMAL,
    /// LOW) and `createdAt` ASC.
    /// - Parameter limit: 0 means "no limit".
    ///
    /// `priority` is stored as a `String`, so we sort in-memory via the
    /// `priorityRank` helper. `createdAt` ASC is the secondary sort and is
    /// applied via the SQL store directly to keep the in-memory work minimal.
    ///
    /// Eligibility gate: a `PENDING` item is only dispatchable once its
    /// retry cooldown has elapsed, i.e. `nextRetryAt <= now`. A row with no
    /// deadline pending (`0`, which every enqueue writes) trivially satisfies
    /// the comparison, so freshly enqueued items are always returned. This is
    /// the only place that filters by cooldown:
    /// `countPending` (capacity) deliberately does not.
    func fetchPending(limit: Int = 0) async throws -> [StoredSyncItem] {
        try await stack.performBackgroundTask { [clock] context in
            // `%lld` formats the scalar `Int64` `nextRetryAt` (NOT `%d`, which
            // is 32-bit and would truncate epoch-ms timestamps).
            let nowMs = clock.nowMs()
            let request = NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
            request.predicate = NSPredicate(format: "status == %@ AND nextRetryAt <= %lld",
                                            SyncItemStatus.pending.rawValue, nowMs)
            request.sortDescriptors = [
                NSSortDescriptor(key: "createdAt", ascending: true)
            ]
            let entities = try context.fetch(request)
            let sorted = try entities.map(Self.materialize).sorted(by: Self.isDispatchedBefore)
            if limit > 0 {
                return Array(sorted.prefix(limit))
            }
            return sorted
        }
    }

    /// Fetch the items waiting to be dispatched: every `PENDING` row in dispatch
    /// order, including rows in retry cooldown. Backs `getPendingItems()`.
    /// `IN_FLIGHT` and `FAILED` rows are not part of the queue contents.
    func fetchQueued() async throws -> [StoredSyncItem] {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
            request.predicate = NSPredicate(format: "status == %@", SyncItemStatus.pending.rawValue)
            let entities = try context.fetch(request)
            return try entities.map(Self.materialize).sorted(by: Self.isDispatchedBefore)
        }
    }

    /// Fetch every row, in any status.
    func fetchAll() async throws -> [StoredSyncItem] {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
            request.sortDescriptors = [
                NSSortDescriptor(key: "createdAt", ascending: true)
            ]
            let entities = try context.fetch(request)
            return try entities.map(Self.materialize)
        }
    }

    /// `MIN(nextRetryAt)` over the `PENDING` rows that carry a deadline
    /// (`nextRetryAt > 0`: a retry cooldown or a credential hold), or `nil`
    /// when none qualifies. This is when the retry timer must wake.
    ///
    /// Rows with no deadline (`nextRetryAt = 0`) stay out on purpose: a batch
    /// that left fresh rows behind does not start a new cycle by itself. The
    /// minimum may already be in the past; the caller clamps the delay to 0,
    /// so an elapsed cooldown is never stranded.
    func earliestRetryAt() async throws -> Int64? {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
            request.predicate = NSPredicate(format: "status == %@ AND nextRetryAt > 0",
                                            SyncItemStatus.pending.rawValue)
            request.sortDescriptors = [NSSortDescriptor(key: "nextRetryAt", ascending: true)]
            request.fetchLimit = 1
            return try context.fetch(request).first?.nextRetryAt
        }
    }

    /// `MIN(nextRetryAt)` over **all** `PENDING` rows, or `nil` for an empty
    /// queue. A row with no deadline (`nextRetryAt = 0`) makes this 0, which
    /// means "dispatch now".
    /// Used when the engine resumes.
    func earliestPendingAt() async throws -> Int64? {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
            request.predicate = NSPredicate(format: "status == %@", SyncItemStatus.pending.rawValue)
            request.sortDescriptors = [NSSortDescriptor(key: "nextRetryAt", ascending: true)]
            request.fetchLimit = 1
            return try context.fetch(request).first?.nextRetryAt
        }
    }

    /// Find a single item by id.
    func find(id: String) async throws -> StoredSyncItem? {
        try await stack.performBackgroundTask { context in
            guard let entity = try Self.fetchEntity(id: id, in: context) else { return nil }
            return try Self.materialize(entity)
        }
    }

    // MARK: - Updates

    /// Claims an item for dispatch and counts the attempt. Counting here, before
    /// the request, means a kill mid-request spends the attempt.
    ///
    /// Runs one at a time with the purges (see
    /// ``CoreDataStack/performSerializedTask(_:)``), so its answer still holds
    /// when it saves.
    ///
    /// - Returns: `false` when no queue row has `id`: it was purged since the
    ///   batch was read, and there is nothing to send.
    @discardableResult
    func markInFlight(id: String) async throws -> Bool {
        try await stack.performSerializedTask { [clock] context in
            guard let entity = try Self.fetchEntity(id: id, in: context) else { return false }
            entity.status = SyncItemStatus.inFlight.rawValue
            entity.attempts += 1
            entity.lastAttemptAt = clock.nowMs()
            return true
        }
    }

    /// Moves an item that failed for good out of the queue, into the
    /// dead-letter store, in one transaction: the queue row is deleted and the
    /// dead letter written by the same save, so no copy is ever left in both,
    /// nor in neither.
    ///
    /// The dead letter keeps the request, `createdAt`, the attempts made and
    /// the last one's time. A row with no attempt time, one whose budget was
    /// spent before it was ever claimed, takes the time of the move, which is
    /// also its `deadLetteredAt`. `errorMessage` is cut to
    /// ``kDeadLetterMessageMaxLength`` characters. An older dead letter with
    /// the same id, from an earlier failure of a replayed item, is replaced.
    ///
    /// Runs one at a time with the purges and replays (see
    /// ``CoreDataStack/performSerializedTask(_:)``): a row purged before the
    /// move saves, while its request was out say, is never copied.
    ///
    /// - Returns: `false` when no queue row has `id`, removed meanwhile.
    @discardableResult
    func moveToDeadLetters(id: String, errorCode: String, errorMessage: String?) async throws -> Bool {
        try await stack.performSerializedTask { [clock] context in
            guard let entity = try Self.fetchEntity(id: id, in: context) else { return false }
            if let older = try Self.fetchDeadLetter(id: id, in: context) {
                context.delete(older)
            }
            let nowMs = clock.nowMs()
            let letter = SyncDeadLetterEntity(context: context)
            letter.id = entity.id
            letter.method = entity.method
            letter.url = entity.url
            letter.headersJSON = entity.headersJSON
            letter.body = entity.body
            letter.contentType = entity.contentType
            letter.priority = entity.priority
            letter.createdAt = entity.createdAt
            letter.metadataJSON = entity.metadataJSON
            letter.attempts = entity.attempts
            letter.lastAttemptAt = entity.lastAttemptAt > 0 ? entity.lastAttemptAt : nowMs
            letter.lastErrorCode = errorCode
            letter.lastErrorMessage = errorMessage.map { String($0.prefix(kDeadLetterMessageMaxLength)) }
            letter.deadLetteredAt = nowMs
            context.delete(entity)
            return true
        }
    }

    /// Puts the dead letter `id` back in the queue, in one transaction: a
    /// fresh `PENDING` row with the same id, request and `createdAt`, no
    /// attempts, no deadline and no last error, and the dead letter deleted.
    ///
    /// Checked against `capacity` in the same transaction, counted the way
    /// ``countPending()`` counts it for an enqueue; dead letters count toward
    /// it no more than before. A queue row that already has `id`, which only
    /// a damaged store can leave, is left alone, and so is the dead letter.
    ///
    /// Runs one at a time with the purges, moves and other replays (see
    /// ``CoreDataStack/performSerializedTask(_:)``): a dead letter discarded
    /// or cleared before the replay saves is not put back in the queue, and
    /// of two replays of one id only the first finds it.
    func replayDeadLetter(id: String, capacity: Int) async throws -> DeadLetterReplay {
        try await stack.performSerializedTask { context in
            guard let letter = try Self.fetchDeadLetter(id: id, in: context) else { return .unknown }
            // The model makes `id` unique, and the merge policy would let this
            // insert overwrite that row instead of failing.
            guard try Self.fetchEntity(id: id, in: context) == nil else { return .alreadyQueued }
            let pending = NSFetchRequest<NSNumber>(entityName: "SyncItemEntity")
            pending.resultType = .countResultType
            pending.predicate = NSPredicate(format: "status == %@", SyncItemStatus.pending.rawValue)
            guard try context.count(for: pending) < capacity else { return .queueFull }
            let entity = SyncItemEntity(context: context)
            entity.id = letter.id
            entity.method = letter.method
            entity.url = letter.url
            entity.headersJSON = letter.headersJSON
            entity.body = letter.body
            entity.contentType = letter.contentType
            entity.priority = letter.priority
            entity.createdAt = letter.createdAt
            entity.metadataJSON = letter.metadataJSON
            entity.attempts = 0
            entity.lastAttemptAt = 0
            entity.nextRetryAt = 0
            entity.lastErrorCode = nil
            entity.status = SyncItemStatus.pending.rawValue
            context.delete(letter)
            return .replayed
        }
    }

    /// Deletes the dead letter `id`. `false` when there is none.
    @discardableResult
    func deleteDeadLetter(id: String) async throws -> Bool {
        try await stack.performSerializedTask { context in
            guard let letter = try Self.fetchDeadLetter(id: id, in: context) else { return false }
            context.delete(letter)
            return true
        }
    }

    /// Every dead letter, `deadLetteredAt` ascending, then `id` ascending.
    func fetchDeadLetters() async throws -> [StoredDeadLetter] {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<SyncDeadLetterEntity>(entityName: "SyncDeadLetterEntity")
            request.sortDescriptors = [
                NSSortDescriptor(key: "deadLetteredAt", ascending: true),
                NSSortDescriptor(key: "id", ascending: true)
            ]
            return try context.fetch(request).map(Self.materialize)
        }
    }

    /// Reset an item to PENDING for another attempt, recording the error code,
    /// the attempt timestamp, and a **retry cooldown**.
    ///
    /// The cooldown is persisted as `nextRetryAt = now + retryDelayMs`, where
    /// `now` is read here, after the attempt finished. ``fetchPending(limit:)``
    /// honors it, so a degraded server cannot drive a hot-retry loop.
    ///
    /// The value is a positive timestamp even when the delay is 0, so the row
    /// keeps `nextRetryAt > 0` ("a cooldown or credential hold is pending")
    /// rather than `0` ("eligible now", no deadline). The retry timer's usual
    /// wake source, ``earliestRetryAt()``, only considers rows with
    /// `nextRetryAt > 0`, so it relies on that distinction. `0` is not a
    /// "never attempted" marker.
    ///
    /// - Note: `attempts` is intentionally **not** modified here. It counts
    ///   recorded attempts: ``markInFlight(id:)`` increments it once when each
    ///   dispatch starts, and it must keep counting across retries so the
    ///   backoff curve advances.
    /// - Parameters:
    ///   - errorCode: the last failure's code, surfaced to JS via `lastErrorCode`.
    ///   - retryDelayMs: backoff delay (ms) from ``RetryPolicyEvaluator``.
    func markPendingForRetry(id: String, errorCode: String?, retryDelayMs: Int) async throws {
        try await stack.performBackgroundTask { [clock] context in
            guard let entity = try Self.fetchEntity(id: id, in: context) else { return }
            let nowMs = clock.nowMs()
            entity.status = SyncItemStatus.pending.rawValue
            entity.lastErrorCode = errorCode
            entity.lastAttemptAt = nowMs
            entity.nextRetryAt = nowMs + Int64(max(0, retryDelayMs))
        }
    }

    /// Reset an item rejected for a stale persisted credential. It goes back to
    /// PENDING behind a cooldown, and the attempt is refunded: an expired token
    /// says nothing about the item, so it must not spend the retry budget.
    ///
    /// - Parameter attempts: the value from before ``markInFlight(id:)``.
    /// - Returns: when the hold ends, even if the row was removed meanwhile: the
    ///   credential was rejected either way.
    @discardableResult
    func markCredentialRejected(id: String,
                                errorCode: String?,
                                retryDelayMs: Int,
                                attempts: Int32) async throws -> Int64 {
        try await stack.performBackgroundTask { [clock] context in
            let nowMs = clock.nowMs()
            let heldUntil = nowMs + Int64(max(0, retryDelayMs))
            guard let entity = try Self.fetchEntity(id: id, in: context) else { return heldUntil }
            entity.status = SyncItemStatus.pending.rawValue
            entity.attempts = attempts
            entity.lastErrorCode = errorCode
            entity.lastAttemptAt = nowMs
            entity.nextRetryAt = heldUntil
            return heldUntil
        }
    }

    /// Reset every IN_FLIGHT item back to PENDING. Used by ``RecoveryManager``
    /// on app launch to requeue items orphaned by a crash / force-quit.
    ///
    /// - Important: Only `status` is rewritten. `nextRetryAt` (and `attempts`)
    ///   are deliberately left untouched: recovery must neither extend nor
    ///   clear an item's retry cooldown. An item that was IN_FLIGHT had already
    ///   cleared its eligibility gate, so its `nextRetryAt` is in the past and
    ///   it becomes dispatchable again immediately, which is correct: a crash
    ///   must not impose a fresh backoff, nor reset the attempt count.
    @discardableResult
    func resetInFlightToPending() async throws -> Int {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
            request.predicate = NSPredicate(format: "status == %@", SyncItemStatus.inFlight.rawValue)
            let entities = try context.fetch(request)
            for entity in entities {
                // Status only — `nextRetryAt` / `attempts` are preserved.
                entity.status = SyncItemStatus.pending.rawValue
            }
            return entities.count
        }
    }

    // MARK: - Deletes

    /// Delete a single item by id: the queued one, or else the dead letter
    /// with that id. Returns `true` if a row was removed from either store.
    @discardableResult
    func delete(id: String) async throws -> Bool {
        try await stack.performSerializedTask { context in
            if let entity = try Self.fetchEntity(id: id, in: context) {
                context.delete(entity)
                return true
            }
            guard let letter = try Self.fetchDeadLetter(id: id, in: context) else { return false }
            context.delete(letter)
            return true
        }
    }

    /// Delete every item, in the queue and in the dead-letter store: a purge
    /// such as a logout must leave no request behind.
    func clear() async throws {
        try await stack.performSerializedTask { context in
            for entityName in ["SyncItemEntity", "SyncDeadLetterEntity"] {
                let fetch: NSFetchRequest<NSFetchRequestResult> = NSFetchRequest(entityName: entityName)
                let batch = NSBatchDeleteRequest(fetchRequest: fetch)
                batch.resultType = .resultTypeStatusOnly
                _ = try context.execute(batch)
            }
            // Reset the context after a batch delete so it reflects the change.
            context.reset()
        }
    }

    // MARK: - History

    func insertResult(_ result: StoredSyncResult) async throws {
        try await stack.performBackgroundTask { context in
            let entity = SyncResultEntity(context: context)
            entity.id = result.id
            entity.startedAt = result.startedAt
            entity.finishedAt = result.finishedAt
            entity.itemsAttempted = result.itemsAttempted
            entity.itemsSucceeded = result.itemsSucceeded
            entity.itemsFailed = result.itemsFailed
            entity.errorMessage = result.errorMessage
        }
    }

    /// Fetch flush history, most recent first. `limit == 0` means no limit.
    func fetchHistory(limit: Int = 0) async throws -> [StoredSyncResult] {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<SyncResultEntity>(entityName: "SyncResultEntity")
            request.sortDescriptors = [
                NSSortDescriptor(key: "finishedAt", ascending: false)
            ]
            if limit > 0 {
                request.fetchLimit = limit
            }
            let entities = try context.fetch(request)
            return entities.map { entity in
                StoredSyncResult(id: entity.id ?? "",
                                 startedAt: entity.startedAt,
                                 finishedAt: entity.finishedAt,
                                 itemsAttempted: entity.itemsAttempted,
                                 itemsSucceeded: entity.itemsSucceeded,
                                 itemsFailed: entity.itemsFailed,
                                 errorMessage: entity.errorMessage)
            }
        }
    }

    func clearHistory() async throws {
        try await stack.performBackgroundTask { context in
            let fetch: NSFetchRequest<NSFetchRequestResult> = NSFetchRequest(entityName: "SyncResultEntity")
            let batch = NSBatchDeleteRequest(fetchRequest: fetch)
            batch.resultType = .resultTypeStatusOnly
            _ = try context.execute(batch)
            context.reset()
        }
    }

    // MARK: - Helpers

    private static func fetchDeadLetter(id: String, in context: NSManagedObjectContext) throws -> SyncDeadLetterEntity? {
        let request = NSFetchRequest<SyncDeadLetterEntity>(entityName: "SyncDeadLetterEntity")
        request.predicate = NSPredicate(format: "id == %@", id)
        request.fetchLimit = 1
        return try context.fetch(request).first
    }

    private static func fetchEntity(id: String, in context: NSManagedObjectContext) throws -> SyncItemEntity? {
        let request = NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
        request.predicate = NSPredicate(format: "id == %@", id)
        request.fetchLimit = 1
        return try context.fetch(request).first
    }

    private static func materialize(_ entity: SyncDeadLetterEntity) throws -> StoredDeadLetter {
        StoredDeadLetter(id: entity.id ?? "",
                         method: entity.method ?? "POST",
                         url: entity.url ?? "",
                         headers: try decodeJSON(entity.headersJSON),
                         body: entity.body,
                         contentType: entity.contentType,
                         priority: entity.priority ?? "NORMAL",
                         createdAt: entity.createdAt,
                         metadata: try decodeJSON(entity.metadataJSON),
                         attempts: entity.attempts,
                         lastAttemptAt: entity.lastAttemptAt,
                         // An empty or missing code, which only a damaged row
                         // can have, reads as the legacy fallback.
                         lastErrorCode: entity.lastErrorCode.flatMap { $0.isEmpty ? nil : $0 } ?? kLegacyDeadLetterErrorCode,
                         lastErrorMessage: entity.lastErrorMessage,
                         deadLetteredAt: entity.deadLetteredAt)
    }

    private static func materialize(_ entity: SyncItemEntity) throws -> StoredSyncItem {
        let headers = try decodeJSON(entity.headersJSON)
        let metadata = try decodeJSON(entity.metadataJSON)
        return StoredSyncItem(
            id: entity.id ?? "",
            method: entity.method ?? "POST",
            url: entity.url ?? "",
            headers: headers,
            body: entity.body,
            contentType: entity.contentType,
            priority: entity.priority ?? "NORMAL",
            createdAt: entity.createdAt,
            attempts: entity.attempts,
            // Core Data stores `lastAttemptAt` as a scalar `Int64` with `0`
            // sentinel meaning "never attempted"; map back to `Int64?` so the
            // public-facing `StoredSyncItem` keeps nullable semantics.
            lastAttemptAt: entity.lastAttemptAt == 0 ? nil : entity.lastAttemptAt,
            // `nextRetryAt` is a scalar `Int64`; `0` means "eligible now" (no
            // cooldown or credential hold pending). A positive value is a
            // deadline (epoch ms) written by `markPendingForRetry` (retry
            // cooldown) or `markCredentialRejected` (credential hold);
            // `fetchPending` excludes the item until that deadline elapses.
            nextRetryAt: entity.nextRetryAt,
            lastErrorCode: entity.lastErrorCode,
            status: entity.status ?? SyncItemStatus.pending.rawValue,
            metadata: metadata
        )
    }

    /// Dispatch order: priority first, then oldest first.
    private static func isDispatchedBefore(_ lhs: StoredSyncItem, _ rhs: StoredSyncItem) -> Bool {
        let lp = priorityRank(lhs.priority)
        let rp = priorityRank(rhs.priority)
        if lp != rp { return lp < rp }
        return lhs.createdAt < rhs.createdAt
    }

    private static func priorityRank(_ raw: String) -> Int {
        switch raw {
        case "HIGH": return 0
        case "NORMAL": return 1
        case "LOW": return 2
        default: return 3
        }
    }
}

// MARK: - Top-level JSON helpers

private func encodeJSON(_ dictionary: [String: String]?) throws -> Data? {
    guard let dictionary = dictionary, !dictionary.isEmpty else { return nil }
    return try JSONSerialization.data(withJSONObject: dictionary, options: [])
}

private func decodeJSON(_ data: Data?) throws -> [String: String]? {
    guard let data = data, !data.isEmpty else { return nil }
    let object = try JSONSerialization.jsonObject(with: data, options: [])
    return object as? [String: String]
}

