import Foundation
@testable import SyncProvider

/// A HEAD probe that answers from a script, records every call and can hold
/// calls until released, so tests decide what a measurement sees and when it
/// finishes.
final class FakeHeadProbe: HeadProbing, @unchecked Sendable {
    private let lock = NSLock()
    private var script: [HeadProbeResult] = []
    private var fallback: HeadProbeResult = .response(statusCode: 200, latencyMs: 100)
    private var calls: [(url: URL, timeoutMs: Int)] = []
    private var holding = false
    private var holdFromCall: Int?
    private var held: [CheckedContinuation<Void, Never>] = []

    /// Every URL probed, in order.
    var probedURLs: [URL] {
        lock.withLock { calls.map(\.url) }
    }

    /// The timeout of every call, in order.
    var timeouts: [Int] {
        lock.withLock { calls.map(\.timeoutMs) }
    }

    var callCount: Int {
        lock.withLock { calls.count }
    }

    /// Calls waiting for ``release()``.
    var heldCalls: Int {
        lock.withLock { held.count }
    }

    /// Answers the next calls with `results`, in order, and every later one
    /// with `fallback` when given, otherwise with the current fallback.
    func script(_ results: [HeadProbeResult], then fallback: HeadProbeResult? = nil) {
        lock.withLock {
            script = results
            if let fallback { self.fallback = fallback }
        }
    }

    /// Answers every call with a response after `latencyMs`.
    func answer(latencyMs: Int, statusCode: Int = 200) {
        script([], then: .response(statusCode: statusCode, latencyMs: latencyMs))
    }

    /// Makes every call wait until ``release()``.
    func hold() {
        lock.withLock { holding = true }
    }

    /// Makes every call from the `index`-th on (0-based) wait until
    /// ``release()``; the earlier ones answer at once.
    func hold(fromCall index: Int) {
        lock.withLock { holdFromCall = index }
    }

    /// Lets every held call, and every later one, through.
    func release() {
        let waiting = lock.withLock { () -> [CheckedContinuation<Void, Never>] in
            holding = false
            defer { held.removeAll() }
            return held
        }
        waiting.forEach { $0.resume() }
    }

    /// Returns once `count` calls are held, failing after `timeout`.
    func waitUntilHeld(_ count: Int = 1, timeout: TimeInterval = 2) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while heldCalls < count {
            guard Date() < deadline else { return false }
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        return true
    }

    func head(_ url: URL, timeoutMs: Int) async -> HeadProbeResult {
        let mustWait = lock.withLock { () -> Bool in
            calls.append((url, timeoutMs))
            if let holdFromCall, calls.count - 1 >= holdFromCall {
                holding = true
                self.holdFromCall = nil
            }
            return holding
        }
        if mustWait {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                let stillHolding = lock.withLock { () -> Bool in
                    guard holding else { return false }
                    held.append(continuation)
                    return true
                }
                if !stillHolding {
                    continuation.resume()
                }
            }
        }
        // What the real probe reports for a cancelled caller.
        if Task.isCancelled {
            return .failure(kind: .network, message: "The probe was cancelled")
        }
        return lock.withLock { script.isEmpty ? fallback : script.removeFirst() }
    }
}
