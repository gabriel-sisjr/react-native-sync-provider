import Foundation
@testable import SyncProvider

/// Connectivity the test controls. `NWPathMonitor` cannot be driven from a unit
/// test, and the offline rule has to be proven, not assumed.
final class FakeConnectivity: ConnectivityProviding, @unchecked Sendable {
    private let lock = NSLock()
    private var snapshot: ConnectionSnapshot
    private var listeners: [UUID: @Sendable (ConnectionSnapshot) -> Void] = [:]

    init(status: ConnectionSnapshot.Status = .connected) {
        snapshot = Self.snapshot(status)
    }

    var current: ConnectionSnapshot {
        lock.withLock { snapshot }
    }

    func start() {}

    @discardableResult
    func subscribe(_ listener: @escaping @Sendable (ConnectionSnapshot) -> Void) -> UUID {
        let id = UUID()
        let replay = lock.withLock { () -> ConnectionSnapshot in
            listeners[id] = listener
            return snapshot
        }
        listener(replay)
        return id
    }

    func unsubscribe(_ token: UUID) {
        lock.withLock { _ = listeners.removeValue(forKey: token) }
    }

    /// Changes the status and notifies subscribers, as a path update would.
    func set(_ status: ConnectionSnapshot.Status) {
        let (next, targets) = lock.withLock { () -> (ConnectionSnapshot, [@Sendable (ConnectionSnapshot) -> Void]) in
            snapshot = Self.snapshot(status)
            return (snapshot, Array(listeners.values))
        }
        targets.forEach { $0(next) }
    }

    private static func snapshot(_ status: ConnectionSnapshot.Status) -> ConnectionSnapshot {
        ConnectionSnapshot(status: status, type: .wifi, isInternetReachable: status == .connected, isExpensive: false)
    }
}
