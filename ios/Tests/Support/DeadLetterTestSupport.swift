import Foundation
@testable import SyncProvider

extension SyncQueueStorage {
    /// The dead letter with `id`, or `nil` when there is none.
    func deadLetter(_ id: String) async throws -> StoredDeadLetter? {
        try await getDeadLetters().first { $0.id == id }
    }

    /// `true` while `id` is a row of the queue store, in any status.
    func isQueued(_ id: String) async throws -> Bool {
        try await getAll().contains { $0.id == id }
    }
}
