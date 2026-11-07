import Foundation
@testable import SyncProvider

/// Connectivity the test controls. `NWPathMonitor` cannot be driven from a unit
/// test, and the offline rule has to be proven, not assumed.
final class FakeConnectivity: ConnectivityProviding, @unchecked Sendable {
    private let lock = NSLock()
    private var snapshot: ConnectionSnapshot
    private var listeners: [UUID: @Sendable (ConnectionSnapshot) -> Void] = [:]
    private var knownWaiters: [UUID: CheckedContinuation<Void, Never>] = [:]
    private var waits: [Int] = []

    init(status: ConnectionSnapshot.Status = .connected) {
        snapshot = Self.snapshot(status)
    }

    var current: ConnectionSnapshot {
        lock.withLock { snapshot }
    }

    /// The timeout of every `waitUntilKnown` call, in order.
    var waitTimeouts: [Int] {
        lock.withLock { waits }
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

    /// Like the real monitor: returns at once on a known status, otherwise on
    /// the first `set` of a known one or after `timeoutMs`, in real time.
    func waitUntilKnown(timeoutMs: Int) async {
        let id = UUID()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let known = lock.withLock { () -> Bool in
                waits.append(timeoutMs)
                guard snapshot.status == .unknown else { return true }
                knownWaiters[id] = continuation
                return false
            }
            if known {
                continuation.resume()
                return
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) { [weak self] in
                self?.releaseWaiter(id)
            }
        }
    }

    /// Changes the status and notifies subscribers, as a path update would.
    func set(_ status: ConnectionSnapshot.Status) {
        set(snapshot: Self.snapshot(status))
    }

    /// Replaces the whole snapshot and notifies subscribers.
    func set(snapshot next: ConnectionSnapshot) {
        let (targets, waiters) = lock.withLock {
            () -> ([@Sendable (ConnectionSnapshot) -> Void], [CheckedContinuation<Void, Never>]) in
            snapshot = next
            var released: [CheckedContinuation<Void, Never>] = []
            if next.status != .unknown {
                released = Array(knownWaiters.values)
                knownWaiters.removeAll()
            }
            return (Array(listeners.values), released)
        }
        targets.forEach { $0(next) }
        waiters.forEach { $0.resume() }
    }

    private func releaseWaiter(_ id: UUID) {
        lock.withLock { knownWaiters.removeValue(forKey: id) }?.resume()
    }

    private static func snapshot(_ status: ConnectionSnapshot.Status) -> ConnectionSnapshot {
        ConnectionSnapshot(status: status, type: .wifi, isInternetReachable: status == .connected, isExpensive: false)
    }
}

extension ConnectionSnapshot {
    /// A usable link with the given signals.
    static func link(_ type: Kind = .wifi,
                     expensive: Bool = false,
                     constrained: Bool = false) -> ConnectionSnapshot {
        ConnectionSnapshot(status: expensive ? .metered : .connected,
                           type: type,
                           isInternetReachable: true,
                           isExpensive: expensive,
                           isConstrained: constrained)
    }

    /// No link.
    static let disconnected = ConnectionSnapshot(status: .disconnected,
                                                 type: .none,
                                                 isInternetReachable: false,
                                                 isExpensive: false)
}
