import XCTest
@testable import SyncProvider

/// Handlers are registered at process start, before JS exists.
final class BackgroundTaskRegistrarTests: XCTestCase {
    private var scheduler: FakeBackgroundTaskScheduler!
    private var fixture: EngineFixture!

    override func setUp() {
        super.setUp()
        scheduler = FakeBackgroundTaskScheduler()
        fixture = EngineFixture()
    }

    override func tearDown() {
        fixture.tearDown()
        super.tearDown()
    }

    private func makeRegistrar(permitted: [String],
                               onResolve: @escaping @Sendable () -> Void = {}) -> BackgroundTaskRegistrar {
        let fixture = self.fixture!
        let engine = fixture.buildEngine()
        return BackgroundTaskRegistrar(scheduler: scheduler,
                                       permittedIdentifiers: { permitted },
                                       resolveManager: {
                                           onResolve()
                                           return engine.background
                                       })
    }

    func test_registersBothDeclaredIdentifiers() {
        makeRegistrar(permitted: [kBackgroundRefreshIdentifier, kBackgroundProcessingIdentifier]).registerHandlers()

        XCTAssertEqual(scheduler.registeredIdentifiers,
                       [kBackgroundRefreshIdentifier, kBackgroundProcessingIdentifier].sorted())
    }

    /// `register` traps for an identifier the app did not declare. A consumer
    /// that does not use background sync must still launch.
    func test_appDeclaringNothing_registersNothing() {
        makeRegistrar(permitted: []).registerHandlers()

        XCTAssertTrue(scheduler.registeredIdentifiers.isEmpty)
    }

    func test_onlyDeclaredIdentifiers_areRegistered() {
        makeRegistrar(permitted: [kBackgroundProcessingIdentifier, "com.example.unrelated"]).registerHandlers()

        XCTAssertEqual(scheduler.registeredIdentifiers, [kBackgroundProcessingIdentifier])
    }

    /// A second `register` for one identifier traps, and the bridge may ask
    /// again after a JS reload.
    func test_registeringTwice_registersOnce() {
        let registrar = makeRegistrar(permitted: [kBackgroundRefreshIdentifier])

        registrar.registerHandlers()
        registrar.registerHandlers()

        XCTAssertEqual(scheduler.registrationCalls, 1)
    }

    /// Registration runs before `main`, so it must not build the engine.
    func test_engineIsResolvedOnlyWhenATaskFires() throws {
        let resolved = RequestLog()
        let registrar = makeRegistrar(permitted: [kBackgroundRefreshIdentifier]) { resolved.record() }

        registrar.registerHandlers()
        XCTAssertTrue(resolved.times.isEmpty, "registration must stay lazy")

        let task = FakeBackgroundTask()
        scheduler.launch(kBackgroundRefreshIdentifier, task: task)

        XCTAssertEqual(resolved.times.count, 1)
        XCTAssertEqual(task.completedResults, [true], "never enabled, so the fired task completes inert")
    }

    /// The whole path with no JS: a task fires, the engine comes up from
    /// persisted state alone, and the queue is dispatched.
    func test_firedTask_withNoJS_dispatchesTheQueue() async throws {
        let first = await fixture.makeEngine()
        await first.configure(.sample(defaultHeaders: ["Authorization": "Bearer persisted"]))
        try first.background.enable(options: BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                                                        requiresCharging: nil,
                                                                        requiresUnmeteredNetwork: nil,
                                                                        requiresDeviceIdle: nil,
                                                                        foregroundOnly: nil,
                                                                        taskIdentifier: nil))
        let id = try await first.storage.enqueue(input: .sample())
        first.shutdown()
        URLProtocolStub.setHandler(stubResponse(200))
        defer { URLProtocolStub.setHandler(nil) }

        let fixture = self.fixture!
        let registrar = BackgroundTaskRegistrar(scheduler: scheduler,
                                                permittedIdentifiers: { [kBackgroundProcessingIdentifier] },
                                                resolveManager: { fixture.buildEngine().background })
        registrar.registerHandlers()

        let task = FakeBackgroundTask()
        scheduler.launch(kBackgroundProcessingIdentifier, task: task)

        await task.waitForCompletion()
        XCTAssertEqual(task.completedResults, [true])
        let request = try XCTUnwrap(URLProtocolStub.capturedRequests.first)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer persisted")
        let remaining = try await first.storage.getAll()
        XCTAssertFalse(remaining.contains { $0.id == id })
    }
}
