//
//  BackgroundSyncManager.swift
//  SyncProvider
//
//  `BGTaskScheduler` registration + scheduling. Owns two task identifiers:
//    - `com.gabriel-sisjr.syncprovider.background` — short app refresh, used
//      to opportunistically peek at the queue and decide whether to schedule
//      the heavier processing task.
//    - `com.gabriel-sisjr.syncprovider.processing` — long, retriable
//      processing task that drains the queue.
//

import Foundation
#if canImport(BackgroundTasks)
import BackgroundTasks
#endif

/// Identifier for the `BGAppRefreshTask`. Must appear in the consumer app's
/// `Info.plist` under `BGTaskSchedulerPermittedIdentifiers`.
let kBackgroundRefreshIdentifier = "com.gabriel-sisjr.syncprovider.background"

/// Identifier for the `BGProcessingTask`. Same `Info.plist` requirement.
let kBackgroundProcessingIdentifier = "com.gabriel-sisjr.syncprovider.processing"

/// Sendable mirror of `BackgroundSyncOptions` (Nitro). Translated by the
/// bridge so this layer does not need to import the generated C++ struct.
struct BackgroundSyncOptionsValue: Sendable, Equatable, Codable {
    let minimumIntervalMs: Double
    let requiresCharging: Bool?
    let requiresUnmeteredNetwork: Bool?
    let requiresDeviceIdle: Bool?
    /// When `true`, the consumer opted out of OS-driven background sync: no
    /// `BGTask` is scheduled and any already-submitted request is cancelled.
    /// `nil`/`false` preserve the historical (background-enabled) behavior.
    let foregroundOnly: Bool?
    let taskIdentifier: String?
}

#if canImport(BackgroundTasks)
/// Seam over the `BGTask` members the handlers touch. `BGTask` and its
/// subclasses have no public initializer, so tests cannot construct one; this
/// protocol lets a fake stand in to assert completion/re-schedule behavior.
protocol BackgroundTaskHandle: AnyObject {
    var expirationHandler: (() -> Void)? { get set }
    func setTaskCompleted(success: Bool)
}

extension BGTask: BackgroundTaskHandle {}

/// Seam over the subset of `BGTaskScheduler` this library uses. Lets unit tests
/// inject a fake and verify scheduling decisions without touching the
/// process-wide system scheduler, which rejects identifiers absent from
/// `Info.plist` and traps on a duplicate `register` for the same identifier.
protocol BackgroundTaskScheduling: AnyObject {
    @discardableResult
    func registerHandler(forTaskWithIdentifier identifier: String,
                         launchHandler: @escaping (BackgroundTaskHandle) -> Void) -> Bool
    func submit(_ taskRequest: BGTaskRequest) throws
    func cancel(taskRequestWithIdentifier identifier: String)
}

extension BGTaskScheduler: BackgroundTaskScheduling {
    @discardableResult
    func registerHandler(forTaskWithIdentifier identifier: String,
                         launchHandler: @escaping (BackgroundTaskHandle) -> Void) -> Bool {
        register(forTaskWithIdentifier: identifier, using: nil) { task in
            launchHandler(task)
        }
    }
}
#endif

/// Owns BGTaskScheduler interactions. Single instance per engine.
///
/// The options and the enabled state are persisted, so a task launched by the
/// OS honors them with no JS loaded and can continue the chain.
final class BackgroundSyncManager: @unchecked Sendable {
    private weak var dispatcher: SyncDispatcher?
    private let emitter: SyncEventEmitter
    private let store: BackgroundSyncOptionsStore
    private let lock = NSLock()
    private var registered = false
    private var enabled: Bool
    private var lastOptions: BackgroundSyncOptionsValue?
    private var prepare: (@Sendable () async -> Void)?
    #if canImport(BackgroundTasks)
    private let scheduler: BackgroundTaskScheduling
    #endif

    #if canImport(BackgroundTasks)
    init(dispatcher: SyncDispatcher,
         emitter: SyncEventEmitter,
         scheduler: BackgroundTaskScheduling = BGTaskScheduler.shared,
         store: BackgroundSyncOptionsStore = BackgroundSyncOptionsStore()) {
        self.dispatcher = dispatcher
        self.emitter = emitter
        self.scheduler = scheduler
        self.store = store
        let persisted = store.load()
        self.enabled = persisted.enabled
        self.lastOptions = persisted.options
        dispatcher.hooks.foregroundOnly = persisted.options?.foregroundOnly == true
    }
    #else
    init(dispatcher: SyncDispatcher,
         emitter: SyncEventEmitter,
         store: BackgroundSyncOptionsStore = BackgroundSyncOptionsStore()) {
        self.dispatcher = dispatcher
        self.emitter = emitter
        self.store = store
        let persisted = store.load()
        self.enabled = persisted.enabled
        self.lastOptions = persisted.options
        dispatcher.hooks.foregroundOnly = persisted.options?.foregroundOnly == true
    }
    #endif

    var isEnabled: Bool {
        lock.withLock { enabled }
    }

    /// `true` when the active options requested foreground-only sync. The
    /// background task handlers consult this and become inert when it is set:
    /// a `BGTask` submitted before the flag flipped can still be launched by the
    /// OS, so suppressing scheduling alone is not enough.
    var isForegroundOnly: Bool {
        lock.withLock { lastOptions?.foregroundOnly == true }
    }

    /// Work a fired task must wait for before flushing. On a cold launch that
    /// is launch recovery, which has to finish first.
    func setPrepare(_ prepare: @escaping @Sendable () async -> Void) {
        lock.withLock { self.prepare = prepare }
    }

    /// Register handlers for both task identifiers. Must be called before
    /// `application(_:didFinishLaunchingWithOptions:)` returns to satisfy the
    /// `BGTaskScheduler` contract. Idempotent — safe to call from `init`.
    func registerHandlers() {
        #if canImport(BackgroundTasks)
        let already = lock.withLock { () -> Bool in
            let wasRegistered = registered
            registered = true
            return wasRegistered
        }
        guard !already else { return }

        let scheduler = self.scheduler
        scheduler.registerHandler(forTaskWithIdentifier: kBackgroundRefreshIdentifier) { [weak self] task in
            guard let self = self else {
                task.setTaskCompleted(success: false)
                return
            }
            self.handleAppRefresh(task: task)
        }
        scheduler.registerHandler(forTaskWithIdentifier: kBackgroundProcessingIdentifier) { [weak self] task in
            guard let self = self else {
                task.setTaskCompleted(success: false)
                return
            }
            self.handleProcessing(task: task)
        }
        SyncLogger.info("Registered BGTaskScheduler handlers", category: "background")
        #endif
    }

    /// Enable + schedule the next BGAppRefresh window.
    /// - Throws: `SyncProviderError(.backgroundTaskRegistrationFailed)` if the
    ///   OS rejects the submission (most commonly: missing `Info.plist` key).
    func enable(options: BackgroundSyncOptionsValue) throws {
        #if canImport(BackgroundTasks)
        registerHandlers()
        if options.foregroundOnly == true {
            // Foreground-only: schedule no OS-driven work, and cancel any request
            // a previous (background-enabled) `enable` may have submitted so it
            // cannot fire. We still mark the feature `enabled`: the flag, not
            // `enabled`, governs whether background work runs, and `disable()`
            // remains the way to turn the feature off entirely.
            //
            // The flag is published before cancelling. A handler already running
            // submits under `lock`, so it either finishes first and its request
            // is cancelled here, or it sees the flag and submits nothing.
            publish(options)
            scheduler.cancel(taskRequestWithIdentifier: kBackgroundRefreshIdentifier)
            scheduler.cancel(taskRequestWithIdentifier: kBackgroundProcessingIdentifier)
        } else {
            try scheduleAppRefresh(options: options)
            publish(options)
        }
        SyncLogger.info("Background sync enabled (interval=\(options.minimumIntervalMs)ms, foregroundOnly=\(options.foregroundOnly == true))",
                        category: "background")
        #else
        throw SyncProviderError.backgroundRegistrationFailed("BackgroundTasks framework unavailable")
        #endif
    }

    private func publish(_ options: BackgroundSyncOptionsValue) {
        lock.withLock {
            enabled = true
            lastOptions = options
        }
        dispatcher?.hooks.foregroundOnly = options.foregroundOnly == true
        store.save(options: options, enabled: true)
    }

    /// Cancel any pending submissions and mark disabled.
    func disable() {
        #if canImport(BackgroundTasks)
        scheduler.cancel(taskRequestWithIdentifier: kBackgroundRefreshIdentifier)
        scheduler.cancel(taskRequestWithIdentifier: kBackgroundProcessingIdentifier)
        #endif
        let options = lock.withLock { () -> BackgroundSyncOptionsValue? in
            enabled = false
            return lastOptions
        }
        store.save(options: options, enabled: false)
        SyncLogger.info("Background sync disabled", category: "background")
    }

    // MARK: - Submission

    private func scheduleAppRefresh(options: BackgroundSyncOptionsValue) throws {
        #if canImport(BackgroundTasks)
        let request = BGAppRefreshTaskRequest(identifier: kBackgroundRefreshIdentifier)
        let intervalSeconds = max(options.minimumIntervalMs / 1000.0, 60)
        request.earliestBeginDate = Date(timeIntervalSinceNow: intervalSeconds)
        do {
            try scheduler.submit(request)
        } catch {
            SyncLogger.error("BGAppRefresh submit failed: \(error.localizedDescription)", category: "background")
            throw SyncProviderError.backgroundRegistrationFailed(error.localizedDescription)
        }
        #endif
    }

    private func scheduleProcessing(options: BackgroundSyncOptionsValue) {
        #if canImport(BackgroundTasks)
        let request = BGProcessingTaskRequest(identifier: kBackgroundProcessingIdentifier)
        request.requiresExternalPower = options.requiresCharging ?? false
        // FIXME: `requiresNetworkConnectivity` is hardcoded `true`, and
        // `options.requiresUnmeteredNetwork` / `options.requiresDeviceIdle` are
        // never mapped onto the request.
        request.requiresNetworkConnectivity = true
        // `earliestBeginDate` nil means "as soon as possible".
        do {
            try scheduler.submit(request)
        } catch {
            SyncLogger.error("BGProcessing submit failed: \(error.localizedDescription)", category: "background")
        }
        #endif
    }

    // MARK: - Handlers

    #if canImport(BackgroundTasks)
    func handleAppRefresh(task: BackgroundTaskHandle) {
        // Re-schedule the next window before doing any work so the chain
        // continues even if we get terminated.
        let shouldRun = continueChain { options in
            try? scheduleAppRefresh(options: options)
            scheduleProcessing(options: options)
        }
        guard shouldRun else {
            SyncLogger.info("BGAppRefresh fired while background sync is off; completing inert", category: "background")
            task.setTaskCompleted(success: true)
            return
        }
        SyncLogger.info("BGAppRefresh task fired", category: "background")
        emitter.emit(.now(type: "BACKGROUND_SYNC_STARTED"))

        let dispatchTask = Task { [weak self] in
            await self?.waitUntilPrepared()
            do {
                _ = try await self?.dispatcher?.flush()
                self?.emitter.emit(.now(type: "BACKGROUND_SYNC_COMPLETED",
                                        metadata: ["kind": "refresh"]))
                task.setTaskCompleted(success: true)
            } catch {
                SyncLogger.error("BGAppRefresh flush failed: \(error.localizedDescription)", category: "background")
                self?.emitter.emit(.now(type: "BACKGROUND_SYNC_COMPLETED",
                                        errorCode: (error as? SyncProviderError)?.code.stringValue,
                                        metadata: ["kind": "refresh", "ok": "false"]))
                task.setTaskCompleted(success: false)
            }
        }
        task.expirationHandler = {
            dispatchTask.cancel()
            SyncLogger.error("BGAppRefresh expired", category: "background")
        }
    }

    func handleProcessing(task: BackgroundTaskHandle) {
        let shouldRun = continueChain { options in
            scheduleProcessing(options: options)
        }
        guard shouldRun else {
            // Same rationale as `handleAppRefresh`: a previously submitted
            // processing task must become inert once background sync is off.
            SyncLogger.info("BGProcessing fired while background sync is off; completing inert", category: "background")
            task.setTaskCompleted(success: true)
            return
        }
        SyncLogger.info("BGProcessing task fired", category: "background")
        emitter.emit(.now(type: "BACKGROUND_SYNC_STARTED",
                          metadata: ["kind": "processing"]))

        let dispatchTask = Task { [weak self] in
            await self?.waitUntilPrepared()
            do {
                _ = try await self?.dispatcher?.flush()
                self?.emitter.emit(.now(type: "BACKGROUND_SYNC_COMPLETED",
                                        metadata: ["kind": "processing"]))
                task.setTaskCompleted(success: true)
            } catch {
                SyncLogger.error("BGProcessing flush failed: \(error.localizedDescription)", category: "background")
                self?.emitter.emit(.now(type: "BACKGROUND_SYNC_COMPLETED",
                                        errorCode: (error as? SyncProviderError)?.code.stringValue,
                                        metadata: ["kind": "processing", "ok": "false"]))
                task.setTaskCompleted(success: false)
            }
        }
        task.expirationHandler = {
            dispatchTask.cancel()
            SyncLogger.error("BGProcessing expired", category: "background")
        }
    }

    /// Runs `submit` unless background sync is off, and reports which.
    ///
    /// The check and the submission share `lock`. `enable(foregroundOnly: true)`
    /// publishes under the same lock before it cancels, so a submission can
    /// never land after that cancel.
    private func continueChain(submit: (BackgroundSyncOptionsValue) -> Void) -> Bool {
        lock.withLock {
            guard enabled, let options = lastOptions, options.foregroundOnly != true else {
                return false
            }
            submit(options)
            return true
        }
    }

    private func waitUntilPrepared() async {
        let prepare = lock.withLock { self.prepare }
        await prepare?()
    }
    #endif
}
