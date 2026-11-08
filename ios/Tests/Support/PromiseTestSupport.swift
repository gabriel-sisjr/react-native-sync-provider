import XCTest
import NitroModules
@testable import SyncProvider

/// A promise outcome written from whichever thread settles the promise.
final class PromiseOutcome<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Result<T, Error>?
    private var count = 0

    /// The outcome, or `nil` while the promise has not settled.
    var value: Result<T, Error>? {
        lock.withLock { stored }
    }

    /// How many listener calls were made. More than one is a bug.
    var calls: Int {
        lock.withLock { count }
    }

    func record(_ result: Result<T, Error>) {
        lock.withLock {
            stored = result
            count += 1
        }
    }
}

extension XCTestCase {
    /// Attaches `.then` and then `.catch` to `promise` the way the generated
    /// glue does, right after the call that returned it, and returns what had
    /// run by the time both were attached. A promise settled before it was
    /// returned runs its listener inside the attach; `nil` means it was still
    /// pending.
    func attachLikeTheBridge<T>(_ promise: Promise<T>) -> PromiseOutcome<T> {
        let outcome = PromiseOutcome<T>()
        promise
            .then { outcome.record(.success($0)) }
            .catch { outcome.record(.failure($0)) }
        return outcome
    }

    /// Attaches listeners the same way, then waits for one of them, failing
    /// the test instead of hanging when none is called within `timeout`
    /// (``livenessTimeout`` by default).
    func settle<T>(_ promise: Promise<T>, timeout: TimeInterval = livenessTimeout) async -> Result<T, Error>? {
        let settled = expectation(description: "the promise settles")
        let outcome = PromiseOutcome<T>()
        promise
            .then { value in
                outcome.record(.success(value))
                settled.fulfill()
            }
            .catch { error in
                outcome.record(.failure(error))
                settled.fulfill()
            }
        await fulfillment(of: [settled], timeout: timeout)
        return outcome.value
    }
}
