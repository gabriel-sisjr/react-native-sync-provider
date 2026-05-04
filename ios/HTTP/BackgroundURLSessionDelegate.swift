//
//  BackgroundURLSessionDelegate.swift
//  SyncProvider
//
//  Delegate retained for the lifetime of the app to receive background-session
//  callbacks even after the process was suspended and resumed. Each pending
//  upload is keyed by `task.taskDescription = SyncItem.id` so we can route the
//  completion back to the storage layer.
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

/// Closure invoked when the system finishes flushing all background events
/// after waking the app via `application(_:handleEventsForBackgroundURLSession:completionHandler:)`.
typealias BackgroundEventsCompletionHandler = () -> Void

/// Delegate for the background `URLSession`. Thread-safe via `NSLock`.
final class BackgroundURLSessionDelegate: NSObject, URLSessionDelegate, URLSessionTaskDelegate, URLSessionDataDelegate, @unchecked Sendable {
    /// Stored response data per task. Background data tasks deliver bytes
    /// incrementally — accumulate them so we can attach to the outcome.
    private let lock = NSLock()
    private var responseData: [Int: Data] = [:]
    private var completions: [String: BackgroundCompletionHandler] = [:]
    private var eventsCompletionHandler: BackgroundEventsCompletionHandler?

    /// Register a per-task completion handler keyed by `task.taskDescription`
    /// (the `SyncItem.id`).
    func registerCompletion(itemId: String, handler: @escaping BackgroundCompletionHandler) {
        lock.lock()
        completions[itemId] = handler
        lock.unlock()
    }

    /// Park the system-supplied completion handler so we can fire it when the
    /// session reports `urlSessionDidFinishEvents(forBackgroundURLSession:)`.
    func storeEventsCompletionHandler(_ handler: @escaping BackgroundEventsCompletionHandler) {
        lock.lock()
        eventsCompletionHandler = handler
        lock.unlock()
    }

    // MARK: - URLSessionDataDelegate

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        responseData[dataTask.taskIdentifier, default: Data()].append(data)
        lock.unlock()
    }

    // MARK: - URLSessionTaskDelegate

    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    didCompleteWithError error: Error?) {
        let itemId = task.taskDescription ?? ""
        let statusCode = (task.response as? HTTPURLResponse)?.statusCode

        lock.lock()
        let handler = completions.removeValue(forKey: itemId)
        responseData.removeValue(forKey: task.taskIdentifier)
        lock.unlock()

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
        lock.lock()
        let handler = eventsCompletionHandler
        eventsCompletionHandler = nil
        lock.unlock()

        if let handler = handler {
            DispatchQueue.main.async(execute: handler)
        }
    }
}
