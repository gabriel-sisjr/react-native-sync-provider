//
//  SyncEngine.swift
//  SyncProvider
//
//  The process-wide sync runtime. It exists independently of JS, because a
//  BGTask launch needs the queue, the dispatcher and the persisted
//  configuration with no bridge loaded. `HybridSyncProvider` is a thin
//  translation layer over this type.
//

import Foundation
#if canImport(BackgroundTasks)
import BackgroundTasks
#endif

/// Where the retry timer learns its next deadline.
protocol RetryWakeSource: Sendable {
    func earliestRetryAt() async throws -> Int64?
    func earliestPendingAt() async throws -> Int64?
}

extension SyncQueueStorage: RetryWakeSource {}

/// Which rows decide when the retry timer wakes.
enum RetryWakeScope: Sendable {
    /// Rows already attempted. Fresh rows left behind by a full batch do not
    /// start a new cycle by themselves.
    case attempted
    /// Every `PENDING` row, so never-attempted rows dispatch at once.
    case allPending
}

/// Owns the queue, the dispatcher and the retry timer.
final class SyncEngine: @unchecked Sendable {
    /// The engine for this process. Built on first use, from whichever side
    /// gets there first: JS creating the hybrid, or a fired BGTask.
    static let shared = SyncEngine()

    let storage: SyncQueueStorage
    let emitter: SyncEventEmitter
    let connectivity: ConnectivityProviding
    let dispatcher: SyncDispatcher
    let background: BackgroundSyncManager

    private let recovery: RecoveryManager
    private let clock: SyncClock
    private let retryTimer: RetryWakeScheduling
    private let wakeSource: RetryWakeSource

    private let lock = NSLock()
    private var options: SyncOptionsSnapshot = .default
    /// A timer fired while offline. Flushing then would spend attempts on items
    /// that are fine, so the wake waits here for connectivity.
    private var wakeAwaitingConnectivity = false
    /// Bumped by every re-arm; only the newest may touch the timer. Re-arming
    /// reads the store with no lock held, so a slow read must not overwrite
    /// the decision of a newer one. A read taken while the row was IN_FLIGHT
    /// finds nothing, and acting on it late would cancel a valid wake.
    private var rearmTicket: UInt64 = 0
    /// JS-facing hybrids alive in this process. The timer runs only while there
    /// is one: a background launch answers to the OS, and a timer there would
    /// dispatch outside the constraints the consumer asked for.
    private var attachedHybrids = 0
    private var connectivityToken: UUID?
    private var startup: Task<Void, Never>?
    private var attachWake: Task<Void, Never>?
    private var reconnectWake: Task<Void, Never>?
    private var isShutDown = false

    /// - Parameters:
    ///   - sessions: test seam. `nil` builds the production sessions.
    ///   - clock: drives cooldowns; tests move it explicitly.
    ///   - retryTimer: tests fire it explicitly.
    ///   - wrapWakeSource: test seam around the deadline queries.
    init(stack: CoreDataStack = .shared,
         connectivity: ConnectivityProviding = ConnectivityMonitor.shared,
         emitter: SyncEventEmitter = SyncEventEmitter(),
         scheduler: BackgroundTaskScheduling = BGTaskScheduler.shared,
         sessions: (foreground: URLSession, background: URLSession)? = nil,
         clock: SyncClock = SystemClock(),
         retryTimer: RetryWakeScheduling = RetryWakeTimer(),
         wrapWakeSource: ((RetryWakeSource) -> RetryWakeSource)? = nil) {
        let storage = SyncQueueStorage(dao: SyncItemDao(stack: stack, clock: clock))
        let config = SyncOptionsSnapshot.default.toDispatcherConfig()
        let dispatcher: SyncDispatcher
        if let sessions {
            dispatcher = SyncDispatcher(storage: storage,
                                        emitter: emitter,
                                        connectivity: connectivity,
                                        config: config,
                                        foregroundSession: sessions.foreground,
                                        backgroundSession: sessions.background)
        } else {
            dispatcher = SyncDispatcher(storage: storage,
                                        emitter: emitter,
                                        connectivity: connectivity,
                                        config: config)
        }

        self.clock = clock
        self.retryTimer = retryTimer
        self.wakeSource = wrapWakeSource?(storage) ?? storage
        self.storage = storage
        self.emitter = emitter
        self.connectivity = connectivity
        self.dispatcher = dispatcher
        self.background = BackgroundSyncManager(dispatcher: dispatcher,
                                                emitter: emitter,
                                                scheduler: scheduler)
        self.recovery = RecoveryManager(storage: storage, emitter: emitter)

        dispatcher.hooks.onCycleFinished = { [weak self] in
            await self?.rearmRetryTimer()
        }

        // `enableBackgroundSync` is the consumer-controlled toggle; registering
        // only flips on the dispatch slot, it does not schedule anything.
        background.registerHandlers()

        // Eagerly start the monitor so the first read returns a real snapshot
        // rather than the `.unknown` default.
        connectivity.start()
        connectivityToken = connectivity.subscribe { [weak self] snapshot in
            self?.connectivityChanged(snapshot)
        }

        // Detached from the caller: this must not block the bridge or a
        // launch handler. Handlers await it before flushing.
        startup = Task { [weak self] in
            await self?.recovery.recoverPendingItems()
        }
    }

    /// Returns once launch recovery has run and, with a hybrid attached, the
    /// retry timer is armed.
    func waitUntilStarted() async {
        await startup?.value
        await lock.withLock { attachWake }?.value
    }

    // MARK: - JS lifecycle

    /// A JS-facing hybrid was created, so an app is running in this process.
    func attachHybrid() {
        lock.withLock { attachedHybrids += 1 }
        let wake = Task { [weak self, startup] in
            await startup?.value
            await self?.rearmRetryTimer()
        }
        lock.withLock { attachWake = wake }
    }

    /// A JS-facing hybrid went away. The last one takes the timer with it. A
    /// reload can create the new hybrid first, so this has to count.
    func detachHybrid() {
        let wasLast = lock.withLock { () -> Bool in
            attachedHybrids = max(0, attachedHybrids - 1)
            return attachedHybrids == 0
        }
        if wasLast {
            disarmRetryTimer()
        }
    }

    /// Returns once a flush started by connectivity coming back has finished.
    func waitForReconnectWake() async {
        await lock.withLock { reconnectWake }?.value
    }

    /// Stops the engine's timers and subscriptions for good: a wake already in
    /// flight will not re-arm. The shared engine lives as long as the process;
    /// this exists for short-lived instances.
    func shutdown() {
        let token = lock.withLock { () -> UUID? in
            isShutDown = true
            defer { connectivityToken = nil }
            return connectivityToken
        }
        retryTimer.cancel()
        if let token {
            connectivity.unsubscribe(token)
        }
    }

    // MARK: - Configuration

    /// The active options.
    func currentOptions() -> SyncOptionsSnapshot {
        lock.withLock { options }
    }

    /// Applies a configuration set by the app.
    func configure(_ snapshot: SyncOptionsSnapshot) async {
        lock.withLock { options = snapshot }
        await storage.updateCapacity(Int(snapshot.maxQueueSize))
        await dispatcher.updateConfig(snapshot.toDispatcherConfig())
        await rearmRetryTimer()
    }

    // MARK: - Pause

    func pause() async {
        await dispatcher.setPaused(true)
        disarmRetryTimer()
    }

    func resume() async {
        await dispatcher.setPaused(false)
        await rearmRetryTimer(scope: .allPending)
    }

    // MARK: - Retry timer

    /// `true` while a wake is scheduled.
    var isRetryTimerArmed: Bool {
        retryTimer.isArmed
    }

    /// `true` when a wake fired offline and is waiting for connectivity.
    var hasWakeAwaitingConnectivity: Bool {
        lock.withLock { wakeAwaitingConnectivity }
    }

    /// Arms the single retry timer for the next due row, replacing any pending
    /// wake. Called when a cycle ends, when a hybrid attaches, and on
    /// configuration changes.
    func rearmRetryTimer(scope: RetryWakeScope = .attempted) async {
        let (ticket, canArm) = lock.withLock { () -> (UInt64, Bool) in
            rearmTicket &+= 1
            return (rearmTicket, attachedHybrids > 0 && !options.isManual)
        }
        let isPaused = await dispatcher.paused()

        var wakeAt: Int64?
        if canArm && !isPaused {
            do {
                switch scope {
                case .attempted:
                    wakeAt = try await wakeSource.earliestRetryAt()
                case .allPending:
                    wakeAt = try await wakeSource.earliestPendingAt()
                }
            } catch {
                SyncLogger.error("Could not arm the retry timer: \(error.localizedDescription)",
                                 category: "dispatcher")
                return
            }
        }

        let nowMs = clock.nowMs()
        lock.withLock {
            guard ticket == rearmTicket, !isShutDown else { return }
            guard let wakeAt else {
                retryTimer.cancel()
                wakeAwaitingConnectivity = false
                return
            }
            retryTimer.arm(afterMs: max(0, wakeAt - nowMs)) { [weak self] in
                await self?.retryWakeFired()
            }
        }
    }

    private func disarmRetryTimer() {
        lock.withLock {
            rearmTicket &+= 1
            retryTimer.cancel()
            wakeAwaitingConnectivity = false
        }
    }

    /// Runs on timer expiry, where no JS caller exists to receive an error.
    private func retryWakeFired() async {
        guard lock.withLock({ attachedHybrids > 0 && !isShutDown }) else { return }
        guard await !dispatcher.paused() else { return }
        guard connectivity.current.isOnline else {
            // Neither the dispatcher nor the session checks connectivity, so a
            // flush now would exhaust valid items against a dead network.
            lock.withLock { wakeAwaitingConnectivity = true }
            SyncLogger.debug("Retry wake while offline; waiting for connectivity", category: "dispatcher")
            return
        }
        do {
            _ = try await dispatcher.flush()
        } catch {
            SyncLogger.error("Timer-driven flush failed: \(error.localizedDescription)",
                             category: "dispatcher")
        }
    }

    private func connectivityChanged(_ snapshot: ConnectionSnapshot) {
        emitter.emit(.now(type: "CONNECTION_CHANGED", connectionStatus: snapshot.status.rawValue))

        guard snapshot.isOnline else { return }
        let hadWake = lock.withLock { () -> Bool in
            defer { wakeAwaitingConnectivity = false }
            return wakeAwaitingConnectivity
        }
        if hadWake {
            let wake = Task { await retryWakeFired() }
            lock.withLock { reconnectWake = wake }
        }
    }
}
