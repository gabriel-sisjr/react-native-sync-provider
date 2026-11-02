import Foundation
@testable import SyncProvider

/// A clock tests move by hand. Time passes only when a test says so, including
/// during an attempt, so results cannot depend on how busy the machine is.
final class ManualClock: SyncClock, @unchecked Sendable {
    private let lock = NSLock()
    private var now: Int64

    init(startMs: Int64 = 1_700_000_000_000) {
        now = startMs
    }

    func nowMs() -> Int64 {
        lock.withLock { now }
    }

    func advance(ms: Int64) {
        lock.withLock { now += ms }
    }
}

/// A retry wake tests fire by hand.
final class ManualWakeTimer: RetryWakeScheduling, @unchecked Sendable {
    typealias Wake = @Sendable () async -> Void

    private let lock = NSLock()
    private var pending: (delayMs: Int64, fire: Wake)?

    var isArmed: Bool {
        lock.withLock { pending != nil }
    }

    /// The delay of the pending wake, if any.
    var delayMs: Int64? {
        lock.withLock { pending?.delayMs }
    }

    func arm(afterMs delayMs: Int64, fire: @escaping @Sendable () async -> Void) {
        lock.withLock { pending = (delayMs, fire) }
    }

    func cancel() {
        lock.withLock { pending = nil }
    }

    /// Removes the pending wake without running it: a wake that already fired
    /// but has not been scheduled onto a thread yet.
    func takeWake() -> Wake? {
        lock.withLock {
            defer { pending = nil }
            return pending?.fire
        }
    }

    /// Fires the pending wake and waits for everything it started.
    func fire() async {
        await takeWake()?()
    }
}
