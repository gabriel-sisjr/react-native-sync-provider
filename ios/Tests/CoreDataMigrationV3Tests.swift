import CoreData
import XCTest
@testable import SyncProvider

/// Stores written by models v1 and v2, opened through the production
/// `CoreDataStack` load: before it loads the store, failed items move to the
/// dead-letter store, explicitly, never by inference. The fixtures are real
/// SQLite stores, opened with persistent history tracking as the app opens
/// them. Tests are named after the parity scenarios the Android suite runs.
final class CoreDataMigrationV3Tests: XCTestCase {
    private var momd: URL!
    private var directory: URL!

    private let storeOptions: [AnyHashable: Any] = [NSPersistentHistoryTrackingKey: true as NSNumber]

    /// One `SyncItemEntity` row of a fixture store.
    private struct Row {
        let id: String
        let status: String
        var attempts: Int32 = 0
        var createdAt: Int64
        var lastAttemptAt: Int64 = 0
        var nextRetryAt: Int64 = 0
        var lastErrorCode: String?
    }

    /// Two queued rows, one in flight, and three failed ones covering every
    /// legacy fallback.
    private let rows = [
        Row(id: "01A-PENDING", status: "PENDING", createdAt: 1_000),
        Row(id: "01B-COOLING", status: "PENDING", attempts: 2, createdAt: 2_000,
            lastAttemptAt: 2_500, nextRetryAt: 9_000, lastErrorCode: "SERVER_ERROR"),
        Row(id: "01C-INFLIGHT", status: "IN_FLIGHT", attempts: 1, createdAt: 2_500, lastAttemptAt: 2_600),
        Row(id: "01D-FAILED", status: "FAILED", attempts: 5, createdAt: 3_000,
            lastAttemptAt: 3_500, lastErrorCode: "SERVER_ERROR"),
        Row(id: "01E-FAILED", status: "FAILED", attempts: 1, createdAt: 4_000, lastErrorCode: nil),
        Row(id: "01F-FAILED", status: "FAILED", attempts: 3, createdAt: 5_000, lastErrorCode: ""),
    ]

    override func setUpWithError() throws {
        try super.setUpWithError()
        momd = try XCTUnwrap(Self.locateCompiledModel(), "SyncProvider.momd is not in the test bundle; re-run pod install")
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("syncprovider-v3-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let directory {
            try? FileManager.default.removeItem(at: directory)
        }
        try super.tearDownWithError()
    }

    // MARK: - Migration through the production load

    func test_migration_v2_movesFailedRows_keepsPending() async throws {
        let url = try makeStore(version: "SyncProvider 2")

        try await assertMigrated(storeAt: url, fromV1: false)
    }

    /// Explicit v1 to v3, in one step: v1 has no `nextRetryAt`, which the
    /// queued rows get as 0.
    func test_migration_v1_movesFailedRows_keepsPending() async throws {
        let url = try makeStore(version: "SyncProvider")

        try await assertMigrated(storeAt: url, fromV1: true)
    }

    func test_storeAlreadyAtTheCurrentModel_isLeftAlone() async throws {
        let url = try makeStore(version: "SyncProvider 2")
        let first = CoreDataStack(storeURL: url, modelURL: momd)
        _ = first.persistentContainer
        let currentModel = try XCTUnwrap(NSManagedObjectModel(contentsOf: momd))

        let migratedAgain = try CoreDataMigration.migrateStoreIfNeeded(at: url, momd: momd,
                                                                      currentModel: currentModel,
                                                                      storeOptions: storeOptions)

        XCTAssertNil(migratedAgain)
        let letters = try await SyncItemDao(stack: first).fetchDeadLetters()
        XCTAssertEqual(letters.count, 3)
    }

    func test_missingStore_isLeftToTheLoad() throws {
        let url = directory.appendingPathComponent("absent.sqlite")
        let currentModel = try XCTUnwrap(NSManagedObjectModel(contentsOf: momd))

        let migrated = try CoreDataMigration.migrateStoreIfNeeded(at: url, momd: momd,
                                                                 currentModel: currentModel,
                                                                 storeOptions: storeOptions)

        XCTAssertNil(migrated)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    // MARK: - The model

    /// Inference would accept v2 to v3: the queue entity did not change. That
    /// is why the migration is explicit.
    func test_v2ToV3_wouldBeInferable_soInferenceWouldSilentlySkipTheMove() throws {
        let v2 = try XCTUnwrap(CoreDataMigration.slice("SyncProvider 2", in: momd))
        let v3 = try XCTUnwrap(CoreDataMigration.slice("SyncProvider 3", in: momd))

        XCTAssertEqual(v2.entityVersionHashesByName["SyncItemEntity"], v3.entityVersionHashesByName["SyncItemEntity"])
        XCTAssertNoThrow(try NSMappingModel.inferredMappingModel(forSourceModel: v2, destinationModel: v3))
    }

    func test_inMemoryModel_matchesTheCompiledCurrentModel() throws {
        let compiled = try XCTUnwrap(NSManagedObjectModel(contentsOf: momd))

        XCTAssertEqual(InMemoryCoreDataStack.makeModel().entityVersionHashesByName, compiled.entityVersionHashesByName)
    }

    /// `.xccurrentversion` must name the newest version, and every older one
    /// must have a migration.
    func test_currentVersion_isTheHighestBundledVersion() throws {
        let info = try XCTUnwrap(NSDictionary(contentsOf: momd.appendingPathComponent("VersionInfo.plist")))
        let current = try XCTUnwrap(info["NSManagedObjectModel_CurrentVersionName"] as? String)
        let versions = try FileManager.default.contentsOfDirectory(atPath: momd.path)
            .filter { $0.hasSuffix(".mom") }
            .map { String($0.dropLast(".mom".count)) }
        let number: (String) -> Int = { version in
            version == "SyncProvider" ? 1 : Int(version.split(separator: " ").last ?? "") ?? 0
        }

        XCTAssertEqual(current, versions.max { number($0) < number($1) })
        XCTAssertEqual(current, CoreDataMigration.destinationVersion)
        XCTAssertEqual(Set(versions), Set(CoreDataMigration.sourceVersions + [CoreDataMigration.destinationVersion]))
    }

    func test_legacyFields_fallBackAsTheContractSays() {
        XCTAssertEqual(LegacyDeadLetterFields(createdAt: 10, lastAttemptAt: 20, lastErrorCode: "TIMEOUT"),
                       LegacyDeadLetterFields(createdAt: 99, lastAttemptAt: 20, lastErrorCode: "TIMEOUT"))
        let attempted = LegacyDeadLetterFields(createdAt: 10, lastAttemptAt: 20, lastErrorCode: "TIMEOUT")
        XCTAssertEqual(attempted.deadLetteredAt, 20)
        XCTAssertEqual(attempted.lastAttemptAt, 20)
        XCTAssertEqual(attempted.lastErrorCode, "TIMEOUT")
        let never = LegacyDeadLetterFields(createdAt: 10, lastAttemptAt: 0, lastErrorCode: nil)
        XCTAssertEqual(never.deadLetteredAt, 10)
        XCTAssertEqual(never.lastAttemptAt, 10)
        XCTAssertEqual(never.lastErrorCode, "MAX_ATTEMPTS_EXCEEDED")
        XCTAssertEqual(LegacyDeadLetterFields(createdAt: 1, lastAttemptAt: 0, lastErrorCode: "").lastErrorCode,
                       "MAX_ATTEMPTS_EXCEEDED")
    }

    // MARK: - Assertions

    private func assertMigrated(storeAt url: URL, fromV1: Bool,
                                file: StaticString = #filePath, line: UInt = #line) async throws {
        let stack = CoreDataStack(storeURL: url, modelURL: momd)
        _ = stack.persistentContainer
        XCTAssertNil(stack.initializationError, file: file, line: line)
        let dao = SyncItemDao(stack: stack)

        let queue = try await dao.fetchAll()
        XCTAssertEqual(queue.map(\.id).sorted(), ["01A-PENDING", "01B-COOLING", "01C-INFLIGHT"],
                       "PENDING and IN_FLIGHT rows stay; no FAILED row is left behind", file: file, line: line)
        let cooling = try XCTUnwrap(queue.first { $0.id == "01B-COOLING" })
        XCTAssertEqual(cooling.status, "PENDING", file: file, line: line)
        XCTAssertEqual(cooling.attempts, 2, file: file, line: line)
        XCTAssertEqual(cooling.createdAt, 2_000, file: file, line: line)
        XCTAssertEqual(cooling.lastAttemptAt, 2_500, file: file, line: line)
        XCTAssertEqual(cooling.lastErrorCode, "SERVER_ERROR", file: file, line: line)
        XCTAssertEqual(cooling.nextRetryAt, fromV1 ? 0 : 9_000, file: file, line: line)
        XCTAssertEqual(queue.first { $0.id == "01C-INFLIGHT" }?.status, "IN_FLIGHT", file: file, line: line)

        let letters = try await dao.fetchDeadLetters()
        XCTAssertEqual(letters.map(\.id), ["01D-FAILED", "01E-FAILED", "01F-FAILED"], file: file, line: line)
        guard letters.count == 3 else { return }
        let attempted = letters[0]
        XCTAssertEqual(attempted.deadLetteredAt, 3_500, "the last attempt", file: file, line: line)
        XCTAssertEqual(attempted.lastAttemptAt, 3_500, file: file, line: line)
        XCTAssertEqual(attempted.lastErrorCode, "SERVER_ERROR", file: file, line: line)
        XCTAssertEqual(attempted.attempts, 5, file: file, line: line)
        XCTAssertEqual(attempted.createdAt, 3_000, file: file, line: line)
        XCTAssertEqual(attempted.url, "https://api.test/01D-FAILED", file: file, line: line)
        XCTAssertEqual(attempted.headers, ["X-Item": "01D-FAILED"], file: file, line: line)
        XCTAssertEqual(attempted.body, "{\"id\":\"01D-FAILED\"}", file: file, line: line)
        XCTAssertEqual(attempted.priority, "HIGH", file: file, line: line)
        for letter in letters {
            XCTAssertNil(letter.lastErrorMessage, "no message is made up for \(letter.id)", file: file, line: line)
        }
        XCTAssertEqual(letters[1].deadLetteredAt, 4_000, "never attempted: creation", file: file, line: line)
        XCTAssertEqual(letters[1].lastAttemptAt, 4_000, file: file, line: line)
        XCTAssertEqual(letters[1].lastErrorCode, "MAX_ATTEMPTS_EXCEEDED", file: file, line: line)
        XCTAssertEqual(letters[2].lastErrorCode, "MAX_ATTEMPTS_EXCEEDED", "an empty code too", file: file, line: line)

        let history = try await dao.fetchHistory()
        XCTAssertEqual(history.map(\.id), ["RESULT-1"], file: file, line: line)

        let metadata = try NSPersistentStoreCoordinator.metadataForPersistentStore(ofType: NSSQLiteStoreType,
                                                                                  at: url,
                                                                                  options: storeOptions)
        let current = try XCTUnwrap(NSManagedObjectModel(contentsOf: momd))
        XCTAssertTrue(current.isConfiguration(withName: nil, compatibleWithStoreMetadata: metadata),
                      "the store on disk is at the current model", file: file, line: line)
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .filter { $0.contains("-migrating-") }
        XCTAssertEqual(leftovers, [], "the migrated copy is gone", file: file, line: line)
    }

    // MARK: - Fixtures

    /// A real SQLite store written by `version`, closed before returning.
    private func makeStore(version: String) throws -> URL {
        let model = try XCTUnwrap(CoreDataMigration.slice(version, in: momd))
        let url = directory.appendingPathComponent("SyncProvider.sqlite")
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: model)
        let store = try coordinator.addPersistentStore(type: .sqlite, configuration: nil, at: url,
                                                       options: storeOptions)
        let context = NSManagedObjectContext(concurrencyType: .privateQueueConcurrencyType)
        context.persistentStoreCoordinator = coordinator
        let hasNextRetryAt = model.entitiesByName["SyncItemEntity"]?.attributesByName["nextRetryAt"] != nil
        let rows = self.rows
        try context.performAndWait {
            for row in rows {
                let object = NSEntityDescription.insertNewObject(forEntityName: "SyncItemEntity", into: context)
                object.setValue(row.id, forKey: "id")
                object.setValue("POST", forKey: "method")
                object.setValue("https://api.test/\(row.id)", forKey: "url")
                object.setValue(try JSONSerialization.data(withJSONObject: ["X-Item": row.id]), forKey: "headersJSON")
                object.setValue("{\"id\":\"\(row.id)\"}", forKey: "body")
                object.setValue("application/json", forKey: "contentType")
                object.setValue(row.id == "01D-FAILED" ? "HIGH" : "NORMAL", forKey: "priority")
                object.setValue(row.createdAt, forKey: "createdAt")
                object.setValue(row.attempts, forKey: "attempts")
                object.setValue(row.lastAttemptAt, forKey: "lastAttemptAt")
                object.setValue(row.lastErrorCode, forKey: "lastErrorCode")
                object.setValue(row.status, forKey: "status")
                if hasNextRetryAt {
                    object.setValue(row.nextRetryAt, forKey: "nextRetryAt")
                }
            }
            let result = NSEntityDescription.insertNewObject(forEntityName: "SyncResultEntity", into: context)
            result.setValue("RESULT-1", forKey: "id")
            result.setValue(Int64(100), forKey: "startedAt")
            result.setValue(Int64(200), forKey: "finishedAt")
            try context.save()
        }
        try coordinator.remove(store)
        return url
    }

    private static func locateCompiledModel() -> URL? {
        var candidates: [Bundle] = [Bundle(for: CoreDataMigrationV3Tests.self)]
        candidates.append(contentsOf: Bundle.allBundles)
        for bundle in candidates {
            if let url = bundle.url(forResource: "SyncProvider", withExtension: "momd") {
                return url
            }
            if let nestedURL = bundle.url(forResource: "SyncProvider", withExtension: "bundle"),
               let nested = Bundle(url: nestedURL),
               let url = nested.url(forResource: "SyncProvider", withExtension: "momd") {
                return url
            }
        }
        return nil
    }
}
