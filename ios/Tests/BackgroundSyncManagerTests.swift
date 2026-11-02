import XCTest
@testable import SyncProvider
#if canImport(BackgroundTasks)
import BackgroundTasks
#endif

final class BackgroundSyncManagerTests: XCTestCase {
    private var stack: CoreDataStack!
    private var dao: SyncItemDao!
    private var storage: SyncQueueStorage!
    private var emitter: MockSyncEventEmitter!
    private var dispatcher: SyncDispatcher!
    private var scheduler: FakeBackgroundTaskScheduler!
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
        scheduler = FakeBackgroundTaskScheduler()
        manager = makeManager()
    }

    override func tearDown() {
        manager = nil
        scheduler = nil
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
                                            foregroundOnly: true,
                                            taskIdentifier: "x")
        let b = BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                            requiresCharging: true,
                                            requiresUnmeteredNetwork: false,
                                            requiresDeviceIdle: nil,
                                            foregroundOnly: true,
                                            taskIdentifier: "x")
        let c = BackgroundSyncOptionsValue(minimumIntervalMs: 30_000,
                                            requiresCharging: true,
                                            requiresUnmeteredNetwork: false,
                                            requiresDeviceIdle: nil,
                                            foregroundOnly: true,
                                            taskIdentifier: "x")
        let d = BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                            requiresCharging: true,
                                            requiresUnmeteredNetwork: false,
                                            requiresDeviceIdle: nil,
                                            foregroundOnly: false,
                                            taskIdentifier: "x")
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(a, c)
        XCTAssertNotEqual(a, d, "foregroundOnly participates in equality")
    }

    // MARK: - `foregroundOnly`

    #if canImport(BackgroundTasks)
    private func makeManager() -> BackgroundSyncManager {
        BackgroundSyncManager(dispatcher: dispatcher, emitter: emitter, scheduler: scheduler)
    }

    private func makeOptions(foregroundOnly: Bool?) -> BackgroundSyncOptionsValue {
        BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                   requiresCharging: nil,
                                   requiresUnmeteredNetwork: nil,
                                   requiresDeviceIdle: nil,
                                   foregroundOnly: foregroundOnly,
                                   taskIdentifier: nil)
    }

    func test_enable_withForegroundOnly_doesNotSchedule_andCancelsBothIdentifiers() throws {
        try manager.enable(options: makeOptions(foregroundOnly: true))

        XCTAssertTrue(scheduler.submittedIdentifiers.isEmpty,
                      "foregroundOnly must not submit any BGTask request")
        XCTAssertEqual(Set(scheduler.cancelledIdentifiers),
                       [kBackgroundRefreshIdentifier, kBackgroundProcessingIdentifier],
                       "foregroundOnly must clear both already-submitted requests")
        XCTAssertTrue(manager.isEnabled,
                      "feature stays enabled; the flag, not `enabled`, governs scheduling")
        XCTAssertTrue(manager.isForegroundOnly)
    }

    func test_enable_withForegroundOnlyFalse_schedulesAppRefresh_andCancelsNothing() throws {
        try manager.enable(options: makeOptions(foregroundOnly: false))

        XCTAssertEqual(scheduler.submittedIdentifiers, [kBackgroundRefreshIdentifier])
        XCTAssertTrue(scheduler.cancelledIdentifiers.isEmpty)
        XCTAssertTrue(manager.isEnabled)
        XCTAssertFalse(manager.isForegroundOnly)
    }

    func test_enable_withForegroundOnlyNil_schedulesAppRefresh() throws {
        try manager.enable(options: makeOptions(foregroundOnly: nil))

        XCTAssertEqual(scheduler.submittedIdentifiers, [kBackgroundRefreshIdentifier])
        XCTAssertFalse(manager.isForegroundOnly)
    }

    func test_enable_withoutForegroundOnly_propagatesSubmitFailure() {
        scheduler.submitError = NSError(domain: "BGTaskSchedulerErrorDomain", code: 1)

        XCTAssertThrowsError(try manager.enable(options: makeOptions(foregroundOnly: false))) { error in
            guard let providerError = error as? SyncProviderError else {
                return XCTFail("expected SyncProviderError, got \(error)")
            }
            XCTAssertEqual(providerError.codeName, "BACKGROUND_TASK_REGISTRATION_FAILED")
        }
    }

    func test_flippingForegroundOnlyOff_schedulesAgain() throws {
        try manager.enable(options: makeOptions(foregroundOnly: true))
        scheduler.reset()

        try manager.enable(options: makeOptions(foregroundOnly: false))

        XCTAssertEqual(scheduler.submittedIdentifiers, [kBackgroundRefreshIdentifier])
        XCTAssertFalse(manager.isForegroundOnly)
        XCTAssertFalse(dispatcher.hooks.foregroundOnly)
    }

    func test_enable_publishesTheFlagToTheDispatcher() throws {
        try manager.enable(options: makeOptions(foregroundOnly: true))
        XCTAssertTrue(dispatcher.hooks.foregroundOnly)
    }

    /// The flag must be visible before the cancels. An OS launch landing right
    /// after them must not put a request back, or it would survive `enable`
    /// and fire after a relaunch.
    func test_handlerLandingDuringEnable_cannotResubmit() throws {
        try manager.enable(options: makeOptions(foregroundOnly: false))
        scheduler.reset()

        let task = FakeBackgroundTask()
        scheduler.onCancelled = { [manager] in
            manager?.handleAppRefresh(task: task)
        }
        try manager.enable(options: makeOptions(foregroundOnly: true))

        XCTAssertTrue(scheduler.submittedIdentifiers.isEmpty,
                      "a handler racing enable(foregroundOnly: true) resubmitted a request")
        XCTAssertEqual(task.completedResults, [true])
    }

    func test_handleAppRefresh_withForegroundOnly_completesInert_withoutReschedulingOrEmitting() throws {
        try manager.enable(options: makeOptions(foregroundOnly: true))
        scheduler.reset()
        emitter.clear()

        let task = FakeBackgroundTask()
        manager.handleAppRefresh(task: task)

        XCTAssertEqual(task.completedResults, [true], "inert handler completes successfully exactly once")
        XCTAssertTrue(scheduler.submittedIdentifiers.isEmpty, "inert handler must not re-schedule")
        XCTAssertTrue(emitter.events(ofType: "BACKGROUND_SYNC_STARTED").isEmpty,
                      "inert handler must not emit a started event")
    }

    func test_handleProcessing_withForegroundOnly_completesInert_withoutReschedulingOrEmitting() throws {
        try manager.enable(options: makeOptions(foregroundOnly: true))
        scheduler.reset()
        emitter.clear()

        let task = FakeBackgroundTask()
        manager.handleProcessing(task: task)

        XCTAssertEqual(task.completedResults, [true])
        XCTAssertTrue(scheduler.submittedIdentifiers.isEmpty)
        XCTAssertTrue(emitter.events(ofType: "BACKGROUND_SYNC_STARTED").isEmpty)
    }

    func test_handleAppRefresh_withoutForegroundOnly_reschedulesSynchronously() throws {
        try manager.enable(options: makeOptions(foregroundOnly: false))
        scheduler.reset()

        let task = FakeBackgroundTask()
        manager.handleAppRefresh(task: task)

        // The re-schedule (app-refresh + processing) is synchronous and runs
        // before the async flush `Task` is spawned, so this is deterministic.
        XCTAssertEqual(scheduler.submittedIdentifiers,
                       [kBackgroundRefreshIdentifier, kBackgroundProcessingIdentifier],
                       "non-foregroundOnly handler must keep the BGTask chain going")
    }

    func test_handleProcessing_withoutForegroundOnly_reschedulesProcessing() throws {
        try manager.enable(options: makeOptions(foregroundOnly: false))
        scheduler.reset()

        manager.handleProcessing(task: FakeBackgroundTask())

        XCTAssertEqual(scheduler.submittedIdentifiers, [kBackgroundProcessingIdentifier],
                       "the processing chain must continue")
    }

    func test_handlers_completeTheTask_afterFlushing() async throws {
        try manager.enable(options: makeOptions(foregroundOnly: false))

        let refresh = FakeBackgroundTask()
        manager.handleAppRefresh(task: refresh)
        let processing = FakeBackgroundTask()
        manager.handleProcessing(task: processing)

        await refresh.waitForCompletion()
        await processing.waitForCompletion()
        XCTAssertEqual(refresh.completedResults, [true])
        XCTAssertEqual(processing.completedResults, [true])
        XCTAssertEqual(emitter.events(ofType: "BACKGROUND_SYNC_COMPLETED").count, 2)
    }

    // MARK: - Background uploads

    private func makeDispatcher(isAppActive: @escaping @Sendable () async -> Bool) -> SyncDispatcher {
        SyncDispatcher(storage: storage,
                       emitter: emitter,
                       connectivity: ConnectivityMonitor.shared,
                       config: .default,
                       foregroundSession: URLSession(configuration: .ephemeral),
                       backgroundSession: URLSession(configuration: .ephemeral),
                       isAppActive: isAppActive)
    }

    func test_backgroundSession_isUsedOffscreen_unlessForegroundOnly() async {
        let dispatcher = makeDispatcher(isAppActive: { false })

        let offscreen = await dispatcher.shouldUseBackgroundSession()
        XCTAssertTrue(offscreen, "control: an inactive app uses the background session")

        dispatcher.hooks.foregroundOnly = true
        let gated = await dispatcher.shouldUseBackgroundSession()
        XCTAssertFalse(gated, "foregroundOnly must keep uploads off the OS-driven session")
    }

    func test_backgroundSession_isNotUsed_whileActive() async {
        let dispatcher = makeDispatcher(isAppActive: { true })
        let active = await dispatcher.shouldUseBackgroundSession()
        XCTAssertFalse(active)
    }
    #endif
}
