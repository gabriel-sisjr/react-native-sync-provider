//
//  BackgroundTaskRegistrar.swift
//  SyncProvider
//
//  Registers the BGTask launch handlers at process start, before
//  `application(_:didFinishLaunchingWithOptions:)` returns, as iOS requires.
//  JS loads later than that, and not at all when the OS launches the app for a
//  fired task, so registration cannot wait for the bridge.
//

import Foundation
#if canImport(BackgroundTasks)
import BackgroundTasks

/// Registers each BGTask handler once per process. The engine is resolved only
/// when a task fires, so nothing heavy runs during launch.
final class BackgroundTaskRegistrar: @unchecked Sendable {
    static let shared = BackgroundTaskRegistrar(
        scheduler: BGTaskScheduler.shared,
        permittedIdentifiers: { BackgroundTaskRegistrar.declaredIdentifiers() },
        resolveManager: { SyncEngine.shared.background }
    )

    private let scheduler: BackgroundTaskScheduling
    private let permittedIdentifiers: () -> [String]
    private let resolveManager: @Sendable () -> BackgroundSyncManager
    private let lock = NSLock()
    private var didRegister = false

    init(scheduler: BackgroundTaskScheduling,
         permittedIdentifiers: @escaping () -> [String],
         resolveManager: @escaping @Sendable () -> BackgroundSyncManager) {
        self.scheduler = scheduler
        self.permittedIdentifiers = permittedIdentifiers
        self.resolveManager = resolveManager
    }

    /// Registers every handler the app declared. Safe to call more than once:
    /// a second `register` for the same identifier traps. An identifier
    /// missing from `BGTaskSchedulerPermittedIdentifiers` is skipped, because
    /// registering it traps too, and background sync is optional.
    func registerHandlers() {
        let alreadyDone = lock.withLock { () -> Bool in
            defer { didRegister = true }
            return didRegister
        }
        guard !alreadyDone else { return }

        let permitted = Set(permittedIdentifiers())
        let resolveManager = self.resolveManager

        if permitted.contains(kBackgroundRefreshIdentifier) {
            scheduler.registerHandler(forTaskWithIdentifier: kBackgroundRefreshIdentifier) { task in
                resolveManager().handleAppRefresh(task: task)
            }
        }
        if permitted.contains(kBackgroundProcessingIdentifier) {
            scheduler.registerHandler(forTaskWithIdentifier: kBackgroundProcessingIdentifier) { task in
                resolveManager().handleProcessing(task: task)
            }
        }
        SyncLogger.info("Registered BGTask handlers for \(permitted.count) declared identifiers",
                        category: "background")
    }

    private static func declaredIdentifiers() -> [String] {
        Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String] ?? []
    }
}

/// Entry point for `SyncProviderBackgroundBootstrap.m`.
@_cdecl("SyncProviderRegisterBackgroundTasks")
func syncProviderRegisterBackgroundTasks() {
    BackgroundTaskRegistrar.shared.registerHandlers()
}
#endif
