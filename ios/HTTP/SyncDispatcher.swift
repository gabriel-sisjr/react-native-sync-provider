//
//  SyncDispatcher.swift
//  SyncProvider
//
//  Drives the actual HTTP work. Owns:
//    - One `URLSession(configuration: .default)` for in-app flushes.
//    - One `URLSession(configuration: .background(withIdentifier:))` for
//      OS-driven sync (BGTask handlers + iOS-resumed background uploads).
//    - The `flush` cycle: drains the queue, asks the JS header resolver and
//      `beforeDispatch` before each claim, applies the retry policy and the
//      JS retry decider, records results, emits events, and then calls
//      `afterDispatch` without waiting for it.
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

/// The code of an item the retry decider dropped or dead-lettered: on its
/// `ITEM_FAILED`, as its dead letter's `lastErrorCode`, and in the metadata
/// of its `ITEM_DEAD_LETTERED`.
let kRetryDeciderRejectedCode = "RETRY_DECIDER_REJECTED"

/// The code of an item the header resolver or `beforeDispatch` deferred: on
/// its `ITEM_RETRYING`, and as its row's `lastErrorCode`.
let kInterceptorFailedCode = "INTERCEPTOR_FAILED"

/// The least an item an interceptor deferred waits: `attempts` does not
/// advance on a deferral, so backoff alone would stay at its first value. The
/// credential hold's floor, for the same reason.
let kMinInterceptorDeferMs = kMinCredentialHoldMs

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
    /// How long native waits for the answer of an installed JS callback, in
    /// milliseconds. See ``NumericOption/callbackTimeoutMs(_:)``.
    var callbackTimeoutMs: Int = SyncOptionsSnapshot.defaultCallbackTimeoutMs

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
    /// `false` when the request never reached `URLSession`: its URL is not
    /// http(s), or its body could not be written for a background upload. A
    /// cycle that dies before such an outcome is written gives the row back
    /// as it was before its claim.
    var requestSent = true
    /// The request's own duration, in whole milliseconds, from the moment
    /// `URLSession` had it to its answer: no wait for a JS callback counts.
    var durationMs: Int64 = 0
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

/// How a flush cycle ended, for the wake armed after it.
enum CycleEnd: Sendable, Equatable {
    /// It ran to its end, whatever its items' outcomes.
    case completed
    /// It died: a write that failed, say. Its `SYNC_FAILED` carries the code.
    case aborted
    /// Its own task was cancelled, a BGTask that expired say. Nothing failed.
    /// A `CancellationError` that its task did not get ends it as `aborted`.
    case cancelled
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
    private var cycleEnded: (@Sendable (CycleEnd) -> Void)?
    private var cycleFinished: (@Sendable (Int64?, CycleEnd) async -> Void)?
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

    /// Called when a flush cycle ends, with how it ended, on the actor and
    /// before another cycle can start, so cycle ends reach it in the order the
    /// cycles ran. It must not block.
    var onCycleEnded: (@Sendable (CycleEnd) -> Void)? {
        get { lock.withLock { cycleEnded } }
        set { lock.withLock { cycleEnded = newValue } }
    }

    /// Awaited when a flush cycle ends, whatever its outcome, so whoever awaits
    /// `flush()` sees its effects. Receives the moment a credential hold ends
    /// when the cycle was stopped by one, and how the cycle ended. Another
    /// cycle can start while it runs: see ``onCycleEnded``.
    var onCycleFinished: (@Sendable (Int64?, CycleEnd) async -> Void)? {
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
    /// The JS callbacks installed in this process.
    private let callbacks: CallbackRegistry
    /// Where a cycle waits for the answers of those callbacks.
    private let gateway: CallbackGateway
    /// Times each request, for `afterDispatch`.
    private let clock: SyncClock

    /// See ``DispatcherHooks``.
    nonisolated let hooks = DispatcherHooks()

    // MARK: - State

    private var config: DispatcherConfig
    private var isCycleRunning = false
    private var credentialHeldUntil: Int64?
    private(set) var lastResult: StoredSyncResult?
    private var isPaused: Bool { hooks.paused }

    // MARK: - Init

    init(storage: SyncQueueStorage,
         emitter: SyncEventEmitter,
         connectivity: ConnectivityProviding = ConnectivityMonitor.shared,
         config: DispatcherConfig = .default,
         paused: Bool = false,
         callbacks: CallbackRegistry = CallbackRegistry(),
         gateway: CallbackGateway = CallbackGateway(),
         clock: SyncClock = SystemClock()) {
        self.storage = storage
        self.emitter = emitter
        self.connectivity = connectivity
        self.config = config
        self.hooks.paused = paused
        self.isAppActive = Self.applicationIsActive
        self.callbacks = callbacks
        self.gateway = gateway
        self.clock = clock

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
                  isAppActive: (@Sendable () async -> Bool)? = nil,
                  callbacks: CallbackRegistry = CallbackRegistry(),
                  gateway: CallbackGateway = CallbackGateway(),
                  clock: SyncClock = SystemClock()) {
        self.storage = storage
        self.emitter = emitter
        self.connectivity = connectivity
        self.config = config
        self.foregroundSession = foregroundSession
        self.backgroundSession = backgroundSession
        self.backgroundDelegate = backgroundDelegate
        self.hooks.paused = paused
        self.isAppActive = isAppActive ?? Self.applicationIsActive
        self.callbacks = callbacks
        self.gateway = gateway
        self.clock = clock
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
    ///
    /// A cancelled caller, a BGTask whose time expired, gets `CancellationError`.
    /// Cancelled before its cycle starts, the flush emits nothing. Cancelled
    /// during it, the cycle claims no further item, the request in flight is
    /// cancelled and its item goes back to `PENDING` with the attempt its
    /// claim spent refunded, and `lastAttemptAt` as the claim set it when
    /// `URLSession` had the request (it may have reached the server) or as it
    /// was before the claim when it never did, and `SYNC_FAILED` ends the cycle without
    /// `errorCode` or `metadata`: nothing failed, and no history row is kept.
    /// A response that arrived before the cancellation is recorded as usual,
    /// and a cancellation while the retry decider is asked about it ends that
    /// wait at once: the retry policy's verdict is recorded. A cancellation
    /// while the header resolver or `beforeDispatch` is asked sends nothing
    /// and fails nothing: they run before the claim, so the row stays exactly
    /// as it was, with no event of its own.
    /// A `CancellationError` thrown while the caller's task is not cancelled,
    /// by a callee say, also ends the cycle with `SYNC_FAILED` without a code,
    /// but counts as a cycle that died for the wait after it.
    ///
    /// A cycle that dies for any other reason, a write that fails say, ends
    /// with `SYNC_FAILED` and its code (see ``cycleFailureCode(_:)``: a cause
    /// that is not a `SyncProviderError` is `NETWORK_ERROR`), and the row whose outcome
    /// could not be written is settled first: with that outcome, as the
    /// normal path records it, when its request went out (a 2xx delivered, a
    /// failure retried or dead-lettered with its own code, a stale credential
    /// held); as it was before its claim when the request never went out.
    func flushOutcome() async throws -> FlushOutcome {
        try Task.checkCancellation()
        if let early = exitBeforeCycle() {
            return early
        }
        // Only suspends while the path is still unknown, and during that wait
        // a pause, a configuration or another flush can land.
        let blockingQuality = await networkQualityBlock()
        try Task.checkCancellation()
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
        try Task.checkCancellation()
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
            await finishCycle(.completed)
            return .ran(result)
        } catch {
            // Listeners track a cycle from SYNC_STARTED to its terminal event,
            // so a cycle that dies must still end with one. A cancelled cycle
            // failed nothing, so its event carries no code.
            if error is CancellationError {
                emitter.emit(.now(type: "SYNC_FAILED"))
            } else {
                emitter.emit(.now(type: "SYNC_FAILED", errorCode: Self.cycleFailureCode(error)))
            }
            // Only a cancellation of the cycle's own task ends it as
            // cancelled. A CancellationError thrown while that task runs on,
            // by a callee say, is a failure, and the wait after a cycle that
            // died applies to it, as on Android.
            let cancelled = error is CancellationError && Task.isCancelled
            await finishCycle(cancelled ? .cancelled : .aborted)
            throw error
        }
    }

    private func finishCycle(_ end: CycleEnd) async {
        // Before the next cycle can start: the follow-up below suspends, and
        // a whole cycle could run before it does.
        hooks.onCycleEnded?(end)
        isCycleRunning = false
        let notBefore = credentialHeldUntil
        credentialHeldUntil = nil
        await hooks.onCycleFinished?(notBefore, end)
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
            // A cancelled cycle claims nothing more: the BGTask it runs for
            // has run out of time.
            try Task.checkCancellation()
            if isPaused { break }

            // A budget spent before this cycle, by a `maxAttempts` lowered
            // since the last attempt say, ends the item without a request.
            if Int(item.attempts) >= max(1, config.retryPolicy.maxAttempts) {
                emitProgress(index: index, total: total)
                // Purged since the batch was read: like a claim that finds no
                // row, it is neither attempted nor failed.
                guard try await deadLetterSpentBudget(item: item) else { continue }
                attempted += 1
                failedIds.append(item.id)
                if firstError == nil {
                    firstError = Self.spentBudgetMessage
                }
                continue
            }

            // The header resolver and `beforeDispatch`, when one is installed,
            // run before the claim. An item they defer was never claimed, so
            // nothing is given back, and an app killed while they are asked
            // has spent no attempt. With neither installed, nothing here runs.
            var resolvedHeaders: [String: String]?
            var interceptedWith: DispatcherConfig?
            if callbacks.hasInterceptors, Self.dispatchURL(for: item) != nil {
                // Purged since the batch was read: nothing to ask about.
                guard try await storage.isPending(id: item.id) else {
                    emitProgress(index: index, total: total)
                    continue
                }
                switch await runInterceptors(item: item) {
                case let .proceed(headers, snapshot):
                    resolvedHeaders = headers
                    interceptedWith = snapshot
                case let .deferred(kind, skip):
                    emitProgress(index: index, total: total)
                    try await deferItem(item, by: kind, skip: skip)
                    continue
                case let .stopped(kind):
                    // Cancelled while JS was asked, a BGTask that expired say:
                    // no failure. Nothing was claimed or sent, the row stays
                    // as it was, and no event is emitted for it.
                    CallbackGateway.log("\(Self.interceptorName(kind)) was not answered for \(item.id): "
                                        + "\(CallbackSkip.cancelled). Nothing was sent.",
                                        for: .cancelled)
                    throw CancellationError()
                }
                // As at the top of the loop: either can land during the waits.
                try Task.checkCancellation()
                if isPaused { break dispatchLoop }
            }

            // The claim is the last check that the row is still queued: a
            // purge since the batch was read, or while an interceptor was
            // asked, a logout say, leaves this item nothing to send, and no
            // event of its own.
            guard try await storage.markInFlight(id: item.id) else {
                emitProgress(index: index, total: total)
                continue
            }
            attempted += 1

            // `configureSync` can land while the request is out. Its answer is
            // about the credential it carried, not the one configured since.
            // With interceptors, that is the configuration they merged and
            // showed `beforeDispatch`.
            let sentWith = interceptedWith ?? config
            let outcome: ItemDispatchOutcome
            switch await dispatchOne(item: item, sentWith: sentWith, resolvedHeaders: resolvedHeaders) {
            case let .answered(answered):
                outcome = answered
            case let .cancelled(requestSent):
                // Cancelled before an answer came: there is no outcome to
                // record. The attempt is refunded, so the next cycle sends the
                // item again. The claim's time stays when URLSession had the
                // request, which may have reached the server; otherwise the
                // row keeps the time it had before its claim.
                do {
                    try await storage.releaseClaim(id: item.id,
                                                   attempts: item.attempts,
                                                   lastAttemptAt: requestSent ? nil : (item.lastAttemptAt ?? 0))
                } catch {
                    // Still a cancellation, which failed nothing: the row
                    // waits IN_FLIGHT for launch recovery.
                    SyncLogger.error("Could not give a cancelled request's row back: \(error.localizedDescription)",
                                     category: "dispatcher")
                }
                throw CancellationError()
            }

            emitProgress(index: index, total: total)

            // What the outcome does to the row, decided once: by the retry
            // policy, then by the retry decider where it is asked. The write
            // below and the settle of a cycle that dies apply this same verdict.
            let verdict: ItemVerdict
            if outcome.succeeded {
                verdict = .delivered
            } else {
                verdict = .failed(await decideFailure(item: item, outcome: outcome, sentWith: sentWith))
            }

            let disposition: FailureDisposition?
            do {
                switch verdict {
                case .delivered:
                    succeededIds.append(item.id)
                    try await storage.remove(id: item.id)
                    emitSucceeded(item: item, outcome: outcome)
                    disposition = nil
                case let .failed(decision):
                    // Persists the cooldown, the hold, the dead letter or the
                    // drop, and emits the events.
                    disposition = try await recordFailureOutcome(item: item, outcome: outcome, decision: decision)
                }
            } catch {
                // The outcome could not be written, and the cycle dies with
                // `error`. Its row must not wait IN_FLIGHT for the next launch.
                await settleUnrecorded(item: item, outcome: outcome, verdict: verdict)
                throw error
            }

            // Once the outcome is written and its event emitted, before the
            // cycle can stop on a held credential.
            notifyAfterDispatch(item: item, outcome: outcome, verdict: verdict)

            switch disposition {
            case nil, .retrying?:
                break // delivered, or kept PENDING with a cooldown; not counted as failed
            case let .failed(_, message)?:
                failedIds.append(item.id)
                if firstError == nil {
                    firstError = message
                }
            case let .credentialRejected(heldUntil)?:
                // The next item would carry the same rejected credential.
                credentialHeldUntil = heldUntil
                break dispatchLoop
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

    private func emitSucceeded(item: StoredSyncItem, outcome: ItemDispatchOutcome) {
        emitter.emit(.now(type: "ITEM_SUCCEEDED",
                          itemId: item.id,
                          statusCode: outcome.statusCode.map(Double.init),
                          attempt: Double(item.attempts + 1)))
    }

    private func emitProgress(index: Int, total: Int) {
        emitter.emit(.now(type: "SYNC_PROGRESS",
                          progress: Double(index + 1) / Double(total),
                          metadata: ["index": String(index + 1),
                                     "total": String(total)]))
    }

    // MARK: - Failure handling

    /// What the dead letter of an item whose budget was spent before its
    /// request says.
    static let spentBudgetMessage = "Retry budget exhausted"

    /// Ends an item whose attempts already reached `maxAttempts`, with no
    /// request: it moves to the dead-letter store with the attempts it made
    /// and its last attempt's time, then `ITEM_FAILED` with
    /// `MAX_ATTEMPTS_EXCEEDED` and `ITEM_DEAD_LETTERED` follow, as after a
    /// failed last attempt. `attempt` is the attempts made, none being added.
    ///
    /// - Returns: `false` when the row was purged since the batch was read.
    ///   No request was sent, so there is nothing to end: nothing is emitted.
    ///   A failed request reports `ITEM_FAILED` even then.
    private func deadLetterSpentBudget(item: StoredSyncItem) async throws -> Bool {
        let code = "MAX_ATTEMPTS_EXCEEDED"
        let moved = try await storage.moveToDeadLetters(id: item.id,
                                                        errorCode: code,
                                                        errorMessage: Self.spentBudgetMessage)
        guard moved else { return false }
        emitter.emit(.now(type: "ITEM_FAILED",
                          itemId: item.id,
                          errorCode: code,
                          attempt: Double(item.attempts)))
        emitter.emit(.now(type: "ITEM_DEAD_LETTERED",
                          itemId: item.id,
                          errorCode: "DEAD_LETTERED",
                          metadata: ["lastErrorCode": code]))
        return true
    }

    /// Records the outcome of a **failed** dispatch, as `decision` says:
    /// persists the retry cooldown (`nextRetryAt`) when retrying, holds the
    /// row for a stale credential, moves it to the dead-letter store, or
    /// deletes it for the retry decider's DROP, then emits the matching events.
    ///
    /// A terminal failure emits `ITEM_FAILED` with its code, then, right
    /// after, `ITEM_DEAD_LETTERED` with `DEAD_LETTERED` and that code in
    /// `metadata.lastErrorCode`. The dead letter keeps the failure's message
    /// (a status line or an error's description, never a header or a body),
    /// cut to ``kDeadLetterMessageMaxLength`` characters. A drop emits only
    /// `ITEM_FAILED`, with ``kRetryDeciderRejectedCode``, after its delete.
    ///
    /// The background `URLSession` delegate does not use this yet: a
    /// completion delivered after a relaunch is still dropped, and that item
    /// comes back through launch recovery instead, with no cooldown. Routing
    /// that path here is follow-up work.
    ///
    /// - Important: `item` must be the snapshot taken **before**
    ///   ``SyncQueueStorage/markInFlight(id:)``. The current attempt is
    ///   `item.attempts + 1`, so a row re-read from storage after the dispatch
    ///   would count the same attempt twice. A caller that only has the row,
    ///   like a delegate fallback after a relaunch, must subtract one.
    ///   `attempts` itself is never reset, so the backoff curve keeps
    ///   advancing.
    /// - Parameter decision: from ``decideFailure(item:outcome:sentWith:)``.
    /// - Returns: the ``FailureDisposition`` so callers can update their
    ///   aggregate counters.
    private func recordFailureOutcome(item: StoredSyncItem,
                                      outcome: ItemDispatchOutcome,
                                      decision: FailureDecision) async throws -> FailureDisposition {
        switch decision {
        case let .credentialHold(holdMs):
            let heldUntil = try await storage.markCredentialRejected(id: item.id,
                                                                     errorCode: outcome.errorCode,
                                                                     retryDelayMs: holdMs,
                                                                     attempts: item.attempts)
            emitRetrying(item: item, outcome: outcome)
            return .credentialRejected(heldUntil: heldUntil)
        case let .deadLetter(errorCode, message):
            let moved = try await storage.moveToDeadLetters(id: item.id,
                                                            errorCode: errorCode,
                                                            errorMessage: Self.deadLetterMessage(for: outcome,
                                                                                                 fallback: message))
            // A row removed while its request was out has nothing to move.
            emitFailed(item: item, outcome: outcome, errorCode: errorCode, deadLettered: moved)
            return .failed(errorCode: errorCode, message: message)
        case let .retry(delayMs):
            // Park the item behind a backoff cooldown so it is not
            // re-dispatched until `now + delay`.
            try await storage.markPendingForRetry(id: item.id,
                                                  errorCode: outcome.errorCode,
                                                  retryDelayMs: delayMs)
            emitRetrying(item: item, outcome: outcome)
            return .retrying(attempt: item.attempts + 1)
        case let .drop(message):
            // The delete comes first: when it fails, the settle deletes the
            // row and reports it, so `ITEM_FAILED` goes out once either way.
            // A row removed while its request was out has nothing to delete,
            // and still reports how its request ended.
            try await storage.deleteQueued(id: item.id)
            emitFailed(item: item, outcome: outcome, errorCode: kRetryDeciderRejectedCode, deadLettered: false)
            return .failed(errorCode: kRetryDeciderRejectedCode, message: message)
        }
    }

    /// What one attempt's outcome does to its row, fixed before anything is
    /// written. The normal path writes it, and the settle of a cycle that dies
    /// before that write applies the very same value: it never asks the retry
    /// decider again, nor recomputes a delay.
    private enum ItemVerdict: Sendable {
        /// A 2xx: the row is removed as delivered.
        case delivered
        case failed(FailureDecision)
    }

    /// What a failed attempt does to its row.
    private enum FailureDecision: Sendable, Equatable {
        /// A 401/403 to default headers read back from storage: the attempt is
        /// refunded, and the row waits `holdMs`.
        case credentialHold(holdMs: Int)
        /// Retryable, with attempts left: the row cools for `delayMs`, the
        /// policy's or the retry decider's.
        case retry(delayMs: Int)
        /// Permanent, retryable with no attempt left, or dead-lettered by the
        /// retry decider (with ``kRetryDeciderRejectedCode``).
        case deadLetter(errorCode: String, message: String)
        /// Dropped by the retry decider: the row is deleted, with no dead
        /// letter. `message` is the failure's, for the cycle's result.
        case drop(message: String)
    }

    /// The verdict on a failed attempt: the retry policy's, then the retry
    /// decider's wherever the policy would retry, a retryable failure with
    /// budget left whose request went out, and not a stale credential's
    /// hold. The decider can keep the retry, with its own delay or the
    /// policy's, drop the item or dead-letter it. It cannot retry what the
    /// policy would not, nor add attempts.
    ///
    /// While the cycle waits for the decider, other calls can land on this
    /// actor: a pause, a configuration, a flush that joins the cycle. None of
    /// them changes this verdict, whose policy half is fixed before the wait,
    /// nor the wait, which ends at the `callbackTimeoutMs` of `sentWith`: a
    /// configuration that lands while the request is out applies from the
    /// next item.
    /// The row itself is read again by the write that follows, and the loop
    /// checks for a cancellation and a pause before the next item.
    ///
    /// Anything other than a decision leaves the policy's verdict, with one
    /// log line: a FAILED answer, an answer of another kind, no answer within
    /// `callbackTimeoutMs`, the decider's hybrid going away, or a cancellation
    /// of the cycle, a BGTask that expires say. A delay the decider gives
    /// replaces the policy's as given: no jitter, no `maxDelayMs`, only the
    /// deadline clamp. Either delay counts from the write that follows the
    /// decision.
    private func decideFailure(item: StoredSyncItem,
                               outcome: ItemDispatchOutcome,
                               sentWith: DispatcherConfig) async -> FailureDecision {
        let policy = failureDecision(item: item, outcome: outcome, sentWith: sentWith)
        guard case .retry = policy,
              outcome.requestSent,
              let decider = callbacks.retryDecider else {
            return policy
        }
        let errorCode = outcome.errorCode ?? "NETWORK_ERROR"
        let request = RetryDeciderRequest(itemId: item.id,
                                          attempt: item.attempts + 1,
                                          errorCode: errorCode,
                                          statusCode: outcome.statusCode,
                                          url: item.url,
                                          method: item.method,
                                          metadata: item.metadata)
        let answer = await gateway.requestDecision(owner: decider.owner,
                                                   timeoutMs: sentWith.callbackTimeoutMs) { requestId in
            decider.invoke(requestId, request)
        }
        switch answer {
        case let .answered(decision):
            let message = outcome.errorMessage ?? errorCode
            switch decision.action {
            case .retry:
                guard let decidedMs = NumericOption.decidedDelayMs(decision.delayMs) else {
                    if decision.delayMs != nil {
                        // A warning, as for any answer that could not be used.
                        SyncLogger.warning("The retry decider's delay for \(item.id) is unusable; "
                                           + "the policy's delay applies", category: "callbacks")
                    }
                    return policy
                }
                return .retry(delayMs: decidedMs)
            case .drop:
                return .drop(message: message)
            case .deadLetter:
                return .deadLetter(errorCode: kRetryDeciderRejectedCode, message: message)
            }
        case let .skipped(skip):
            CallbackGateway.log("The retry decider gave no decision for \(item.id): \(skip). "
                                + "The retry policy applies.",
                                for: skip)
            return policy
        }
    }

    private func failureDecision(item: StoredSyncItem,
                                 outcome: ItemDispatchOutcome,
                                 sentWith: DispatcherConfig) -> FailureDecision {
        let attemptsAfter = item.attempts + 1
        if isStaleCredentialRejection(outcome, sentWith: sentWith) {
            let delayMs = RetryPolicyEvaluator.nextDelayMs(attempt: Int(attemptsAfter),
                                                           policy: config.retryPolicy)
            return .credentialHold(holdMs: max(delayMs, kMinCredentialHoldMs))
        }
        let isRetryable = decideRetry(outcome: outcome, statusCode: outcome.statusCode)
        let exhausted = Int(attemptsAfter) >= config.retryPolicy.maxAttempts
        guard isRetryable && !exhausted else {
            let finalErrorCode = isRetryable
                ? "MAX_ATTEMPTS_EXCEEDED"
                : (outcome.errorCode ?? "NETWORK_ERROR")
            return .deadLetter(errorCode: finalErrorCode, message: outcome.errorMessage ?? finalErrorCode)
        }
        return .retry(delayMs: RetryPolicyEvaluator.nextDelayMs(attempt: Int(attemptsAfter),
                                                                policy: config.retryPolicy))
    }

    private func emitRetrying(item: StoredSyncItem, outcome: ItemDispatchOutcome) {
        emitter.emit(.now(type: "ITEM_RETRYING",
                          itemId: item.id,
                          errorCode: outcome.errorCode,
                          statusCode: outcome.statusCode.map(Double.init),
                          attempt: Double(item.attempts + 1)))
    }

    private func emitFailed(item: StoredSyncItem,
                            outcome: ItemDispatchOutcome,
                            errorCode: String,
                            deadLettered: Bool) {
        emitter.emit(.now(type: "ITEM_FAILED",
                          itemId: item.id,
                          errorCode: errorCode,
                          statusCode: outcome.statusCode.map(Double.init),
                          attempt: Double(item.attempts + 1)))
        if deadLettered {
            emitter.emit(.now(type: "ITEM_DEAD_LETTERED",
                              itemId: item.id,
                              errorCode: "DEAD_LETTERED",
                              metadata: ["lastErrorCode": errorCode]))
        }
    }

    /// The code a cycle that dies of `error` reports on its `SYNC_FAILED`: a
    /// `SyncProviderError` keeps its own, and any
    /// other cause, a Core Data write that fails say, is `NETWORK_ERROR`, as
    /// on Android, the code the docs give a cycle that dies. The bridge's
    /// fallback for a rejected call, `INVALID_PAYLOAD`, which the docs call
    /// permanent, is not used here.
    static func cycleFailureCode(_ error: Error) -> String {
        guard let failure = error as? SyncProviderError else {
            return SyncErrorCode.networkError.stringValue
        }
        return failure.code.stringValue
    }

    /// Settles the row of an item whose outcome could not be written, before
    /// its cycle dies. `item` is the snapshot from before the claim, which
    /// already counted the attempt, and `verdict` what the normal path tried
    /// to write.
    ///
    /// When the request went out, the settle applies that same verdict, in its
    /// own transaction, with the normal path's events: a 2xx removes the row
    /// as delivered, with `ITEM_SUCCEEDED`; a retry cools it for the delay the
    /// verdict holds, the retry decider's or the policy's, with the attempt's
    /// own code; a dead letter moves it with the verdict's code, the attempt's
    /// own, `MAX_ATTEMPTS_EXCEEDED` or the decider's
    /// ``kRetryDeciderRejectedCode``; the decider's drop deletes it, with
    /// `ITEM_FAILED`; a 401 or 403 to default headers read back from storage
    /// refunds the attempt and holds the row, and the hold reaches the wake
    /// armed after the cycle. It never asks the decider again. Every request
    /// that went out has an outcome here: a failure during the send is a
    /// transport error, which the classifier turns into one (`NETWORK_ERROR`
    /// or `TIMEOUT`, say). A request that never went out leaves the row as it
    /// was before its claim, with no event. When this write fails too, the
    /// row waits `IN_FLIGHT` for launch recovery.
    ///
    /// Only a row still `IN_FLIGHT` is settled, as on Android: a row purged
    /// since, a logout say, stays gone and gets no event. The credential
    /// hold applies either way.
    private func settleUnrecorded(item: StoredSyncItem,
                                  outcome: ItemDispatchOutcome,
                                  verdict: ItemVerdict) async {
        do {
            guard outcome.requestSent else {
                try await storage.releaseClaim(id: item.id,
                                               attempts: item.attempts,
                                               lastAttemptAt: item.lastAttemptAt ?? 0)
                return
            }
            let decision: FailureDecision
            switch verdict {
            case .delivered:
                let settled = try await storage.settleClaim(id: item.id, .delivered)
                if settled.applied {
                    emitSucceeded(item: item, outcome: outcome)
                }
                return
            case let .failed(recorded):
                decision = recorded
            }
            let settlement: ClaimSettlement
            switch decision {
            case let .credentialHold(holdMs):
                settlement = .credentialHold(errorCode: outcome.errorCode, holdMs: holdMs, attempts: item.attempts)
            case let .retry(delayMs):
                settlement = .retry(errorCode: outcome.errorCode, delayMs: delayMs)
            case let .deadLetter(errorCode, message):
                settlement = .deadLetter(errorCode: errorCode,
                                         errorMessage: Self.deadLetterMessage(for: outcome, fallback: message))
            case .drop:
                settlement = .dropped
            }
            let settled = try await storage.settleClaim(id: item.id, settlement)
            if let heldUntil = settled.heldUntil {
                credentialHeldUntil = heldUntil
            }
            guard settled.applied else { return }
            switch decision {
            case .credentialHold, .retry:
                emitRetrying(item: item, outcome: outcome)
            case let .deadLetter(errorCode, _):
                emitFailed(item: item, outcome: outcome, errorCode: errorCode, deadLettered: true)
            case .drop:
                emitFailed(item: item, outcome: outcome, errorCode: kRetryDeciderRejectedCode, deadLettered: false)
            }
        } catch {
            SyncLogger.error("Could not settle the row of a cycle that died; it waits for launch recovery: "
                             + error.localizedDescription,
                             category: "dispatcher")
        }
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

    // MARK: - Interceptors

    /// What the header resolver and `beforeDispatch` came to for one item.
    private enum InterceptorVerdict {
        /// Send the request, with the headers the resolver supplied and the
        /// configuration they were merged against.
        case proceed(resolvedHeaders: [String: String]?, sentWith: DispatcherConfig)
        /// Send nothing this cycle. `skip` is why `kind` gave nothing to use;
        /// `nil` when `beforeDispatch` answered SKIP.
        case deferred(kind: CallbackKind, skip: CallbackSkip?)
        /// The cycle was cancelled while `kind` was asked: send nothing.
        case stopped(kind: CallbackKind)
    }

    /// How the log names an interceptor.
    private static func interceptorName(_ kind: CallbackKind) -> String {
        kind == .headerResolver ? "The header resolver" : "beforeDispatch"
    }

    /// Asks the header resolver, then `beforeDispatch`, about the attempt
    /// `item` is about to make, as installed. A resolver that fails defers the
    /// item, and `beforeDispatch` is then not asked.
    ///
    /// The configuration read after the resolver's wait is the one the
    /// headers are merged against, that `beforeDispatch` sees, and that the
    /// request is built from and judged by: a `configureSync` during the
    /// hook's wait applies from the next item. Resolver-supplied credentials
    /// do not change the credential policy, which looks at `defaultHeaders`.
    ///
    /// Anything from JS that is not a usable answer defers the item, as when
    /// no answer comes: a FAILED answer, an answer of another kind, a header
    /// HTTP does not allow, no answer within `callbackTimeoutMs`, or the
    /// installing hybrid going away. Only a cancellation does not: it sends
    /// nothing and leaves the row as it was.
    private func runInterceptors(item: StoredSyncItem) async -> InterceptorVerdict {
        let attempt = item.attempts + 1
        var resolved: [String: String]?
        if let resolver = callbacks.headerResolver {
            let request = HeaderResolverRequest(itemId: item.id,
                                                attempt: attempt,
                                                url: item.url,
                                                method: item.method,
                                                metadata: item.metadata)
            let answer = await gateway.requestHeaders(owner: resolver.owner,
                                                      timeoutMs: config.callbackTimeoutMs) { requestId in
                resolver.invoke(requestId, request)
            }
            switch answer {
            case let .answered(headers):
                resolved = headers
            case .skipped(.cancelled):
                return .stopped(kind: .headerResolver)
            case let .skipped(skip):
                return .deferred(kind: .headerResolver, skip: skip)
            }
        }
        let snapshot = config
        if let before = callbacks.beforeDispatch {
            let request = BeforeDispatchRequest(itemId: item.id,
                                                attempt: attempt,
                                                url: item.url,
                                                method: item.method,
                                                headers: HeaderMerge.merge(snapshot.defaultHeaders, resolved, item.headers),
                                                contentType: item.contentType,
                                                metadata: item.metadata)
            let answer = await gateway.requestBeforeDispatch(owner: before.owner,
                                                             timeoutMs: snapshot.callbackTimeoutMs) { requestId in
                before.invoke(requestId, request)
            }
            switch answer {
            case .answered(.proceed):
                break
            case .answered(.skip):
                return .deferred(kind: .beforeDispatch, skip: nil)
            case .skipped(.cancelled):
                return .stopped(kind: .beforeDispatch)
            case let .skipped(skip):
                return .deferred(kind: .beforeDispatch, skip: skip)
            }
        }
        return .proceed(resolvedHeaders: resolved, sentWith: snapshot)
    }

    /// Defers an item an interceptor stopped before its claim. Nothing is
    /// sent, so the row stays `PENDING` with its attempts and `lastAttemptAt`:
    /// no attempt was made. It waits the longer of the would-be attempt's
    /// backoff and ``kMinInterceptorDeferMs``, with `INTERCEPTOR_FAILED` as
    /// its code, and `ITEM_RETRYING` reports it with that code, no status and
    /// the would-be attempt. Only this item waits: the cycle goes on, and no
    /// hold is set. A row purged during the wait stays gone, with no event.
    /// When the write fails, the cycle dies and the row stays as it was: the
    /// cooldown and the code are lost for this cycle only.
    ///
    /// One log line says why: a warning when the interceptor failed or never
    /// answered, info when its hybrid went away or `beforeDispatch` answered
    /// SKIP.
    private func deferItem(_ item: StoredSyncItem, by kind: CallbackKind, skip: CallbackSkip?) async throws {
        let attempt = item.attempts + 1
        let delayMs = max(RetryPolicyEvaluator.nextDelayMs(attempt: Int(attempt), policy: config.retryPolicy),
                          kMinInterceptorDeferMs)
        let report = { (consequence: String) in
            if let skip {
                CallbackGateway.log("\(Self.interceptorName(kind)) gave nothing to use for \(item.id): \(skip). "
                                    + "Nothing was sent. \(consequence)",
                                    for: skip)
            } else {
                SyncLogger.info("beforeDispatch answered SKIP for \(item.id). Nothing was sent. \(consequence)",
                                category: "callbacks")
            }
        }
        let deferredUntil: Int64?
        do {
            deferredUntil = try await storage.deferPending(id: item.id,
                                                           errorCode: kInterceptorFailedCode,
                                                           delayMs: delayMs)
        } catch {
            report("The deferral could not be written: \(error.localizedDescription)")
            throw error
        }
        guard let deferredUntil else {
            report("It was removed meanwhile: nothing to defer.")
            return
        }
        report("Deferred until \(deferredUntil).")
        emitter.emit(.now(type: "ITEM_RETRYING",
                          itemId: item.id,
                          errorCode: kInterceptorFailedCode,
                          attempt: Double(attempt)))
    }

    /// Calls `afterDispatch`, when installed, for a request that went out,
    /// once its outcome is written and its event emitted. Nothing waits for
    /// it, and nothing it answers changes anything: a FAILED answer, or none
    /// within `callbackTimeoutMs`, is only logged. It is told the attempt,
    /// how the request ended (`RETRYING` for a retry or a credential hold,
    /// `FAILED` for a dead letter or a drop, with its code), the status, the
    /// request's own duration, and the item's URL, method and metadata;
    /// never its headers or its body.
    private func notifyAfterDispatch(item: StoredSyncItem, outcome: ItemDispatchOutcome, verdict: ItemVerdict) {
        guard outcome.requestSent, let hook = callbacks.afterDispatch else { return }
        let kind: DispatchOutcomeKind
        let errorCode: String?
        switch verdict {
        case .delivered:
            kind = .succeeded
            errorCode = nil
        case .failed(.retry), .failed(.credentialHold):
            kind = .retrying
            errorCode = outcome.errorCode
        case let .failed(.deadLetter(code, _)):
            kind = .failed
            errorCode = code
        case .failed(.drop):
            kind = .failed
            errorCode = kRetryDeciderRejectedCode
        }
        let report = AfterDispatchReport(itemId: item.id,
                                         attempt: item.attempts + 1,
                                         outcome: kind,
                                         statusCode: outcome.statusCode,
                                         errorCode: errorCode,
                                         url: item.url,
                                         method: item.method,
                                         metadata: item.metadata,
                                         durationMs: outcome.durationMs)
        gateway.notifyAfterDispatch(owner: hook.owner, timeoutMs: config.callbackTimeoutMs) { requestId in
            hook.invoke(requestId, report)
        }
    }

    // MARK: - Single-item dispatch

    /// The URL `item` goes to, or `nil` when it is not http(s): such an item
    /// is never sent, and no interceptor is asked about it.
    private static func dispatchURL(for item: StoredSyncItem) -> URL? {
        guard let url = URL(string: item.url), url.scheme?.hasPrefix("http") == true else { return nil }
        return url
    }

    /// What one ``dispatchOne(item:sentWith:resolvedHeaders:)`` came to.
    private enum DispatchResult {
        /// The outcome to record: an answer, a transport error, or a request
        /// that could not be sent (see ``ItemDispatchOutcome/requestSent``).
        case answered(ItemDispatchOutcome)
        /// The caller was cancelled before an answer came. `requestSent` is
        /// `false` when the request was never handed to `URLSession`.
        case cancelled(requestSent: Bool)
    }

    /// Sends `item` once. The request is built from `sentWith` alone, never
    /// from `config`, which can change during the awaits around it: its
    /// timeout, and its headers, ``HeaderMerge/merge(_:)`` of
    /// `sentWith.defaultHeaders`, `resolvedHeaders` and the item's own, plus
    /// the item's `contentType` when no layer sets a `Content-Type`.
    ///
    /// A background upload carries the headers its task was created with. One
    /// the OS finishes after a relaunch keeps them, and gets no
    /// `afterDispatch`: no JS is there to tell.
    private func dispatchOne(item: StoredSyncItem,
                             sentWith: DispatcherConfig,
                             resolvedHeaders: [String: String]?) async -> DispatchResult {
        guard let url = Self.dispatchURL(for: item) else {
            return .answered(ItemDispatchOutcome(itemId: item.id,
                                                 succeeded: false,
                                                 statusCode: nil,
                                                 errorCode: "INVALID_URL",
                                                 errorMessage: "Invalid URL: \(item.url)",
                                                 requestSent: false))
        }

        var request = URLRequest(url: url)
        request.httpMethod = item.method
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = TimeInterval(sentWith.requestTimeoutMs) / 1000.0

        // `defaultHeaders`, then the resolver's, then the item's: a later
        // layer wins, whatever the case of the name.
        for (name, value) in HeaderMerge.merge(sentWith.defaultHeaders, resolvedHeaders, item.headers) {
            request.setValue(value, forHTTPHeaderField: name)
        }
        if let contentType = item.contentType,
           request.value(forHTTPHeaderField: "Content-Type") == nil {
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }

        let bodyData = (item.body ?? "").data(using: .utf8)

        let useBackground = await shouldUseBackgroundSession()
        guard !Task.isCancelled else { return .cancelled(requestSent: false) }

        if useBackground, let bodyData = bodyData {
            return await dispatchOnBackground(request: request, body: bodyData, item: item)
        }
        return await dispatchOnForeground(request: request, body: bodyData, item: item)
    }

    private func dispatchOnForeground(request: URLRequest,
                                      body: Data?,
                                      item: StoredSyncItem) async -> DispatchResult {
        var requestCopy = request
        if let body = body, !body.isEmpty {
            requestCopy.httpBody = body
        }

        let startedAt = clock.nowMs()
        do {
            let (_, response) = try await foregroundSession.data(for: requestCopy)
            let statusCode = (response as? HTTPURLResponse)?.statusCode
            var outcome = classifyResponse(itemId: item.id, statusCode: statusCode, transportError: nil)
            outcome.durationMs = elapsedMs(since: startedAt)
            return .answered(outcome)
        } catch {
            // Cancelling the caller cancels the request: that is no answer,
            // and must not spend an attempt as a NETWORK_ERROR. URLSession had
            // the request, which may have reached the server.
            if Task.isCancelled { return .cancelled(requestSent: true) }
            var outcome = classifyResponse(itemId: item.id, statusCode: nil, transportError: error)
            outcome.durationMs = elapsedMs(since: startedAt)
            return .answered(outcome)
        }
    }

    /// Whole milliseconds on `clock` since `startedAt`, never below 0: a wall
    /// clock can step back.
    private func elapsedMs(since startedAt: Int64) -> Int64 {
        max(0, clock.nowMs() - startedAt)
    }

    private func dispatchOnBackground(request: URLRequest,
                                      body: Data,
                                      item: StoredSyncItem) async -> DispatchResult {
        // Background `URLSession` requires the request body to be on disk.
        let tmpURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("syncprovider-\(item.id).body")
        do {
            try body.write(to: tmpURL, options: .atomic)
        } catch {
            var outcome = classifyResponse(itemId: item.id, statusCode: nil, transportError: error)
            outcome.requestSent = false
            return .answered(outcome)
        }

        let task = backgroundSession.uploadTask(with: request, fromFile: tmpURL)
        task.taskDescription = item.id
        let wait = BackgroundUploadWait()

        // The wait ends once, at the session's completion or at a
        // cancellation of the caller, whichever comes first. A cancellation
        // also cancels the upload: its completion, when it comes, is dropped.
        let startedAt = clock.nowMs()
        let completed = await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<BackgroundDispatchOutcome?, Never>) in
                guard wait.install(continuation) else { return }
                backgroundDelegate.registerCompletion(for: task) { outcome in
                    wait.finish(outcome)
                }
                task.resume()
            }
        } onCancel: {
            wait.cancel()
            task.cancel()
        }
        let durationMs = elapsedMs(since: startedAt)
        backgroundDelegate.unregisterCompletion(for: task)
        // Best-effort cleanup of the body file.
        try? FileManager.default.removeItem(at: tmpURL)

        // A wait the cancellation ended first never started its upload.
        guard let completed else { return .cancelled(requestSent: wait.started) }
        var outcome = classifyResponseSync(itemId: completed.itemId,
                                           statusCode: completed.statusCode,
                                           transportError: completed.error)
        outcome.durationMs = durationMs
        return .answered(outcome)
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
