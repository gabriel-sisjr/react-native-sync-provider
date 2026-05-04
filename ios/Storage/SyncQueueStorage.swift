//
//  SyncQueueStorage.swift
//  SyncProvider
//
//  High-level facade over `SyncItemDao`. Owns:
//    1. ULID assignment for new items.
//    2. The queue cap enforcement (default 10_000) via `QUEUE_FULL`.
//    3. Materialization of `SyncItemInput` (Nitro) into the storage record.
//

import Foundation

/// Default queue capacity. Configurable via `SyncOptions.maxQueueSize`.
let kDefaultQueueCapacity: Int = 10_000

/// `actor` so the cap check + insert are atomic from the caller's POV. We keep
/// a serial barrier across `enqueue` calls; reads (`getSize`, `getPending`,
/// etc.) are also serialized through the actor to keep the API simple.
actor SyncQueueStorage {
    private let dao: SyncItemDao
    private(set) var capacity: Int

    init(dao: SyncItemDao = SyncItemDao(), capacity: Int = kDefaultQueueCapacity) {
        self.dao = dao
        self.capacity = capacity
    }

    func updateCapacity(_ newCapacity: Int) {
        capacity = max(1, newCapacity)
    }

    // MARK: - Mutations

    /// Enqueue a single item. Returns the assigned ULID.
    /// - Throws: `SyncProviderError(.queueFull)` if the cap would be exceeded.
    func enqueue(input: SyncItemInputData) async throws -> String {
        let currentCount = try await dao.countPending()
        guard currentCount < capacity else {
            throw SyncProviderError.queueFull(limit: capacity)
        }

        let stored = build(from: input)
        try await dao.insert(stored)
        return stored.id
    }

    /// Enqueue a batch atomically. Returns the assigned ULIDs in input order.
    /// - Throws: `SyncProviderError(.queueFull)` if accepting the whole batch
    ///   would exceed the cap.
    func enqueueBatch(inputs: [SyncItemInputData]) async throws -> [String] {
        guard !inputs.isEmpty else { return [] }
        let currentCount = try await dao.countPending()
        guard currentCount + inputs.count <= capacity else {
            throw SyncProviderError.queueFull(limit: capacity)
        }
        let stored = inputs.map { build(from: $0) }
        try await dao.insertBatch(stored)
        return stored.map(\.id)
    }

    @discardableResult
    func remove(id: String) async throws -> Bool {
        try await dao.delete(id: id)
    }

    func clear() async throws {
        try await dao.clear()
    }

    // MARK: - Reads

    func getSize() async throws -> Int {
        try await dao.countPending()
    }

    func getPending(limit: Int = 0) async throws -> [StoredSyncItem] {
        try await dao.fetchPending(limit: limit)
    }

    func getAll() async throws -> [StoredSyncItem] {
        try await dao.fetchAll()
    }

    // MARK: - Status mutations

    func markInFlight(id: String) async throws {
        try await dao.markInFlight(id: id)
    }

    func markFailed(id: String, errorCode: String) async throws {
        try await dao.markFailed(id: id, errorCode: errorCode)
    }

    func markPendingForRetry(id: String, errorCode: String?) async throws {
        try await dao.markPendingForRetry(id: id, errorCode: errorCode)
    }

    @discardableResult
    func resetInFlightToPending() async throws -> Int {
        try await dao.resetInFlightToPending()
    }

    // MARK: - History

    func recordResult(_ result: StoredSyncResult) async throws {
        try await dao.insertResult(result)
    }

    func getHistory(limit: Int = 0) async throws -> [StoredSyncResult] {
        try await dao.fetchHistory(limit: limit)
    }

    func clearHistory() async throws {
        try await dao.clearHistory()
    }

    // MARK: - Helpers

    /// Sendable mirror of `SyncItemInput` from the Nitro spec — translated by
    /// `SyncProvider.swift` so this file does not need to import the
    /// generated bridge types.
    struct SyncItemInputData: Sendable, Equatable {
        let method: String
        let url: String
        let headers: [String: String]?
        let body: String?
        let contentType: String?
        let priority: String
        let metadata: [String: String]?
    }

    private func build(from input: SyncItemInputData) -> StoredSyncItem {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        return StoredSyncItem(
            id: ULID.generate(),
            method: input.method,
            url: input.url,
            headers: input.headers,
            body: input.body,
            contentType: input.contentType,
            priority: input.priority,
            createdAt: nowMs,
            attempts: 0,
            lastAttemptAt: nil,
            lastErrorCode: nil,
            status: SyncItemStatus.pending.rawValue,
            metadata: input.metadata
        )
    }
}
