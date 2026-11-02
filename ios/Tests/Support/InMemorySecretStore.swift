import Foundation
import Security
@testable import SyncProvider

/// In-memory ``SecretStore``. Hostless XCTest has no Keychain entitlement, so
/// the real store cannot run here.
final class InMemorySecretStore: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var headers: [String: String]?
    private var readFailure: OSStatus?
    private var writeFailure: OSStatus?

    /// The headers currently stored.
    var stored: [String: String]? {
        lock.withLock { headers }
    }

    /// Stores `headers` directly, as a previous install would have.
    func seed(_ headers: [String: String]) {
        lock.withLock { self.headers = headers }
    }

    /// When set, reads fail with this status, as on a device that has not been
    /// unlocked since boot. `nil` restores normal reads.
    func failReads(with status: OSStatus?) {
        lock.withLock { readFailure = status }
    }

    /// When set, writes fail with this status.
    func failWrites(with status: OSStatus?) {
        lock.withLock { writeFailure = status }
    }

    func readDefaultHeaders() -> SecretReadResult {
        lock.withLock {
            if let readFailure {
                return .unavailable(readFailure)
            }
            return headers.map(SecretReadResult.found) ?? .notFound
        }
    }

    func writeDefaultHeaders(_ headers: [String: String]) throws {
        try lock.withLock {
            if let writeFailure {
                throw SecretStoreError(status: writeFailure)
            }
            self.headers = headers
        }
    }

    func deleteDefaultHeaders() throws {
        lock.withLock { headers = nil }
    }
}
