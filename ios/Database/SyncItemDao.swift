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
    let attempts: Int32
    let lastAttemptAt: Int64?
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

/// Asynchronous CRUD wrapper around `SyncItemEntity` / `SyncResultEntity`.
final class SyncItemDao {
    private let stack: CoreDataStack

    init(stack: CoreDataStack = .shared) {
        self.stack = stack
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
        entity.lastAttemptAt = item.lastAttemptAt.map { NSNumber(value: $0) }
        entity.lastErrorCode = item.lastErrorCode
        entity.status = item.status
        entity.metadataJSON = try encodeJSON(item.metadata)
    }

    // MARK: - Reads

    /// Total number of pending items (status `PENDING`). Used to enforce the
    /// queue cap.
    func countPending() async throws -> Int {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<NSNumber>(entityName: "SyncItemEntity")
            request.resultType = .countResultType
            request.predicate = NSPredicate(format: "status == %@", SyncItemStatus.pending.rawValue)
            let count = try context.count(for: request)
            return count
        }
    }

    /// Fetch pending items sorted by priority (HIGH, NORMAL, LOW) and `createdAt` ASC.
    /// - Parameter limit: 0 means "no limit".
    ///
    /// `priority` is stored as a `String`, so we sort in-memory via the
    /// `priorityRank` helper. `createdAt` ASC is the secondary sort and is
    /// applied via the SQL store directly to keep the in-memory work minimal.
    func fetchPending(limit: Int = 0) async throws -> [StoredSyncItem] {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
            request.predicate = NSPredicate(format: "status == %@", SyncItemStatus.pending.rawValue)
            request.sortDescriptors = [
                NSSortDescriptor(key: "createdAt", ascending: true)
            ]
            let entities = try context.fetch(request)
            let materialized = try entities.map(Self.materialize)
            let sorted = materialized.sorted { lhs, rhs in
                let lp = Self.priorityRank(lhs.priority)
                let rp = Self.priorityRank(rhs.priority)
                if lp != rp { return lp < rp }
                return lhs.createdAt < rhs.createdAt
            }
            if limit > 0 {
                return Array(sorted.prefix(limit))
            }
            return sorted
        }
    }

    /// Fetch every item (any status). Used by `getPendingItems()` facade —
    /// "pending" semantics are documented as "queue contents".
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

    /// Find a single item by id.
    func find(id: String) async throws -> StoredSyncItem? {
        try await stack.performBackgroundTask { context in
            guard let entity = try Self.fetchEntity(id: id, in: context) else { return nil }
            return try Self.materialize(entity)
        }
    }

    // MARK: - Updates

    func markInFlight(id: String) async throws {
        try await stack.performBackgroundTask { context in
            guard let entity = try Self.fetchEntity(id: id, in: context) else { return }
            entity.status = SyncItemStatus.inFlight.rawValue
            entity.attempts += 1
            entity.lastAttemptAt = Int64(Date().timeIntervalSince1970 * 1000)
        }
    }

    func markFailed(id: String, errorCode: String) async throws {
        try await stack.performBackgroundTask { context in
            guard let entity = try Self.fetchEntity(id: id, in: context) else { return }
            entity.status = SyncItemStatus.failed.rawValue
            entity.lastErrorCode = errorCode
        }
    }

    /// Reset to PENDING (for retry), bumping the lastErrorCode and timestamp.
    func markPendingForRetry(id: String, errorCode: String?) async throws {
        try await stack.performBackgroundTask { context in
            guard let entity = try Self.fetchEntity(id: id, in: context) else { return }
            entity.status = SyncItemStatus.pending.rawValue
            entity.lastErrorCode = errorCode
            entity.lastAttemptAt = Int64(Date().timeIntervalSince1970 * 1000)
        }
    }

    /// Reset every IN_FLIGHT item back to PENDING. Used by RecoveryManager on
    /// app launch.
    @discardableResult
    func resetInFlightToPending() async throws -> Int {
        try await stack.performBackgroundTask { context in
            let request = NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
            request.predicate = NSPredicate(format: "status == %@", SyncItemStatus.inFlight.rawValue)
            let entities = try context.fetch(request)
            for entity in entities {
                entity.status = SyncItemStatus.pending.rawValue
            }
            return entities.count
        }
    }

    // MARK: - Deletes

    /// Delete a single item by id. Returns `true` if a row was removed.
    @discardableResult
    func delete(id: String) async throws -> Bool {
        try await stack.performBackgroundTask { context in
            guard let entity = try Self.fetchEntity(id: id, in: context) else { return false }
            context.delete(entity)
            return true
        }
    }

    /// Delete every item.
    func clear() async throws {
        try await stack.performBackgroundTask { context in
            let fetch: NSFetchRequest<NSFetchRequestResult> = NSFetchRequest(entityName: "SyncItemEntity")
            let batch = NSBatchDeleteRequest(fetchRequest: fetch)
            batch.resultType = .resultTypeStatusOnly
            _ = try context.execute(batch)
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

    private static func fetchEntity(id: String, in context: NSManagedObjectContext) throws -> SyncItemEntity? {
        let request = NSFetchRequest<SyncItemEntity>(entityName: "SyncItemEntity")
        request.predicate = NSPredicate(format: "id == %@", id)
        request.fetchLimit = 1
        return try context.fetch(request).first
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
            lastAttemptAt: entity.lastAttemptAt?.int64Value,
            lastErrorCode: entity.lastErrorCode,
            status: entity.status ?? SyncItemStatus.pending.rawValue,
            metadata: metadata
        )
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

