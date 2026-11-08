//
//  CoreDataMigrationV1ToV2Tests.swift
//  SyncProvider
//
//  Core Data model v1 -> v2 migration tests.
//
//  These tests load the *real* compiled `.momd` from the XCTest bundle (the
//  podspec `test_spec.resources` ships `ios/Database/SyncProvider.xcdatamodeld`,
//  and `momc` emits one `.mom` slice per model version). They build a tmp
//  SQLite store from the **v1** model, then migrate it to **v2** with mapping
//  inference *disabled* on the open path — the migration is driven by an
//  explicitly generated mapping model via `NSMigrationManager`. This catches
//  silent lightweight-migration failures that the production inference-ON path
//  (`CoreDataStack.swift`) would otherwise mask.
//
//  PRODUCTION keeps inference ON: adding a non-optional attribute *with a
//  default value* is a lightweight (inferable) change, so consumers' stores
//  migrate v1->v2 automatically. These tests assert that the inferred mapping
//  is genuinely producible and that existing rows survive with the new
//  `nextRetryAt` column defaulting to 0.
//

import CoreData
import XCTest

final class CoreDataMigrationV1ToV2Tests: XCTestCase {
    /// Entity + attribute under test.
    private let entityName = "SyncItemEntity"
    private let newAttribute = "nextRetryAt"

    /// Known fixture row written into the v1 store; asserted intact post-migration.
    private let fixtureID = "01J0MIGRATIONTESTITEM000001"
    private let fixtureURL = "https://example.com/migrate"
    private let fixtureAttempts: Int32 = 3
    private let fixtureCreatedAt: Int64 = 1_700_000_000_000

    private var tempDirectory: URL!
    private var v1Model: NSManagedObjectModel!
    private var v2Model: NSManagedObjectModel!

    override func setUpWithError() throws {
        try super.setUpWithError()

        let momd = try XCTUnwrap(
            Self.locateCompiledModel(),
            """
            Could not find SyncProvider.momd in the XCTest bundle. Ensure the \
            podspec test_spec declares \
            `resources = ["ios/Database/SyncProvider.xcdatamodeld"]` and re-run \
            `pod install`.
            """
        )

        v1Model = Self.genericized(
            try XCTUnwrap(
                Self.model(version: "SyncProvider", in: momd),
                "Failed to load the v1 model slice (SyncProvider.mom) from \(momd.path)."
            )
        )
        v2Model = Self.genericized(
            try XCTUnwrap(
                Self.model(version: "SyncProvider 2", in: momd),
                "Failed to load the v2 model slice (SyncProvider 2.mom) from \(momd.path)."
            )
        )

        tempDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("syncprovider-migration-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let tempDirectory, FileManager.default.fileExists(atPath: tempDirectory.path) {
            try FileManager.default.removeItem(at: tempDirectory)
        }
        tempDirectory = nil
        v1Model = nil
        v2Model = nil
        try super.tearDownWithError()
    }

    // MARK: - Schema assertions

    /// The shipped v1 model must NOT contain `nextRetryAt` (guards against a
    /// stray edit to the frozen v1 model).
    func test_v1Model_doesNotDeclareNextRetryAt() throws {
        let item = try XCTUnwrap(v1Model.entitiesByName[entityName])
        XCTAssertNil(
            item.attributesByName[newAttribute],
            "v1 must remain frozen; `nextRetryAt` belongs to v2 only."
        )
    }

    /// The shipped v2 model must declare `nextRetryAt` as a non-optional
    /// `Integer 64` with a default of 0.
    func test_v2Model_declaresNextRetryAtWithDefaultZero() throws {
        let item = try XCTUnwrap(v2Model.entitiesByName[entityName])
        let attribute = try XCTUnwrap(
            item.attributesByName[newAttribute],
            "v2 model is missing the `nextRetryAt` attribute."
        )
        XCTAssertEqual(attribute.attributeType, .integer64AttributeType)
        XCTAssertFalse(attribute.isOptional, "`nextRetryAt` must be non-optional.")
        XCTAssertEqual(
            (attribute.defaultValue as? NSNumber)?.int64Value, 0,
            "`nextRetryAt` must default to 0 (no deadline pending, so eligible now)."
        )
    }

    // MARK: - Migration assertions

    /// The v1 -> v2 change must be lightweight (inferable). Generating the
    /// mapping model *explicitly* here is the guard: if a future schema edit
    /// makes the change non-inferable, this throws instead of silently failing
    /// under the production inference-ON path.
    func test_v1ToV2_isLightweightInferable() throws {
        XCTAssertNoThrow(
            try NSMappingModel.inferredMappingModel(forSourceModel: v1Model, destinationModel: v2Model),
            "v1->v2 is not lightweight-inferable — a custom mapping model is required."
        )
    }

    /// Opening a v1 store against the v2 model with BOTH auto-migration and
    /// inference disabled must fail. Proves the two versions are genuinely
    /// incompatible — i.e., the migration test below is not a silent no-op.
    func test_openingV1StoreWithV2Model_withoutMigration_fails() throws {
        let storeURL = try makeV1Store(named: "no-op-guard")
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: v2Model)
        XCTAssertThrowsError(
            try coordinator.addPersistentStore(
                type: .sqlite,
                configuration: nil,
                at: storeURL,
                options: [
                    NSMigratePersistentStoresAutomaticallyOption: false,
                    NSInferMappingModelAutomaticallyOption: false
                ]
            ),
            "v1 store opened against v2 without migration — versions are not distinct."
        )
    }

    /// Full path: build a v1 store with a fixture row, migrate it to v2 using an
    /// explicit mapping model, reopen with inference disabled, and assert the
    /// row survived with `nextRetryAt` defaulted to 0.
    func test_migrateV1ToV2_addsNextRetryAtDefaultZero_andPreservesRow() throws {
        let sourceURL = try makeV1Store(named: "source")
        let destinationURL = tempDirectory.appendingPathComponent("migrated-v2.sqlite")

        // Explicit mapping (do NOT rely on the open-path's silent inference).
        let mapping = try NSMappingModel.inferredMappingModel(
            forSourceModel: v1Model,
            destinationModel: v2Model
        )
        let manager = NSMigrationManager(sourceModel: v1Model, destinationModel: v2Model)
        try manager.migrateStore(
            from: sourceURL,
            type: .sqlite,
            options: nil,
            mapping: mapping,
            to: destinationURL,
            type: .sqlite,
            options: nil
        )

        // Reopen the migrated store with inference + auto-migration OFF. This
        // only succeeds if the store is genuinely at v2 already.
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: v2Model)
        _ = try coordinator.addPersistentStore(
            type: .sqlite,
            configuration: nil,
            at: destinationURL,
            options: [
                NSMigratePersistentStoresAutomaticallyOption: false,
                NSInferMappingModelAutomaticallyOption: false
            ]
        )

        let context = NSManagedObjectContext(concurrencyType: .privateQueueConcurrencyType)
        context.persistentStoreCoordinator = coordinator

        let request = NSFetchRequest<NSManagedObject>(entityName: entityName)
        let rows = try context.fetch(request)
        XCTAssertEqual(rows.count, 1, "Exactly one fixture row should survive the migration.")

        let row = try XCTUnwrap(rows.first)
        XCTAssertEqual(row.value(forKey: "id") as? String, fixtureID, "id must survive.")
        XCTAssertEqual(row.value(forKey: "url") as? String, fixtureURL, "url must survive.")
        XCTAssertEqual(
            (row.value(forKey: "attempts") as? NSNumber)?.int32Value, fixtureAttempts,
            "attempts must survive the migration."
        )
        XCTAssertEqual(
            (row.value(forKey: "createdAt") as? NSNumber)?.int64Value, fixtureCreatedAt,
            "createdAt must survive the migration."
        )
        let nextRetryAt = try XCTUnwrap(
            (row.value(forKey: newAttribute) as? NSNumber)?.int64Value,
            "`nextRetryAt` must exist on the migrated row."
        )
        XCTAssertEqual(nextRetryAt, 0, "Existing rows must default `nextRetryAt` to 0.")
    }

    // MARK: - Fixtures

    /// Create a fresh v1 SQLite store at `tempDirectory/<named>.sqlite`,
    /// insert the fixture row, save, and detach the store so the file is closed
    /// (avoids SQLite/WAL lock contention with later coordinators).
    private func makeV1Store(named name: String) throws -> URL {
        let storeURL = tempDirectory.appendingPathComponent("\(name).sqlite")
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: v1Model)
        let store = try coordinator.addPersistentStore(
            type: .sqlite,
            configuration: nil,
            at: storeURL,
            options: nil
        )

        let context = NSManagedObjectContext(concurrencyType: .privateQueueConcurrencyType)
        context.persistentStoreCoordinator = coordinator

        let object = NSEntityDescription.insertNewObject(forEntityName: entityName, into: context)
        object.setValue(fixtureID, forKey: "id")
        object.setValue("POST", forKey: "method")
        object.setValue(fixtureURL, forKey: "url")
        object.setValue(fixtureCreatedAt, forKey: "createdAt")
        object.setValue(fixtureAttempts, forKey: "attempts")
        object.setValue(Int64(0), forKey: "lastAttemptAt")
        object.setValue("NORMAL", forKey: "priority")
        object.setValue("PENDING", forKey: "status")
        try context.save()

        // Detach so the SQLite connection is released before migration reopens it.
        try coordinator.remove(store)
        return storeURL
    }

    // MARK: - Bundle / model loading

    /// Locate the compiled `SyncProvider.momd` in the XCTest bundle. CocoaPods
    /// may place test resources directly in the test bundle or in a nested
    /// `.bundle`, so we search both plus all loaded bundles.
    private static func locateCompiledModel() -> URL? {
        let resourceName = "SyncProvider"
        var candidates: [Bundle] = [Bundle(for: CoreDataMigrationV1ToV2Tests.self)]
        candidates.append(contentsOf: Bundle.allBundles)

        for bundle in candidates {
            if let url = bundle.url(forResource: resourceName, withExtension: "momd") {
                return url
            }
            // Nested resource bundle (e.g. `SyncProvider.bundle`).
            if let nestedURL = bundle.url(forResource: resourceName, withExtension: "bundle"),
               let nested = Bundle(url: nestedURL),
               let url = nested.url(forResource: resourceName, withExtension: "momd") {
                return url
            }
        }
        return nil
    }

    /// Load a single model version slice (`<version>.mom`) out of a `.momd`.
    private static func model(version: String, in momd: URL) -> NSManagedObjectModel? {
        let momURL = momd.appendingPathComponent("\(version).mom")
        return NSManagedObjectModel(contentsOf: momURL)
    }

    /// Decouple the loaded model from the concrete `SyncItemEntity` subclass by
    /// repointing every entity at `NSManagedObject`. The fixture writes/reads
    /// via KVC, so no `@NSManaged` property is touched that a given version's
    /// model does not back. (Version hashes are unaffected, so inference still
    /// resolves correctly.)
    private static func genericized(_ model: NSManagedObjectModel) -> NSManagedObjectModel {
        let genericClassName = NSStringFromClass(NSManagedObject.self)
        for entity in model.entities {
            entity.managedObjectClassName = genericClassName
        }
        return model
    }
}
