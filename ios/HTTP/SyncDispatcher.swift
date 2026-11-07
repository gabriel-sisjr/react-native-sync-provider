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

/// The least a persisted credential is left alone after a rejection. `attempts`
/// does not advance on one, so backoff alone would stay at its first value.
let kMinCredentialHoldMs = 60_000

/// How long a flush behind a network-quality gate waits for the process's
/// first path update. A BGTask launch flushes before it has one, and judging
/// that unknown path would gate it as offline.
let kFirstPathUpdateWaitMs = 1_500

/// Knobs the dispatcher reads on each cycle. Updated by the bridge whenever
/// `configureSync` is called.
struct DispatcherConfig: Sendable {
    var retryPolicy: RetryPolicyValue
    var requestTimeoutMs: Int
    var batchSize: Int
    var defaultHeaders: [String: String]
    /// Where this configuration came from. See ``ConfigSource``.
    var source: ConfigSource = .defaults
    /// `true` when the configuration declares default headers, even if they
    /// could not be read back.
    var hasDefaultHeaders: Bool = false
    /// `true` when default headers are declared but unreadable. Dispatch is
    /// suspended: sending without them would fail every item for no reason.
    var credentialsUnavailable: Bool = false
    /// The lowest network quality a cycle may start at. `nil`, or `offline`,
    /// means no gate.
    var minNetworkQuality: NetworkQualityLevel? = nil
    /// Checked with a HEAD before a cycle claims its first item. `nil`: no
    /// health check.
    var healthCheckUrl: URL? = nil
    /// How long a 2xx spares the next health-check HEAD.
    var healthCheckTtlMs: Int64 = SyncOptionsSnapshot.defaultHealthCheckTtlMs
    /// How long cycles are skipped, without a HEAD, after a failed check.
    var healthCheckBackoffMs: Int64 = SyncOptionsSnapshot.defaultHealthCheckBackoffMs

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

/// What ``SyncDispatcher/recordFailureOutcome(item:outcome:sentWith:)`` decided to do
/// with a failed item, so callers can update their aggregate counters.
enum FailureDisposition: Sendable {
    /// Kept `PENDING` for another attempt; a retry cooldown (`nextRetryAt`)
    /// was persisted. `attempt` is the 1-based attempt that just failed.
    case retrying(attempt: Int32)
    /// Marked terminally `FAILED` (non-retryable, or attempts exhausted).
    case failed(errorCode: String, message: String?)
    /// Rejected with 401/403 while running on a persisted credential. Kept
    /// `PENDING` with its attempt refunded; the cycle must stop claiming items,
    /// and no wake may come before `heldUntil`.
    case credentialRejected(heldUntil: Int64)
}

/// Why a flush started no cycle.
enum FlushSkipReason: Sendable, Equatable {
    case paused
    case credentialsUnavailable
    case networkQualityTooLow
    case healthCheckFailed
}

/// What one ``SyncDispatcher/flushOutcome()`` call did.
enum FlushOutcome: Sendable {
    /// A cycle ran, or the call joined the one already running.
    case ran(StoredSyncResult)
    /// No cycle ran. The result is the zero-count one a skipped flush returns.
    case skipped(StoredSyncResult, FlushSkipReason)

    var result: StoredSyncResult {
        switch self {
        case let .ran(result), let .skipped(result, _):
            return result
        }
    }
}

/// Collaborators of ``SyncDispatcher`` that are set and read from outside the
/// actor, synchronously. `BackgroundSyncManager` publishes `foregroundOnly`
/// from a lock-protected, non-async path, so this cannot be actor state.
final class DispatcherHooks: @unchecked Sendable {
    private let lock = NSLock()
    private var foregroundOnlyValue = false
    private var pausedValue = false
    private var cycleFinished: (@Sendable (Int64?) async -> Void)?
    private var credentialRecovery: (@Sendable () -> [String: String]?)?
    private var qualitySource: (@Sendable () -> NetworkQualityLevel)?
    private var qualityRefresh: (@Sendable () -> Void)?
    private var healthChecker: HealthCheck?
    private var healthFailure: (@Sendable () async -> Void)?

    /// When `true`, uploads never go through the background `URLSession`.
    var foregroundOnly: Bool {
        get { lock.withLock { foregroundOnlyValue } }
        set { lock.withLock { foregroundOnlyValue = newValue } }
    }

    /// When `true`, no cycle runs. Lives here so creating the hybrid can clear
    /// it synchronously: a `pauseSync()` right after must not be overtaken.
    var paused: Bool {
        get { lock.withLock { pausedValue } }
        set { lock.withLock { pausedValue = newValue } }
    }

    /// Awaited when a flush cycle ends, whatever its outcome, so whoever awaits
    /// `flush()` sees its effects. Receives the moment a credential hold ends
    /// when the cycle was stopped by one.
    var onCycleFinished: (@Sendable (Int64?) async -> Void)? {
        get { lock.withLock { cycleFinished } }
        set { lock.withLock { cycleFinished = newValue } }
    }

    /// Reads the default headers again when they were unavailable at start.
    /// Returns `nil` when they still cannot be read.
    var recoverCredentials: (@Sendable () -> [String: String]?)? {
        get { lock.withLock { credentialRecovery } }
        set { lock.withLock { credentialRecovery = newValue } }
    }

    /// The engine's current network-quality grade. Without it the gate grades
    /// the link alone, as if no measurement had been taken.
    var networkQuality: (@Sendable () -> NetworkQualityLevel)? {
        get { lock.withLock { qualitySource } }
        set { lock.withLock { qualitySource = newValue } }
    }

    /// Called on every gate evaluation, so a stale measurement is taken again
    /// in the background. The evaluation itself uses the grade as it is.
    var refreshStaleNetworkQuality: (@Sendable () -> Void)? {
        get { lock.withLock { qualityRefresh } }
        set { lock.withLock { qualityRefresh = newValue } }
    }

    /// The engine's health check. Without it no health check runs, whatever
    /// the configuration says.
    var healthCheck: HealthCheck? {
        get { lock.withLock { healthChecker } }
        set { lock.withLock { healthChecker = newValue } }
    }

    /// Awaited after a flush the health check stopped, so the next retry wake
    /// can be armed no earlier than the end of the backoff.
    var onHealthCheckFailed: (@Sendable () async -> Void)? {
        get { lock.withLock { healthFailure } }
        set { lock.withLock { healthFailure = newValue } }
    }
}

/// The dispatcher coordinates queue draining, retries, and result/event
/// publication. Marked `actor` so the in-flight set + cycle flag are
/// serialized without explicit locks.
actor SyncDispatcher {
    // MARK: - Dependencies

    private let storage: SyncQueueStorage
    private let emitter: SyncEventEmitter
    private let connectivity: ConnectivityProviding
    private let foregroundSession: URLSession
    private let backgroundSession: URLSession
    private let backgroundDelegate: BackgroundURLSessionDelegate
    private let isAppActive: @Sendable () async -> Bool

    /// See ``DispatcherHooks``.
    nonisolated let hooks = DispatcherHooks()

    // MARK: - State

    private var config: DispatcherConfig
    private var inFlight: Set<String> = []
    private var isCycleRunning = false
    private var credentialHeldUntil: Int64?
    private(set) var lastResult: StoredSyncResult?
    private var isPaused: Bool { hooks.paused }

    // MARK: - Init

    init(storage: SyncQueueStorage,
         emitter: SyncEventEmitter,
         connectivity: ConnectivityProviding = ConnectivityMonitor.shared,
         config: DispatcherConfig = .default,
         paused: Bool = false) {
        self.storage = storage
        self.emitter = emitter
        self.connectivity = connectivity
        self.config = config
        self.hooks.paused = paused
        self.isAppActive = Self.applicationIsActive

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
                  connectivity: ConnectivityProviding,
                  config: DispatcherConfig,
                  foregroundSession: URLSession,
                  backgroundSession: URLSession,
                  backgroundDelegate: BackgroundURLSessionDelegate = BackgroundURLSessionDelegate(),
                  paused: Bool = false,
                  isAppActive: (@Sendable () async -> Bool)? = nil) {
        self.storage = storage
        self.emitter = emitter
        self.connectivity = connectivity
        self.config = config
        self.foregroundSession = foregroundSession
        self.backgroundSession = backgroundSession
        self.backgroundDelegate = backgroundDelegate
        self.hooks.paused = paused
        self.isAppActive = isAppActive ?? Self.applicationIsActive
    }

    // MARK: - Configuration

    func updateConfig(_ newConfig: DispatcherConfig) {
        self.config = newConfig
    }

    func currentConfig() -> DispatcherConfig {
        return config
    }

    func setPaused(_ paused: Bool) {
        hooks.paused = paused
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

    /// Run one flush cycle: dispatches one batch of eligible items with respect
    /// to the retry policy and emits the aggregate result. A call made while a
    /// cycle is running returns the last result instead of starting another.
    @discardableResult
    func flush() async throws -> StoredSyncResult {
        try await flushOutcome().result
    }

    /// ``flush()``, reporting why no cycle ran when none did.
    ///
    /// The checks before a cycle run in this order: paused, stored credentials,
    /// a cycle already running, the network-quality gate, the health check.
    /// Only the last two emit anything when they stop the flush: `SYNC_FAILED`
    /// with `NETWORK_QUALITY_TOO_LOW` or `HEALTH_CHECK_FAILED`, with no
    /// `SYNC_STARTED` before it, no history row and no item touched. A cycle
    /// already running is joined rather than gated, so its listeners never see
    /// a `SYNC_FAILED` in the middle of it.
    func flushOutcome() async throws -> FlushOutcome {
        if let early = exitBeforeCycle() {
            return early
        }
        // Only suspends while the path is still unknown, and during that wait
        // a pause, a configuration or another flush can land.
        let blockingQuality = await networkQualityBlock()
        if let early = exitBeforeCycle() {
            return early
        }
        if let blockingQuality {
            SyncLogger.info("Flush skipped: network quality \(blockingQuality.rawValue) is below the minimum \(config.minNetworkQuality?.rawValue ?? "-")",
                            category: "dispatcher")
            emitter.emit(.now(type: "SYNC_FAILED", errorCode: "NETWORK_QUALITY_TOO_LOW"))
            return .skipped(Self.skippedResult(reason: "network-quality-too-low"), .networkQualityTooLow)
        }
        // Suspends for the HEAD, if one is sent.
        let unhealthy = await healthCheckFails()
        if let early = exitBeforeCycle() {
            return early
        }
        if unhealthy {
            SyncLogger.info("Flush skipped: the health check failed", category: "dispatcher")
            emitter.emit(.now(type: "SYNC_FAILED", errorCode: "HEALTH_CHECK_FAILED"))
            await hooks.onHealthCheckFailed?()
            return .skipped(Self.skippedResult(reason: "health-check-failed"), .healthCheckFailed)
        }

        isCycleRunning = true
        let startedAt = Int64(Date().timeIntervalSince1970 * 1000)
        emitter.emit(.now(type: "SYNC_STARTED"))

        do {
            let result = try await runCycle(startedAt: startedAt)
            await finishCycle()
            return .ran(result)
        } catch {
            // Listeners track a cycle from SYNC_STARTED to its terminal event,
            // so a cycle that dies must still end with one.
            emitter.emit(.now(type: "SYNC_FAILED",
                              errorCode: wrapAsSyncProviderError(error).code.stringValue))
            await finishCycle()
            throw error
        }
    }

    private func finishCycle() async {
        isCycleRunning = false
        let notBefore = credentialHeldUntil
        credentialHeldUntil = nil
        await hooks.onCycleFinished?(notBefore)
    }

    /// The outcome of a flush that must not start a cycle, or `nil` when one
    /// may start: paused, credentials still unreadable, or a cycle already
    /// running.
    private func exitBeforeCycle() -> FlushOutcome? {
        if isPaused {
            SyncLogger.debug("flush() called while paused; returning empty result", category: "dispatcher")
            return .skipped(Self.skippedResult(reason: "paused"), .paused)
        }
        guard restoreCredentialsIfNeeded() else {
            // Items stay PENDING and the caller sees a normal, empty result: a
            // worker reports success instead of driving OS-level retries.
            SyncLogger.error("Default headers could not be read; skipping dispatch until they can",
                             category: "dispatcher")
            return .skipped(Self.skippedResult(reason: "credentials-unavailable"), .credentialsUnavailable)
        }
        if isCycleRunning {
            SyncLogger.debug("flush() called while cycle in progress; piggybacking", category: "dispatcher")
            return .ran(lastResult ?? Self.emptyResult())
        }
        return nil
    }

    /// `true` when the health check keeps this flush from starting a cycle:
    /// the HEAD to `healthCheckUrl` failed, or an earlier failure's backoff is
    /// running. `false` without a URL, and whenever a 2xx is cached.
    private func healthCheckFails() async -> Bool {
        guard let url = config.healthCheckUrl, let healthCheck = hooks.healthCheck else { return false }
        let verdict = await healthCheck.check(url: url,
                                              ttlMs: config.healthCheckTtlMs,
                                              backoffMs: config.healthCheckBackoffMs)
        return verdict != .healthy
    }

    /// The grade that keeps this flush from starting a cycle, or `nil` when it
    /// may start one: no gate is set, or the network meets it.
    ///
    /// On a path that is still unknown, as in a process the OS just launched
    /// for a BGTask, waits up to ``kFirstPathUpdateWaitMs`` for the first path
    /// update. A path still unknown after that grades `offline`.
    private func networkQualityBlock() async -> NetworkQualityLevel? {
        guard let minimum = config.minNetworkQuality, minimum > .offline else { return nil }
        if connectivity.current.status == .unknown {
            await connectivity.waitUntilKnown(timeoutMs: kFirstPathUpdateWaitMs)
        }
        hooks.refreshStaleNetworkQuality?()
        let quality = hooks.networkQuality?()
            ?? NetworkQualityClassifier.classify(link: connectivity.current, samples: nil)
        // Read again: a configuration can land during the wait.
        return quality.satisfies(minimum: config.minNetworkQuality) ? nil : quality
    }

    private func runCycle(startedAt: Int64) async throws -> StoredSyncResult {
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
        var attempted = 0
        let total = pending.count

        dispatchLoop: for (index, item) in pending.enumerated() {
            if isPaused { break }
            attempted += 1
            inFlight.insert(item.id)
            try await storage.markInFlight(id: item.id)

            // `configureSync` can land while the request is out. Its answer is
            // about the credential it carried, not the one configured since.
            let sentWith = config
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
                // Decides retry vs. terminal failure, persists the retry
                // cooldown, and emits the event.
                switch try await recordFailureOutcome(item: item, outcome: outcome, sentWith: sentWith) {
                case .retrying:
                    break // kept PENDING with a cooldown; not counted as failed
                case let .failed(_, message):
                    failedIds.append(item.id)
                    if firstError == nil {
                        firstError = message
                    }
                case let .credentialRejected(heldUntil):
                    // The next item would carry the same rejected credential.
                    credentialHeldUntil = heldUntil
                    break dispatchLoop
                }
            }
        }

        let finishedAt = Int64(Date().timeIntervalSince1970 * 1000)
        let result = StoredSyncResult(id: UUID().uuidString,
                                      startedAt: startedAt,
                                      finishedAt: finishedAt,
                                      itemsAttempted: Int32(attempted),
                                      itemsSucceeded: Int32(succeededIds.count),
                                      itemsFailed: Int32(failedIds.count),
                                      errorMessage: firstError)
        try await storage.recordResult(result)
        self.lastResult = result

        if failedIds.isEmpty {
            emitter.emit(.now(type: "SYNC_SUCCEEDED",
                              metadata: ["attempted": String(attempted),
                                         "succeeded": String(succeededIds.count)]))
        } else {
            emitter.emit(.now(type: "SYNC_FAILED",
                              metadata: ["attempted": String(attempted),
                                         "succeeded": String(succeededIds.count),
                                         "failed": String(failedIds.count)]))
        }

        return result
    }

    // MARK: - Failure handling

    /// Records the outcome of a **failed** dispatch: decides retry vs. terminal
    /// failure, persists the retry cooldown (`nextRetryAt`) via
    /// ``RetryPolicyEvaluator`` when retrying, and otherwise moves the item to
    /// the dead-letter store, then emits the matching events.
    ///
    /// A terminal failure emits `ITEM_FAILED` with the real code, then, right
    /// after, `ITEM_DEAD_LETTERED` with `DEAD_LETTERED` and that code in
    /// `metadata.lastErrorCode`. The dead letter keeps the failure's message
    /// (a status line or an error's description, never a header or a body),
    /// cut to ``kDeadLetterMessageMaxLength`` characters.
    ///
    /// This is the single source of truth for failure handling. The background
    /// `URLSession` delegate does not use it yet: a completion delivered after
    /// a relaunch is still dropped, and that item comes back through launch
    /// recovery instead, with no cooldown. Routing that path here is follow-up
    /// work.
    ///
    /// A retriable failure with no budget left ends as `MAX_ATTEMPTS_EXCEEDED`.
    /// A non-retryable failure keeps its real code, even on the last attempt.
    ///
    /// - Important: `item` must be the snapshot taken **before**
    ///   ``SyncQueueStorage/markInFlight(id:)``. This method computes the
    ///   current attempt as `item.attempts + 1`, so a row re-read from storage
    ///   after the dispatch would count the same attempt twice. A caller that
    ///   only has the row, like a delegate fallback after a relaunch, must
    ///   subtract one. `attempts` itself is never reset, so the backoff curve
    ///   keeps advancing.
    /// - Parameter sentWith: the configuration the request went out with.
    /// - Returns: the ``FailureDisposition`` so callers can update their
    ///   aggregate counters.
    @discardableResult
    func recordFailureOutcome(item: StoredSyncItem,
                              outcome: ItemDispatchOutcome,
                              sentWith: DispatcherConfig) async throws -> FailureDisposition {
        let attemptsAfter = item.attempts + 1

        if isStaleCredentialRejection(outcome, sentWith: sentWith) {
            let delayMs = RetryPolicyEvaluator.nextDelayMs(attempt: Int(attemptsAfter),
                                                           policy: config.retryPolicy)
            let heldUntil = try await storage.markCredentialRejected(id: item.id,
                                                                     errorCode: outcome.errorCode,
                                                                     retryDelayMs: max(delayMs, kMinCredentialHoldMs),
                                                                     attempts: item.attempts)
            emitter.emit(.now(type: "ITEM_RETRYING",
                              itemId: item.id,
                              errorCode: outcome.errorCode,
                              statusCode: outcome.statusCode.map(Double.init),
                              attempt: Double(attemptsAfter)))
            return .credentialRejected(heldUntil: heldUntil)
        }

        let isRetryable = decideRetry(outcome: outcome, statusCode: outcome.statusCode)
        let exhausted = Int(attemptsAfter) >= config.retryPolicy.maxAttempts

        guard isRetryable && !exhausted else {
            let finalErrorCode = isRetryable
                ? "MAX_ATTEMPTS_EXCEEDED"
                : (outcome.errorCode ?? "NETWORK_ERROR")
            let message = outcome.errorMessage ?? finalErrorCode
            let moved = try await storage.moveToDeadLetters(id: item.id,
                                                            errorCode: finalErrorCode,
                                                            errorMessage: Self.deadLetterMessage(for: outcome,
                                                                                                 fallback: message))
            emitter.emit(.now(type: "ITEM_FAILED",
                              itemId: item.id,
                              errorCode: finalErrorCode,
                              statusCode: outcome.statusCode.map(Double.init),
                              attempt: Double(attemptsAfter)))
            // A row removed while its request was out has nothing to move.
            if moved {
                emitter.emit(.now(type: "ITEM_DEAD_LETTERED",
                                  itemId: item.id,
                                  errorCode: "DEAD_LETTERED",
                                  metadata: ["lastErrorCode": finalErrorCode]))
            }
            return .failed(errorCode: finalErrorCode, message: message)
        }

        // Retryable and attempts remain: park the item behind a backoff
        // cooldown so it is not re-dispatched until `now + delay`.
        let delayMs = RetryPolicyEvaluator.nextDelayMs(attempt: Int(attemptsAfter),
                                                       policy: config.retryPolicy)
        try await storage.markPendingForRetry(id: item.id,
                                              errorCode: outcome.errorCode,
                                              retryDelayMs: delayMs)
        emitter.emit(.now(type: "ITEM_RETRYING",
                          itemId: item.id,
                          errorCode: outcome.errorCode,
                          statusCode: outcome.statusCode.map(Double.init),
                          attempt: Double(attemptsAfter)))
        return .retrying(attempt: attemptsAfter)
    }

    /// What a dead letter says about the failure that ended it: for an HTTP
    /// failure, the status line with its reason phrase (`HTTP 404 Not Found`,
    /// the phrase as Foundation localizes it), otherwise the error's message.
    /// Built from the outcome alone, never from a header or a body. The result
    /// and history keep the shorter `HTTP 404`.
    static func deadLetterMessage(for outcome: ItemDispatchOutcome, fallback: String) -> String {
        guard let status = outcome.statusCode,
              outcome.errorCode == "SERVER_ERROR" || outcome.errorCode == "UNAUTHORIZED" else {
            return fallback
        }
        return "HTTP \(status) \(HTTPURLResponse.localizedString(forStatusCode: status).capitalized)"
    }

    /// `true` for a 401/403 sent with default headers read back from storage.
    /// Only the app can renew such a token, and it may simply be asleep: the
    /// item must wait for it, not fail. With a configuration set by the app in
    /// this process, the app owns the token, so the rejection is final.
    private func isStaleCredentialRejection(_ outcome: ItemDispatchOutcome,
                                            sentWith: DispatcherConfig) -> Bool {
        sentWith.source == .persisted
            && sentWith.hasDefaultHeaders
            && outcome.errorCode == "UNAUTHORIZED"
    }

    /// Re-reads default headers that were unavailable at start, for example
    /// when the engine came up before the device's first unlock.
    private func restoreCredentialsIfNeeded() -> Bool {
        guard config.credentialsUnavailable else { return true }
        guard let headers = hooks.recoverCredentials?() else { return false }
        config.defaultHeaders = headers
        config.credentialsUnavailable = false
        return true
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

    /// `true` when an upload must survive the app leaving the foreground. A
    /// background upload is OS-driven work and can relaunch the app, so it is
    /// off entirely under `foregroundOnly`.
    func shouldUseBackgroundSession() async -> Bool {
        if hooks.foregroundOnly {
            return false
        }
        return await !isAppActive()
    }

    @Sendable
    private static func applicationIsActive() async -> Bool {
        #if os(iOS)
        return await MainActor.run {
            UIApplication.shared.applicationState == .active
        }
        #else
        return true
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
        skippedResult(reason: nil)
    }

    /// A zero-count result for a call that dispatched nothing. It is returned
    /// without events or a history row: no cycle ran.
    private static func skippedResult(reason: String?) -> StoredSyncResult {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return StoredSyncResult(id: UUID().uuidString,
                                startedAt: now,
                                finishedAt: now,
                                itemsAttempted: 0,
                                itemsSucceeded: 0,
                                itemsFailed: 0,
                                errorMessage: reason)
    }
}
