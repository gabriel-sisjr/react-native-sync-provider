//
//  RetryWakeTimer.swift
//  SyncProvider
//
//  One single-shot wake-up per engine. It is what re-drives a cooling item
//  while the app runs: nothing else in the process does.
//

import Foundation

/// A single-shot wake. Arming replaces any pending wake.
protocol RetryWakeScheduling: AnyObject, Sendable {
    /// `true` from arming until the wake fires or is cancelled.
    var isArmed: Bool { get }
    /// Schedules `fire` once, `delayMs` from now, replacing any pending wake.
    func arm(afterMs delayMs: Int64, fire: @escaping @Sendable () async -> Void)
    /// Drops the pending wake, if any.
    func cancel()
}

/// The production ``RetryWakeScheduling``.
///
/// The deadline is wall-clock, like `nextRetryAt`. A monotonic deadline stops
/// counting while the device sleeps, so an elapsed cooldown would wait for the
/// remainder after the app resumes. A wall-clock one fires on resume.
final class RetryWakeTimer: RetryWakeScheduling, @unchecked Sendable {
    /// Runs `work` after `delayMs`. A seam so tests decide when time passes.
    typealias Schedule = @Sendable (_ delayMs: Int, _ work: DispatchWorkItem) -> Void

    private let schedule: Schedule
    private let lock = NSLock()
    private var generation: UInt64 = 0
    private var pending: DispatchWorkItem?

    init(schedule: Schedule? = nil) {
        let queue = DispatchQueue(label: "com.gabriel-sisjr.syncprovider.retry-timer", qos: .utility)
        self.schedule = schedule ?? { delayMs, work in
            queue.asyncAfter(wallDeadline: .now() + .milliseconds(delayMs), execute: work)
        }
    }

    var isArmed: Bool {
        lock.withLock { pending != nil }
    }

    /// `fire` runs after the timer has disarmed itself, so it may re-arm.
    func arm(afterMs delayMs: Int64, fire: @escaping @Sendable () async -> Void) {
        // `DispatchTimeInterval` takes an `Int`; longer waits simply wake early
        // and re-arm, because a cycle ending always re-arms.
        let boundedMs = Int(max(0, min(delayMs, Int64(Int32.max))))
        let item: DispatchWorkItem = lock.withLock {
            pending?.cancel()
            generation &+= 1
            let token = generation
            let item = DispatchWorkItem { [weak self] in
                guard let self, self.disarm(ifCurrent: token) else { return }
                Task { await fire() }
            }
            pending = item
            return item
        }
        schedule(boundedMs, item)
    }

    func cancel() {
        lock.withLock {
            pending?.cancel()
            pending = nil
            generation &+= 1
        }
    }

    private func disarm(ifCurrent token: UInt64) -> Bool {
        lock.withLock {
            guard token == generation else { return false }
            pending = nil
            return true
        }
    }
}
