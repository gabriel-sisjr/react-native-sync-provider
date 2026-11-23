import Foundation
@testable import SyncProvider

/// A ``CallbackTimer`` tests fire by hand: no wait times out until
/// ``fireAll()`` runs, so a test decides when a callback's time is up.
final class ManualCallbackTimer: CallbackTimer, @unchecked Sendable {
    private struct Scheduled {
        let delayMs: Int
        let fire: @Sendable () -> Void
        let token: CallbackTimerToken
    }

    private let lock = NSLock()
    private var scheduled: [Scheduled] = []
    private var asked: [Int] = []

    /// Every timeout asked for, in order, fired or not.
    var delays: [Int] {
        lock.withLock { asked }
    }

    func schedule(afterMs delayMs: Int, _ fire: @escaping @Sendable () -> Void) -> CallbackTimerToken {
        let token = CallbackTimerToken()
        lock.withLock {
            asked.append(delayMs)
            scheduled.append(Scheduled(delayMs: delayMs, fire: fire, token: token))
        }
        return token
    }

    /// Fires every timeout scheduled so far that was not cancelled, as if its
    /// time had passed.
    func fireAll() {
        let due = lock.withLock { () -> [Scheduled] in
            defer { scheduled.removeAll() }
            return scheduled
        }
        for entry in due where !entry.token.isCancelled {
            entry.fire()
        }
    }
}

/// Values collected from any thread, in order.
final class Recorder<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [Value] = []

    func record(_ value: Value) {
        lock.withLock { recorded.append(value) }
    }

    var values: [Value] {
        lock.withLock { recorded }
    }
}

extension CallbackReply {
    /// A RESOLVED decider answer.
    static func decision(_ action: RetryDecisionValue.Action, delayMs: Double? = nil) -> CallbackReply {
        CallbackReply(status: .resolved, decision: RetryDecisionValue(action: action, delayMs: delayMs))
    }

    /// A RESOLVED header resolver answer.
    static func headers(_ headers: [String: String]) -> CallbackReply {
        CallbackReply(status: .resolved, headers: headers)
    }

    /// A RESOLVED `beforeDispatch` answer.
    static func before(_ action: BeforeDispatchActionValue) -> CallbackReply {
        CallbackReply(status: .resolved, beforeDispatch: action)
    }

    /// A RESOLVED answer with no payload, as `afterDispatch` sends.
    static let resolvedEmpty = CallbackReply(status: .resolved)
}

extension CallbackAnswer {
    /// The facade's RESOLVED answer for the retry decider.
    static func deciding(_ action: RetryAction, delayMs: Double? = nil) -> CallbackAnswer {
        CallbackAnswer(status: .resolved,
                       decision: RetryDecision(action: action, delayMs: delayMs),
                       headers: nil,
                       beforeDispatch: nil,
                       message: nil)
    }

    /// The facade's FAILED answer.
    static func failing(_ message: String) -> CallbackAnswer {
        CallbackAnswer(status: .failed, decision: nil, headers: nil, beforeDispatch: nil, message: message)
    }
}

/// A failure a fake throws.
struct FakeCallbackError: Error {}

/// A retry decider installed straight into a registry, as a hybrid installs
/// one. It records each request, then answers through the gateway, as the
/// facade's `answerCallback` does, with what `reply` returns; `nil` sends no
/// answer. `reply` runs on the thread that asks, before the wait, so it may
/// first fire the timeout, purge the row, or expire a BGTask.
final class FakeDecider: @unchecked Sendable {
    typealias Reply = @Sendable (_ requestId: String, _ request: RetryDeciderRequest) -> CallbackReply?

    let requests = Recorder<RetryDeciderRequest>()
    let requestIds = Recorder<String>()
    /// The owner the install carries.
    let owner: UInt64
    /// `false` when the registry refused the install.
    private(set) var installed = false

    var callCount: Int {
        requests.values.count
    }

    /// Installed on `engine` as a newly created hybrid would install it.
    convenience init(engine: SyncEngine, reply: @escaping Reply) {
        self.init(registry: engine.callbacks, gateway: engine.gateway, owner: engine.attachCallbacks(), reply: reply)
    }

    /// Installed as the new current owner of `registry`.
    convenience init(registry: CallbackRegistry, gateway: CallbackGateway, reply: @escaping Reply) {
        self.init(registry: registry, gateway: gateway, owner: registry.attach(), reply: reply)
    }

    private init(registry: CallbackRegistry, gateway: CallbackGateway, owner: UInt64, reply: @escaping Reply) {
        self.owner = owner
        let requests = self.requests
        let requestIds = self.requestIds
        installed = registry.setRetryDecider(CallbackInstall(owner: owner) { requestId, request in
            requestIds.record(requestId)
            requests.record(request)
            if let answer = reply(requestId, request) {
                gateway.answer(requestId, answer)
            }
        })
    }
}
