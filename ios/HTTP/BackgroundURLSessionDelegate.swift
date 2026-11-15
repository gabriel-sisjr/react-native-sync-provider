//
//  BackgroundURLSessionDelegate.swift
//  SyncProvider
//
//  Delegate retained for the lifetime of the app to receive background-session
//  callbacks even after the process was suspended and resumed. Each pending
//  upload is keyed by its task, and carries `task.taskDescription = SyncItem.id`
//  so the completion can be routed back to the storage layer.
//

import Foundation

/// Outcome of a single background URL session task. Forwarded by the
/// dispatcher to the storage / event layer.
struct BackgroundDispatchOutcome: Sendable {
    let itemId: String
    let statusCode: Int?
    let error: Error?
}

/// Closure invoked once per task completion. The dispatcher installs one as
/// soon as it kicks off a background upload.
typealias BackgroundCompletionHandler = @Sendable (BackgroundDispatchOutcome) -> Void

/// Where the dispatcher awaits one background upload. It is resumed exactly
/// once: by the session's completion or by a cancellation of the waiting task,
/// whichever comes first; the other is ignored. Resuming a checked
/// continuation twice traps.
final class BackgroundUploadWait: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<BackgroundDispatchOutcome?, Never>?
    private var isSettled = false

    /// Takes the waiter.
    /// - Returns: `false` when a cancellation came first. The waiter is then
    ///   resumed with `nil` at once, and the upload must not start.
    func install(_ continuation: CheckedContinuation<BackgroundDispatchOutcome?, Never>) -> Bool {
        let settled = lock.withLock { () -> Bool in
            if isSettled { return true }
            self.continuation = continuation
            return false
        }
        if settled {
            continuation.resume(returning: nil)
        }
        return !settled
    }

    /// The session's completion. Ignored after a cancellation.
    func finish(_ outcome: BackgroundDispatchOutcome) {
        settle(outcome)
    }

    /// A cancellation of the waiting task. Ignored after the completion.
    func cancel() {
        settle(nil)
    }

    private func settle(_ outcome: BackgroundDispatchOutcome?) {
        let waiter = lock.withLock { () -> CheckedContinuation<BackgroundDispatchOutcome?, Never>? in
            guard !isSettled else { return nil }
            isSettled = true
            defer { continuation = nil }
            return continuation
        }
        waiter?.resume(returning: outcome)
    }
}

/// Closure invoked when the system finishes flushing all background events
/// after waking the app via `application(_:handleEventsForBackgroundURLSession:completionHandler:)`.
typealias BackgroundEventsCompletionHandler = () -> Void

/// Delegate for the background `URLSession`. Thread-safe via `NSLock`.
final class BackgroundURLSessionDelegate: NSObject, URLSessionDelegate, URLSessionTaskDelegate, URLSessionDataDelegate, @unchecked Sendable {
    /// Stored response data per task. Background data tasks deliver bytes
    /// incrementally — accumulate them so we can attach to the outcome.
    private let lock = NSLock()
    private var responseData: [Int: Data] = [:]
    /// By task identifier, not by item: a cancelled upload's completion can
    /// arrive after its item was sent again, and must not end the new wait.
    private var completions: [Int: BackgroundCompletionHandler] = [:]
    private var eventsCompletionHandler: BackgroundEventsCompletionHandler?

    /// Register the completion handler of `task`.
    func registerCompletion(for task: URLSessionTask, handler: @escaping BackgroundCompletionHandler) {
        lock.withLock {
            completions[task.taskIdentifier] = handler
        }
    }

    /// Drop the completion handler of `task`, if it was not called. Its
    /// completion is then ignored.
    func unregisterCompletion(for task: URLSessionTask) {
        lock.withLock {
            _ = completions.removeValue(forKey: task.taskIdentifier)
        }
    }

    /// Park the system-supplied completion handler so we can fire it when the
    /// session reports `urlSessionDidFinishEvents(forBackgroundURLSession:)`.
    func storeEventsCompletionHandler(_ handler: @escaping BackgroundEventsCompletionHandler) {
        lock.withLock {
            eventsCompletionHandler = handler
        }
    }

    // MARK: - URLSessionDataDelegate

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.withLock {
            responseData[dataTask.taskIdentifier, default: Data()].append(data)
        }
    }

    // MARK: - URLSessionTaskDelegate

    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    didCompleteWithError error: Error?) {
        let itemId = task.taskDescription ?? ""
        let statusCode = (task.response as? HTTPURLResponse)?.statusCode

        let handler = lock.withLock { () -> BackgroundCompletionHandler? in
            let removed = completions.removeValue(forKey: task.taskIdentifier)
            responseData.removeValue(forKey: task.taskIdentifier)
            return removed
        }

        if itemId.isEmpty {
            SyncLogger.error("Background task completed with no taskDescription — cannot route outcome",
                             category: "dispatcher")
            return
        }

        let outcome = BackgroundDispatchOutcome(itemId: itemId,
                                                statusCode: statusCode,
                                                error: error)
        handler?(outcome)
    }

    // MARK: - URLSessionDelegate

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        let handler = lock.withLock { () -> BackgroundEventsCompletionHandler? in
            let parked = eventsCompletionHandler
            eventsCompletionHandler = nil
            return parked
        }

        if let handler = handler {
            DispatchQueue.main.async(execute: handler)
        }
    }
}
