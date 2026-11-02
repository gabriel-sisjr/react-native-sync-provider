import CoreData
import XCTest
@testable import SyncProvider

final class CoreDataStackTests: XCTestCase {
    /// JS calls, launch recovery and a BGTask launch all race on first access.
    /// Two containers on one file would run the lightweight migration twice.
    func test_concurrentFirstAccess_loadsTheStoreOnce() async {
        let loads = RequestLog()
        let model = InMemoryCoreDataStack.makeModel()
        let stack = CoreDataStack(load: {
            loads.record()
            Thread.sleep(forTimeInterval: 0.05)
            return (NSPersistentContainer(name: "Concurrent", managedObjectModel: model), nil)
        })

        await withTaskGroup(of: ObjectIdentifier.self) { group in
            for _ in 0..<32 {
                group.addTask { ObjectIdentifier(stack.persistentContainer) }
            }
            var seen = Set<ObjectIdentifier>()
            for await identifier in group {
                seen.insert(identifier)
            }
            XCTAssertEqual(seen.count, 1)
        }

        XCTAssertEqual(loads.times.count, 1)
    }

    /// A container with no store crashes on first use, so the failure must
    /// reach the caller first.
    func test_failedLoad_rejectsWorkInsteadOfCrashing() async throws {
        let model = InMemoryCoreDataStack.makeModel()
        let stack = CoreDataStack(load: {
            (NSPersistentContainer(name: "Broken", managedObjectModel: model),
             CoreDataStackError.persistentStoreLoadFailed(underlying: URLError(.cannotOpenFile)))
        })

        do {
            _ = try await stack.performBackgroundTask { _ in true }
            XCTFail("expected the load error")
        } catch let error as CoreDataStackError {
            XCTAssertNotNil(error.errorDescription)
        }
        XCTAssertNotNil(stack.initializationError)
    }

    func test_failedLoad_reachesTheBridgeAsAnUnavailableModule() {
        let wrapped = wrapAsSyncProviderError(CoreDataStackError.modelNotFound)

        XCTAssertEqual(wrapped.codeName, "NATIVE_MODULE_UNAVAILABLE")
    }

    /// Listeners track a cycle from `SYNC_STARTED` to its terminal event.
    func test_cycleThatDies_stillEndsWithATerminalEvent() async {
        let stack = CoreDataStack(load: {
            (NSPersistentContainer(name: "Broken", managedObjectModel: NSManagedObjectModel()),
             CoreDataStackError.modelNotFound)
        })
        let storage = SyncQueueStorage(dao: SyncItemDao(stack: stack), capacity: 10)
        let emitter = MockSyncEventEmitter()
        let dispatcher = SyncDispatcher(storage: storage,
                                        emitter: emitter,
                                        connectivity: ConnectivityMonitor.shared,
                                        config: .default,
                                        foregroundSession: URLSession(configuration: .ephemeral),
                                        backgroundSession: URLSession(configuration: .ephemeral))

        do {
            _ = try await dispatcher.flush()
            XCTFail("expected the store error")
        } catch {
            XCTAssertEqual(wrapAsSyncProviderError(error).codeName, "NATIVE_MODULE_UNAVAILABLE")
        }

        XCTAssertEqual(emitter.emittedEvents.map(\.type), ["SYNC_STARTED", "SYNC_FAILED"])
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "NATIVE_MODULE_UNAVAILABLE")
        let isRunning = await dispatcher.cycleRunning()
        XCTAssertFalse(isRunning, "a dead cycle must release the guard")
    }

    func test_failedLoad_reachesQueueCallers() async {
        let stack = CoreDataStack(load: {
            (NSPersistentContainer(name: "Broken", managedObjectModel: NSManagedObjectModel()),
             CoreDataStackError.modelNotFound)
        })
        let storage = SyncQueueStorage(dao: SyncItemDao(stack: stack), capacity: 10)

        do {
            _ = try await storage.getSize()
            XCTFail("expected the load error")
        } catch {
            XCTAssertEqual(wrapAsSyncProviderError(error).codeName, "NATIVE_MODULE_UNAVAILABLE")
        }
    }
}
