//
//  CallbackGateway.swift
//  SyncProvider
//
//  The one place native waits for JS. A callback request goes out through a
//  void JS function that receives a request id and a context, and the facade
//  answers through `answerCallback(requestId, answer)`. Each wait ends exactly
//  once: with the answer, at `callbackTimeoutMs`, when its task is cancelled
//  (a BGTask whose time expired), or when the hybrid that installed the
//  callback goes away.
//

import Foundation

/// The most of a message from JS that reaches the log.
let kCallbackLogMessageMaxLength = 256

/// The callback a request is for. Its raw value starts the request id.
enum CallbackKind: String, Sendable {
    case retryDecider = "decider"
    case headerResolver = "resolver"
    case beforeDispatch = "before"
    case afterDispatch = "after"
}

/// Why a callback gave native nothing to use. Every case means the same to
/// the caller: apply the fallback. The description is for the log.
enum CallbackSkip: Error, Sendable, Equatable, CustomStringConvertible {
    /// JS answered FAILED: the callback threw, rejected, or returned something
    /// the facade could not use.
    case failed(message: String?)
    /// JS answered RESOLVED without a usable payload for this kind of request.
    case invalid(reason: String)
    /// The call into JS threw. Nitro's void callbacks never do, but a throw is
    /// handled all the same.
    case invokeFailed(message: String)
    /// The hybrid that installed the callback went away, in a JS reload say,
    /// or a newer one replaced it.
    case ownerGone
    /// No answer within the timeout: a JS thread that is stuck, or a runtime
    /// that is gone. Native cannot tell them apart.
    case timedOut(afterMs: Int)
    /// The waiting task was cancelled: a BGTask whose time expired.
    case cancelled

    var description: String {
        switch self {
        case let .failed(message):
            return "it failed (\(CallbackGateway.forLog(message ?? "no message")))"
        case let .invalid(reason):
            return "its answer was not usable (\(reason))"
        case let .invokeFailed(message):
            return "the call into JS failed (\(CallbackGateway.forLog(message)))"
        case .ownerGone:
            return "the JS runtime that installed it is gone"
        case let .timedOut(afterMs):
            return "no answer in \(afterMs) ms"
        case .cancelled:
            return "the wait was cancelled"
        }
    }

    /// `true` for an end that is no fault of the callback: its wait was
    /// cancelled, or the runtime that installed it went away. The others mean
    /// the callback failed or never answered.
    var isStop: Bool {
        switch self {
        case .cancelled, .ownerGone:
            return true
        case .failed, .invalid, .invokeFailed, .timedOut:
            return false
        }
    }
}

/// What one callback request came to.
enum CallbackOutcome<Value: Sendable>: Sendable {
    case answered(Value)
    case skipped(CallbackSkip)
}

extension CallbackOutcome: Equatable where Value: Equatable {}

/// Cancels one scheduled timeout. Cancelling one that already fired, or
/// cancelling twice, does nothing.
final class CallbackTimerToken: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private let workItem: DispatchWorkItem?

    init(workItem: DispatchWorkItem? = nil) {
        self.workItem = workItem
    }

    var isCancelled: Bool {
        lock.withLock { cancelled }
    }

    func cancel() {
        let first = lock.withLock { () -> Bool in
            defer { cancelled = true }
            return !cancelled
        }
        if first {
            workItem?.cancel()
        }
    }
}

/// Schedules the timeout of a callback request. A seam: tests fire timeouts
/// by hand.
protocol CallbackTimer: Sendable {
    /// Runs `fire` once, `delayMs` from now, unless the token is cancelled
    /// first.
    func schedule(afterMs delayMs: Int, _ fire: @escaping @Sendable () -> Void) -> CallbackTimerToken
}

/// The production ``CallbackTimer``, on one serial utility queue. The delay
/// is uptime: it measures how long JS takes to answer, and no JS runs while
/// the device sleeps.
final class DispatchCallbackTimer: CallbackTimer {
    private let queue = DispatchQueue(label: "com.gabriel-sisjr.syncprovider.callback-timer", qos: .utility)

    func schedule(afterMs delayMs: Int, _ fire: @escaping @Sendable () -> Void) -> CallbackTimerToken {
        let workItem = DispatchWorkItem(block: fire)
        queue.asyncAfter(deadline: .now() + .milliseconds(max(0, delayMs)), execute: workItem)
        return CallbackTimerToken(workItem: workItem)
    }
}

/// One request waiting for JS. It ends exactly once, through
/// ``finish(_:)``, whichever comes first: the answer, the timeout, a
/// cancellation, a call that failed, or its owner going away. Resuming a
/// checked continuation twice traps, so nothing else resumes its waiter.
final class PendingCallback: @unchecked Sendable {
    typealias Outcome = Result<CallbackReply, CallbackSkip>

    let kind: CallbackKind
    /// The generation of the hybrid whose callback it called. See
    /// ``CallbackRegistry/attach()``.
    let owner: UInt64

    private let lock = NSLock()
    private var outcome: Outcome?
    private var waiter: CheckedContinuation<Outcome, Never>?
    private var completion: (@Sendable (Outcome) -> Void)?
    private var timeout: CallbackTimerToken?

    /// - Parameter completion: for a request nothing waits for: called once,
    ///   with how it ended.
    init(kind: CallbackKind, owner: UInt64, completion: (@Sendable (Outcome) -> Void)? = nil) {
        self.kind = kind
        self.owner = owner
        self.completion = completion
    }

    var isFinished: Bool {
        lock.withLock { outcome != nil }
    }

    /// Takes the task that waits.
    /// - Returns: `false` when the request already ended, cancelled before the
    ///   wait began: the waiter is then resumed at once, and nothing may be
    ///   sent.
    func install(_ continuation: CheckedContinuation<Outcome, Never>) -> Bool {
        let early = lock.withLock { () -> Outcome? in
            if let outcome {
                return outcome
            }
            waiter = continuation
            return nil
        }
        guard let early else { return true }
        continuation.resume(returning: early)
        return false
    }

    /// Keeps the timeout, so the end of the request cancels it.
    func arm(_ token: CallbackTimerToken) {
        let ended = lock.withLock { () -> Bool in
            guard outcome == nil else { return true }
            timeout = token
            return false
        }
        if ended {
            token.cancel()
        }
    }

    /// Ends the request with `result`, unless it already ended.
    /// - Returns: `true` when this call ended it.
    @discardableResult
    func finish(_ result: Outcome) -> Bool {
        typealias Taken = (waiter: CheckedContinuation<Outcome, Never>?,
                           completion: (@Sendable (Outcome) -> Void)?,
                           timeout: CallbackTimerToken?)
        let taken = lock.withLock { () -> Taken? in
            guard outcome == nil else { return nil }
            outcome = result
            defer {
                waiter = nil
                completion = nil
                timeout = nil
            }
            return (waiter, completion, timeout)
        }
        guard let taken else { return false }
        taken.timeout?.cancel()
        taken.waiter?.resume(returning: result)
        taken.completion?(result)
        return true
    }
}

/// Where native waits for the answers of the JS callbacks: the requests that
/// went out, by id. One per engine, shared by every callback kind.
///
/// It never holds its lock while it resumes a waiter or calls into JS, and
/// ``answer(_:_:)``, which runs on the JS thread, only looks the request up,
/// completes it and returns.
///
/// An owner is retired when the hybrid that installed its callbacks goes away
/// or a newer one attaches, and every owner when the engine shuts down. Its
/// waits then end at once, and a request made for it later never calls JS.
/// Clearing or replacing a callback retires nothing: JS is alive, and the
/// timeout still bounds a wait already out.
final class CallbackGateway: @unchecked Sendable {
    private let timer: CallbackTimer
    private let lock = NSLock()
    private var pending: [String: PendingCallback] = [:]
    private var lastId: UInt64 = 0
    /// Every owner below this one is retired.
    private var retiredBelow: UInt64 = 0
    /// Owners retired one by one, at their hybrid's teardown.
    private var retired: Set<UInt64> = []
    /// Every owner is retired: the engine shut down.
    private var retiredAll = false

    init(timer: CallbackTimer = DispatchCallbackTimer()) {
        self.timer = timer
    }

    /// The requests still waiting for an answer. Test seam.
    var pendingCount: Int {
        lock.withLock { pending.count }
    }

    // MARK: - Requests

    /// Asks the retry decider, and waits up to `timeoutMs` for its decision.
    func requestDecision(owner: UInt64,
                         timeoutMs: Int,
                         send: @Sendable (_ requestId: String) throws -> Void) async -> CallbackOutcome<RetryDecisionValue> {
        switch await request(.retryDecider, owner: owner, timeoutMs: timeoutMs, send: send) {
        case let .success(reply):
            return Self.decision(in: reply)
        case let .failure(skip):
            return .skipped(skip)
        }
    }

    /// Asks the header resolver, and waits up to `timeoutMs` for its headers.
    func requestHeaders(owner: UInt64,
                        timeoutMs: Int,
                        send: @Sendable (_ requestId: String) throws -> Void) async -> CallbackOutcome<[String: String]> {
        switch await request(.headerResolver, owner: owner, timeoutMs: timeoutMs, send: send) {
        case let .success(reply):
            return Self.headers(in: reply)
        case let .failure(skip):
            return .skipped(skip)
        }
    }

    /// Asks `beforeDispatch`, and waits up to `timeoutMs` for its action.
    func requestBeforeDispatch(owner: UInt64,
                               timeoutMs: Int,
                               send: @Sendable (_ requestId: String) throws -> Void) async
        -> CallbackOutcome<BeforeDispatchActionValue> {
        switch await request(.beforeDispatch, owner: owner, timeoutMs: timeoutMs, send: send) {
        case let .success(reply):
            return Self.beforeDispatch(in: reply)
        case let .failure(skip):
            return .skipped(skip)
        }
    }

    /// Calls `afterDispatch` and returns at once: nothing waits for it. Its
    /// answer, or the lack of one within `timeoutMs`, is only logged.
    func notifyAfterDispatch(owner: UInt64, timeoutMs: Int, send: @Sendable (_ requestId: String) throws -> Void) {
        let id = nextId(.afterDispatch)
        let entry = PendingCallback(kind: .afterDispatch, owner: owner) { outcome in
            Self.logNotification(id, outcome)
        }
        start(id, entry, timeoutMs: timeoutMs, send: send)
    }

    // MARK: - Answers

    /// The facade's answer to `requestId`. A late, unknown or second answer
    /// does nothing. Runs on the JS thread.
    func answer(_ requestId: String, _ reply: CallbackReply) {
        take(requestId)?.finish(.success(reply))
    }

    /// Retires `owner`: the hybrid that installed its callbacks went away.
    func retireOwner(_ owner: UInt64) {
        retire { $0.retired.insert(owner) }
    }

    /// Retires every owner below `generation`: a newer hybrid attached.
    func retireOwners(below generation: UInt64) {
        retire { $0.retiredBelow = max($0.retiredBelow, generation) }
    }

    /// Retires every owner: the engine shut down.
    func retireAll() {
        retire { $0.retiredAll = true }
    }

    /// Applies `mark` and ends, as if no answer came, every wait whose owner
    /// is now retired. Registering a request checks the same state under the
    /// same lock, so no request slips in between.
    private func retire(_ mark: (CallbackGateway) -> Void) {
        let ended = lock.withLock { () -> [PendingCallback] in
            mark(self)
            let owned = pending.filter { isRetiredLocked($0.value.owner) }
            for id in owned.keys {
                pending.removeValue(forKey: id)
            }
            return Array(owned.values)
        }
        for entry in ended {
            entry.finish(.failure(.ownerGone))
        }
    }

    private func isRetiredLocked(_ owner: UInt64) -> Bool {
        retiredAll || owner < retiredBelow || retired.contains(owner)
    }

    // MARK: - Answer classification

    /// RESOLVED counts only with the payload of its kind; any other field is
    /// ignored.
    static func decision(in reply: CallbackReply) -> CallbackOutcome<RetryDecisionValue> {
        guard reply.status == .resolved else { return .skipped(.failed(message: reply.message)) }
        guard let decision = reply.decision else {
            return .skipped(.invalid(reason: "a retry decider answer must carry a decision"))
        }
        return .answered(decision)
    }

    /// Every header must use only the characters HTTP allows. Neither the
    /// name nor the value is logged: a value can carry a credential.
    static func headers(in reply: CallbackReply) -> CallbackOutcome<[String: String]> {
        guard reply.status == .resolved else { return .skipped(.failed(message: reply.message)) }
        guard let headers = reply.headers else {
            return .skipped(.invalid(reason: "a header resolver answer must carry headers"))
        }
        let allowed = headers.allSatisfy { name, value in
            HTTPHeaderRules.isValidName(name) && HTTPHeaderRules.isValidValue(value)
        }
        guard allowed else {
            return .skipped(.invalid(reason: "a header name or value holds a character HTTP does not allow"))
        }
        return .answered(headers)
    }

    static func beforeDispatch(in reply: CallbackReply) -> CallbackOutcome<BeforeDispatchActionValue> {
        guard reply.status == .resolved else { return .skipped(.failed(message: reply.message)) }
        guard let action = reply.beforeDispatch else {
            return .skipped(.invalid(reason: "a beforeDispatch answer must carry an action"))
        }
        return .answered(action)
    }

    /// `text` cut to what the log keeps of a message from JS.
    static func forLog(_ text: String) -> String {
        String(text.prefix(kCallbackLogMessageMaxLength))
    }

    // MARK: - Internals

    private func request(_ kind: CallbackKind,
                         owner: UInt64,
                         timeoutMs: Int,
                         send: @Sendable (String) throws -> Void) async -> PendingCallback.Outcome {
        let id = nextId(kind)
        let entry = PendingCallback(kind: kind, owner: owner)
        let outcome = await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<PendingCallback.Outcome, Never>) in
                guard entry.install(continuation) else { return }
                start(id, entry, timeoutMs: timeoutMs, send: send)
            }
        } onCancel: {
            entry.finish(.failure(.cancelled))
        }
        // Every way out leaves the map: a cancellation leaves the entry there.
        take(id)
        return outcome
    }

    /// Registers `entry`, arms its timeout, then calls into JS: an answer can
    /// only find a request that is already registered. A request for a
    /// retired owner ends at once and never calls JS.
    private func start(_ id: String, _ entry: PendingCallback, timeoutMs: Int, send: (String) throws -> Void) {
        let registered = lock.withLock { () -> Bool in
            guard !isRetiredLocked(entry.owner) else { return false }
            pending[id] = entry
            return true
        }
        guard registered else {
            entry.finish(.failure(.ownerGone))
            return
        }
        entry.arm(timer.schedule(afterMs: timeoutMs) { [weak self] in
            self?.take(id)?.finish(.failure(.timedOut(afterMs: timeoutMs)))
        })
        // Cancelled, or its owner gone, before the call: nothing is sent.
        guard !entry.isFinished else {
            take(id)
            return
        }
        do {
            try send(id)
        } catch {
            take(id)?.finish(.failure(.invokeFailed(message: String(describing: error))))
        }
    }

    /// One line for a callback that gave nothing to use: WARN when the
    /// callback failed or never answered, INFO when its wait was stopped.
    /// Never an answer, a context or a header.
    static func log(_ text: String, for skip: CallbackSkip) {
        if skip.isStop {
            SyncLogger.info(text, category: "callbacks")
        } else {
            SyncLogger.warning(text, category: "callbacks")
        }
    }

    private static func logNotification(_ id: String, _ outcome: PendingCallback.Outcome) {
        switch outcome {
        case let .success(reply):
            guard reply.status == .failed else { return }
            log("afterDispatch \(id): \(CallbackSkip.failed(message: reply.message))",
                for: .failed(message: reply.message))
        case let .failure(skip):
            log("afterDispatch \(id): \(skip)", for: skip)
        }
    }

    private func nextId(_ kind: CallbackKind) -> String {
        let number = lock.withLock { () -> UInt64 in
            lastId &+= 1
            return lastId
        }
        return "\(kind.rawValue):\(number)"
    }

    @discardableResult
    private func take(_ id: String) -> PendingCallback? {
        lock.withLock { pending.removeValue(forKey: id) }
    }
}
