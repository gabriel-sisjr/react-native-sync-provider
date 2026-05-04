import XCTest
@testable import SyncProvider

final class BackgroundSyncManagerTests: XCTestCase {
    private var stack: CoreDataStack!
    private var dao: SyncItemDao!
    private var storage: SyncQueueStorage!
    private var emitter: MockSyncEventEmitter!
    private var dispatcher: SyncDispatcher!
    private var manager: BackgroundSyncManager!

    override func setUp() {
        super.setUp()
        stack = InMemoryCoreDataStack.make()
        dao = SyncItemDao(stack: stack)
        storage = SyncQueueStorage(dao: dao, capacity: 10)
        emitter = MockSyncEventEmitter()
        dispatcher = SyncDispatcher(storage: storage,
                                    emitter: emitter,
                                    connectivity: ConnectivityMonitor.shared,
                                    config: .default,
                                    foregroundSession: URLSession(configuration: .ephemeral),
                                    backgroundSession: URLSession(configuration: .ephemeral))
        manager = BackgroundSyncManager(dispatcher: dispatcher, emitter: emitter)
    }

    override func tearDown() {
        manager = nil
        dispatcher = nil
        emitter = nil
        storage = nil
        dao = nil
        stack = nil
        super.tearDown()
    }

    func test_isEnabled_defaultsToFalse() {
        XCTAssertFalse(manager.isEnabled)
    }

    func test_disable_isIdempotent_andLeavesIsEnabledFalse() {
        manager.disable()
        manager.disable()
        XCTAssertFalse(manager.isEnabled)
    }

    func test_backgroundSyncOptionsValue_isEquatable() {
        let a = BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                            requiresCharging: true,
                                            requiresUnmeteredNetwork: false,
                                            requiresDeviceIdle: nil,
                                            taskIdentifier: "x")
        let b = BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                            requiresCharging: true,
                                            requiresUnmeteredNetwork: false,
                                            requiresDeviceIdle: nil,
                                            taskIdentifier: "x")
        let c = BackgroundSyncOptionsValue(minimumIntervalMs: 30_000,
                                            requiresCharging: true,
                                            requiresUnmeteredNetwork: false,
                                            requiresDeviceIdle: nil,
                                            taskIdentifier: "x")
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(a, c)
    }
}
