import CoreData
import XCTest
@testable import SyncProvider

/// The in-process retry timer: the only thing that re-drives a cooling item
/// while the app runs. Time is manual here: `clock` moves and `timer` fires only
/// when a test says so, and `flush()` returns after the timer was re-armed, so
/// nothing in this file waits.
final class SyncEngineRetryTimerTests: XCTestCase {
    private var fixture: EngineFixture!
    private var clock: ManualClock { fixture.clock }
    private var timer: ManualWakeTimer { fixture.timer }

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

    /// Lets the pending wake come due, then fires it.
    private func fireWhenDue() async {
        clock.advance(ms: timer.delayMs ?? 0)
        await timer.fire()
    }

    /// Queues an item already attempted once, cooling for `delayMs`.
    private func enqueueCooling(in engine: SyncEngine, delayMs: Int) async throws -> String {
        let id = try await engine.storage.enqueue(input: .sample())
        try await engine.storage.markInFlight(id: id)
        try await engine.storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: delayMs)
        return id
    }

    private func options(foregroundOnly: Bool? = nil) -> BackgroundSyncOptionsValue {
        BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                   requiresCharging: nil,
                                   requiresUnmeteredNetwork: nil,
                                   requiresDeviceIdle: nil,
                                   foregroundOnly: foregroundOnly,
                                   taskIdentifier: nil)
    }

    // MARK: - Re-driving cooling items

    func test_coolingItem_isDispatchedAgain_withNoExternalFlush() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: 5, baseDelayMs: 100)))
        let id = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        _ = try await engine.dispatcher.flush()
        XCTAssertEqual(requestCount, 1)
        XCTAssertEqual(timer.delayMs, 100, "the wake must land on the cooldown deadline")

        URLProtocolStub.setHandler(stubResponse(200))
        await fireWhenDue()

        let remaining = try await engine.storage.getAll()
        XCTAssertFalse(remaining.contains { $0.id == id })
    }

    /// A wake that lands early finds nothing to dispatch and re-arms for the
    /// remainder, so it cannot strand the item or dispatch it early.
    func test_earlyWake_rearmsForTheRemainder() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: 5, baseDelayMs: 100)))
        _ = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        _ = try await engine.dispatcher.flush()

        clock.advance(ms: 40)
        await timer.fire()

        XCTAssertEqual(requestCount, 1)
        XCTAssertEqual(timer.delayMs, 60)
    }

    func test_timerDrivenCycles_emitTheNormalEvents() async throws {
        let emitter = MockSyncEventEmitter()
        let engine = await fixture.makeEngine(emitter: emitter)
        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: 5, baseDelayMs: 50)))
        _ = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        _ = try await engine.dispatcher.flush()
        emitter.clear()
        URLProtocolStub.setHandler(stubResponse(200))

        await fireWhenDue()

        XCTAssertEqual(emitter.events(ofType: "SYNC_STARTED").count, 1)
        XCTAssertEqual(emitter.events(ofType: "ITEM_SUCCEEDED").count, 1)
        XCTAssertEqual(emitter.events(ofType: "SYNC_SUCCEEDED").count, 1)
    }

    func test_timerChainContinues_untilTheBudgetRunsOut() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: 3, baseDelayMs: 20)))
        let id = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))

        _ = try await engine.dispatcher.flush()
        XCTAssertEqual(timer.delayMs, 20)
        await fireWhenDue()
        XCTAssertEqual(timer.delayMs, 40)
        await fireWhenDue()

        let stored = try await item(id, in: engine)
        XCTAssertEqual(stored.status, "FAILED")
        XCTAssertEqual(requestCount, 3)
        XCTAssertFalse(timer.isArmed, "nothing is left to wake for")
    }

    func test_elapsedCooldown_isNotStranded() async throws {
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 0)
        URLProtocolStub.setHandler(stubResponse(200))
        clock.advance(ms: 30)

        await engine.rearmRetryTimer()
        XCTAssertEqual(timer.delayMs, 0, "an elapsed deadline wakes at once")
        await timer.fire()

        XCTAssertEqual(requestCount, 1)
    }

    // MARK: - When it is armed

    func test_engineStart_armsForCooldownsLeftByAPreviousProcess() async throws {
        let previous = await fixture.makeEngine()
        _ = try await enqueueCooling(in: previous, delayMs: 150)
        previous.shutdown()
        URLProtocolStub.setHandler(stubResponse(200))

        _ = await fixture.makeEngine()

        XCTAssertEqual(timer.delayMs, 150)
        await fireWhenDue()
        XCTAssertEqual(requestCount, 1)
    }

    /// Launch recovery runs first: a row left IN_FLIGHT after a crash carries
    /// its old cooldown and must be counted. The rows make recovery slower than
    /// the deadline read, so arming too early finds nothing.
    func test_engineStart_armsAfterLaunchRecovery() async throws {
        let previous = await fixture.makeEngine()
        for _ in 0..<300 {
            let id = try await enqueueCooling(in: previous, delayMs: 60_000)
            try await previous.storage.markInFlight(id: id)
        }
        previous.shutdown()

        let relaunched = await fixture.makeEngine()

        let queued = try await relaunched.storage.getQueued()
        XCTAssertEqual(queued.count, 300)
        XCTAssertEqual(timer.delayMs, 60_000)
    }

    func test_cycleWithNothingToWakeFor_doesNotArm() async throws {
        let engine = await fixture.makeEngine()
        URLProtocolStub.setHandler(stubResponse(200))
        _ = try await engine.storage.enqueue(input: .sample())

        _ = try await engine.dispatcher.flush()

        XCTAssertFalse(timer.isArmed)
    }

    /// A full batch leaves fresh rows behind. Those wait for the next external
    /// flush, as they always did; the timer only re-drives attempted rows.
    func test_neverAttemptedRows_doNotArmTheTimer() async throws {
        let engine = await fixture.makeEngine()
        _ = try await engine.storage.enqueue(input: .sample())

        await engine.rearmRetryTimer()

        XCTAssertFalse(timer.isArmed)
    }

    func test_configureSync_arms() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample(strategy: "MANUAL"))
        _ = try await enqueueCooling(in: engine, delayMs: 60_000)
        await engine.rearmRetryTimer()
        XCTAssertFalse(timer.isArmed)

        await engine.configure(.sample(strategy: "AUTOMATIC"))

        XCTAssertTrue(timer.isArmed)
    }

    func test_opportunisticStrategy_arms() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample(strategy: "OPPORTUNISTIC"))
        _ = try await enqueueCooling(in: engine, delayMs: 60_000)

        await engine.rearmRetryTimer()

        XCTAssertTrue(timer.isArmed)
    }

    // MARK: - MANUAL

    func test_manualStrategy_neverArms() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample(strategy: "MANUAL", retryPolicy: .exact(maxAttempts: 5, baseDelayMs: 30)))
        _ = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))

        _ = try await engine.dispatcher.flush()

        XCTAssertFalse(timer.isArmed, "MANUAL dispatches only when asked")
    }

    func test_switchingToManual_cancelsThePendingWake() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample())
        _ = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        _ = try await engine.dispatcher.flush()
        XCTAssertTrue(timer.isArmed)

        await engine.configure(.sample(strategy: "MANUAL"))

        XCTAssertFalse(timer.isArmed)
    }

    // MARK: - Pause and resume

    func test_pause_cancelsThePendingWake() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample())
        _ = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        _ = try await engine.dispatcher.flush()
        XCTAssertTrue(timer.isArmed)

        await engine.pause()

        XCTAssertFalse(timer.isArmed)
    }

    func test_nothingArms_whilePaused() async throws {
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 60_000)
        await engine.pause()

        await engine.rearmRetryTimer()

        XCTAssertFalse(timer.isArmed)
    }

    /// A wake that fired just before the pause lands after it.
    func test_wakeLandingAfterPause_doesNotDispatch() async throws {
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 0)
        await engine.rearmRetryTimer()
        let wake = try XCTUnwrap(timer.takeWake())
        URLProtocolStub.setHandler(stubResponse(200))

        await engine.pause()
        await wake()

        XCTAssertEqual(requestCount, 0)
    }

    func test_resume_dispatchesNeverAttemptedRowsAtOnce() async throws {
        let engine = await fixture.makeEngine()
        await engine.pause()
        _ = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(200))

        await engine.resume()

        XCTAssertEqual(timer.delayMs, 0)
        await timer.fire()
        XCTAssertEqual(requestCount, 1)
    }

    func test_resume_respectsCooldownsOfAttemptedRows() async throws {
        let engine = await fixture.makeEngine()
        await engine.pause()
        _ = try await enqueueCooling(in: engine, delayMs: 60_000)

        await engine.resume()

        XCTAssertEqual(timer.delayMs, 60_000)
    }

    func test_resume_underManualStrategy_doesNotArm() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample(strategy: "MANUAL"))
        await engine.pause()
        _ = try await engine.storage.enqueue(input: .sample())

        await engine.resume()

        XCTAssertFalse(timer.isArmed)
    }

    // MARK: - Offline rule

    /// The critical one. Neither the dispatcher nor the session checks
    /// connectivity, so a timer flushing offline would mark valid items FAILED
    /// while the device merely has no network.
    func test_wakeFiringOffline_consumesNoAttempt_andWaitsForConnectivity() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: 2, baseDelayMs: 50)))
        let id = try await engine.storage.enqueue(input: .sample())
        // The app flushes as the device loses its network. With `maxAttempts`
        // of 2, one attempt spent by the wake would mark the item FAILED.
        fixture.connectivity.set(.disconnected)
        URLProtocolStub.setHandler { _ in throw URLError(.notConnectedToInternet) }
        _ = try await engine.dispatcher.flush()
        XCTAssertEqual(requestCount, 1)

        await fireWhenDue()

        let afterWake = try await item(id, in: engine)
        XCTAssertEqual(requestCount, 1, "a wake while offline must not dispatch")
        XCTAssertEqual(afterWake.attempts, 1, "a wake while offline must not consume an attempt")
        XCTAssertEqual(afterWake.status, "PENDING", "the item is fine; the network is not")
        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)

        URLProtocolStub.setHandler(stubResponse(200))
        fixture.connectivity.set(.connected)
        await engine.waitForReconnectWake()

        let remaining = try await engine.storage.getAll()
        XCTAssertTrue(remaining.isEmpty, "the remembered wake must flush on reconnect")
        XCTAssertFalse(engine.hasWakeAwaitingConnectivity)
    }

    func test_connectivityUnknown_isNotOnline() async throws {
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 0)
        await engine.rearmRetryTimer()
        fixture.connectivity.set(.unknown)
        URLProtocolStub.setHandler(stubResponse(200))

        await timer.fire()

        XCTAssertEqual(requestCount, 0)
        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)
    }

    func test_meteredConnection_isOnline() async throws {
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 0)
        await engine.rearmRetryTimer()
        fixture.connectivity.set(.metered)
        URLProtocolStub.setHandler(stubResponse(200))

        await timer.fire()

        XCTAssertEqual(requestCount, 1)
    }

    func test_reconnecting_withoutARememberedWake_doesNotFlush() async throws {
        let engine = await fixture.makeEngine()
        _ = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(200))

        fixture.connectivity.set(.disconnected)
        fixture.connectivity.set(.connected)
        await engine.waitForReconnectWake()

        XCTAssertEqual(requestCount, 0)
    }

    func test_pausingOffline_forgetsTheRememberedWake() async throws {
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 0)
        await engine.rearmRetryTimer()
        fixture.connectivity.set(.disconnected)
        URLProtocolStub.setHandler(stubResponse(200))
        await timer.fire()
        XCTAssertTrue(engine.hasWakeAwaitingConnectivity)

        await engine.pause()

        XCTAssertFalse(engine.hasWakeAwaitingConnectivity)
        fixture.connectivity.set(.connected)
        await engine.waitForReconnectWake()
        XCTAssertEqual(requestCount, 0)
    }

    /// A manual `flush()` while offline is unchanged: the app asked for it.
    func test_manualFlushWhileOffline_stillDispatches() async throws {
        let engine = await fixture.makeEngine()
        _ = try await engine.storage.enqueue(input: .sample())
        fixture.connectivity.set(.disconnected)
        URLProtocolStub.setHandler(stubResponse(200))

        _ = try await engine.dispatcher.flush()

        XCTAssertEqual(requestCount, 1)
    }

    // MARK: - foregroundOnly

    /// The timer is in-process, so the kill-switch for OS work does not apply.
    func test_foregroundOnly_doesNotDisableTheTimer() async throws {
        let engine = await fixture.makeEngine()
        try engine.background.enable(options: options(foregroundOnly: true))
        await engine.configure(.sample(retryPolicy: .exact(maxAttempts: 5, baseDelayMs: 50)))
        _ = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))

        _ = try await engine.dispatcher.flush()
        XCTAssertEqual(timer.delayMs, 50)
        await fireWhenDue()

        XCTAssertEqual(requestCount, 2)
    }

    // MARK: - Dispose

    func test_shutdown_cancelsThePendingWake_andNothingRearms() async throws {
        let engine = await fixture.makeEngine()
        await engine.configure(.sample())
        _ = try await engine.storage.enqueue(input: .sample())
        URLProtocolStub.setHandler(stubResponse(503))
        _ = try await engine.dispatcher.flush()
        XCTAssertTrue(timer.isArmed)

        engine.shutdown()
        await engine.rearmRetryTimer()

        XCTAssertFalse(timer.isArmed)
    }

    /// A wake already on its way when the engine stops must not dispatch.
    func test_wakeLandingAfterShutdown_doesNotDispatch() async throws {
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 0)
        await engine.rearmRetryTimer()
        let wake = try XCTUnwrap(timer.takeWake())
        URLProtocolStub.setHandler(stubResponse(200))

        engine.shutdown()
        await wake()

        XCTAssertEqual(requestCount, 0)
    }

    // MARK: - JS lifecycle

    /// A background launch answers to the OS: a timer there would dispatch
    /// outside the constraints the consumer asked for.
    func test_engineWithoutAHybrid_armsNothing() async throws {
        let previous = await fixture.makeEngine()
        _ = try await enqueueCooling(in: previous, delayMs: 150)
        previous.shutdown()
        URLProtocolStub.setHandler(stubResponse(503))

        let cold = await fixture.makeColdEngine()
        XCTAssertFalse(timer.isArmed, "on start")

        clock.advance(ms: 150)
        _ = try await cold.dispatcher.flush()

        XCTAssertEqual(requestCount, 1)
        XCTAssertFalse(timer.isArmed, "at the end of a cycle")
    }

    func test_hybridCreatedAfterABackgroundLaunch_arms() async throws {
        let previous = await fixture.makeEngine()
        _ = try await enqueueCooling(in: previous, delayMs: 150)
        previous.shutdown()
        let engine = await fixture.makeColdEngine()

        engine.attachHybrid()
        await engine.waitUntilStarted()

        XCTAssertEqual(timer.delayMs, 150)
    }

    func test_lastHybridLeaving_cancelsTheWake_andNothingRearms() async throws {
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 60_000)
        await engine.rearmRetryTimer()
        XCTAssertTrue(timer.isArmed)

        engine.detachHybrid()
        XCTAssertFalse(timer.isArmed)

        await engine.rearmRetryTimer()
        XCTAssertFalse(timer.isArmed)
    }

    /// A reload can create the new hybrid before the old one is released.
    func test_reloadCreatingTheNewHybridFirst_keepsTheWake() async throws {
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 60_000)

        engine.attachHybrid()
        engine.detachHybrid()
        await engine.waitUntilStarted()
        XCTAssertTrue(timer.isArmed)

        engine.detachHybrid()
        XCTAssertFalse(timer.isArmed)
    }

    func test_wakeLandingAfterTheLastHybridLeft_doesNotDispatch() async throws {
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 0)
        await engine.rearmRetryTimer()
        let wake = try XCTUnwrap(timer.takeWake())
        URLProtocolStub.setHandler(stubResponse(200))

        engine.detachHybrid()
        await wake()

        XCTAssertEqual(requestCount, 0)
    }

    func test_realHybrid_attachesOnCreation_andDetachesOnRelease() async throws {
        let engine = await fixture.makeColdEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 60_000)

        var hybrid: HybridSyncProvider? = HybridSyncProvider(engine: engine)
        await engine.waitUntilStarted()
        XCTAssertNotNil(hybrid)
        XCTAssertTrue(timer.isArmed)

        hybrid = nil
        XCTAssertFalse(timer.isArmed)
    }

    // MARK: - Competing re-arms

    /// Re-arming reads the store with no lock held. A read taken while the row
    /// was IN_FLIGHT finds nothing; if it finished last, it would cancel the
    /// wake armed by the newer read and strand the item.
    func test_staleRearm_cannotCancelANewerWake() async throws {
        var gate: GatedWakeSource!
        fixture.wrapWakeSource = { base in
            gate = GatedWakeSource(base: base)
            return gate
        }
        let engine = await fixture.makeEngine()
        let id = try await engine.storage.enqueue(input: .sample())
        try await engine.storage.markInFlight(id: id)

        gate.holdNextRead()
        async let stale: Void = engine.rearmRetryTimer()
        await gate.waitUntilHeld()

        try await engine.storage.markPendingForRetry(id: id, errorCode: "SERVER_ERROR", retryDelayMs: 60_000)
        await engine.rearmRetryTimer()
        XCTAssertEqual(timer.delayMs, 60_000)

        gate.release()
        await stale

        XCTAssertEqual(timer.delayMs, 60_000, "the older read must not cancel the newer wake")
    }

    /// A re-arm already reading the store must not arm after a pause landed.
    func test_rearmInFlightAcrossPause_doesNotArm() async throws {
        var gate: GatedWakeSource!
        fixture.wrapWakeSource = { base in
            gate = GatedWakeSource(base: base)
            return gate
        }
        let engine = await fixture.makeEngine()
        _ = try await enqueueCooling(in: engine, delayMs: 60_000)

        gate.holdNextRead()
        async let inFlight: Void = engine.rearmRetryTimer()
        await gate.waitUntilHeld()

        await engine.pause()
        gate.release()
        await inFlight

        XCTAssertFalse(timer.isArmed)
    }

    // MARK: - Failure handling

    /// No JS caller exists on a timer path. A store that failed to open must
    /// be logged, not crash the process.
    func test_engineStart_withAFailedStore_doesNotCrash() async {
        let failing = CoreDataStack(load: {
            (NSPersistentContainer(name: "Broken", managedObjectModel: NSManagedObjectModel()),
             CoreDataStackError.modelNotFound)
        })
        let (defaults, suite) = UserDefaults.ephemeral()
        defer { defaults.removePersistentDomain(forName: suite) }
        let engine = SyncEngine(stack: failing,
                                configStore: SyncConfigStore(defaults: defaults, secrets: InMemorySecretStore()),
                                backgroundStore: BackgroundSyncOptionsStore(defaults: defaults),
                                connectivity: FakeConnectivity(),
                                scheduler: FakeBackgroundTaskScheduler(),
                                sessions: (fixture.foregroundSession, fixture.backgroundSession),
                                clock: clock,
                                retryTimer: timer)
        defer { engine.shutdown() }

        engine.attachHybrid()
        await engine.waitUntilStarted()

        XCTAssertFalse(timer.isArmed)
    }
}

/// Holds one deadline read open after it has read, so a test can decide what
/// finishes first. Forces the interleaving a race would only hit sometimes.
private final class GatedWakeSource: RetryWakeSource, @unchecked Sendable {
    private let base: RetryWakeSource
    private let lock = NSLock()
    private var shouldHold = false
    private var heldRead: CheckedContinuation<Void, Never>?
    private var holdWaiter: CheckedContinuation<Void, Never>?

    init(base: RetryWakeSource) {
        self.base = base
    }

    func holdNextRead() {
        lock.withLock { shouldHold = true }
    }

    /// Returns once a read has happened and is being held.
    func waitUntilHeld() async {
        await withCheckedContinuation { continuation in
            let alreadyHeld = lock.withLock { () -> Bool in
                if heldRead != nil { return true }
                holdWaiter = continuation
                return false
            }
            if alreadyHeld {
                continuation.resume()
            }
        }
    }

    func release() {
        let read = lock.withLock { () -> CheckedContinuation<Void, Never>? in
            defer { heldRead = nil }
            return heldRead
        }
        read?.resume()
    }

    func earliestRetryAt() async throws -> Int64? {
        let value = try await base.earliestRetryAt()
        await holdIfAsked()
        return value
    }

    func earliestPendingAt() async throws -> Int64? {
        let value = try await base.earliestPendingAt()
        await holdIfAsked()
        return value
    }

    private func holdIfAsked() async {
        await withCheckedContinuation { continuation in
            let waiter = lock.withLock { () -> (hold: Bool, waiter: CheckedContinuation<Void, Never>?) in
                guard shouldHold else { return (false, nil) }
                shouldHold = false
                heldRead = continuation
                defer { holdWaiter = nil }
                return (true, holdWaiter)
            }
            if waiter.hold {
                waiter.waiter?.resume()
            } else {
                continuation.resume()
            }
        }
    }
}
