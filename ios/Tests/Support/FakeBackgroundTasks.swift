import BackgroundTasks
import Foundation
@testable import SyncProvider

/// Records scheduling calls so tests can assert what would have been asked of
/// the OS, without touching `BGTaskScheduler.shared` (which rejects unregistered
/// identifiers and traps on a duplicate registration).
final class FakeBackgroundTaskScheduler: BackgroundTaskScheduling, @unchecked Sendable {
    private let lock = NSLock()
    private var registered: [String: (BackgroundTaskHandle) -> Void] = [:]
    private var registrationCount = 0
    private var submitted: [String] = []
    private var cancelled: [String] = []

    /// When set, `submit(_:)` throws it. Exercises the error-mapping path.
    var submitError: Error?
    /// Runs once, right after both identifiers were cancelled. Stands in for
    /// the OS launching a task at the worst possible moment.
    var onCancelled: (() -> Void)?

    var registeredIdentifiers: [String] { lock.withLock { Array(registered.keys).sorted() } }
    var registrationCalls: Int { lock.withLock { registrationCount } }
    var submittedIdentifiers: [String] { lock.withLock { submitted } }
    var cancelledIdentifiers: [String] { lock.withLock { cancelled } }

    @discardableResult
    func registerHandler(forTaskWithIdentifier identifier: String,
                         launchHandler: @escaping (BackgroundTaskHandle) -> Void) -> Bool {
        lock.withLock {
            registered[identifier] = launchHandler
            registrationCount += 1
        }
        return true
    }

    func submit(_ taskRequest: BGTaskRequest) throws {
        if let submitError { throw submitError }
        lock.withLock { submitted.append(taskRequest.identifier) }
    }

    func cancel(taskRequestWithIdentifier identifier: String) {
        let hook = lock.withLock { () -> (() -> Void)? in
            cancelled.append(identifier)
            guard cancelled.count >= 2 else { return nil }
            defer { onCancelled = nil }
            return onCancelled
        }
        hook?()
    }

    func reset() {
        lock.withLock {
            submitted.removeAll()
            cancelled.removeAll()
        }
    }

    /// Fires a registered handler, as the OS would.
    func launch(_ identifier: String, task: BackgroundTaskHandle) {
        let handler = lock.withLock { registered[identifier] }
        handler?(task)
    }
}

/// Stand-in for `BGTask` (which has no public initializer) so handler
/// completion and re-schedule behavior can be asserted in unit tests.
final class FakeBackgroundTask: BackgroundTaskHandle, @unchecked Sendable {
    private let lock = NSLock()
    var expirationHandler: (() -> Void)?
    private var completed: [Bool] = []
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Every `success` value passed to `setTaskCompleted`, in order.
    var completedResults: [Bool] { lock.withLock { completed } }

    func setTaskCompleted(success: Bool) {
        let resumed = lock.withLock { () -> [CheckedContinuation<Void, Never>] in
            completed.append(success)
            defer { waiters.removeAll() }
            return waiters
        }
        resumed.forEach { $0.resume() }
    }

    /// Returns once the handler has completed this task.
    func waitForCompletion() async {
        await withCheckedContinuation { continuation in
            let isDone = lock.withLock { () -> Bool in
                if !completed.isEmpty { return true }
                waiters.append(continuation)
                return false
            }
            if isDone {
                continuation.resume()
            }
        }
    }
}
