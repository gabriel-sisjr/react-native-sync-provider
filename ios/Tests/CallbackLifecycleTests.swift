import XCTest
import NitroModules
@testable import SyncProvider

/// Who owns the installed JS callbacks. The engine outlives every JS runtime;
/// a hybrid is created per runtime and torn down in `deinit` when that runtime
/// goes away, after a reload say, possibly once the new runtime's hybrid has
/// installed its own callbacks. Each install carries its hybrid's generation:
/// a newer attach evicts the older ones' installs, a teardown retires only its
/// own, which stay in place, orphaned, until that newer attach, and only the
/// current owner may install.
final class CallbackLifecycleTests: XCTestCase {
    private var fixture: EngineFixture!
    private var engine: SyncEngine!

    override func setUp() async throws {
        try await super.setUp()
        fixture = EngineFixture()
        engine = await fixture.makeColdEngine()
    }

    override func tearDown() async throws {
        engine = nil
        fixture.tearDown()
        fixture = nil
        try await super.tearDown()
    }

    // MARK: - Helpers

    /// Installs every kind through `hybrid`, with closures that hold nothing
    /// of it.
    private func installAll(_ hybrid: HybridSyncProvider) throws {
        _ = try hybrid.setRetryDecider { _, _ in }
        _ = try hybrid.setHeaderResolver { _, _ in }
        _ = try hybrid.setBeforeDispatch { _, _ in }
        _ = try hybrid.setAfterDispatch { _, _ in }
    }

    /// The owner of each slot: decider, resolver, before, after.
    private func owners() -> [UInt64?] {
        [engine.callbacks.retryDecider?.owner,
         engine.callbacks.headerResolver?.owner,
         engine.callbacks.beforeDispatch?.owner,
         engine.callbacks.afterDispatch?.owner]
    }

    /// What ``owners()`` reads when `owner` installed every kind.
    private func owned(by owner: UInt64) -> [UInt64?] {
        Array(repeating: Optional(owner), count: 4)
    }

    private func isDeciderInstalled(through hybrid: HybridSyncProvider) throws -> Bool? {
        try attachLikeTheBridge(try hybrid.isRetryDeciderInstalled()).value?.get()
    }

    private let deciderRequest = RetryDeciderRequest(itemId: "01ITEM",
                                                     attempt: 2,
                                                     errorCode: "SERVER_ERROR",
                                                     statusCode: 503,
                                                     url: "https://api.test/items",
                                                     method: "PUT",
                                                     metadata: ["tenant": "acme"])

    // MARK: - Owners

    func test_lifecycle_newAttach_evictsOlderInstall() throws {
        let old = HybridSyncProvider(engine: engine)
        try installAll(old)
        XCTAssertEqual(owners(), owned(by: old.callbackOwner))

        let new = HybridSyncProvider(engine: engine)

        XCTAssertEqual(owners(), [nil, nil, nil, nil], "Evicted by the attach, with the old hybrid still alive")
        XCTAssertEqual(try isDeciderInstalled(through: new), false)
        XCTAssertTrue(engine.callbacks.isCurrent(new.callbackOwner))
        XCTAssertFalse(engine.callbacks.isCurrent(old.callbackOwner))
        withExtendedLifetime(old) {}
    }

    func test_lifecycle_oldHybridTeardown_keepsNewInstall() throws {
        var old: HybridSyncProvider? = HybridSyncProvider(engine: engine)
        try installAll(try XCTUnwrap(old))
        let new = HybridSyncProvider(engine: engine)
        try installAll(new)

        // The old runtime goes away after the new one installed its callbacks.
        old = nil

        XCTAssertEqual(owners(), owned(by: new.callbackOwner))
        XCTAssertEqual(try isDeciderInstalled(through: new), true)
        XCTAssertTrue(engine.callbacks.isCurrent(new.callbackOwner), "Still the current owner")
    }

    /// A teardown retires what its hybrid installed without removing it: the
    /// installs stay, orphaned, and still count as installed, until a newer
    /// hybrid's attach evicts every one of them. The dispatcher's side is in
    /// `DispatchInterceptorTests`.
    func test_lifecycle_teardown_retiresInstalls_untilNewAttach_inTheRegistry() throws {
        var hybrid: HybridSyncProvider? = HybridSyncProvider(engine: engine)
        let owner = try XCTUnwrap(hybrid).callbackOwner
        try installAll(try XCTUnwrap(hybrid))
        XCTAssertEqual(owners(), owned(by: owner))

        hybrid = nil

        XCTAssertEqual(owners(), owned(by: owner), "Orphaned, not removed")
        XCTAssertFalse(engine.callbacks.isCurrent(owner))
        XCTAssertTrue(engine.callbacks.hasInterceptors, "An orphan still counts as installed")

        let newer = HybridSyncProvider(engine: engine)

        XCTAssertEqual(owners(), [nil, nil, nil, nil], "The newer attach evicted every orphan")
        XCTAssertFalse(engine.callbacks.hasInterceptors)
        XCTAssertEqual(try isDeciderInstalled(through: newer), false)
        XCTAssertTrue(engine.callbacks.isCurrent(newer.callbackOwner))
    }

    /// A hybrid that is no longer the current owner, because a newer one
    /// attached, cannot install. Its clear and its query only ever see what
    /// it installed itself, which is nothing.
    func test_lifecycle_staleOwnerInstall_rejected() throws {
        let old = HybridSyncProvider(engine: engine)
        let new = HybridSyncProvider(engine: engine)

        let refused = [try old.setRetryDecider { _, _ in },
                       try old.setHeaderResolver { _, _ in },
                       try old.setBeforeDispatch { _, _ in },
                       try old.setAfterDispatch { _, _ in }]

        for promise in refused {
            let outcome = attachLikeTheBridge(promise)
            guard case let .failure(error)? = outcome.value else {
                XCTFail("Must have been rejected on return, got \(String(describing: outcome.value))")
                continue
            }
            XCTAssertEqual((error as? SyncProviderError)?.codeName, "NATIVE_MODULE_UNAVAILABLE")
        }
        XCTAssertEqual(owners(), [nil, nil, nil, nil], "Nothing was installed")

        try installAll(new)
        _ = try old.clearRetryDecider()
        _ = try old.clearHeaderResolver()
        _ = try old.clearBeforeDispatch()
        _ = try old.clearAfterDispatch()

        XCTAssertEqual(owners(), owned(by: new.callbackOwner), "A stale clear removes nothing")
        XCTAssertEqual(try isDeciderInstalled(through: old), false)
        XCTAssertEqual(try isDeciderInstalled(through: new), true)
    }

    /// A teardown, which retires its hybrid's installs, and a newer attach,
    /// which evicts the older ones', end their pending waits at once, as when
    /// no answer comes: no timeout is needed.
    func test_lifecycle_eviction_endsOwnersWaits() async throws {
        let gateway = engine.gateway

        // A teardown.
        var tornDown: HybridSyncProvider? = HybridSyncProvider(engine: engine)
        let tornDownOwner = try XCTUnwrap(tornDown).callbackOwner
        let sentBeforeTeardown = expectation(description: "the decider was called")
        let waitOnTeardown = Task {
            await gateway.requestDecision(owner: tornDownOwner, timeoutMs: 3_000) { _ in sentBeforeTeardown.fulfill() }
        }
        await fulfillment(of: [sentBeforeTeardown], timeout: livenessTimeout)

        tornDown = nil

        let afterTeardown = await waitOnTeardown.value
        XCTAssertEqual(afterTeardown, .skipped(.ownerGone))

        // A newer attach, while the older hybrid is still alive.
        let older = HybridSyncProvider(engine: engine)
        let olderOwner = older.callbackOwner
        let sentBeforeAttach = expectation(description: "the resolver was called")
        let waitOnAttach = Task {
            await gateway.requestHeaders(owner: olderOwner, timeoutMs: 3_000) { _ in sentBeforeAttach.fulfill() }
        }
        await fulfillment(of: [sentBeforeAttach], timeout: livenessTimeout)

        let newer = HybridSyncProvider(engine: engine)

        let afterAttach = await waitOnAttach.value
        XCTAssertEqual(afterAttach, .skipped(.ownerGone))
        XCTAssertEqual(gateway.pendingCount, 0)
        XCTAssertEqual(fixture.callbackTimer.delays, [3_000, 3_000], "Both were armed, and neither had to fire")
        withExtendedLifetime(older) {}
        withExtendedLifetime(newer) {}
    }

    /// An engine that shuts down ends every wait for a JS callback at once.
    func test_shuttingTheEngineDown_endsEveryCallbackWait() async throws {
        let hybrid = HybridSyncProvider(engine: engine)
        let owner = hybrid.callbackOwner
        let gateway = engine.gateway
        let sent = expectation(description: "the decider was called")
        let wait = Task { await gateway.requestDecision(owner: owner, timeoutMs: 3_000) { _ in sent.fulfill() } }
        await fulfillment(of: [sent], timeout: livenessTimeout)

        engine.shutdown()

        let outcome = await wait.value
        XCTAssertEqual(outcome, .skipped(.ownerGone))
        XCTAssertEqual(gateway.pendingCount, 0)
        withExtendedLifetime(hybrid) {}
    }

    // MARK: - Replacing and clearing

    func test_aSetFromTheCurrentOwner_replaces_andItsClearUninstalls() throws {
        let hybrid = HybridSyncProvider(engine: engine)
        let first = Recorder<String>()
        let second = Recorder<String>()

        _ = try hybrid.setRetryDecider { requestId, _ in first.record(requestId) }
        _ = try hybrid.setRetryDecider { requestId, _ in second.record(requestId) }
        engine.callbacks.retryDecider?.invoke("decider:1", deciderRequest)

        XCTAssertEqual(first.values, [], "Replaced")
        XCTAssertEqual(second.values, ["decider:1"])
        XCTAssertEqual(try isDeciderInstalled(through: hybrid), true)

        _ = try hybrid.clearRetryDecider()
        _ = try hybrid.clearHeaderResolver()
        _ = try hybrid.clearBeforeDispatch()
        _ = try hybrid.clearAfterDispatch()

        XCTAssertEqual(owners(), [nil, nil, nil, nil])
        XCTAssertEqual(try isDeciderInstalled(through: hybrid), false)
    }

    /// A `clear…` or a replacing `set…` ends no wait already out: the request
    /// runs on, its answer still counts, and its timeout still bounds it.
    func test_lifecycle_clearOrReplace_keepsPendingWait() async throws {
        let hybrid = HybridSyncProvider(engine: engine)
        let owner = hybrid.callbackOwner
        let gateway = engine.gateway
        _ = try hybrid.setHeaderResolver { _, _ in }
        _ = try hybrid.setBeforeDispatch { _, _ in }
        let beforeIds = Recorder<String>()
        let resolverAsked = expectation(description: "the resolver was called")
        let beforeAsked = expectation(description: "beforeDispatch was called")
        let resolverWait = Task {
            await gateway.requestHeaders(owner: owner, timeoutMs: 3_000) { _ in resolverAsked.fulfill() }
        }
        let beforeWait = Task {
            await gateway.requestBeforeDispatch(owner: owner, timeoutMs: 3_000) { requestId in
                beforeIds.record(requestId)
                beforeAsked.fulfill()
            }
        }
        await fulfillment(of: [resolverAsked, beforeAsked], timeout: livenessTimeout)

        _ = try hybrid.clearHeaderResolver()
        _ = try hybrid.setBeforeDispatch { _, _ in }

        XCTAssertEqual(gateway.pendingCount, 2, "Neither wait ended")
        gateway.answer(try XCTUnwrap(beforeIds.values.first), .before(.skip))
        let before = await beforeWait.value
        XCTAssertEqual(before, .answered(.skip), "The replaced hook's answer still counts")
        fixture.callbackTimer.fireAll()
        let resolver = await resolverWait.value
        XCTAssertEqual(resolver, .skipped(.timedOut(afterMs: 3_000)), "The cleared resolver's wait ends at its timeout")
        XCTAssertEqual(gateway.pendingCount, 0)
        withExtendedLifetime(hybrid) {}
    }

    // MARK: - Answers

    func test_decider_unknownId_noop() async throws {
        let hybrid = HybridSyncProvider(engine: engine)
        let owner = hybrid.callbackOwner
        let gateway = engine.gateway
        XCTAssertNoThrow(try hybrid.answerCallback(requestId: "decider:999999", answer: .deciding(.drop)))

        let ids = Recorder<String>()
        let sent = expectation(description: "the decider was called")
        let wait = Task {
            await gateway.requestDecision(owner: owner, timeoutMs: 3_000) { id in
                ids.record(id)
                sent.fulfill()
            }
        }
        await fulfillment(of: [sent], timeout: livenessTimeout)

        XCTAssertNoThrow(try hybrid.answerCallback(requestId: "decider:999999", answer: .deciding(.drop)))
        XCTAssertEqual(gateway.pendingCount, 1, "An answer to another id leaves the request waiting")

        try hybrid.answerCallback(requestId: try XCTUnwrap(ids.values.first), answer: .deciding(.retry, delayMs: 250))
        let outcome = await wait.value
        XCTAssertEqual(outcome, .answered(RetryDecisionValue(action: .retry, delayMs: 250)))
        XCTAssertNoThrow(try hybrid.answerCallback(requestId: try XCTUnwrap(ids.values.first), answer: .failing("late")),
                         "A second answer does nothing either")
    }

    /// What the facade sends arrives as the gateway reads it.
    func test_answerCallback_translatesEveryField() throws {
        let answer = CallbackAnswer(status: .resolved,
                                    decision: RetryDecision(action: .deadLetter, delayMs: 1_500.5),
                                    headers: ["Authorization": "Bearer fresh"],
                                    beforeDispatch: BeforeDispatchResult(action: .skip),
                                    message: "kept for the log")

        XCTAssertEqual(HybridSyncProvider.translate(answer: answer),
                       CallbackReply(status: .resolved,
                                     decision: RetryDecisionValue(action: .deadLetter, delayMs: 1_500.5),
                                     headers: ["Authorization": "Bearer fresh"],
                                     beforeDispatch: .skip,
                                     message: "kept for the log"))
        XCTAssertEqual(HybridSyncProvider.translate(answer: .failing("boom")), .failed("boom"))
        XCTAssertEqual(HybridSyncProvider.translate(answer: CallbackAnswer(status: .resolved,
                                                                            decision: nil,
                                                                            headers: nil,
                                                                            beforeDispatch: BeforeDispatchResult(action: .proceed),
                                                                            message: nil)),
                       .before(.proceed))
    }

    // MARK: - What JS receives

    /// Each install hands JS every field of its context, through the
    /// generated structs.
    func test_anInstall_handsJSEveryContextField() async throws {
        let hybrid = HybridSyncProvider(engine: engine)
        let deciders = Recorder<RetryDeciderRequest>()
        let resolvers = Recorder<HeaderResolverRequest>()
        let befores = Recorder<BeforeDispatchRequest>()
        let afters = Recorder<AfterDispatchReport>()
        let afterCalled = expectation(description: "afterDispatch was called")

        _ = try hybrid.setRetryDecider { _, context in
            deciders.record(RetryDeciderRequest(itemId: context.itemId,
                                                attempt: Int32(context.attempt),
                                                errorCode: context.errorCode.stringValue,
                                                statusCode: context.statusCode.map { Int($0) },
                                                url: context.url,
                                                method: context.method.stringValue,
                                                metadata: context.metadata))
        }
        _ = try hybrid.setHeaderResolver { _, context in
            resolvers.record(HeaderResolverRequest(itemId: context.itemId,
                                                   attempt: Int32(context.attempt),
                                                   url: context.url,
                                                   method: context.method.stringValue,
                                                   metadata: context.metadata))
        }
        _ = try hybrid.setBeforeDispatch { _, context in
            befores.record(BeforeDispatchRequest(itemId: context.itemId,
                                                 attempt: Int32(context.attempt),
                                                 url: context.url,
                                                 method: context.method.stringValue,
                                                 headers: context.headers,
                                                 contentType: context.contentType,
                                                 metadata: context.metadata))
        }
        _ = try hybrid.setAfterDispatch { _, context in
            let outcome: DispatchOutcomeKind
            switch context.outcome.stringValue {
            case "SUCCEEDED": outcome = .succeeded
            case "RETRYING": outcome = .retrying
            default: outcome = .failed
            }
            afters.record(AfterDispatchReport(itemId: context.itemId,
                                              attempt: Int32(context.attempt),
                                              outcome: outcome,
                                              statusCode: context.statusCode.map { Int($0) },
                                              errorCode: context.errorCode?.stringValue,
                                              url: context.url,
                                              method: context.method.stringValue,
                                              metadata: context.metadata,
                                              durationMs: Int64(context.durationMs)))
            afterCalled.fulfill()
        }
        let resolverRequest = HeaderResolverRequest(itemId: "01ITEM", attempt: 3, url: "https://api.test/a",
                                                    method: "DELETE", metadata: nil)
        let beforeRequest = BeforeDispatchRequest(itemId: "01ITEM", attempt: 3, url: "https://api.test/a",
                                                  method: "PATCH", headers: ["Authorization": "Bearer t"],
                                                  contentType: nil, metadata: ["k": "v"])
        let report = AfterDispatchReport(itemId: "01ITEM", attempt: 3, outcome: .retrying, statusCode: 503,
                                         errorCode: "SERVER_ERROR", url: "https://api.test/a", method: "POST",
                                         metadata: ["k": "v"], durationMs: 42)

        engine.callbacks.retryDecider?.invoke("decider:1", deciderRequest)
        engine.callbacks.headerResolver?.invoke("resolver:2", resolverRequest)
        engine.callbacks.beforeDispatch?.invoke("before:3", beforeRequest)
        engine.callbacks.afterDispatch?.invoke("after:4", report)
        await fulfillment(of: [afterCalled], timeout: livenessTimeout)

        XCTAssertEqual(deciders.values, [deciderRequest])
        XCTAssertEqual(resolvers.values, [resolverRequest])
        XCTAssertEqual(befores.values, [beforeRequest])
        XCTAssertEqual(afters.values, [report])
    }

    /// The engine outlives every runtime. Were an install to hold its hybrid,
    /// that hybrid's `deinit`, the iOS teardown, would never run.
    func test_ios_installedCallback_doesNotRetainTheHybrid() throws {
        weak var released: HybridSyncProvider?
        var owner: UInt64?
        do {
            let hybrid = HybridSyncProvider(engine: engine)
            released = hybrid
            owner = hybrid.callbackOwner
            try installAll(hybrid)
            XCTAssertNotNil(engine.callbacks.retryDecider)
        }

        XCTAssertNil(released, "An install kept the hybrid alive")
        XCTAssertEqual(owners(), owned(by: try XCTUnwrap(owner)), "Its deinit left what it installed, orphaned")
        XCTAssertFalse(engine.callbacks.isCurrent(try XCTUnwrap(owner)))
    }
}
