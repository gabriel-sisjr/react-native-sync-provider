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
/// The store is loaded once, on first access of ``persistentContainer`` (or the
/// convenience accessors). Concurrent first accesses are serialized: JS calls,
/// launch recovery and a BGTask launch all race here, and two containers
/// opening the same file would run the lightweight migration twice.
///
/// Lightweight migration is enabled: the model bundle carries every version,
/// and Core Data infers each step between them. Once the library reaches 1.0,
/// explicit migration mappings will replace this default.
final class CoreDataStack: @unchecked Sendable {
    /// Shared singleton.
    static let shared = CoreDataStack()

    /// Name of the `.xcdatamodeld` bundle (without extension).
    private static let modelName = "SyncProvider"

    private let logger = OSLog(subsystem: "com.gabriel-sisjr.syncprovider", category: "core-data")

    /// Serializes the one-time load and guards the two fields below.
    private let loadLock = NSLock()
    private var loadedContainer: NSPersistentContainer?
    private var loadError: Error?

    /// Builds the container and reports any load failure. Runs at most once.
    private let makeContainer: (CoreDataStack) -> (NSPersistentContainer, Error?)

    /// The loaded container. The first caller loads the store while holding
    /// the lock, so concurrent callers wait for it instead of opening their
    /// own coordinator on the same file.
    var persistentContainer: NSPersistentContainer {
        loadLock.withLock {
            if let loadedContainer {
                return loadedContainer
            }
            let (container, error) = makeContainer(self)
            loadedContainer = container
            loadError = error
            return container
        }
    }

    /// Read-only context, exclusively for the main thread.
    var viewContext: NSManagedObjectContext {
        return persistentContainer.viewContext
    }

    /// Returns the load error, if any. ``performBackgroundTask(_:)`` throws it,
    /// so a failed load reaches JS as a `SyncError` instead of a crash.
    var initializationError: Error? {
        loadLock.withLock { loadError }
    }

    private init() {
        self.makeContainer = { $0.loadBundledContainer() }
    }

    /// Test-only initializer. Accepts a pre-loaded `NSPersistentContainer`
    /// so XCTest cases can run without touching disk or the bundled `.momd`
    /// resource.
    ///
    /// - Important: callers are responsible for invoking
    ///   `loadPersistentStores` on the container before passing it in.
    internal init(container: NSPersistentContainer) {
        self.makeContainer = { _ in (container, nil) }
    }

    /// Test-only initializer. Injects the one-time load itself, so tests can
    /// count how many loads run and simulate a store that fails to open.
    internal init(load: @escaping () -> (NSPersistentContainer, Error?)) {
        self.makeContainer = { _ in load() }
    }

    /// Loads the bundled model and store. Called once, under `loadLock`.
    private func loadBundledContainer() -> (NSPersistentContainer, Error?) {
        let bundle = Self.resolveBundle()
        guard let modelURL = bundle.url(forResource: Self.modelName, withExtension: "momd"),
              let model = NSManagedObjectModel(contentsOf: modelURL) else {
            os_log("Failed to find %@.momd in any candidate bundle", log: logger, type: .error, Self.modelName)
            // Returning an empty container would crash on first use; instead we
            // return a stub container backed by an in-memory store, and
            // `performBackgroundTask` rejects with the cached error.
            return (Self.makeFallbackContainer(), CoreDataStackError.modelNotFound)
        }

        let container = NSPersistentContainer(name: Self.modelName, managedObjectModel: model)

        if let description = container.persistentStoreDescriptions.first {
            description.shouldMigrateStoreAutomatically = true
            description.shouldInferMappingModelAutomatically = true
            description.setOption(true as NSNumber, forKey: NSPersistentHistoryTrackingKey)
            description.setOption(true as NSNumber, forKey: NSPersistentStoreRemoteChangeNotificationPostOptionKey)
        }

        var failure: Error?
        container.loadPersistentStores { [logger] _, error in
            if let error = error {
                failure = CoreDataStackError.persistentStoreLoadFailed(underlying: error)
                os_log("Failed to load persistent store: %{public}@",
                       log: logger,
                       type: .error,
                       error.localizedDescription)
            }
        }

        container.viewContext.automaticallyMergesChangesFromParent = true
        container.viewContext.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy

        return (container, failure)
    }

    // MARK: - Background work

    /// Asynchronously performs work on a fresh background context and saves at
    /// the end if the block does not throw. Errors are propagated.
    ///
    /// - Parameter block: Work to perform inside `context.perform { }`. The
    ///   block runs on the context's private queue.
    /// - Returns: The value returned by `block`.
    /// - Throws: ``CoreDataStackError`` when the store failed to load, before
    ///   `block` runs. A container with no store would crash on first use.
    func performBackgroundTask<T>(_ block: @escaping (NSManagedObjectContext) throws -> T) async throws -> T {
        let container = persistentContainer
        if let loadError = initializationError {
            throw loadError
        }
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
