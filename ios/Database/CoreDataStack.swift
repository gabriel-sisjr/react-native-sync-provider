//
//  CoreDataStack.swift
//  SyncProvider
//
//  Singleton wrapper around `NSPersistentContainer` configured for the
//  `SyncProvider` Core Data model. Owns one shared container, exposes a
//  read-only `viewContext`, and offers a `performBackgroundTask` helper for
//  every write so that consumers never touch the main thread for I/O.
//

import CoreData
import Foundation
import os.log

/// Errors thrown by ``CoreDataStack``.
enum CoreDataStackError: LocalizedError {
    case modelNotFound
    case persistentStoreLoadFailed(underlying: Error)

    var errorDescription: String? {
        switch self {
        case .modelNotFound:
            return "Could not locate the SyncProvider.momd resource bundle. " +
                   "Make sure the Core Data model is bundled with the SyncProvider podspec."
        case .persistentStoreLoadFailed(let underlying):
            return "Failed to load the SyncProvider Core Data store: \(underlying.localizedDescription)"
        }
    }
}

/// Lazy, thread-safe wrapper around `NSPersistentContainer`.
///
/// The store is loaded on first access of ``persistentContainer`` (or the
/// convenience accessors). Lightweight migration is enabled — Phase 0.x ships
/// with a single model version and consumer apps will receive automatic
/// migration on schema bumps. Once the library reaches 1.0, explicit migration
/// mappings will replace this default.
final class CoreDataStack {
    /// Shared singleton.
    static let shared = CoreDataStack()

    /// Name of the `.xcdatamodeld` bundle (without extension).
    private static let modelName = "SyncProvider"

    private let logger = OSLog(subsystem: "com.gabriel-sisjr.syncprovider", category: "core-data")

    private var loadError: Error?

    /// Backing storage. Lazily loaded on the queue that asks for it.
    private(set) lazy var persistentContainer: NSPersistentContainer = {
        if let injected = self.injectedContainer {
            return injected
        }
        let bundle = Self.resolveBundle()
        guard let modelURL = bundle.url(forResource: Self.modelName, withExtension: "momd"),
              let model = NSManagedObjectModel(contentsOf: modelURL) else {
            self.loadError = CoreDataStackError.modelNotFound
            os_log("Failed to find %@.momd in any candidate bundle", log: self.logger, type: .error, Self.modelName)
            // Returning an empty container would crash on first use; instead we
            // return a stub container backed by an in-memory store so callers
            // can recover gracefully via the cached `loadError`.
            return Self.makeFallbackContainer()
        }

        let container = NSPersistentContainer(name: Self.modelName, managedObjectModel: model)

        if let description = container.persistentStoreDescriptions.first {
            description.shouldMigrateStoreAutomatically = true
            description.shouldInferMappingModelAutomatically = true
            description.setOption(true as NSNumber, forKey: NSPersistentHistoryTrackingKey)
            description.setOption(true as NSNumber, forKey: NSPersistentStoreRemoteChangeNotificationPostOptionKey)
        }

        container.loadPersistentStores { [weak self] _, error in
            if let error = error {
                self?.loadError = CoreDataStackError.persistentStoreLoadFailed(underlying: error)
                os_log("Failed to load persistent store: %{public}@",
                       log: self?.logger ?? OSLog.default,
                       type: .error,
                       error.localizedDescription)
            }
        }

        container.viewContext.automaticallyMergesChangesFromParent = true
        container.viewContext.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy

        return container
    }()

    /// Read-only context, exclusively for the main thread.
    var viewContext: NSManagedObjectContext {
        return persistentContainer.viewContext
    }

    /// Returns the load error, if any. Useful for surfacing initialization
    /// failures to JS as `SyncError` instances instead of crashing.
    var initializationError: Error? {
        return loadError
    }

    /// Optional pre-built container injected via the test-only initializer.
    /// When non-nil, the lazy `persistentContainer` short-circuits and returns
    /// it instead of resolving the bundled `.momd`.
    private let injectedContainer: NSPersistentContainer?

    private init() {
        self.injectedContainer = nil
    }

    /// Test-only initializer. Accepts a pre-loaded `NSPersistentContainer`
    /// (typically backed by `NSInMemoryStoreType`) so XCTest cases can run
    /// without touching disk or the bundled `.momd` resource.
    ///
    /// - Important: callers are responsible for invoking
    ///   `loadPersistentStores` on the container before passing it in.
    internal init(container: NSPersistentContainer) {
        self.injectedContainer = container
    }

    // MARK: - Background work

    /// Asynchronously performs work on a fresh background context and saves at
    /// the end if the block does not throw. Errors are propagated.
    ///
    /// - Parameter block: Work to perform inside `context.perform { }`. The
    ///   block runs on the context's private queue.
    /// - Returns: The value returned by `block`.
    func performBackgroundTask<T>(_ block: @escaping (NSManagedObjectContext) throws -> T) async throws -> T {
        let container = persistentContainer
        return try await withCheckedThrowingContinuation { continuation in
            container.performBackgroundTask { context in
                context.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy
                do {
                    let value = try block(context)
                    if context.hasChanges {
                        try context.save()
                    }
                    continuation.resume(returning: value)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    // MARK: - Bundle resolution

    /// Tries hard to locate the `.momd` resource — Cocoapods ships it as a
    /// resource bundle alongside the framework, so we look in:
    ///   1. The bundle for `CoreDataStack` itself (the framework / static lib).
    ///   2. The named resource bundle `SyncProvider.bundle` inside (1).
    ///   3. The main bundle (when consumed via SPM that copies resources).
    ///   4. Every loaded framework, in case linking strategies place it
    ///      somewhere unusual.
    private static func resolveBundle() -> Bundle {
        let frameworkBundle = Bundle(for: CoreDataStack.self)
        if frameworkBundle.url(forResource: modelName, withExtension: "momd") != nil {
            return frameworkBundle
        }
        if let resourceBundleURL = frameworkBundle.url(forResource: "SyncProvider", withExtension: "bundle"),
           let resourceBundle = Bundle(url: resourceBundleURL),
           resourceBundle.url(forResource: modelName, withExtension: "momd") != nil {
            return resourceBundle
        }
        if Bundle.main.url(forResource: modelName, withExtension: "momd") != nil {
            return Bundle.main
        }
        for bundle in Bundle.allBundles + Bundle.allFrameworks
        where bundle.url(forResource: modelName, withExtension: "momd") != nil {
            return bundle
        }
        return frameworkBundle
    }

    /// Build a do-nothing in-memory container as fallback. The caller is
    /// expected to surface ``initializationError`` to the JS layer.
    private static func makeFallbackContainer() -> NSPersistentContainer {
        let model = NSManagedObjectModel()
        let container = NSPersistentContainer(name: modelName, managedObjectModel: model)
        let description = NSPersistentStoreDescription()
        description.type = NSInMemoryStoreType
        description.url = URL(fileURLWithPath: "/dev/null")
        container.persistentStoreDescriptions = [description]
        container.loadPersistentStores { _, _ in /* no-op */ }
        return container
    }
}
