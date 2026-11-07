import Foundation
@testable import SyncProvider

/// Everything that outlives a process: the queue, the UserDefaults domain and
/// the secret store. `makeEngine()` builds a new engine over them the way an
/// app launch does, `makeColdEngine()` the way a BGTask launch does. Time is
/// manual: nothing happens until a test moves `clock` and fires `timer`.
final class EngineFixture {
    let stack = InMemoryCoreDataStack.make()
    let secrets = InMemorySecretStore()
    let connectivity = FakeConnectivity()
    /// Every measurement and health check goes through it, with no debounce.
    let probe = FakeHeadProbe()
    /// Set before building an engine to use another probe, a real one say.
    var probeOverride: HeadProbing?
    let scheduler = FakeBackgroundTaskScheduler()
    let clock = ManualClock()
    let timer = ManualWakeTimer()
    /// Re-checks a wake held below the network-quality minimum.
    let recheckTimer = ManualWakeTimer()
    /// Set before building an engine to intercept its deadline queries.
    var wrapWakeSource: ((RetryWakeSource) -> RetryWakeSource)?
    let foregroundSession = URLSession.stubbed()
    let backgroundSession = URLSession(configuration: .ephemeral)
    let defaults: UserDefaults
    private let suite: String
    private var engines: [SyncEngine] = []

    init() {
        (defaults, suite) = UserDefaults.ephemeral()
    }

    /// Builds an engine with a JS-facing hybrid and waits until it has started.
    func makeEngine(emitter: SyncEventEmitter = MockSyncEventEmitter()) async -> SyncEngine {
        let engine = buildEngine(emitter: emitter)
        engine.attachHybrid()
        await engine.waitUntilStarted()
        return engine
    }

    /// Builds an engine no JS will reach and waits until it has started.
    func makeColdEngine(emitter: SyncEventEmitter = MockSyncEventEmitter()) async -> SyncEngine {
        let engine = buildEngine(emitter: emitter)
        await engine.waitUntilStarted()
        return engine
    }

    /// Builds an engine without waiting for it to start.
    func buildEngine(emitter: SyncEventEmitter = MockSyncEventEmitter()) -> SyncEngine {
        let engine = SyncEngine(stack: stack,
                                configStore: SyncConfigStore(defaults: defaults, secrets: secrets),
                                backgroundStore: BackgroundSyncOptionsStore(defaults: defaults),
                                connectivity: connectivity,
                                emitter: emitter,
                                scheduler: scheduler,
                                sessions: (foregroundSession, backgroundSession),
                                probe: probeOverride ?? probe,
                                qualityProbeDebounceMs: 0,
                                clock: clock,
                                retryTimer: timer,
                                heldWakeTimer: recheckTimer,
                                wrapWakeSource: wrapWakeSource)
        engines.append(engine)
        return engine
    }

    /// Stops every engine. The sessions are left alive on purpose: a task
    /// created on an invalidated session raises an Objective-C exception, and
    /// a background handler may still be finishing.
    func tearDown() {
        engines.forEach { $0.shutdown() }
        defaults.removePersistentDomain(forName: suite)
    }
}

extension SyncQueueStorage.SyncItemInputData {
    /// A valid item. The URL is unique enough to tell requests apart.
    static func sample(url: String = "https://api.test/items") -> Self {
        Self(method: "POST",
             url: url,
             headers: nil,
             body: "{}",
             contentType: "application/json",
             priority: "NORMAL",
             metadata: nil)
    }
}

extension RetryPolicyValue {
    /// A policy with exact, jitter-free delays.
    static func exact(maxAttempts: Int, baseDelayMs: Int, maxDelayMs: Int = 60_000) -> Self {
        Self(maxAttempts: maxAttempts,
             backoff: .exponential,
             baseDelayMs: baseDelayMs,
             maxDelayMs: maxDelayMs,
             jitter: false,
             retryOnStatusCodes: [500, 502, 503])
    }
}

extension SyncOptionsSnapshot {
    /// Default options with the given overrides.
    static func sample(strategy: String = "AUTOMATIC",
                       retryPolicy: RetryPolicyValue = .exact(maxAttempts: 5, baseDelayMs: 60_000),
                       batchSize: Double = 25,
                       maxQueueSize: Double = 100,
                       defaultHeaders: [String: String] = [:]) -> Self {
        Self(strategy: strategy,
             retryPolicy: retryPolicy,
             batchSize: batchSize,
             requestTimeoutMs: 30_000,
             maxQueueSize: maxQueueSize,
             persistQueue: true,
             defaultHeaders: defaultHeaders)
    }
}
