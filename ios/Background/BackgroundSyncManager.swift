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
struct BackgroundSyncOptionsValue: Sendable, Equatable {
    let minimumIntervalMs: Double
    let requiresCharging: Bool?
    let requiresUnmeteredNetwork: Bool?
    let requiresDeviceIdle: Bool?
    let taskIdentifier: String?
}

/// Owns BGTaskScheduler interactions. Single instance per `SyncProvider`.
final class BackgroundSyncManager: @unchecked Sendable {
    private weak var dispatcher: SyncDispatcher?
    private let emitter: SyncEventEmitter
    private let lock = NSLock()
    private var registered = false
    private var enabled = false
    private var lastOptions: BackgroundSyncOptionsValue?

    init(dispatcher: SyncDispatcher, emitter: SyncEventEmitter) {
        self.dispatcher = dispatcher
        self.emitter = emitter
    }

    var isEnabled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return enabled
    }

    /// Register handlers for both task identifiers. Must be called before
    /// `application(_:didFinishLaunchingWithOptions:)` returns to satisfy the
    /// `BGTaskScheduler` contract. Idempotent — safe to call from `init`.
    func registerHandlers() {
        #if canImport(BackgroundTasks)
        if #available(iOS 13.0, *) {
            lock.lock()
            let already = registered
            registered = true
            lock.unlock()
            guard !already else { return }

            let scheduler = BGTaskScheduler.shared
            scheduler.register(forTaskWithIdentifier: kBackgroundRefreshIdentifier,
                               using: nil) { [weak self] task in
                guard let self = self, let appRefreshTask = task as? BGAppRefreshTask else {
                    task.setTaskCompleted(success: false)
                    return
                }
                self.handleAppRefresh(task: appRefreshTask)
            }
            scheduler.register(forTaskWithIdentifier: kBackgroundProcessingIdentifier,
                               using: nil) { [weak self] task in
                guard let self = self, let processingTask = task as? BGProcessingTask else {
                    task.setTaskCompleted(success: false)
                    return
                }
                self.handleProcessing(task: processingTask)
            }
            SyncLogger.info("Registered BGTaskScheduler handlers", category: "background")
        }
        #endif
    }

    /// Enable + schedule the next BGAppRefresh window.
    /// - Throws: `SyncProviderError(.backgroundTaskRegistrationFailed)` if the
    ///   OS rejects the submission (most commonly: missing `Info.plist` key).
    func enable(options: BackgroundSyncOptionsValue) throws {
        #if canImport(BackgroundTasks)
        if #available(iOS 13.0, *) {
            registerHandlers()
            try scheduleAppRefresh(options: options)
            lock.lock()
            enabled = true
            lastOptions = options
            lock.unlock()
            SyncLogger.info("Background sync enabled (interval=\(options.minimumIntervalMs)ms)", category: "background")
        } else {
            throw SyncProviderError.backgroundRegistrationFailed("iOS 13.0 or higher required")
        }
        #else
        throw SyncProviderError.backgroundRegistrationFailed("BackgroundTasks framework unavailable")
        #endif
    }

    /// Cancel any pending submissions and mark disabled.
    func disable() {
        #if canImport(BackgroundTasks)
        if #available(iOS 13.0, *) {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: kBackgroundRefreshIdentifier)
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: kBackgroundProcessingIdentifier)
        }
        #endif
        lock.lock()
        enabled = false
        lock.unlock()
        SyncLogger.info("Background sync disabled", category: "background")
    }

    // MARK: - Submission

    @available(iOS 13.0, *)
    private func scheduleAppRefresh(options: BackgroundSyncOptionsValue) throws {
        #if canImport(BackgroundTasks)
        let request = BGAppRefreshTaskRequest(identifier: kBackgroundRefreshIdentifier)
        let intervalSeconds = max(options.minimumIntervalMs / 1000.0, 60)
        request.earliestBeginDate = Date(timeIntervalSinceNow: intervalSeconds)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            SyncLogger.error("BGAppRefresh submit failed: \(error.localizedDescription)", category: "background")
            throw SyncProviderError.backgroundRegistrationFailed(error.localizedDescription)
        }
        #endif
    }

    @available(iOS 13.0, *)
    private func scheduleProcessing(options: BackgroundSyncOptionsValue) {
        #if canImport(BackgroundTasks)
        let request = BGProcessingTaskRequest(identifier: kBackgroundProcessingIdentifier)
        request.requiresExternalPower = options.requiresCharging ?? false
        request.requiresNetworkConnectivity = true
        // `earliestBeginDate` nil means "as soon as possible".
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            SyncLogger.error("BGProcessing submit failed: \(error.localizedDescription)", category: "background")
        }
        #endif
    }

    // MARK: - Handlers

    #if canImport(BackgroundTasks)
    @available(iOS 13.0, *)
    private func handleAppRefresh(task: BGAppRefreshTask) {
        SyncLogger.info("BGAppRefresh task fired", category: "background")
        emitter.emit(.now(type: "BACKGROUND_SYNC_STARTED"))
        // Re-schedule the next window before doing any work so the chain
        // continues even if we get terminated.
        if let opts = lastOptions {
            try? scheduleAppRefresh(options: opts)
            scheduleProcessing(options: opts)
        }

        let dispatchTask = Task { [weak self] in
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

    @available(iOS 13.0, *)
    private func handleProcessing(task: BGProcessingTask) {
        SyncLogger.info("BGProcessing task fired", category: "background")
        emitter.emit(.now(type: "BACKGROUND_SYNC_STARTED",
                          metadata: ["kind": "processing"]))
        if let opts = lastOptions {
            scheduleProcessing(options: opts)
        }

        let dispatchTask = Task { [weak self] in
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
    #endif
}
