import XCTest
@testable import SyncProvider

/// The configuration must reach a process the app never spoke to.
final class SyncEngineConfigTests: XCTestCase {
    private var fixture: EngineFixture!

    override func setUp() {
        super.setUp()
        fixture = EngineFixture()
        URLProtocolStub.setHandler(nil)
    }

    override func tearDown() {
        fixture.tearDown()
        URLProtocolStub.setHandler(nil)
        super.tearDown()
    }

    private func item(_ id: String, in engine: SyncEngine) async throws -> StoredSyncItem {
        let all = try await engine.storage.getAll()
        return try XCTUnwrap(all.first { $0.id == id })
    }

    private var requestCount: Int {
        URLProtocolStub.capturedRequests.count
    }

    /// Configures, enqueues `count` items, and returns a fresh engine over the
    /// same stores, as a launch with no JS would.
    private func relaunchedEngine(after options: SyncOptionsSnapshot,
                                  enqueue count: Int = 1,
                                  emitter: SyncEventEmitter = MockSyncEventEmitter()) async throws
        -> (engine: SyncEngine, ids: [String]) {
        let first = await fixture.makeEngine()
        await first.configure(options)
        var ids: [String] = []
        for index in 0..<count {
            ids.append(try await first.storage.enqueue(input: .sample(url: "https://api.test/\(index)")))
        }
        first.shutdown()
        return (await fixture.makeColdEngine(emitter: emitter), ids)
    }

    /// A relaunched app that has not called `configureSync` yet, so it still
    /// runs on what was persisted.
    private func attach(_ engine: SyncEngine) async {
        engine.attachHybrid()
        await engine.waitUntilStarted()
    }

    /// Makes every row in `ids` attempted and already due.
    private func makeDue(_ ids: [String], in engine: SyncEngine) async throws {
        for id in ids {
            try await engine.storage.markInFlight(id: id)
            try await engine.storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 0)
        }
        fixture.clock.advance(ms: 1)
    }

    private func backgroundOptions() -> BackgroundSyncOptionsValue {
        BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                   requiresCharging: nil,
                                   requiresUnmeteredNetwork: nil,
                                   requiresDeviceIdle: nil,
                                   foregroundOnly: nil,
                                   taskIdentifier: nil)
    }

    // MARK: - Source

    func test_freshInstall_runsOnDefaults() async {
        let engine = await fixture.makeEngine()

        XCTAssertEqual(engine.configSource(), .defaults)
        XCTAssertEqual(engine.currentOptions(), .default)
    }

    func test_configureSync_makesTheSourceLive() async {
        let engine = await fixture.makeEngine()

        await engine.configure(.sample())

        XCTAssertEqual(engine.configSource(), .live)
    }

    // MARK: - Surviving a fresh engine

    func test_configuration_survivesAFreshEngine() async throws {
        let options = SyncOptionsSnapshot.sample(strategy: "MANUAL",
                                                 retryPolicy: .exact(maxAttempts: 9, baseDelayMs: 1_234),
                                                 batchSize: 7,
                                                 maxQueueSize: 11,
                                                 defaultHeaders: ["Authorization": "Bearer persisted"])

        let (engine, _) = try await relaunchedEngine(after: options, enqueue: 0)

        XCTAssertEqual(engine.configSource(), .persisted)
        XCTAssertEqual(engine.currentOptions(), options)
        let live = await engine.dispatcher.currentConfig()
        XCTAssertEqual(live.retryPolicy, options.retryPolicy)
        XCTAssertEqual(live.batchSize, 7)
        XCTAssertEqual(live.defaultHeaders, options.defaultHeaders)
    }

    func test_coldEngine_honorsTheConfiguredMaxAttempts() async throws {
        let (engine, ids) = try await relaunchedEngine(after: .sample(retryPolicy: .exact(maxAttempts: 2, baseDelayMs: 1)))
        let id = try XCTUnwrap(ids.first)
        URLProtocolStub.setHandler(stubResponse(503))

        _ = try await engine.dispatcher.flush()
        fixture.clock.advance(ms: 1)
        _ = try await engine.dispatcher.flush()

        let stored = try await item(id, in: engine)
        XCTAssertEqual(stored.status, "FAILED")
        XCTAssertEqual(requestCount, 2, "the default policy would allow 5")
    }

    func test_coldEngine_honorsTheConfiguredBatchSize() async throws {
        let (engine, _) = try await relaunchedEngine(after: .sample(batchSize: 2), enqueue: 3)
        URLProtocolStub.setHandler(stubResponse(200))

        _ = try await engine.dispatcher.flush()

        XCTAssertEqual(requestCount, 2)
    }

    func test_coldEngine_honorsTheConfiguredQueueCap() async throws {
        let (engine, _) = try await relaunchedEngine(after: .sample(maxQueueSize: 2), enqueue: 2)

        do {
            _ = try await engine.storage.enqueue(input: .sample())
            XCTFail("expected QUEUE_FULL")
        } catch let error as SyncProviderError {
            XCTAssertEqual(error.codeName, "QUEUE_FULL")
        }
    }

    func test_coldEngine_honorsTheConfiguredRequestTimeout() async throws {
        var options = SyncOptionsSnapshot.sample()
        options.requestTimeoutMs = 4_321
        let (engine, _) = try await relaunchedEngine(after: options)
        URLProtocolStub.setHandler(stubResponse(200))

        _ = try await engine.dispatcher.flush()

        let request = try XCTUnwrap(URLProtocolStub.capturedRequests.first)
        XCTAssertEqual(request.timeoutInterval, 4.321, accuracy: 0.001)
    }

    /// With no JS, the strategy has nothing to gate. It matters once the app is
    /// back, before its first `configureSync`.
    func test_relaunchedApp_honorsThePersistedStrategy() async throws {
        let (engine, ids) = try await relaunchedEngine(after: .sample(strategy: "MANUAL"))
        try await makeDue(ids, in: engine)

        await attach(engine)

        XCTAssertFalse(fixture.timer.isArmed)
    }

    /// `MANUAL` only stops the timer. `enableBackgroundSync` is an explicit
    /// request, and `foregroundOnly` is what turns background work off.
    func test_backgroundTasks_dispatchUnderTheManualStrategy() async throws {
        let first = await fixture.makeEngine()
        await first.configure(.sample(strategy: "MANUAL"))
        try first.background.enable(options: backgroundOptions())
        _ = try await first.storage.enqueue(input: .sample(url: "https://api.test/refresh"))
        _ = try await first.storage.enqueue(input: .sample(url: "https://api.test/processing"))
        first.shutdown()
        URLProtocolStub.setHandler(stubResponse(200))

        let cold = await fixture.makeColdEngine()
        let refresh = FakeBackgroundTask()
        cold.background.handleAppRefresh(task: refresh)
        await refresh.waitForCompletion()

        XCTAssertEqual(requestCount, 2, "a refresh task drains the batch")

        _ = try await cold.storage.enqueue(input: .sample())
        let processing = FakeBackgroundTask()
        cold.background.handleProcessing(task: processing)
        await processing.waitForCompletion()

        XCTAssertEqual(requestCount, 3)
    }

    func test_coldEngine_sendsThePersistedHeaders() async throws {
        let (engine, _) = try await relaunchedEngine(after: .sample(defaultHeaders: ["Authorization": "Bearer persisted"]))
        URLProtocolStub.setHandler(stubResponse(200))

        _ = try await engine.dispatcher.flush()

        let request = try XCTUnwrap(URLProtocolStub.capturedRequests.first)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer persisted")
    }

    // MARK: - Logout

    func test_loggingOut_leavesNoHeadersForAColdEngine() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample(defaultHeaders: ["Authorization": "Bearer t"]))
        await engine.configure(.sample())
        engine.shutdown()

        let cold = await fixture.makeColdEngine()

        XCTAssertNil(fixture.secrets.stored)
        XCTAssertTrue(cold.currentOptions().defaultHeaders.isEmpty)
    }

    // MARK: - Pause

    func test_coldEngine_staysPaused_andFlushesAreInert() async throws {
        let first = await fixture.makeEngine()
        _ = try await first.storage.enqueue(input: .sample())
        await first.pause()
        first.shutdown()
        URLProtocolStub.setHandler(stubResponse(200))

        let cold = await fixture.makeColdEngine()

        let isPaused = await cold.dispatcher.paused()
        XCTAssertTrue(isPaused)
        _ = try await cold.dispatcher.flush()
        XCTAssertEqual(requestCount, 0)
    }

    func test_coldEngine_backgroundTasksStayInertWhilePaused() async throws {
        let first = await fixture.makeEngine()
        try first.background.enable(options: backgroundOptions())
        _ = try await first.storage.enqueue(input: .sample())
        await first.pause()
        first.shutdown()
        URLProtocolStub.setHandler(stubResponse(200))

        let cold = await fixture.makeColdEngine()
        let task = FakeBackgroundTask()
        cold.background.handleProcessing(task: task)
        await task.waitForCompletion()

        XCTAssertEqual(task.completedResults, [true])
        XCTAssertEqual(requestCount, 0)
    }

    func test_resume_survivesAFreshEngine() async throws {
        let first = await fixture.makeEngine()
        await first.pause()
        await first.resume()
        first.shutdown()

        let cold = await fixture.makeColdEngine()

        let isPaused = await cold.dispatcher.paused()
        XCTAssertFalse(isPaused)
    }

    /// JS cannot read the flag and starts out unpaused, so a pause that outlived
    /// the app would stop the queue silently.
    func test_creatingTheHybrid_clearsThePersistedPause() async throws {
        let first = await fixture.makeEngine()
        _ = try await first.storage.enqueue(input: .sample())
        await first.pause()
        first.shutdown()
        URLProtocolStub.setHandler(stubResponse(200))

        let relaunched = await fixture.makeEngine()

        let isPaused = await relaunched.dispatcher.paused()
        XCTAssertFalse(isPaused)
        _ = try await relaunched.dispatcher.flush()
        XCTAssertEqual(requestCount, 1)
        relaunched.shutdown()

        let cold = await fixture.makeColdEngine()
        let staysCleared = await cold.dispatcher.paused()
        XCTAssertFalse(staysCleared, "the next background launch must not see the old pause")
    }

    func test_creatingTheHybrid_armsWhatThePauseHeldBack() async throws {
        let first = await fixture.makeEngine()
        let id = try await first.storage.enqueue(input: .sample())
        try await first.storage.markInFlight(id: id)
        try await first.storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 60_000)
        await first.pause()
        first.shutdown()

        _ = await fixture.makeEngine()

        XCTAssertEqual(fixture.timer.delayMs, 60_000)
    }

    /// JS can pause as soon as the hybrid exists, while launch recovery still
    /// runs. A clear that landed later would silently undo that pause.
    func test_creatingTheHybrid_clearsThePauseBeforeReturning() async throws {
        let first = await fixture.makeEngine()
        await first.pause()
        first.shutdown()
        let engine = fixture.buildEngine()
        XCTAssertTrue(engine.dispatcher.hooks.paused)

        engine.attachHybrid()
        XCTAssertFalse(engine.dispatcher.hooks.paused)

        await engine.pause()
        await engine.waitUntilStarted()
        let isPaused = await engine.dispatcher.paused()
        XCTAssertTrue(isPaused)
    }

    // MARK: - Unrecoverable secret

    func test_unrecoverableSecret_dispatchesNothing_andReportsSuccess() async throws {
        let first = await fixture.makeEngine()
        await first.configure(.sample(defaultHeaders: ["Authorization": "Bearer t"]))
        try first.background.enable(options: BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                                                        requiresCharging: nil,
                                                                        requiresUnmeteredNetwork: nil,
                                                                        requiresDeviceIdle: nil,
                                                                        foregroundOnly: nil,
                                                                        taskIdentifier: nil))
        let id = try await first.storage.enqueue(input: .sample())
        first.shutdown()
        fixture.secrets.failReads(with: errSecInteractionNotAllowed)
        URLProtocolStub.setHandler(stubResponse(200))
        let emitter = MockSyncEventEmitter()

        let cold = await fixture.makeColdEngine(emitter: emitter)
        let task = FakeBackgroundTask()
        cold.background.handleProcessing(task: task)

        await task.waitForCompletion()
        XCTAssertEqual(task.completedResults, [true], "the task reports success")
        XCTAssertEqual(requestCount, 0, "sending without the headers would fail every item")
        let stored = try await item(id, in: cold)
        XCTAssertEqual(stored.status, "PENDING")
        XCTAssertEqual(stored.attempts, 0)
        XCTAssertTrue(emitter.events(ofType: "SYNC_STARTED").isEmpty, "no cycle ran, so no cycle event")
    }

    /// Before the first unlock the Keychain is unreadable; a process that lives
    /// past it must pick the headers up without a restart.
    func test_secretThatBecomesReadable_isPickedUp() async throws {
        let first = await fixture.makeEngine()
        await first.configure(.sample(defaultHeaders: ["Authorization": "Bearer t"]))
        _ = try await first.storage.enqueue(input: .sample())
        first.shutdown()
        fixture.secrets.failReads(with: errSecInteractionNotAllowed)
        URLProtocolStub.setHandler(stubResponse(200))

        let cold = await fixture.makeColdEngine()
        _ = try await cold.dispatcher.flush()
        XCTAssertEqual(requestCount, 0)

        fixture.secrets.failReads(with: nil)
        _ = try await cold.dispatcher.flush()

        let request = try XCTUnwrap(URLProtocolStub.capturedRequests.first)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer t")
        XCTAssertEqual(cold.currentOptions().defaultHeaders["Authorization"], "Bearer t")
    }

    func test_configureSync_recoversFromAnUnreadableSecret() async throws {
        let first = await fixture.makeEngine()
        await first.configure(.sample(defaultHeaders: ["Authorization": "Bearer old"]))
        _ = try await first.storage.enqueue(input: .sample())
        first.shutdown()
        fixture.secrets.failReads(with: errSecInteractionNotAllowed)
        URLProtocolStub.setHandler(stubResponse(200))

        let cold = await fixture.makeColdEngine()
        await cold.configure(.sample(defaultHeaders: ["Authorization": "Bearer new"]))
        _ = try await cold.dispatcher.flush()

        let request = try XCTUnwrap(URLProtocolStub.capturedRequests.first)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer new")
    }

    // MARK: - Stale credential guard

    func test_staleCredential_keepsTheItem_withoutConsumingAnAttempt() async throws {
        for status in [401, 403] {
            let (engine, ids) = try await relaunchedEngine(after: .sample(retryPolicy: .exact(maxAttempts: 1, baseDelayMs: 5_000),
                                                                          defaultHeaders: ["Authorization": "Bearer expired"]))
            let id = try XCTUnwrap(ids.first)
            URLProtocolStub.setHandler(stubResponse(status))
            let before = fixture.clock.nowMs()

            let result = try await engine.dispatcher.flush()

            let stored = try await item(id, in: engine)
            XCTAssertEqual(stored.status, "PENDING", "HTTP \(status)")
            XCTAssertEqual(stored.attempts, 0, "an expired token says nothing about the item")
            XCTAssertEqual(stored.lastErrorCode, "UNAUTHORIZED")
            XCTAssertEqual(stored.nextRetryAt, before + 60_000)
            XCTAssertEqual(result.itemsFailed, 0)
            _ = try await engine.storage.remove(id: id)
            engine.shutdown()
        }
    }

    /// `attempts` does not advance, so backoff alone would stay near its first
    /// value and a background launch would keep sending a dead token.
    func test_staleCredential_holdsForTheLongerOfBackoffAndAMinute() async throws {
        for (baseDelayMs, expectedHold) in [(5_000, Int64(60_000)), (120_000, Int64(120_000))] {
            let policy = RetryPolicyValue.exact(maxAttempts: 5, baseDelayMs: baseDelayMs, maxDelayMs: 600_000)
            let (engine, ids) = try await relaunchedEngine(after: .sample(retryPolicy: policy,
                                                                          defaultHeaders: ["Authorization": "Bearer expired"]))
            let id = try XCTUnwrap(ids.first)
            URLProtocolStub.setHandler(stubResponse(401))

            _ = try await engine.dispatcher.flush()

            let stored = try await item(id, in: engine)
            XCTAssertEqual(stored.nextRetryAt, fixture.clock.nowMs() + expectedHold, "base delay \(baseDelayMs)")
            _ = try await engine.storage.remove(id: id)
            engine.shutdown()
        }
    }

    /// Android reports the same number: the attempt the server just rejected.
    func test_staleCredential_reportsTheRejectedAttempt_whileTheRowKeepsItsCount() async throws {
        let emitter = MockSyncEventEmitter()
        let (engine, ids) = try await relaunchedEngine(after: .sample(defaultHeaders: ["Authorization": "Bearer expired"]),
                                                       emitter: emitter)
        let id = try XCTUnwrap(ids.first)
        try await makeDue(ids, in: engine)
        URLProtocolStub.setHandler(stubResponse(401))

        _ = try await engine.dispatcher.flush()

        let stored = try await item(id, in: engine)
        XCTAssertEqual(stored.attempts, 1)
        XCTAssertEqual(emitter.events(ofType: "ITEM_RETRYING").map(\.attempt), [2])
    }

    func test_staleCredential_stopsTheCycle() async throws {
        let (engine, _) = try await relaunchedEngine(after: .sample(defaultHeaders: ["Authorization": "Bearer expired"]),
                                                     enqueue: 3)
        URLProtocolStub.setHandler(stubResponse(401))

        let result = try await engine.dispatcher.flush()

        XCTAssertEqual(requestCount, 1, "the next item would carry the same rejected credential")
        XCTAssertEqual(result.itemsAttempted, 1)
        let queued = try await engine.storage.getQueued()
        XCTAssertEqual(queued.count, 3)
        let untouched = queued.filter { $0.attempts == 0 && $0.nextRetryAt == 0 && $0.lastErrorCode == nil }
        XCTAssertEqual(untouched.count, 2, "the rest of the batch stays as it was")
    }

    func test_staleCredential_costsOneRequestPerWake() async throws {
        let (engine, _) = try await relaunchedEngine(after: .sample(defaultHeaders: ["Authorization": "Bearer expired"]),
                                                     enqueue: 3)
        await attach(engine)
        URLProtocolStub.setHandler(stubResponse(401))

        for wake in 1...3 {
            if wake == 1 {
                _ = try await engine.dispatcher.flush()
            } else {
                fixture.clock.advance(ms: fixture.timer.delayMs ?? 0)
                await fixture.timer.fire()
            }
            XCTAssertEqual(requestCount, wake)
            XCTAssertEqual(fixture.timer.delayMs, 60_000)
        }
    }

    func test_staleCredential_nextWakeWaitsForTheHold_evenWithARowDue() async throws {
        let (engine, ids) = try await relaunchedEngine(after: .sample(defaultHeaders: ["Authorization": "Bearer expired"]),
                                                       enqueue: 2)
        await attach(engine)
        try await makeDue(ids, in: engine)
        URLProtocolStub.setHandler(stubResponse(401))

        _ = try await engine.dispatcher.flush()

        XCTAssertEqual(requestCount, 1)
        XCTAssertEqual(fixture.timer.delayMs, 60_000, "the other row is due, but a wake now would spend a request")
    }

    /// Hybrid creation, for one, re-arms knowing nothing about the rejection.
    func test_staleCredentialHold_outlivesTheRearmThatCarriedIt() async throws {
        let (engine, ids) = try await relaunchedEngine(after: .sample(defaultHeaders: ["Authorization": "Bearer expired"]),
                                                       enqueue: 2)
        try await makeDue(ids, in: engine)
        URLProtocolStub.setHandler(stubResponse(401))
        _ = try await engine.dispatcher.flush()

        await attach(engine)

        XCTAssertEqual(fixture.timer.delayMs, 60_000)
    }

    func test_staleCredentialHold_endsWithANewCredential() async throws {
        let (engine, ids) = try await relaunchedEngine(after: .sample(defaultHeaders: ["Authorization": "Bearer expired"]),
                                                       enqueue: 2)
        await attach(engine)
        try await makeDue(ids, in: engine)
        URLProtocolStub.setHandler(stubResponse(401))
        _ = try await engine.dispatcher.flush()

        await engine.configure(.sample(defaultHeaders: ["Authorization": "Bearer fresh"]))

        XCTAssertEqual(fixture.timer.delayMs, 0, "the row that was due wakes at once")
    }

    /// `resumeSync` is still a wake, and the credential has not changed.
    func test_staleCredentialHold_appliesToResume() async throws {
        let (engine, ids) = try await relaunchedEngine(after: .sample(defaultHeaders: ["Authorization": "Bearer expired"]),
                                                       enqueue: 2)
        await attach(engine)
        try await makeDue(ids, in: engine)
        URLProtocolStub.setHandler(stubResponse(401))
        _ = try await engine.dispatcher.flush()

        await engine.pause()
        await engine.resume()

        XCTAssertEqual(fixture.timer.delayMs, 60_000)
    }

    /// An app usually configures right after launch, while a wake from launch
    /// may already be out with the old token.
    func test_staleCredential_configuringWhileTheRequestIsOut_keepsTheItem() async throws {
        let (engine, ids) = try await relaunchedEngine(after: .sample(defaultHeaders: ["Authorization": "Bearer expired"]))
        let id = try XCTUnwrap(ids.first)
        let rejection = stubResponse(401)
        URLProtocolStub.setHandler { request in
            let configured = DispatchSemaphore(value: 0)
            Task {
                await engine.configure(.sample(defaultHeaders: ["Authorization": "Bearer fresh"]))
                configured.signal()
            }
            configured.wait()
            return try rejection(request)
        }

        _ = try await engine.dispatcher.flush()

        let stored = try await item(id, in: engine)
        XCTAssertEqual(stored.status, "PENDING", "the rejection was about the old token")
        XCTAssertEqual(stored.attempts, 0)
    }

    func test_liveCredential_rejectionStaysTerminal() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample(defaultHeaders: ["Authorization": "Bearer t"]))
        let id = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(401))

        _ = try await engine.dispatcher.flush()

        let stored = try await item(id, in: engine)
        XCTAssertEqual(stored.status, "FAILED")
        XCTAssertEqual(stored.lastErrorCode, "UNAUTHORIZED")
        XCTAssertEqual(stored.attempts, 1)
    }

    func test_persistedConfig_withoutHeaders_rejectionStaysTerminal() async throws {
        let (engine, ids) = try await relaunchedEngine(after: .sample())
        let id = try XCTUnwrap(ids.first)
        URLProtocolStub.setHandler(stubResponse(401))

        _ = try await engine.dispatcher.flush()

        let stored = try await item(id, in: engine)
        XCTAssertEqual(stored.status, "FAILED", "no persisted credential is involved")
    }

    func test_staleCredential_recoversOnceTheAppConfigures() async throws {
        let (engine, ids) = try await relaunchedEngine(after: .sample(defaultHeaders: ["Authorization": "Bearer expired"]))
        let id = try XCTUnwrap(ids.first)
        URLProtocolStub.setHandler(stubResponse(401))
        _ = try await engine.dispatcher.flush()

        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: 5, baseDelayMs: 1),
                                       defaultHeaders: ["Authorization": "Bearer fresh"]))
        URLProtocolStub.setHandler(stubResponse(200))
        let cooling = try await item(id, in: engine)
        fixture.clock.advance(ms: cooling.nextRetryAt - fixture.clock.nowMs())
        _ = try await engine.dispatcher.flush()

        let remaining = try await engine.storage.getAll()
        XCTAssertTrue(remaining.isEmpty)
        let last = try XCTUnwrap(URLProtocolStub.capturedRequests.last)
        XCTAssertEqual(last.value(forHTTPHeaderField: "Authorization"), "Bearer fresh")
    }
}
