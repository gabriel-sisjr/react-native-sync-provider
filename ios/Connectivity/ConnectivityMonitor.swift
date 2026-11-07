//
//  ConnectivityMonitor.swift
//  SyncProvider
//
//  Wraps `NWPathMonitor` (Network framework) so the rest of the codebase can
//  read the current connection snapshot or subscribe to changes without
//  touching `NWPath` directly. Maps `NWPath.isExpensive` → `METERED`.
//

import Foundation
import Network

/// Sendable snapshot of the current connection state. Translated to the
/// Nitro `ConnectionState` struct at the bridge surface.
struct ConnectionSnapshot: Sendable, Equatable {
    enum Status: String, Sendable {
        case connected = "CONNECTED"
        case disconnected = "DISCONNECTED"
        case metered = "METERED"
        case unknown = "UNKNOWN"
    }

    enum Kind: String, Sendable {
        case unknown = "UNKNOWN"
        case wifi = "WIFI"
        case cellular = "CELLULAR"
        case ethernet = "ETHERNET"
        case other = "OTHER"
        case none = "NONE"
    }

    let status: Status
    let type: Kind
    let isInternetReachable: Bool?
    let isExpensive: Bool?
    /// Low Data Mode (`NWPath.isConstrained`). Engine-only: it caps the
    /// network-quality grade, and `ConnectionState` has no field for it.
    var isConstrained: Bool? = nil
    /// The path's gateway addresses (`NWPath.gateways`), sorted. Engine-only:
    /// `NWPath` names no network, so these are what tells one Wi-Fi network
    /// from another of the same cost. Two networks behind the same gateway
    /// address still look alike.
    var gateways: [String]? = nil

    static let unknown = ConnectionSnapshot(status: .unknown,
                                            type: .unknown,
                                            isInternetReachable: nil,
                                            isExpensive: nil)

    /// `true` only when a path is known to be usable. `.unknown` is not online:
    /// it lasts until the first path update, and treating it as online would
    /// let a background wake spend retry attempts with no network.
    var isOnline: Bool {
        status == .connected || status == .metered
    }
}

/// The part of ``ConnectivityMonitor`` the engine depends on. `NWPathMonitor`
/// cannot be driven from a test, so this seam is what lets the offline rule be
/// proven deterministically.
protocol ConnectivityProviding: AnyObject, Sendable {
    var current: ConnectionSnapshot { get }
    func start()
    @discardableResult
    func subscribe(_ listener: @escaping @Sendable (ConnectionSnapshot) -> Void) -> UUID
    func unsubscribe(_ token: UUID)
    /// Returns once the status is known (anything but `.unknown`), or after
    /// `timeoutMs` if it is still unknown then.
    func waitUntilKnown(timeoutMs: Int) async
}

/// Singleton-style monitor. Starts on first instantiation; consumers get the
/// latest snapshot via `current` and subscribe via `subscribe(_:)` for change
/// callbacks.
final class ConnectivityMonitor: ConnectivityProviding, @unchecked Sendable {
    static let shared = ConnectivityMonitor()

    private let monitor: NWPathMonitor
    private let queue: DispatchQueue
    private let lock = NSLock()

    private var _current: ConnectionSnapshot = .unknown
    private var listeners: [UUID: @Sendable (ConnectionSnapshot) -> Void] = [:]
    private var knownWaiters: [UUID: CheckedContinuation<Void, Never>] = [:]
    private var didStart = false

    /// The last observed snapshot. Thread-safe.
    var current: ConnectionSnapshot {
        lock.withLock { _current }
    }

    private init(monitor: NWPathMonitor = NWPathMonitor()) {
        self.monitor = monitor
        self.queue = DispatchQueue(label: "com.gabriel-sisjr.syncprovider.connectivity",
                                   qos: .utility)
    }

    /// Start the monitor. Idempotent.
    func start() {
        let alreadyStarted = lock.withLock { () -> Bool in
            let wasStarted = didStart
            if !wasStarted {
                didStart = true
            }
            return wasStarted
        }

        guard !alreadyStarted else { return }

        monitor.pathUpdateHandler = { [weak self] path in
            self?.handlePathUpdate(path)
        }
        monitor.start(queue: queue)
    }

    /// Stop the monitor. Mostly used by tests.
    func stop() {
        monitor.cancel()
        lock.withLock {
            didStart = false
            listeners.removeAll()
        }
    }

    /// Subscribe for change notifications. Returns a token that can be passed
    /// to `unsubscribe` to remove the listener.
    @discardableResult
    func subscribe(_ listener: @escaping @Sendable (ConnectionSnapshot) -> Void) -> UUID {
        let id = UUID()
        let snapshot = lock.withLock { () -> ConnectionSnapshot in
            listeners[id] = listener
            return _current
        }
        // Replay the current snapshot synchronously so subscribers always see
        // a value rather than waiting for the next path update.
        listener(snapshot)
        return id
    }

    func unsubscribe(_ token: UUID) {
        lock.withLock {
            _ = listeners.removeValue(forKey: token)
        }
    }

    /// A process launched by a BGTask has no path yet. Waiting here keeps a
    /// flush from being judged on that unknown path instead of the real one.
    func waitUntilKnown(timeoutMs: Int) async {
        guard lock.withLock({ _current.status == .unknown }) else { return }
        let id = UUID()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let known = lock.withLock { () -> Bool in
                guard _current.status == .unknown else { return true }
                knownWaiters[id] = continuation
                return false
            }
            if known {
                continuation.resume()
                return
            }
            queue.asyncAfter(wallDeadline: .now() + .milliseconds(max(0, timeoutMs))) { [weak self] in
                self?.releaseKnownWaiter(id)
            }
        }
    }

    private func releaseKnownWaiter(_ id: UUID) {
        lock.withLock { knownWaiters.removeValue(forKey: id) }?.resume()
    }

    // MARK: - Path translation

    private func handlePathUpdate(_ path: NWPath) {
        let snapshot = Self.translate(path)

        let (snapshotListeners, waiters) = lock.withLock {
            () -> ([@Sendable (ConnectionSnapshot) -> Void], [CheckedContinuation<Void, Never>]) in
            _current = snapshot
            var released: [CheckedContinuation<Void, Never>] = []
            if snapshot.status != .unknown {
                released = Array(knownWaiters.values)
                knownWaiters.removeAll()
            }
            return (Array(listeners.values), released)
        }

        SyncLogger.debug("Connectivity changed: status=\(snapshot.status.rawValue) type=\(snapshot.type.rawValue) expensive=\(snapshot.isExpensive ?? false) constrained=\(snapshot.isConstrained ?? false)",
                         category: "connectivity")

        for listener in snapshotListeners {
            listener(snapshot)
        }
        waiters.forEach { $0.resume() }
    }

    static func translate(_ path: NWPath) -> ConnectionSnapshot {
        let status: ConnectionSnapshot.Status
        switch path.status {
        case .satisfied:
            status = path.isExpensive ? .metered : .connected
        case .unsatisfied:
            status = .disconnected
        case .requiresConnection:
            status = .disconnected
        @unknown default:
            status = .unknown
        }

        let type: ConnectionSnapshot.Kind
        if path.usesInterfaceType(.wifi) {
            type = .wifi
        } else if path.usesInterfaceType(.cellular) {
            type = .cellular
        } else if path.usesInterfaceType(.wiredEthernet) {
            type = .ethernet
        } else if path.usesInterfaceType(.other) {
            type = .other
        } else if path.status == .unsatisfied {
            type = .none
        } else {
            type = .unknown
        }

        return ConnectionSnapshot(
            status: status,
            type: type,
            isInternetReachable: path.status == .satisfied,
            isExpensive: path.isExpensive,
            isConstrained: path.isConstrained,
            gateways: path.gateways.map { String(describing: $0) }.sorted()
        )
    }
}
