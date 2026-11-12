//
//  CoreDataMigration.swift
//  SyncProvider
//
//  Explicit migration of the queue store to the current model, with the
//  mapping built in code.
//

import CoreData
import Foundation

private let kItemEntityName = "SyncItemEntity"
private let kDeadLetterEntityName = "SyncDeadLetterEntity"
private let kResultEntityName = "SyncResultEntity"

/// The `lastErrorCode` a migrated failure gets when its row carries none.
let kLegacyDeadLetterErrorCode = "MAX_ATTEMPTS_EXCEEDED"

/// Errors of the explicit store migration.
enum CoreDataMigrationError: LocalizedError {
    case modelVersionMissing(String)
    case entityMissing(String)

    var errorDescription: String? {
        switch self {
        case .modelVersionMissing(let version):
            return "The compiled model has no \(version).mom slice to migrate with"
        case .entityMissing(let entity):
            return "A model version to migrate with has no \(entity) entity"
        }
    }
}

/// Brings a store written by an earlier model up to the current one.
///
/// Every earlier model kept items that failed for good in the queue, as
/// `FAILED` rows of `SyncItemEntity`; the current one moves them to
/// `SyncDeadLetterEntity`. Inference cannot be trusted with that: the new
/// entity is purely additive, so it would accept the step, add an empty
/// dead-letter table and leave every failed item behind in the queue, where
/// nothing reads it. So the migration is explicit, from a mapping built here:
///
/// - `SyncItemEntity` rows that are not `FAILED` are copied attribute by
///   attribute; one the source model lacks (v1 has no `nextRetryAt`) takes
///   the destination's default.
/// - `FAILED` rows become dead letters through ``DeadLetterMigrationPolicy``.
/// - `SyncResultEntity` rows are copied.
///
/// It runs before the store is loaded, on every path that loads it, a BGTask
/// launch's included.
enum CoreDataMigration {
    /// The versions a store is migrated from, each a `.mom` slice of the
    /// compiled `.momd`. The source is found by testing the store's metadata
    /// against each, never from a version string.
    static let sourceVersions = ["SyncProvider", "SyncProvider 2"]
    /// The version every migration ends at: the current one.
    static let destinationVersion = "SyncProvider 3"

    /// Migrates the store at `storeURL` when one of ``sourceVersions`` wrote
    /// it. Does nothing for a missing store, one already at `currentModel`, or
    /// one no bundled version recognizes, which the normal load then judges.
    ///
    /// The store is migrated into a new file next to it, then swapped in with
    /// `replacePersistentStore`, which carries the SQLite `-wal` and `-shm`
    /// files along: moving the files by hand could split them from the
    /// database and lose the queue.
    ///
    /// - Parameter storeOptions: the options the store is opened with.
    /// - Returns: the version migrated from, or `nil` when nothing was done.
    /// - Throws: when a recognized store could not be migrated. The original
    ///   store is left as it was, and the next load tries again.
    @discardableResult
    static func migrateStoreIfNeeded(at storeURL: URL,
                                     momd: URL,
                                     currentModel: NSManagedObjectModel,
                                     storeOptions: [AnyHashable: Any]) throws -> String? {
        guard FileManager.default.fileExists(atPath: storeURL.path),
              let metadata = try? NSPersistentStoreCoordinator.metadataForPersistentStore(ofType: NSSQLiteStoreType,
                                                                                         at: storeURL,
                                                                                         options: storeOptions),
              !currentModel.isConfiguration(withName: nil, compatibleWithStoreMetadata: metadata) else {
            return nil
        }
        var recognized: (version: String, model: NSManagedObjectModel)?
        for version in sourceVersions {
            if let model = slice(version, in: momd),
               model.isConfiguration(withName: nil, compatibleWithStoreMetadata: metadata) {
                recognized = (version, model)
                break
            }
        }
        guard let recognized else { return nil }
        guard let destination = slice(destinationVersion, in: momd) else {
            throw CoreDataMigrationError.modelVersionMissing(destinationVersion)
        }

        let mapping = try mappingModel(from: recognized.model, to: destination)
        let migratedURL = storeURL.deletingLastPathComponent()
            .appendingPathComponent("\(storeURL.deletingPathExtension().lastPathComponent)-migrating-\(UUID().uuidString).sqlite")
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: destination)
        defer {
            // Destroying a SQLite store empties it but leaves its files: once
            // it is closed and empty, they can go.
            try? coordinator.destroyPersistentStore(at: migratedURL, ofType: NSSQLiteStoreType, options: storeOptions)
            for suffix in ["", "-wal", "-shm"] {
                try? FileManager.default.removeItem(atPath: migratedURL.path + suffix)
            }
        }
        let manager = NSMigrationManager(sourceModel: recognized.model, destinationModel: destination)
        // The store-specific path would skip the source filters and the policy.
        manager.usesStoreSpecificMigrationManager = false
        try manager.migrateStore(from: storeURL,
                                 sourceType: NSSQLiteStoreType,
                                 options: storeOptions,
                                 with: mapping,
                                 toDestinationURL: migratedURL,
                                 destinationType: NSSQLiteStoreType,
                                 destinationOptions: storeOptions)
        try coordinator.replacePersistentStore(at: storeURL,
                                               destinationOptions: storeOptions,
                                               withPersistentStoreFrom: migratedURL,
                                               sourceOptions: storeOptions,
                                               ofType: NSSQLiteStoreType)
        return recognized.version
    }

    /// The mapping from `source` (v1 or v2) to `destination` (v3).
    static func mappingModel(from source: NSManagedObjectModel,
                             to destination: NSManagedObjectModel) throws -> NSMappingModel {
        let mapping = NSMappingModel()
        mapping.entityMappings = [
            try copyMapping(named: "QueuedItems", entity: kItemEntityName,
                            sourcePredicate: "status != 'FAILED'", from: source, to: destination),
            try deadLetterMapping(from: source, to: destination),
            try copyMapping(named: "SyncResults", entity: kResultEntityName,
                            sourcePredicate: "TRUEPREDICATE", from: source, to: destination),
        ]
        return mapping
    }

    /// Copies the rows of `entity` that match `sourcePredicate`, attribute by
    /// attribute. An attribute the source lacks takes the destination default.
    private static func copyMapping(named name: String,
                                    entity entityName: String,
                                    sourcePredicate: String,
                                    from source: NSManagedObjectModel,
                                    to destination: NSManagedObjectModel) throws -> NSEntityMapping {
        let sourceEntity = try entity(entityName, in: source)
        let destinationEntity = try entity(entityName, in: destination)
        let mapping = NSEntityMapping()
        mapping.name = name
        mapping.mappingType = .transformEntityMappingType
        mapping.sourceEntityName = entityName
        mapping.sourceEntityVersionHash = sourceEntity.versionHash
        mapping.destinationEntityName = entityName
        mapping.destinationEntityVersionHash = destinationEntity.versionHash
        mapping.sourceExpression = fetchExpression(entityName, predicate: sourcePredicate)
        mapping.attributeMappings = destinationEntity.attributesByName.keys.sorted().map { attribute in
            let property = NSPropertyMapping()
            property.name = attribute
            if sourceEntity.attributesByName[attribute] != nil {
                property.valueExpression = NSExpression(format: "FUNCTION($source, \"valueForKey:\", %@)", attribute)
            } else {
                property.valueExpression = NSExpression(forConstantValue: destinationEntity.attributesByName[attribute]?.defaultValue)
            }
            return property
        }
        return mapping
    }

    /// `FAILED` rows of `SyncItemEntity` to `SyncDeadLetterEntity`.
    private static func deadLetterMapping(from source: NSManagedObjectModel,
                                          to destination: NSManagedObjectModel) throws -> NSEntityMapping {
        let sourceEntity = try entity(kItemEntityName, in: source)
        let destinationEntity = try entity(kDeadLetterEntityName, in: destination)
        let mapping = NSEntityMapping()
        mapping.name = "FailedItemsToDeadLetters"
        mapping.mappingType = .customEntityMappingType
        mapping.sourceEntityName = kItemEntityName
        mapping.sourceEntityVersionHash = sourceEntity.versionHash
        mapping.destinationEntityName = kDeadLetterEntityName
        mapping.destinationEntityVersionHash = destinationEntity.versionHash
        mapping.sourceExpression = fetchExpression(kItemEntityName, predicate: "status == 'FAILED'")
        mapping.entityMigrationPolicyClassName = NSStringFromClass(DeadLetterMigrationPolicy.self)
        return mapping
    }

    private static func fetchExpression(_ entityName: String, predicate: String) -> NSExpression {
        NSExpression(format: "FETCH(FUNCTION($manager, \"fetchRequestForSourceEntityNamed:predicateString:\", %@, %@), $manager.sourceContext, NO)",
                     entityName, predicate)
    }

    private static func entity(_ name: String, in model: NSManagedObjectModel) throws -> NSEntityDescription {
        guard let entity = model.entitiesByName[name] else {
            throw CoreDataMigrationError.entityMissing(name)
        }
        return entity
    }

    /// One version of the compiled model, loaded fresh and with every entity
    /// backed by `NSManagedObject`. A v1 row as a `SyncItemEntity` would carry
    /// a `nextRetryAt` its model lacks; class names do not enter the version
    /// hashes, so the migrated store still matches the app's model. Fresh, so
    /// the model the app loads is never touched.
    static func slice(_ version: String, in momd: URL) -> NSManagedObjectModel? {
        guard let model = NSManagedObjectModel(contentsOf: momd.appendingPathComponent("\(version).mom")) else {
            return nil
        }
        for entity in model.entities {
            entity.managedObjectClassName = NSStringFromClass(NSManagedObject.self)
        }
        return model
    }
}

/// What a legacy `FAILED` row becomes as a dead letter.
struct LegacyDeadLetterFields: Equatable {
    let deadLetteredAt: Int64
    let lastAttemptAt: Int64
    let lastErrorCode: String

    /// - `deadLetteredAt`: the last attempt when there was one, else creation.
    /// - `lastAttemptAt`: the row's, when set; else that same moment, since
    ///   the dead letter requires one.
    /// - `lastErrorCode`: the row's, else ``kLegacyDeadLetterErrorCode``.
    init(createdAt: Int64, lastAttemptAt: Int64, lastErrorCode: String?) {
        deadLetteredAt = lastAttemptAt > 0 ? lastAttemptAt : createdAt
        self.lastAttemptAt = lastAttemptAt > 0 ? lastAttemptAt : deadLetteredAt
        if let lastErrorCode, !lastErrorCode.isEmpty {
            self.lastErrorCode = lastErrorCode
        } else {
            self.lastErrorCode = kLegacyDeadLetterErrorCode
        }
    }
}

/// Turns one legacy `FAILED` row into a dead letter. The class name is pinned
/// because the mapping names the policy by it.
@objc(SyncProviderDeadLetterMigrationPolicy)
final class DeadLetterMigrationPolicy: NSEntityMigrationPolicy {
    private static let copiedKeys = ["id", "method", "url", "headersJSON", "body", "contentType",
                                     "priority", "createdAt", "metadataJSON", "attempts"]

    override func createDestinationInstances(forSource source: NSManagedObject,
                                             in mapping: NSEntityMapping,
                                             manager: NSMigrationManager) throws {
        let letter = NSEntityDescription.insertNewObject(forEntityName: kDeadLetterEntityName,
                                                         into: manager.destinationContext)
        for key in Self.copiedKeys {
            letter.setValue(source.value(forKey: key), forKey: key)
        }
        let fields = LegacyDeadLetterFields(
            createdAt: (source.value(forKey: "createdAt") as? NSNumber)?.int64Value ?? 0,
            lastAttemptAt: (source.value(forKey: "lastAttemptAt") as? NSNumber)?.int64Value ?? 0,
            lastErrorCode: source.value(forKey: "lastErrorCode") as? String
        )
        letter.setValue(fields.deadLetteredAt, forKey: "deadLetteredAt")
        letter.setValue(fields.lastAttemptAt, forKey: "lastAttemptAt")
        letter.setValue(fields.lastErrorCode, forKey: "lastErrorCode")
        // A legacy row kept no message: none is made up.
        manager.associate(sourceInstance: source, withDestinationInstance: letter, for: mapping)
    }
}
