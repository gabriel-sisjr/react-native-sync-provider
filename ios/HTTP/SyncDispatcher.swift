//
//  SyncDispatcher.swift
//  SyncProvider
//
//  Drives the actual HTTP work. Owns:
//    - One `URLSession(configuration: .default)` for in-app flushes.
//    - One `URLSession(configuration: .background(withIdentifier:))` for
//      OS-driven sync (BGTask handlers + iOS-resumed background uploads).
//    - The `flush` cycle: drains the queue, applies retry policy, records
//      results, emits events.
//

import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// Identifier for the iOS background `URLSession`. Must match the value the
/// AppDelegate forwards from `application(_:handleEventsForBackgroundURLSession:completionHandler:)`.
let kBackgroundURLSessionIdentifier = "com.gabriel-sisjr.syncprovider.background-upload"

/// Knobs the dispatcher reads on each cycle. Updated by the bridge whenever
/// `configureSync` is called.
struct DispatcherConfig: Sendable {
    var retryPolicy: RetryPolicyValue
    var requestTimeoutMs: Int
    var batchSize: Int
    var defaultHeaders: [String: String]

    static let `default` = DispatcherConfig(
        retryPolicy: .default,
        requestTimeoutMs: 30_000,
        batchSize: 25,
        defaultHeaders: [:]
    )
}

/// Result of dispatching a single item. Used by the flush loop to record the
/// aggregate `SyncResult`.
struct ItemDispatchOutcome: Sendable {
    let itemId: String
    let succeeded: Bool
    let statusCode: Int?
    let errorCode: String?
    let errorMessage: String?
}

/// The dispatcher coordinates queue draining, retries, and result/event
/// publication. Marked `actor` so the in-flight set + cycle flag are
/// serialized without explicit locks.
actor SyncDispatcher {
    // MARK: - Dependencies

    private let storage: SyncQueueStorage
    private let emitter: SyncEventEmitter
    private let connectivity: ConnectivityMonitor
    private let foregroundSession: URLSession
    private let backgroundSession: URLSession
    private let backgroundDelegate: BackgroundURLSessionDelegate

    // MARK: - State

    private var config: DispatcherConfig
    private var inFlight: Set<String> = []
    private var isCycleRunning = false
    private var isPaused = false
    private(set) var lastResult: StoredSyncResult?

    // MARK: - Init

    init(storage: SyncQueueStorage,
         emitter: SyncEventEmitter,
         connectivity: ConnectivityMonitor = .shared,
         config: DispatcherConfig = .default) {
        self.storage = storage
        self.emitter = emitter
        self.connectivity = connectivity
        self.config = config

        let foreground = URLSessionConfiguration.default
        foreground.timeoutIntervalForRequest = TimeInterval(config.requestTimeoutMs) / 1000.0
        foreground.requestCachePolicy = .reloadIgnoringLocalCacheData
        foreground.waitsForConnectivity = true
        self.foregroundSession = URLSession(configuration: foreground)

        let backgroundDelegate = BackgroundURLSessionDelegate()
        self.backgroundDelegate = backgroundDelegate

        let background = URLSessionConfiguration.background(withIdentifier: kBackgroundURLSessionIdentifier)
        background.isDiscretionary = false
        background.sessionSendsLaunchEvents = true
        background.requestCachePolicy = .reloadIgnoringLocalCacheData
        background.allowsConstrainedNetworkAccess = true
        background.allowsExpensiveNetworkAccess = true
        self.backgroundSession = URLSession(configuration: background,
                                            delegate: backgroundDelegate,
                                            delegateQueue: nil)
    }

    /// Test-only initializer. Accepts pre-built foreground/background sessions
    /// (typically a `URLSession` configured with a `URLProtocol` stub) so
    /// XCTest cases can drive `dispatchOne`/`flush` without touching the
    /// network. The background session is reused as-is — tests that don't
    /// exercise background uploads should pass an idle `URLSession`.
    internal init(storage: SyncQueueStorage,
                  emitter: SyncEventEmitter,
                  connectivity: ConnectivityMonitor,
                  config: DispatcherConfig,
                  foregroundSession: URLSession,
                  backgroundSession: URLSession,
                  backgroundDelegate: BackgroundURLSessionDelegate = BackgroundURLSessionDelegate()) {
        self.storage = storage
        self.emitter = emitter
        self.connectivity = connectivity
        self.config = config
        self.foregroundSession = foregroundSession
        self.backgroundSession = backgroundSession
        self.backgroundDelegate = backgroundDelegate
    }

    // MARK: - Configuration

    func updateConfig(_ newConfig: DispatcherConfig) {
        self.config = newConfig
    }

    func currentConfig() -> DispatcherConfig {
        return config
    }

    func setPaused(_ paused: Bool) {
        self.isPaused = paused
    }

    func paused() -> Bool {
        return isPaused
    }

    func cycleRunning() -> Bool {
        return isCycleRunning
    }

    // MARK: - Background session glue

    /// Invoked by the host AppDelegate's
    /// `application(_:handleEventsForBackgroundURLSession:completionHandler:)`.
    nonisolated func handleBackgroundURLSessionEvents(identifier: String,
                                                      completionHandler: @escaping () -> Void) {
        guard identifier == kBackgroundURLSessionIdentifier else {
            // Not ours — invoke the handler so the system does not stall.
            completionHandler()
            return
        }
        backgroundDelegate.storeEventsCompletionHandler(completionHandler)
    }

    // MARK: - Flush

    /// Run one flush cycle. Drains every pending item with respect to retry
    /// policy and emits the aggregate result. Multiple concurrent calls are
    /// coalesced — the second call awaits the first.
    @discardableResult
    func flush() async throws -> StoredSyncResult {
        if isPaused {
            SyncLogger.debug("flush() called while paused; returning empty result", category: "dispatcher")
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            return StoredSyncResult(id: UUID().uuidString,
                                    startedAt: now,
                                    finishedAt: now,
                                    itemsAttempted: 0,
                                    itemsSucceeded: 0,
                                    itemsFailed: 0,
                                    errorMessage: "paused")
        }
        if isCycleRunning {
            SyncLogger.debug("flush() called while cycle in progress; piggybacking", category: "dispatcher")
            // Wait briefly then return the last known result.
            return lastResult ?? Self.emptyResult()
        }

        isCycleRunning = true
        defer { isCycleRunning = false }

        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)
        emitter.emit(.now(type: "SYNC_STARTED"))

        let pending = try await storage.getPending(limit: max(config.batchSize, 1))
        if pending.isEmpty {
            let result = StoredSyncResult(id: UUID().uuidString,
                                          startedAt: startedAt,
                                          finishedAt: Int64(Date().timeIntervalSince1970 * 1000),
                                          itemsAttempted: 0,
                                          itemsSucceeded: 0,
                                          itemsFailed: 0,
                                          errorMessage: nil)
            try await storage.recordResult(result)
            self.lastResult = result
            emitter.emit(.now(type: "SYNC_SUCCEEDED",
                              metadata: ["attempted": "0",
                                         "succeeded": "0",
                                         "failed": "0"]))
            return result
        }

        var succeededIds: [String] = []
        var failedIds: [String] = []
        var firstError: String?
        let total = pending.count

        for (index, item) in pending.enumerated() {
            if isPaused { break }
            inFlight.insert(item.id)
            try await storage.markInFlight(id: item.id)

            let outcome = await dispatchOne(item: item)
            inFlight.remove(item.id)

            // Progress event.
            let progress = Double(index + 1) / Double(total)
            emitter.emit(.now(type: "SYNC_PROGRESS",
                              progress: progress,
                              metadata: ["index": String(index + 1),
                                         "total": String(total)]))

            if outcome.succeeded {
                succeededIds.append(item.id)
                try await storage.remove(id: item.id)
                emitter.emit(.now(type: "ITEM_SUCCEEDED",
                                  itemId: item.id,
                                  statusCode: outcome.statusCode.map(Double.init),
                                  attempt: Double(item.attempts + 1)))
            } else {
                let attemptsAfter = item.attempts + 1
                let isRetryable = decideRetry(outcome: outcome, statusCode: outcome.statusCode)
                let exhausted = attemptsAfter >= Int32(config.retryPolicy.maxAttempts)

                if isRetryable && !exhausted {
                    try await storage.markPendingForRetry(id: item.id, errorCode: outcome.errorCode)
                    emitter.emit(.now(type: "ITEM_RETRYING",
                                      itemId: item.id,
                                      errorCode: outcome.errorCode,
                                      statusCode: outcome.statusCode.map(Double.init),
                                      attempt: Double(attemptsAfter)))
                } else {
                    failedIds.append(item.id)
                    let finalErrorCode = exhausted ? "MAX_ATTEMPTS_EXCEEDED" : (outcome.errorCode ?? "NETWORK_ERROR")
                    try await storage.markFailed(id: item.id, errorCode: finalErrorCode)
                    emitter.emit(.now(type: "ITEM_FAILED",
                                      itemId: item.id,
                                      errorCode: finalErrorCode,
                                      statusCode: outcome.statusCode.map(Double.init),
                                      attempt: Double(attemptsAfter)))
                    if firstError == nil {
                        firstError = outcome.errorMessage ?? finalErrorCode
                    }
                }
            }
        }

        let finishedAt = Int64(Date().timeIntervalSince1970 * 1000)
        let result = StoredSyncResult(id: UUID().uuidString,
                                      startedAt: startedAt,
                                      finishedAt: finishedAt,
                                      itemsAttempted: Int32(pending.count),
                                      itemsSucceeded: Int32(succeededIds.count),
                                      itemsFailed: Int32(failedIds.count),
                                      errorMessage: firstError)
        try await storage.recordResult(result)
        self.lastResult = result

        if failedIds.isEmpty {
            emitter.emit(.now(type: "SYNC_SUCCEEDED",
                              metadata: ["attempted": String(pending.count),
                                         "succeeded": String(succeededIds.count)]))
        } else {
            emitter.emit(.now(type: "SYNC_FAILED",
                              metadata: ["attempted": String(pending.count),
                                         "succeeded": String(succeededIds.count),
                                         "failed": String(failedIds.count)]))
        }

        return result
    }

    // MARK: - Single-item dispatch

    private func dispatchOne(item: StoredSyncItem) async -> ItemDispatchOutcome {
        guard let url = URL(string: item.url), url.scheme?.hasPrefix("http") == true else {
            return ItemDispatchOutcome(itemId: item.id,
                                       succeeded: false,
                                       statusCode: nil,
                                       errorCode: "INVALID_URL",
                                       errorMessage: "Invalid URL: \(item.url)")
        }

        var request = URLRequest(url: url)
        request.httpMethod = item.method
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = TimeInterval(config.requestTimeoutMs) / 1000.0

        // Default headers first, item headers can override.
        for (key, value) in config.defaultHeaders {
            request.setValue(value, forHTTPHeaderField: key)
        }
        if let headers = item.headers {
            for (key, value) in headers {
                request.setValue(value, forHTTPHeaderField: key)
            }
        }
        if let contentType = item.contentType,
           request.value(forHTTPHeaderField: "Content-Type") == nil {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }

        let bodyData = (item.body ?? "").data(using: .utf8)

        let useBackground = await shouldUseBackgroundSession()

        if useBackground, let bodyData = bodyData {
            return await dispatchOnBackground(request: request, body: bodyData, item: item)
        }
        return await dispatchOnForeground(request: request, body: bodyData, item: item)
    }

    private func dispatchOnForeground(request: URLRequest,
                                      body: Data?,
                                      item: StoredSyncItem) async -> ItemDispatchOutcome {
        var requestCopy = request
        if let body = body, !body.isEmpty {
            requestCopy.httpBody = body
        }

        do {
            let (_, response) = try await foregroundSession.data(for: requestCopy)
            let statusCode = (response as? HTTPURLResponse)?.statusCode
            return classifyResponse(itemId: item.id, statusCode: statusCode, transportError: nil)
        } catch {
            return classifyResponse(itemId: item.id, statusCode: nil, transportError: error)
        }
    }

    private func dispatchOnBackground(request: URLRequest,
                                      body: Data,
                                      item: StoredSyncItem) async -> ItemDispatchOutcome {
        // Background `URLSession` requires the request body to be on disk.
        let tmpURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("syncprovider-\(item.id).body")
        do {
            try body.write(to: tmpURL, options: .atomic)
        } catch {
            return classifyResponse(itemId: item.id, statusCode: nil, transportError: error)
        }

        let task = backgroundSession.uploadTask(with: request, fromFile: tmpURL)
        task.taskDescription = item.id

        return await withCheckedContinuation { (continuation: CheckedContinuation<ItemDispatchOutcome, Never>) in
            backgroundDelegate.registerCompletion(itemId: item.id) { [weak self] outcome in
                // Best-effort cleanup of the body file.
                try? FileManager.default.removeItem(at: tmpURL)
                let result = self?.classifyResponseSync(itemId: outcome.itemId,
                                                        statusCode: outcome.statusCode,
                                                        transportError: outcome.error)
                    ?? ItemDispatchOutcome(itemId: outcome.itemId,
                                           succeeded: false,
                                           statusCode: outcome.statusCode,
                                           errorCode: "NETWORK_ERROR",
                                           errorMessage: outcome.error?.localizedDescription)
                continuation.resume(returning: result)
            }
            task.resume()
        }
    }

    private func shouldUseBackgroundSession() async -> Bool {
        #if os(iOS)
        let state = await MainActor.run {
            UIApplication.shared.applicationState
        }
        return state != .active
        #else
        return false
        #endif
    }

    // MARK: - Classification

    private func classifyResponse(itemId: String,
                                  statusCode: Int?,
                                  transportError: Error?) -> ItemDispatchOutcome {
        return classifyResponseSync(itemId: itemId,
                                    statusCode: statusCode,
                                    transportError: transportError)
    }

    /// Same logic as `classifyResponse`, exposed as `nonisolated`-friendly so
    /// the background delegate can call it from any thread.
    nonisolated func classifyResponseSync(itemId: String,
                                          statusCode: Int?,
                                          transportError: Error?) -> ItemDispatchOutcome {
        if let error = transportError {
            let wrapped = wrapAsSyncProviderError(error)
            return ItemDispatchOutcome(itemId: itemId,
                                       succeeded: false,
                                       statusCode: statusCode,
                                       errorCode: wrapped.code.stringValue,
                                       errorMessage: wrapped.message)
        }

        guard let status = statusCode else {
            return ItemDispatchOutcome(itemId: itemId,
                                       succeeded: false,
                                       statusCode: nil,
                                       errorCode: "NETWORK_ERROR",
                                       errorMessage: "No HTTP response received")
        }

        if (200..<300).contains(status) {
            return ItemDispatchOutcome(itemId: itemId,
                                       succeeded: true,
                                       statusCode: status,
                                       errorCode: nil,
                                       errorMessage: nil)
        }

        if status == 401 || status == 403 {
            return ItemDispatchOutcome(itemId: itemId,
                                       succeeded: false,
                                       statusCode: status,
                                       errorCode: "UNAUTHORIZED",
                                       errorMessage: "HTTP \(status)")
        }

        return ItemDispatchOutcome(itemId: itemId,
                                   succeeded: false,
                                   statusCode: status,
                                   errorCode: "SERVER_ERROR",
                                   errorMessage: "HTTP \(status)")
    }

    /// Should we retry the failed outcome?
    private func decideRetry(outcome: ItemDispatchOutcome, statusCode: Int?) -> Bool {
        if outcome.errorCode == "UNAUTHORIZED" || outcome.errorCode == "INVALID_URL" {
            return false
        }
        if outcome.errorCode == "NETWORK_ERROR" || outcome.errorCode == "TIMEOUT" {
            return true
        }
        if let status = statusCode {
            return RetryPolicyEvaluator.shouldRetry(statusCode: status, policy: config.retryPolicy)
        }
        return false
    }

    // MARK: - Helpers

    static func emptyResult() -> StoredSyncResult {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return StoredSyncResult(id: UUID().uuidString,
                                startedAt: now,
                                finishedAt: now,
                                itemsAttempted: 0,
                                itemsSucceeded: 0,
                                itemsFailed: 0,
                                errorMessage: nil)
    }
}
