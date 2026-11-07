//
//  NetworkQualityMonitor.swift
//  SyncProvider
//
//  Keeps the engine's network-quality grade: link signals from the
//  connectivity provider, latency measurements from the HEAD probe, graded by
//  `NetworkQualityClassifier`.
//

import Foundation

/// The wait between a path update and the probe it triggers, so a burst of
/// updates costs one measurement. The same on both platforms.
let kQualityProbeDebounceMs = 1_000
/// How long a measurement spares the same link another probe.
let kQualityMeasurementCacheMs: Int64 = 30_000
/// The timeout of each HEAD in a measurement.
let kQualityProbeTimeoutMs = 2_000
/// The HEADs in a measurement, sent back to back.
let kQualityProbeSamples = 3

/// The link and its grade, read together.
struct NetworkState: Sendable, Equatable {
    let link: ConnectionSnapshot
    let quality: NetworkQualityLevel
}

/// Grades the network continuously.
///
/// - Without a probe URL (`healthCheckUrl` unset) it never sends anything:
///   the grade comes from link signals alone.
/// - With one, every path update on a usable link may trigger a measurement:
///   after ``kQualityProbeDebounceMs``, three back-to-back HEADs on the one
///   shared probe, a failure counting as infinitely slow. Until the first
///   measurement of a link completes, the grade comes from link signals.
/// - A measurement belongs to the link it was taken on: its interface type,
///   whether it is expensive, whether it is constrained, its gateways. A path
///   update on that same link reuses a measurement younger than
///   ``kQualityMeasurementCacheMs`` and re-probes once it is older. A
///   different link drops it and is probed afresh. Losing the link cancels a
///   measurement in flight but keeps the last one, for the same link coming
///   back. Changing the probe URL drops it and probes the new URL.
/// - A measurement never expires on its own: the grade does not fall back to
///   link signals with age. A gate evaluation that finds it stale asks for a
///   new one through ``refreshIfStale()``.
/// - A measurement interrupted by any of those, or by ``stop()``, is
///   discarded whole: a cancelled HEAD reads as a failure, and a set with one
///   would grade the link `poor` for nothing.
///
/// Changes to what `ConnectionState` reports go to ``onChange``, synchronously
/// from the update that caused them; an update that changes none of it is
/// not reported. A lock, not an actor, so the bridge reads ``current``
/// synchronously.
final class NetworkQualityMonitor: @unchecked Sendable {
    /// What a measurement is valid for: one network, as far as `NWPath` can
    /// tell. It names no network, so the gateway addresses stand in for one;
    /// two networks behind the same gateway address are taken for one, and a
    /// stale measurement on them is taken again by ``refreshIfStale()``.
    private struct LinkIdentity: Equatable {
        let type: ConnectionSnapshot.Kind
        let expensive: Bool
        let constrained: Bool
        let gateways: [String]

        init(_ link: ConnectionSnapshot) {
            type = link.type
            expensive = NetworkQualityClassifier.isMetered(link)
            constrained = link.isConstrained == true
            gateways = link.gateways ?? []
        }
    }

    private struct Measurement {
        let samples: [Int?]
        let identity: LinkIdentity
        let measuredAtMs: Int64
    }

    /// What `ConnectionState` carries. A change here is a `CONNECTION_CHANGED`.
    private struct Published: Equatable {
        let status: ConnectionSnapshot.Status
        let type: ConnectionSnapshot.Kind
        let isInternetReachable: Bool?
        let isExpensive: Bool?
        let quality: NetworkQualityLevel

        init(_ state: NetworkState) {
            status = state.link.status
            type = state.link.type
            isInternetReachable = state.link.isInternetReachable
            isExpensive = state.link.isExpensive
            quality = state.quality
        }
    }

    private let connectivity: ConnectivityProviding
    private let probe: HeadProbing
    private let clock: SyncClock
    private let debounceMs: Int

    private let lock = NSLock()
    private var link: ConnectionSnapshot = .unknown
    private var measurement: Measurement?
    private var probeURL: URL?
    private var probeTask: Task<Void, Never>?
    /// Every measurement still running, cancelled ones too, by ticket. Each
    /// removes itself when it ends.
    private var runningProbes: [UInt64: Task<Void, Never>] = [:]
    /// Bumped whenever a measurement in flight stops being wanted, so one that
    /// finishes anyway cannot be applied.
    private var generation: UInt64 = 0
    private var published: Published?
    private var changeHandler: (@Sendable (NetworkState) -> Void)?
    private var subscription: UUID?

    /// - Parameter probe: the probe the health check shares, so both reuse
    ///   one connection to the host.
    init(connectivity: ConnectivityProviding,
         probe: HeadProbing,
         clock: SyncClock,
         debounceMs: Int = kQualityProbeDebounceMs) {
        self.connectivity = connectivity
        self.probe = probe
        self.clock = clock
        self.debounceMs = debounceMs
    }

    /// The link and its grade now.
    var current: NetworkState {
        lock.withLock { stateLocked() }
    }

    /// Called with the new state whenever what `ConnectionState` reports
    /// changes. Never called for the first state, which is no change.
    var onChange: (@Sendable (NetworkState) -> Void)? {
        get { lock.withLock { changeHandler } }
        set { lock.withLock { changeHandler = newValue } }
    }

    /// Starts following the connectivity provider. Idempotent.
    func start() {
        guard lock.withLock({ subscription == nil }) else { return }
        let token = connectivity.subscribe { [weak self] snapshot in
            self?.linkChanged(snapshot)
        }
        lock.withLock { subscription = token }
    }

    /// Stops following the provider and drops any measurement in flight.
    func stop() {
        let token = lock.withLock { () -> UUID? in
            cancelProbeLocked()
            defer { subscription = nil }
            return subscription
        }
        if let token {
            connectivity.unsubscribe(token)
        }
    }

    /// Sets the URL measurements are taken against: `healthCheckUrl`, or
    /// `nil` for none.
    func setProbeURL(_ url: URL?) {
        update { () -> Bool in
            guard url != probeURL else { return false }
            probeURL = url
            cancelProbeLocked()
            measurement = nil
            return true
        }
    }

    /// Measures again, in the background and without debounce, when the
    /// measurement is ``kQualityMeasurementCacheMs`` old or older, or missing.
    /// Every gate evaluation calls it: a measurement never expires on its own,
    /// so without this a stable path would keep an old grade, and a wake held
    /// on it, indefinitely. At most one measurement runs at a time, and never
    /// without a URL or a usable link.
    func refreshIfStale() {
        lock.withLock { scheduleProbeIfDueLocked(debounced: false) }
    }

    /// Returns once every measurement running now, a cancelled one included,
    /// has finished.
    func waitForProbe() async {
        let running = lock.withLock { Array(runningProbes.values) }
        for probe in running {
            await probe.value
        }
    }

    // MARK: - Updates

    private func linkChanged(_ snapshot: ConnectionSnapshot) {
        update { () -> Bool in
            link = snapshot
            if !snapshot.isOnline {
                // The measurement outlives an offline blip: the same link back
                // within the cache window reuses it.
                cancelProbeLocked()
            } else if let measurement, measurement.identity != LinkIdentity(snapshot) {
                cancelProbeLocked()
                self.measurement = nil
            }
            return true
        }
    }

    /// Applies `change` under the lock, then reports the new state if what
    /// `ConnectionState` carries changed, and starts a measurement if one is
    /// now due. `change` returns `false` when it changed nothing.
    private func update(_ change: () -> Bool) {
        let (notify, handler) = lock.withLock { () -> (NetworkState?, (@Sendable (NetworkState) -> Void)?) in
            guard change() else { return (nil, nil) }
            scheduleProbeIfDueLocked()
            return (publishLocked(), changeHandler)
        }
        if let notify {
            handler?(notify)
        }
    }

    // MARK: - Measuring

    private func scheduleProbeIfDueLocked(debounced: Bool = true) {
        guard probeTask == nil, let url = probeURL, link.isOnline else { return }
        if let measurement, isFresh(measurement) {
            return
        }
        generation &+= 1
        let ticket = generation
        let identity = LinkIdentity(link)
        let delayMs = debounced ? debounceMs : 0
        let task = Task { [weak self] in
            await self?.measure(url: url, identity: identity, ticket: ticket, delayMs: delayMs)
            self?.retire(ticket)
        }
        probeTask = task
        runningProbes[ticket] = task
    }

    private func retire(_ ticket: UInt64) {
        lock.withLock { _ = runningProbes.removeValue(forKey: ticket) }
    }

    /// Younger than the cache window. The clock is the wall clock, which the
    /// uptime clocks' privacy declaration would be the price of avoiding, so
    /// it can move back: a negative age counts as stale, not as fresh.
    private func isFresh(_ measurement: Measurement) -> Bool {
        let ageMs = clock.nowMs() - measurement.measuredAtMs
        return ageMs >= 0 && ageMs < kQualityMeasurementCacheMs
    }

    private func cancelProbeLocked() {
        probeTask?.cancel()
        probeTask = nil
        generation &+= 1
    }

    private func measure(url: URL, identity: LinkIdentity, ticket: UInt64, delayMs: Int) async {
        if delayMs > 0 {
            try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
        }
        var samples: [Int?] = []
        while samples.count < kQualityProbeSamples, !Task.isCancelled {
            let result = await probe.head(url, timeoutMs: kQualityProbeTimeoutMs)
            if Task.isCancelled { break }
            // Any status is a latency sample: what a status means is the
            // health check's business.
            samples.append(result.latencyMs)
        }
        let complete = samples.count == kQualityProbeSamples && !Task.isCancelled
        finish(ticket: ticket, measurement: complete ? Measurement(samples: samples,
                                                                   identity: identity,
                                                                   measuredAtMs: clock.nowMs()) : nil)
    }

    private func finish(ticket: UInt64, measurement newMeasurement: Measurement?) {
        let (notify, handler) = lock.withLock { () -> (NetworkState?, (@Sendable (NetworkState) -> Void)?) in
            // A newer update cancelled this one, or will.
            guard ticket == generation else { return (nil, nil) }
            probeTask = nil
            guard let newMeasurement, link.isOnline, LinkIdentity(link) == newMeasurement.identity else {
                return (nil, nil)
            }
            measurement = newMeasurement
            return (publishLocked(), changeHandler)
        }
        if let notify {
            handler?(notify)
        }
    }

    // MARK: - State

    private func stateLocked() -> NetworkState {
        let samples = measurement.flatMap { $0.identity == LinkIdentity(link) ? $0.samples : nil }
        return NetworkState(link: link, quality: NetworkQualityClassifier.classify(link: link, samples: samples))
    }

    /// The state to report, or `nil` when `ConnectionState` would not change.
    private func publishLocked() -> NetworkState? {
        let state = stateLocked()
        let next = Published(state)
        defer { published = next }
        guard let previous = published, previous != next else { return nil }
        return state
    }
}
