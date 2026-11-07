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

    /// Removes the queued item with `id`, or else the dead letter with it.
    /// `true` when either store deleted a row.
    @discardableResult
    func remove(id: String) async throws -> Bool {
        try await dao.delete(id: id)
    }

    /// Removes every item, queued or dead-lettered.
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

    /// Items waiting to be dispatched: every `PENDING` row, cooling or not.
    func getQueued() async throws -> [StoredSyncItem] {
        try await dao.fetchQueued()
    }

    /// Every row, in any status.
    func getAll() async throws -> [StoredSyncItem] {
        try await dao.fetchAll()
    }

    /// When the retry timer must wake for rows that carry a deadline
    /// (`nextRetryAt > 0`: a retry cooldown or a credential hold), or `nil`
    /// when none is waiting.
    func earliestRetryAt() async throws -> Int64? {
        try await dao.earliestRetryAt()
    }

    /// The earliest due time across every `PENDING` row, or `nil` for an empty
    /// queue. `0` means a row with no deadline pending (eligible now) is
    /// waiting.
    func earliestPendingAt() async throws -> Int64? {
        try await dao.earliestPendingAt()
    }

    // MARK: - Status mutations

    func markInFlight(id: String) async throws {
        try await dao.markInFlight(id: id)
    }

    /// See ``SyncItemDao/moveToDeadLetters(id:errorCode:errorMessage:)``.
    @discardableResult
    func moveToDeadLetters(id: String, errorCode: String, errorMessage: String?) async throws -> Bool {
        try await dao.moveToDeadLetters(id: id, errorCode: errorCode, errorMessage: errorMessage)
    }

    /// Every dead letter, `deadLetteredAt` ascending, then `id` ascending.
    func getDeadLetters() async throws -> [StoredDeadLetter] {
        try await dao.fetchDeadLetters()
    }

    /// Puts the dead letter `id` back in the queue as a fresh item. `false`
    /// when nothing changed: no dead letter has that id, or a queue row
    /// already has it.
    /// - Throws: `SyncProviderError(.queueFull)` when the queue is at its cap;
    ///   the dead letter stays.
    @discardableResult
    func replayDeadLetter(id: String) async throws -> Bool {
        switch try await dao.replayDeadLetter(id: id, capacity: capacity) {
        case .replayed:
            return true
        case .unknown, .alreadyQueued:
            return false
        case .queueFull:
            throw SyncProviderError.queueFull(limit: capacity)
        }
    }

    /// Deletes the dead letter `id`. `false` when there is none.
    @discardableResult
    func discardDeadLetter(id: String) async throws -> Bool {
        try await dao.deleteDeadLetter(id: id)
    }

    /// Reset an item to PENDING for retry and persist its backoff cooldown.
    ///
    /// - Parameters:
    ///   - errorCode: the failure code to record on the item.
    ///   - retryDelayMs: delay (ms) before the item is dispatchable again,
    ///     computed by ``RetryPolicyEvaluator`` so a degraded server cannot
    ///     hot-loop. There is no default: a caller must not be able to skip the
    ///     cooldown by omission.
    func markPendingForRetry(id: String, errorCode: String?, retryDelayMs: Int) async throws {
        try await dao.markPendingForRetry(id: id, errorCode: errorCode, retryDelayMs: retryDelayMs)
    }

    /// Put back an item rejected for a stale persisted credential, refunding
    /// its attempt. See ``SyncItemDao/markCredentialRejected(id:errorCode:retryDelayMs:attempts:)``.
    @discardableResult
    func markCredentialRejected(id: String,
                                errorCode: String?,
                                retryDelayMs: Int,
                                attempts: Int32) async throws -> Int64 {
        try await dao.markCredentialRejected(id: id,
                                             errorCode: errorCode,
                                             retryDelayMs: retryDelayMs,
                                             attempts: attempts)
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
            // `0` means "eligible now": no cooldown or credential hold is
            // pending. `markPendingForRetry` writes a positive deadline on a
            // retriable failure (the persisted retry cooldown), and
            // `markCredentialRejected` writes one for a credential hold.
            nextRetryAt: 0,
            lastErrorCode: nil,
            status: SyncItemStatus.pending.rawValue,
            metadata: input.metadata
        )
    }
}
