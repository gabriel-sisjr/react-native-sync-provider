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

/// The wait before the first silent re-check of a wake held below the
/// network-quality minimum. Each re-check that finds it still gated doubles
/// the wait.
let kHeldWakeRecheckInitialMs: Int64 = 30_000
/// The longest wait between two re-checks of a held wake.
let kHeldWakeRecheckMaxMs: Int64 = 900_000

/// Where the retry timer learns its next deadline.
protocol RetryWakeSource: Sendable {
    func earliestRetryAt() async throws -> Int64?
    func earliestPendingAt() async throws -> Int64?
}

extension SyncQueueStorage: RetryWakeSource {}

/// Which rows decide when the retry timer wakes.
enum RetryWakeScope: Sendable {
    /// Rows that carry a deadline (`nextRetryAt > 0`: a retry cooldown or a
    /// credential hold). Fresh rows left behind by a full batch do not start a
    /// new cycle by themselves.
    case attempted
    /// Every `PENDING` row, so rows with no deadline (`nextRetryAt = 0`,
    /// eligible now) dispatch at once.
    case allPending
}

/// Owns the queue, the dispatcher, the retry timer and the configuration.
final class SyncEngine: @unchecked Sendable {
    /// The engine for this process. Built on first use, from whichever side
    /// gets there first: JS creating the hybrid, or a fired BGTask.
    static let shared = SyncEngine()

    let storage: SyncQueueStorage
    let emitter: SyncEventEmitter
    let connectivity: ConnectivityProviding
    let dispatcher: SyncDispatcher
    let background: BackgroundSyncManager
    /// Grades the network. Internal so tests can await its measurements.
    let qualityMonitor: NetworkQualityMonitor
    /// The HEAD before a cycle, and its cache and backoff.
    let healthCheck: HealthCheck
    /// The one HEAD probe of the process: measurements and the health check
    /// share it, and with it one connection to the host.
    let probe: HeadProbing

    private let configStore: SyncConfigStore
    private let recovery: RecoveryManager
    private let clock: SyncClock
    private let retryTimer: RetryWakeScheduling
    /// Re-checks a wake held below the network-quality minimum. Nothing else
    /// would: on a stable path no update comes to re-run it.
    private let heldWakeTimer: RetryWakeScheduling
    private let wakeSource: RetryWakeSource

    private let lock = NSLock()
    private var options: SyncOptionsSnapshot
    private var source: ConfigSource
    /// A timer fired while the network could not take a flush: offline, or
    /// below `minNetworkQuality`. Flushing offline would spend attempts on
    /// items that are fine, and below the minimum the gate would refuse it.
    /// The wake waits here and runs once the network is online and, with a
    /// minimum set, graded at or above it.
    private var wakeAwaitingConnectivity = false
    /// The wait before the next re-check of a wake held below the minimum.
    private var heldWakeRecheckMs = kHeldWakeRecheckInitialMs
    /// The link of the last reported change, to tell a new path from a new
    /// grade on the same one.
    private var lastLink: ConnectionSnapshot?
    /// Bumped by every re-arm; only the newest may touch the timer. Re-arming
    /// reads the store with no lock held, so a slow read must not overwrite
    /// the decision of a newer one. A read taken while the row was IN_FLIGHT
    /// finds nothing, and acting on it late would cancel a valid wake.
    private var rearmTicket: UInt64 = 0
    /// JS-facing hybrids alive in this process. The timer runs only while there
    /// is one: a background launch answers to the OS, and a timer there would
    /// dispatch outside the constraints the consumer asked for.
    private var attachedHybrids = 0
    /// The credential hold for this process: no retry-timer wake before this
    /// epoch-ms moment, whatever else is due. `0` means no hold. It is set when
    /// a cycle ends after a request sent with default headers restored from
    /// storage (a persisted configuration, not one `configure` set in this
    /// process) got a 401/403. Waking sooner would spend another request on
    /// the rejected credential.
    ///
    /// - Every retry-timer arm waits for it: the timer fires at the later of
    ///   the row deadline and this value, and a new hold never shortens an
    ///   older one.
    /// - It lives only in memory, so a process relaunch drops it. The held row
    ///   keeps its persisted `nextRetryAt`, but another due row can wake the
    ///   queue and spend one request with the stored, rejected credential,
    ///   which sets a new hold.
    /// - Every `configure` clears it, whether or not the headers changed.
    /// - Only retry-timer arms read it. BGTask runs are not retry wakes and do
    ///   not consult it (a rejection during one still sets it), and neither
    ///   does a JS `flush()`.
    private var credentialHoldUntil: Int64 = 0
    private var startup: Task<Void, Never>?
    private var attachWake: Task<Void, Never>?
    private var reconnectWake: Task<Void, Never>?
    private var isShutDown = false

    /// - Parameters:
    ///   - sessions: test seam. `nil` builds the production sessions.
    ///   - probe: the one HEAD probe of the process, for every measurement.
    ///   - qualityProbeDebounceMs: test seam; see ``kQualityProbeDebounceMs``.
    ///   - clock: drives cooldowns; tests move it explicitly.
    ///   - retryTimer: tests fire it explicitly.
    ///   - heldWakeTimer: re-checks a held wake; tests fire it explicitly.
    ///   - wrapWakeSource: test seam around the deadline queries.
    init(stack: CoreDataStack = .shared,
         configStore: SyncConfigStore = SyncConfigStore(),
         backgroundStore: BackgroundSyncOptionsStore = BackgroundSyncOptionsStore(),
         connectivity: ConnectivityProviding = ConnectivityMonitor.shared,
         emitter: SyncEventEmitter = SyncEventEmitter(),
         scheduler: BackgroundTaskScheduling = BGTaskScheduler.shared,
         sessions: (foreground: URLSession, background: URLSession)? = nil,
         probe: HeadProbing = HeadProbe(),
         qualityProbeDebounceMs: Int = kQualityProbeDebounceMs,
         clock: SyncClock = SystemClock(),
         retryTimer: RetryWakeScheduling = RetryWakeTimer(),
         heldWakeTimer: RetryWakeScheduling = RetryWakeTimer(),
         wrapWakeSource: ((RetryWakeSource) -> RetryWakeSource)? = nil) {
        let loaded = configStore.load()
        let storage = SyncQueueStorage(dao: SyncItemDao(stack: stack, clock: clock),
                                       capacity: max(1, Int(loaded.options.maxQueueSize)))
        let config = loaded.options.toDispatcherConfig(source: loaded.source,
                                                       credentialsUnavailable: loaded.credentialsUnavailable)
        let dispatcher: SyncDispatcher
        if let sessions {
            dispatcher = SyncDispatcher(storage: storage,
                                        emitter: emitter,
                                        connectivity: connectivity,
                                        config: config,
                                        foregroundSession: sessions.foreground,
                                        backgroundSession: sessions.background,
                                        paused: loaded.paused)
        } else {
            dispatcher = SyncDispatcher(storage: storage,
                                        emitter: emitter,
                                        connectivity: connectivity,
                                        config: config,
                                        paused: loaded.paused)
        }

        self.clock = clock
        self.retryTimer = retryTimer
        self.heldWakeTimer = heldWakeTimer
        self.wakeSource = wrapWakeSource?(storage) ?? storage
        self.configStore = configStore
        self.options = loaded.options
        self.source = loaded.source
        self.storage = storage
        self.emitter = emitter
        self.connectivity = connectivity
        self.dispatcher = dispatcher
        self.background = BackgroundSyncManager(dispatcher: dispatcher,
                                                emitter: emitter,
                                                scheduler: scheduler,
                                                store: backgroundStore)
        self.recovery = RecoveryManager(storage: storage, emitter: emitter)
        let qualityMonitor = NetworkQualityMonitor(connectivity: connectivity,
                                                   probe: probe,
                                                   clock: clock,
                                                   debounceMs: qualityProbeDebounceMs)
        let healthCheck = HealthCheck(probe: probe, clock: clock)
        self.qualityMonitor = qualityMonitor
        self.healthCheck = healthCheck
        self.probe = probe

        dispatcher.hooks.onCycleFinished = { [weak self] notBefore in
            await self?.rearmRetryTimer(notBefore: notBefore)
        }
        dispatcher.hooks.recoverCredentials = { [weak self] in
            self?.recoverDefaultHeaders()
        }
        dispatcher.hooks.networkQuality = {
            qualityMonitor.current.quality
        }
        dispatcher.hooks.refreshStaleNetworkQuality = {
            qualityMonitor.refreshIfStale()
        }
        dispatcher.hooks.healthCheck = healthCheck
        dispatcher.hooks.onHealthCheckFailed = { [weak self] in
            await self?.rearmRetryTimer(scope: .allPending)
        }
        qualityMonitor.onHealthySample = { url in
            healthCheck.recordHealthy(url: url)
        }
        background.setPrepare { [weak self] in
            await self?.waitUntilStarted()
        }

        // Eagerly start the monitor so the first read returns a real snapshot
        // rather than the `.unknown` default.
        qualityMonitor.setProbeURL(Self.probeURL(loaded.options))
        qualityMonitor.onChange = { [weak self] state in
            self?.networkChanged(state)
        }
        connectivity.start()
        qualityMonitor.start()
        let startLink = qualityMonitor.current.link
        lock.withLock { lastLink = startLink }

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
    ///
    /// Clears the persisted pause: JS cannot read it and starts out unpaused, so
    /// keeping it would stop the queue silently. A launch that never reaches JS
    /// does not get here, so it stays inert while the app is dead.
    func attachHybrid() {
        lock.withLock { attachedHybrids += 1 }
        configStore.setPaused(false)
        dispatcher.hooks.paused = false
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
        lock.withLock { isShutDown = true }
        retryTimer.cancel()
        heldWakeTimer.cancel()
        qualityMonitor.stop()
    }

    // MARK: - Network

    /// The link and its network-quality grade now.
    func currentNetwork() -> NetworkState {
        qualityMonitor.current
    }

    /// `healthCheckUrl`, the target of every measurement.
    private static func probeURL(_ options: SyncOptionsSnapshot) -> URL? {
        options.healthCheckUrl.flatMap { URL(string: $0) }
    }

    // MARK: - Configuration

    /// The active options.
    func currentOptions() -> SyncOptionsSnapshot {
        lock.withLock { options }
    }

    /// Where the active options came from.
    func configSource() -> ConfigSource {
        lock.withLock { source }
    }

    /// Applies and persists a configuration set by the app in this process.
    func configure(_ snapshot: SyncOptionsSnapshot) async {
        lock.withLock {
            options = snapshot
            source = .live
            credentialHoldUntil = 0
            // A held wake is re-evaluated by the re-arm below, against the new
            // options, with its re-check backoff starting over.
            heldWakeTimer.cancel()
            heldWakeRecheckMs = kHeldWakeRecheckInitialMs
        }
        configStore.save(snapshot)
        healthCheck.clearBackoff()
        qualityMonitor.setProbeURL(Self.probeURL(snapshot))
        await storage.updateCapacity(Int(snapshot.maxQueueSize))
        await dispatcher.updateConfig(snapshot.toDispatcherConfig(source: .live))
        await rearmRetryTimer()
    }

    private func recoverDefaultHeaders() -> [String: String]? {
        guard let headers = configStore.readDefaultHeaders() else { return nil }
        lock.withLock { options.defaultHeaders = headers }
        return headers
    }

    // MARK: - Pause

    func pause() async {
        configStore.setPaused(true)
        await dispatcher.setPaused(true)
        disarmRetryTimer()
    }

    func resume() async {
        configStore.setPaused(false)
        await dispatcher.setPaused(false)
        await rearmRetryTimer(scope: .allPending)
    }

    // MARK: - Retry timer

    /// `true` while a wake is scheduled.
    var isRetryTimerArmed: Bool {
        retryTimer.isArmed
    }

    /// `true` when a wake fired while the network could not take a flush and
    /// is waiting for one that can: offline, or below `minNetworkQuality`.
    var hasWakeAwaitingConnectivity: Bool {
        lock.withLock { wakeAwaitingConnectivity }
    }

    /// Arms the single retry timer for the next due row, replacing any pending
    /// wake. Called when a cycle ends, when a hybrid attaches, on
    /// configuration changes, and after the health check stopped a flush.
    ///
    /// The wake comes no earlier than the latest of the row's deadline, the
    /// credential hold and the end of a running health-check backoff.
    ///
    /// - Parameter notBefore: when the hold on a rejected credential ends. It
    ///   outlives this call, so a later re-arm cannot wake earlier.
    func rearmRetryTimer(scope: RetryWakeScope = .attempted, notBefore: Int64? = nil) async {
        let (ticket, canArm, holdUntil) = lock.withLock { () -> (UInt64, Bool, Int64) in
            rearmTicket &+= 1
            credentialHoldUntil = max(credentialHoldUntil, notBefore ?? 0)
            return (rearmTicket, attachedHybrids > 0 && !options.isManual, credentialHoldUntil)
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
        let backoffUntil = healthCheckBackoffEnd() ?? 0
        lock.withLock {
            guard ticket == rearmTicket, !isShutDown else { return }
            guard let wakeAt else {
                retryTimer.cancel()
                releaseHeldWakeLocked()
                return
            }
            // Any new arm restarts a held wake's re-check backoff: the timer
            // takes over, and a wake it finds still held starts again at 30 s.
            heldWakeTimer.cancel()
            heldWakeRecheckMs = kHeldWakeRecheckInitialMs
            retryTimer.arm(afterMs: max(0, max(wakeAt, holdUntil, backoffUntil) - nowMs)) { [weak self] in
                await self?.retryWakeFired()
            }
        }
    }

    private func disarmRetryTimer() {
        lock.withLock {
            rearmTicket &+= 1
            retryTimer.cancel()
            releaseHeldWakeLocked()
        }
    }

    /// Forgets a held wake, its pending re-check and its re-check backoff.
    private func releaseHeldWakeLocked() {
        wakeAwaitingConnectivity = false
        heldWakeTimer.cancel()
        heldWakeRecheckMs = kHeldWakeRecheckInitialMs
    }

    /// Arms the next silent re-check of a held wake and doubles the wait for
    /// the one after, up to ``kHeldWakeRecheckMaxMs``.
    private func scheduleHeldWakeRecheckLocked() {
        let delayMs = heldWakeRecheckMs
        heldWakeRecheckMs = min(delayMs * 2, kHeldWakeRecheckMaxMs)
        heldWakeTimer.arm(afterMs: delayMs) { [weak self] in
            await self?.heldWakeRecheckFired()
        }
    }

    private func heldWakeRecheckFired() async {
        guard lock.withLock({ wakeAwaitingConnectivity }) else { return }
        await retryWakeFired()
    }

    /// The wait before the next re-check of a held wake. Test seam.
    var heldWakeRecheckDelayMs: Int64 {
        lock.withLock { heldWakeRecheckMs }
    }

    /// Runs on timer expiry, where no JS caller exists to receive an error.
    ///
    /// A wake the network cannot take is remembered, not run: offline (neither
    /// the dispatcher nor the session checks connectivity, so a flush now
    /// would exhaust valid items against a dead network), or below
    /// `minNetworkQuality`. Like an offline wake, a gated one emits nothing;
    /// it runs once the network can take it.
    private func retryWakeFired() async {
        guard lock.withLock({ attachedHybrids > 0 && !isShutDown }) else { return }
        guard await !dispatcher.paused() else { return }
        let network = qualityMonitor.current
        guard canTakeAutomaticFlush(network) else {
            holdWake(on: network)
            return
        }
        lock.withLock { releaseHeldWakeLocked() }
        if healthCheckBackoffEnd() != nil {
            // A health-check backoff is running: like a gated wake, wait for it
            // silently. The re-arm lands no earlier than its end.
            SyncLogger.debug("Retry wake during a health-check backoff; waiting for its end", category: "dispatcher")
            await rearmRetryTimer(scope: .allPending)
            return
        }
        do {
            // The grade can drop between the check above and the gate. That is
            // a real attempt, so the gate reports it with `SYNC_FAILED`, as it
            // does on Android; the wake is held again.
            if case .skipped(_, .networkQualityTooLow) = try await dispatcher.flushOutcome() {
                holdWake(on: qualityMonitor.current)
            }
        } catch {
            SyncLogger.error("Timer-driven flush failed: \(error.localizedDescription)",
                             category: "dispatcher")
        }
    }

    /// Remembers a wake the network cannot take. Offline, reconnecting runs
    /// it. On a usable link below the minimum, a silent re-check is armed
    /// with backoff, and a stale measurement is taken again in the background:
    /// a new grade that meets the minimum runs the wake as soon as it lands.
    private func holdWake(on network: NetworkState) {
        let online = network.link.isOnline
        if online {
            qualityMonitor.refreshIfStale()
        }
        lock.withLock {
            wakeAwaitingConnectivity = true
            if online {
                scheduleHeldWakeRecheckLocked()
            } else {
                heldWakeTimer.cancel()
            }
        }
        SyncLogger.debug(online
                             ? "Retry wake below the network-quality minimum; waiting for it"
                             : "Retry wake while offline; waiting for connectivity",
                         category: "dispatcher")
    }

    /// The end of a running health-check backoff, when a health check is set.
    private func healthCheckBackoffEnd() -> Int64? {
        guard lock.withLock({ options.healthCheckUrl != nil }) else { return nil }
        return healthCheck.backoffUntilMs
    }

    /// `true` when an automatic wake may flush: online and, with a
    /// `minNetworkQuality` set, graded at or above it.
    private func canTakeAutomaticFlush(_ network: NetworkState) -> Bool {
        let minimum = lock.withLock { options.minNetworkQuality }
        return network.link.isOnline && network.quality.satisfies(minimum: minimum)
    }

    /// Runs for every change to what `ConnectionState` reports, the grade
    /// included, synchronously from the path update or measurement behind it:
    /// `CONNECTION_CHANGED` is emitted before the update returns, and the
    /// emitter calls listeners on the main thread. Android emits it
    /// asynchronously.
    private func networkChanged(_ network: NetworkState) {
        emitter.emit(.now(type: "CONNECTION_CHANGED", connectionStatus: network.link.status.rawValue))

        let pathChanged = lock.withLock { () -> Bool in
            defer { lastLink = network.link }
            return lastLink != network.link
        }
        guard canTakeAutomaticFlush(network) else {
            // Still held. A new path starts the re-check backoff over.
            guard pathChanged else { return }
            lock.withLock {
                guard wakeAwaitingConnectivity else { return }
                heldWakeRecheckMs = kHeldWakeRecheckInitialMs
                if network.link.isOnline {
                    scheduleHeldWakeRecheckLocked()
                } else {
                    heldWakeTimer.cancel()
                }
            }
            return
        }
        let hadWake = lock.withLock { () -> Bool in
            defer { releaseHeldWakeLocked() }
            return wakeAwaitingConnectivity
        }
        if hadWake {
            let wake = Task { await retryWakeFired() }
            lock.withLock { reconnectWake = wake }
        }
    }
}
