//
//  HealthCheck.swift
//  SyncProvider
//
//  The HEAD sent to `healthCheckUrl` before a cycle claims its first item, the
//  cache that spares it, and the backoff that follows a failure.
//

import Foundation

/// The timeout of a health-check HEAD: the probe's short one.
let kHealthCheckTimeoutMs = 2_000

/// What the health check decided before a cycle.
enum HealthCheckVerdict: Sendable, Equatable {
    /// The cycle may run: a 2xx younger than the TTL was cached, or the HEAD
    /// just sent got one.
    case healthy
    /// The cycle must not run: the HEAD just sent failed, or an earlier
    /// failure's backoff runs until `backoffUntilMs`.
    case unhealthy(backoffUntilMs: Int64)
}

/// The engine's health-check state, shared by everything that sends a HEAD to
/// `healthCheckUrl`.
///
/// - Healthy means a final 2xx, after redirects. Any other status, a transport
///   error or a timeout is unhealthy.
/// - Any 2xx HEAD to the URL refreshes "last healthy at", a network-quality
///   sample's included. While that is younger than the TTL, no HEAD is sent.
///   It is kept per URL: a new URL is checked afresh.
/// - A failure starts a backoff: until it ends, checks fail at once, without a
///   HEAD. Time, a successful check or ``clearBackoff()`` (every
///   `configureSync`) ends it; a 2xx quality sample does not.
/// - Automatic retries wait for the backoff too, but for no less than a floor
///   after the failure (``retryBackoffUntilMs(floorMs:)``). Checks themselves
///   keep to the backoff alone.
/// - Checks that overlap share one HEAD.
/// - Ages run on the wall clock, which can move back. A negative age makes a
///   cached 2xx stale and ends a backoff, so neither outlives a clock change.
final class HealthCheck: @unchecked Sendable {
    private struct Backoff {
        let startMs: Int64
        let durationMs: Int64

        var endMs: Int64 { startMs + durationMs }

        func isRunning(atMs nowMs: Int64) -> Bool {
            let elapsedMs = nowMs - startMs
            return elapsedMs >= 0 && elapsedMs < durationMs
        }
    }

    private let probe: HeadProbing
    private let clock: SyncClock

    private let lock = NSLock()
    private var lastHealthy: (url: URL, atMs: Int64)?
    private var backoff: Backoff?
    private var inFlight: (url: URL, id: UUID, task: Task<HeadProbeResult, Never>)?

    /// - Parameter probe: the probe the network-quality measurement uses, so
    ///   both reuse one connection to the host.
    init(probe: HeadProbing, clock: SyncClock) {
        self.probe = probe
        self.clock = clock
    }

    /// The end of the running backoff, or `nil` when none is running.
    var backoffUntilMs: Int64? {
        let nowMs = clock.nowMs()
        return lock.withLock {
            guard let backoff, backoff.isRunning(atMs: nowMs) else { return nil }
            return backoff.endMs
        }
    }

    /// The end of the wait automatic retries keep after the last failure: the
    /// backoff, lasting at least `floorMs`, so that a backoff of 0 cannot have
    /// them probe back to back while the check keeps failing. `nil` once that
    /// wait is over, and with no failure since the last success or
    /// ``clearBackoff()``.
    func retryBackoffUntilMs(floorMs: Int64) -> Int64? {
        let nowMs = clock.nowMs()
        return lock.withLock {
            guard let backoff else { return nil }
            let floored = Backoff(startMs: backoff.startMs, durationMs: max(backoff.durationMs, floorMs))
            guard floored.isRunning(atMs: nowMs) else { return nil }
            return floored.endMs
        }
    }

    /// Records a 2xx from a HEAD to `url`, whoever sent it.
    func recordHealthy(url: URL) {
        let nowMs = clock.nowMs()
        lock.withLock { lastHealthy = (url, nowMs) }
    }

    /// Ends a running backoff.
    func clearBackoff() {
        lock.withLock { backoff = nil }
    }

    /// Decides whether a cycle may run against `url`. Sends a HEAD only when
    /// no backoff runs and no 2xx younger than `ttlMs` is cached for `url`.
    func check(url: URL, ttlMs: Int64, backoffMs: Int64) async -> HealthCheckVerdict {
        let nowMs = clock.nowMs()
        let pending = lock.withLock { () -> (id: UUID, task: Task<HeadProbeResult, Never>)? in
            if let backoff, backoff.isRunning(atMs: nowMs) {
                return nil
            }
            if let lastHealthy, lastHealthy.url == url {
                let ageMs = nowMs - lastHealthy.atMs
                if ageMs >= 0 && ageMs < ttlMs {
                    return nil
                }
            }
            if let inFlight, inFlight.url == url {
                return (inFlight.id, inFlight.task)
            }
            let id = UUID()
            let probe = self.probe
            let task = Task { await probe.head(url, timeoutMs: kHealthCheckTimeoutMs) }
            inFlight = (url, id, task)
            return (id, task)
        }
        guard let pending else {
            return lock.withLock {
                if let backoff, backoff.isRunning(atMs: nowMs) {
                    return .unhealthy(backoffUntilMs: backoff.endMs)
                }
                return .healthy
            }
        }

        let result = await pending.task.value
        let doneMs = clock.nowMs()
        return lock.withLock {
            // The first check to see the result applies it; the others read it.
            if inFlight?.id == pending.id {
                inFlight = nil
                if result.isHealthy {
                    lastHealthy = (url, doneMs)
                    backoff = nil
                } else {
                    backoff = Backoff(startMs: doneMs, durationMs: max(0, backoffMs))
                }
            }
            return result.isHealthy ? .healthy : .unhealthy(backoffUntilMs: backoff?.endMs ?? doneMs)
        }
    }
}
