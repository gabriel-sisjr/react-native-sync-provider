import XCTest
@testable import SyncProvider

/// Background options outlive the process, so a task the OS fires with no JS
/// loaded still knows what the app asked for.
final class BackgroundSyncPersistenceTests: XCTestCase {
    private var stack: CoreDataStack!
    private var storage: SyncQueueStorage!
    private var emitter: MockSyncEventEmitter!
    private var dispatcher: SyncDispatcher!
    private var scheduler: FakeBackgroundTaskScheduler!
    private var manager: BackgroundSyncManager!
    private var defaults: UserDefaults!
    private var suite: String!

    override func setUp() {
        super.setUp()
        stack = InMemoryCoreDataStack.make()
        storage = SyncQueueStorage(dao: SyncItemDao(stack: stack), capacity: 10)
        emitter = MockSyncEventEmitter()
        dispatcher = SyncDispatcher(storage: storage,
                                    emitter: emitter,
                                    connectivity: ConnectivityMonitor.shared,
                                    config: .default,
                                    foregroundSession: URLSession(configuration: .ephemeral),
                                    backgroundSession: URLSession(configuration: .ephemeral))
        (defaults, suite) = UserDefaults.ephemeral()
        scheduler = FakeBackgroundTaskScheduler()
        manager = makeManager()
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    /// A new manager over the same persisted store, which is what a relaunch
    /// builds.
    private func makeManager() -> BackgroundSyncManager {
        BackgroundSyncManager(dispatcher: dispatcher,
                              emitter: emitter,
                              scheduler: scheduler,
                              store: BackgroundSyncOptionsStore(defaults: defaults))
    }

    private func makeOptions(foregroundOnly: Bool?) -> BackgroundSyncOptionsValue {
        BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                   requiresCharging: nil,
                                   requiresUnmeteredNetwork: nil,
                                   requiresDeviceIdle: nil,
                                   foregroundOnly: foregroundOnly,
                                   taskIdentifier: nil)
    }

    /// A cold launch must finish launch recovery before the first flush, or
    /// rows left IN_FLIGHT are invisible to that flush.
    func test_handlers_finishPreparing_beforeFlushing() async throws {
        try manager.enable(options: makeOptions(foregroundOnly: false))
        let order = RequestLog()
        let emitter = self.emitter!
        manager.setPrepare {
            // Recorded as 1 when the prepare step ran with no cycle started.
            order.record(at: emitter.events(ofType: "SYNC_STARTED").isEmpty ? 1 : 0)
        }

        let task = FakeBackgroundTask()
        manager.handleAppRefresh(task: task)
        await task.waitForCompletion()

        XCTAssertEqual(order.times, [1], "preparation must run, and finish before the flush")
        XCTAssertEqual(emitter.events(ofType: "SYNC_STARTED").count, 1)
    }

    func test_options_surviveARelaunch() throws {
        let options = BackgroundSyncOptionsValue(minimumIntervalMs: 900_000,
                                                 requiresCharging: true,
                                                 requiresUnmeteredNetwork: true,
                                                 requiresDeviceIdle: false,
                                                 foregroundOnly: false,
                                                 taskIdentifier: "custom")
        try manager.enable(options: options)

        let stored = BackgroundSyncOptionsStore(defaults: defaults).load()

        XCTAssertEqual(stored.options, options)
        XCTAssertTrue(stored.enabled)
    }

    func test_afterRelaunch_withNoJS_aFiredTaskContinuesTheChain() throws {
        try manager.enable(options: makeOptions(foregroundOnly: false))
        scheduler.reset()

        let relaunched = makeManager()
        XCTAssertTrue(relaunched.isEnabled)
        relaunched.handleAppRefresh(task: FakeBackgroundTask())

        XCTAssertEqual(scheduler.submittedIdentifiers,
                       [kBackgroundRefreshIdentifier, kBackgroundProcessingIdentifier],
                       "a relaunch must not break the chain")
    }

    func test_afterRelaunch_foregroundOnlyIsStillHonored() throws {
        try manager.enable(options: makeOptions(foregroundOnly: true))
        scheduler.reset()
        emitter.clear()
        dispatcher.hooks.foregroundOnly = false

        let relaunched = makeManager()
        XCTAssertTrue(relaunched.isForegroundOnly)
        XCTAssertTrue(dispatcher.hooks.foregroundOnly, "uploads must stay off the background session")

        let task = FakeBackgroundTask()
        relaunched.handleAppRefresh(task: task)

        XCTAssertEqual(task.completedResults, [true])
        XCTAssertTrue(scheduler.submittedIdentifiers.isEmpty)
        XCTAssertTrue(emitter.events(ofType: "BACKGROUND_SYNC_STARTED").isEmpty)
    }

    func test_disable_survivesARelaunch_andMakesFiredTasksInert() throws {
        try manager.enable(options: makeOptions(foregroundOnly: false))
        manager.disable()
        scheduler.reset()

        let relaunched = makeManager()
        XCTAssertFalse(relaunched.isEnabled)

        let task = FakeBackgroundTask()
        relaunched.handleProcessing(task: task)

        XCTAssertEqual(task.completedResults, [true])
        XCTAssertTrue(scheduler.submittedIdentifiers.isEmpty)
    }

    func test_neverEnabled_aFiredTaskIsInert() {
        let task = FakeBackgroundTask()
        manager.handleAppRefresh(task: task)

        XCTAssertEqual(task.completedResults, [true])
        XCTAssertTrue(scheduler.submittedIdentifiers.isEmpty)
    }

    /// `disable()` turns scheduling off; it does not re-allow background
    /// uploads for an app that opted out of them.
    func test_disable_keepsForegroundOnlyInForce() throws {
        try manager.enable(options: makeOptions(foregroundOnly: true))

        manager.disable()

        XCTAssertFalse(manager.isEnabled)
        XCTAssertTrue(manager.isForegroundOnly)
        XCTAssertTrue(dispatcher.hooks.foregroundOnly)
    }
}
