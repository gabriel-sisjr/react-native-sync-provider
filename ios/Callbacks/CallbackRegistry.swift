//
//  CallbackRegistry.swift
//  SyncProvider
//
//  The JS callbacks installed in this process, each tagged with the hybrid
//  that installed it. The engine outlives every JS runtime, so an install must
//  not outlive the runtime that made it: a newer hybrid's attach evicts the
//  installs of older ones, and a hybrid's teardown removes its own.
//

import Foundation

/// One JS callback, as one hybrid installed it.
struct CallbackInstall<Request: Sendable>: Sendable {
    /// The generation of the hybrid that installed it. See
    /// ``CallbackRegistry/attach()``.
    let owner: UInt64
    /// Calls into JS. It never blocks: Nitro queues the call on the JS thread,
    /// and drops it with a log line once that runtime is gone.
    let invoke: @Sendable (_ requestId: String, _ request: Request) -> Void
}

/// The four callback slots, and which hybrid may fill them.
///
/// - Only the current owner may install: the newest hybrid that attached and
///   has not gone away. A set from it replaces what was installed. A set from
///   any other is refused.
/// - A clear removes only what the caller installed.
/// - Attaching evicts every install an older hybrid made. Two live JS runtimes
///   in one process would evict each other's installs.
/// - An install is released outside the lock: releasing it releases a JS
///   function reference.
final class CallbackRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var generation: UInt64 = 0
    private var currentOwner: UInt64?
    private var decider: CallbackInstall<RetryDeciderRequest>?
    private var resolver: CallbackInstall<HeaderResolverRequest>?
    private var before: CallbackInstall<BeforeDispatchRequest>?
    private var after: CallbackInstall<AfterDispatchReport>?

    init() {}

    // MARK: - Owners

    /// A JS-facing hybrid was created. Returns its generation, the owner token
    /// of what it installs. It is now the current owner, and what every older
    /// generation installed is evicted.
    func attach() -> UInt64 {
        let (owner, evicted) = lock.withLock { () -> (UInt64, [Any]) in
            generation &+= 1
            let newest = generation
            currentOwner = newest
            return (newest, removeLocked { $0 < newest })
        }
        withExtendedLifetime(evicted) {}
        return owner
    }

    /// The hybrid of `owner` went away. Only what it installed is removed, and
    /// it is no longer the current owner.
    func detach(owner: UInt64) {
        let removed = lock.withLock { () -> [Any] in
            if currentOwner == owner {
                currentOwner = nil
            }
            return removeLocked { $0 == owner }
        }
        withExtendedLifetime(removed) {}
    }

    /// `true` while `owner` is the newest hybrid that attached and has not
    /// gone away.
    func isCurrent(_ owner: UInt64) -> Bool {
        lock.withLock { currentOwner == owner }
    }

    // MARK: - Slots

    var retryDecider: CallbackInstall<RetryDeciderRequest>? {
        read(\.decider)
    }

    var headerResolver: CallbackInstall<HeaderResolverRequest>? {
        read(\.resolver)
    }

    var beforeDispatch: CallbackInstall<BeforeDispatchRequest>? {
        read(\.before)
    }

    var afterDispatch: CallbackInstall<AfterDispatchReport>? {
        read(\.after)
    }

    /// `true` when a callback that runs before a request is installed: the
    /// header resolver or `beforeDispatch`.
    var hasInterceptors: Bool {
        lock.withLock { resolver != nil || before != nil }
    }

    /// Installs the retry decider, replacing any earlier one.
    /// - Returns: `false`, with nothing changed, when its owner is not the
    ///   current one.
    @discardableResult
    func setRetryDecider(_ install: CallbackInstall<RetryDeciderRequest>) -> Bool {
        set(\.decider, to: install)
    }

    /// See ``setRetryDecider(_:)``.
    @discardableResult
    func setHeaderResolver(_ install: CallbackInstall<HeaderResolverRequest>) -> Bool {
        set(\.resolver, to: install)
    }

    /// See ``setRetryDecider(_:)``.
    @discardableResult
    func setBeforeDispatch(_ install: CallbackInstall<BeforeDispatchRequest>) -> Bool {
        set(\.before, to: install)
    }

    /// See ``setRetryDecider(_:)``.
    @discardableResult
    func setAfterDispatch(_ install: CallbackInstall<AfterDispatchReport>) -> Bool {
        set(\.after, to: install)
    }

    /// Removes the retry decider if `owner` installed it.
    func clearRetryDecider(owner: UInt64) {
        clear(\.decider, owner: owner)
    }

    /// Removes the header resolver if `owner` installed it.
    func clearHeaderResolver(owner: UInt64) {
        clear(\.resolver, owner: owner)
    }

    /// Removes `beforeDispatch` if `owner` installed it.
    func clearBeforeDispatch(owner: UInt64) {
        clear(\.before, owner: owner)
    }

    /// Removes `afterDispatch` if `owner` installed it.
    func clearAfterDispatch(owner: UInt64) {
        clear(\.after, owner: owner)
    }

    // MARK: - Internals

    private func read<Request>(_ slot: KeyPath<CallbackRegistry, CallbackInstall<Request>?>)
        -> CallbackInstall<Request>? {
        lock.withLock { self[keyPath: slot] }
    }

    private func set<Request>(_ slot: ReferenceWritableKeyPath<CallbackRegistry, CallbackInstall<Request>?>,
                              to install: CallbackInstall<Request>) -> Bool {
        let (installed, replaced) = lock.withLock { () -> (Bool, CallbackInstall<Request>?) in
            guard currentOwner == install.owner else { return (false, nil) }
            let previous = self[keyPath: slot]
            self[keyPath: slot] = install
            return (true, previous)
        }
        withExtendedLifetime(replaced) {}
        return installed
    }

    private func clear<Request>(_ slot: ReferenceWritableKeyPath<CallbackRegistry, CallbackInstall<Request>?>,
                                owner: UInt64) {
        let removed = lock.withLock { () -> CallbackInstall<Request>? in
            guard let install = self[keyPath: slot], install.owner == owner else { return nil }
            self[keyPath: slot] = nil
            return install
        }
        withExtendedLifetime(removed) {}
    }

    /// Empties every slot whose owner `matches`, and returns what it held, so
    /// the caller releases it once the lock is free.
    private func removeLocked(where matches: (UInt64) -> Bool) -> [Any] {
        var removed: [Any] = []
        if let install = decider, matches(install.owner) {
            removed.append(install)
            decider = nil
        }
        if let install = resolver, matches(install.owner) {
            removed.append(install)
            resolver = nil
        }
        if let install = before, matches(install.owner) {
            removed.append(install)
            before = nil
        }
        if let install = after, matches(install.owner) {
            removed.append(install)
            after = nil
        }
        return removed
    }
}
