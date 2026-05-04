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

    static let unknown = ConnectionSnapshot(status: .unknown,
                                            type: .unknown,
                                            isInternetReachable: nil,
                                            isExpensive: nil)
}

/// Singleton-style monitor. Starts on first instantiation; consumers get the
/// latest snapshot via `current` and subscribe via `subscribe(_:)` for change
/// callbacks.
final class ConnectivityMonitor: @unchecked Sendable {
    static let shared = ConnectivityMonitor()

    private let monitor: NWPathMonitor
    private let queue: DispatchQueue
    private let lock = NSLock()

    private var _current: ConnectionSnapshot = .unknown
    private var listeners: [UUID: @Sendable (ConnectionSnapshot) -> Void] = [:]
    private var didStart = false

    /// The last observed snapshot. Thread-safe.
    var current: ConnectionSnapshot {
        lock.lock()
        defer { lock.unlock() }
        return _current
    }

    private init(monitor: NWPathMonitor = NWPathMonitor()) {
        self.monitor = monitor
        self.queue = DispatchQueue(label: "com.gabriel-sisjr.syncprovider.connectivity",
                                   qos: .utility)
    }

    /// Start the monitor. Idempotent.
    func start() {
        lock.lock()
        let alreadyStarted = didStart
        if !alreadyStarted {
            didStart = true
        }
        lock.unlock()

        guard !alreadyStarted else { return }

        monitor.pathUpdateHandler = { [weak self] path in
            self?.handlePathUpdate(path)
        }
        monitor.start(queue: queue)
    }

    /// Stop the monitor. Mostly used by tests.
    func stop() {
        monitor.cancel()
        lock.lock()
        didStart = false
        listeners.removeAll()
        lock.unlock()
    }

    /// Subscribe for change notifications. Returns a token that can be passed
    /// to `unsubscribe` to remove the listener.
    @discardableResult
    func subscribe(_ listener: @escaping @Sendable (ConnectionSnapshot) -> Void) -> UUID {
        let id = UUID()
        lock.lock()
        listeners[id] = listener
        let snapshot = _current
        lock.unlock()
        // Replay the current snapshot synchronously so subscribers always see
        // a value rather than waiting for the next path update.
        listener(snapshot)
        return id
    }

    func unsubscribe(_ token: UUID) {
        lock.lock()
        listeners.removeValue(forKey: token)
        lock.unlock()
    }

    // MARK: - Path translation

    private func handlePathUpdate(_ path: NWPath) {
        let snapshot = Self.translate(path)

        lock.lock()
        _current = snapshot
        let snapshotListeners = Array(listeners.values)
        lock.unlock()

        SyncLogger.debug("Connectivity changed: status=\(snapshot.status.rawValue) type=\(snapshot.type.rawValue) expensive=\(snapshot.isExpensive ?? false)",
                         category: "connectivity")

        for listener in snapshotListeners {
            listener(snapshot)
        }
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
            isExpensive: path.isExpensive
        )
    }
}
