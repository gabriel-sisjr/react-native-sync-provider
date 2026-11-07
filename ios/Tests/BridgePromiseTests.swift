import XCTest
import NitroModules
@testable import SyncProvider

/// Bridge calls that answer from memory hand back promises they have already
/// settled.
///
/// The generated glue attaches `.then` and then `.catch` to the returned
/// promise right after the call, and Nitro's Swift `Promise` before 0.35.8 is
/// not synchronized. A promise that a `Task` settles while those listeners are
/// being attached can drop them, and its JS promise then never settles. Each
/// test attaches listeners exactly as the glue does and requires the outcome
/// to be there once the attach returns: the listener ran inside the attach,
/// so nothing was left to settle later, on another thread. That holds every
/// time for a promise settled before it is returned, so the check is
/// deterministic.
final class BridgePromiseTests: XCTestCase {
    private var fixture: EngineFixture!
    private var engine: SyncEngine!
    private var hybrid: HybridSyncProvider!

    override func setUp() async throws {
        try await super.setUp()
        fixture = EngineFixture()
        engine = await fixture.makeColdEngine()
        hybrid = HybridSyncProvider(engine: engine)
    }

    override func tearDown() async throws {
        hybrid = nil
        engine = nil
        fixture.tearDown()
        fixture = nil
        try await super.tearDown()
    }

    // MARK: - Settled on return

    func test_getSyncConfig_isSettledOnReturn() throws {
        assertResolvedOnReturn(try hybrid.getSyncConfig())
    }

    func test_getConnectionStatus_isSettledOnReturn() throws {
        assertResolvedOnReturn(try hybrid.getConnectionStatus())
    }

    func test_isBackgroundSyncEnabled_isSettledOnReturn() throws {
        let outcome = attachLikeTheBridge(try hybrid.isBackgroundSyncEnabled())

        XCTAssertEqual(try outcome.value?.get(), false)
        XCTAssertEqual(outcome.calls, 1)
    }

    func test_addListener_isSettledOnReturn() throws {
        let outcome = attachLikeTheBridge(try hybrid.addListener(event: kSyncEventChannel) { _ in })

        let id = try XCTUnwrap(try outcome.value?.get(), "Must have resolved inside the attach")
        XCTAssertFalse(id.isEmpty)
        XCTAssertEqual(outcome.calls, 1)
    }

    func test_addListenerOnAnUnknownChannel_isRejectedOnReturn() throws {
        let outcome = attachLikeTheBridge(try hybrid.addListener(event: "nope") { _ in })

        guard case let .failure(error)? = outcome.value else {
            return XCTFail("Must have rejected inside the attach, got \(String(describing: outcome.value))")
        }
        XCTAssertEqual((error as? SyncProviderError)?.codeName, "INVALID_PAYLOAD")
        XCTAssertEqual(outcome.calls, 1)
    }

    func test_removeListener_isSettledOnReturn() throws {
        let added = attachLikeTheBridge(try hybrid.addListener(event: kSyncEventChannel) { _ in })
        let id = try XCTUnwrap(try added.value?.get())

        assertResolvedOnReturn(try hybrid.removeListener(event: kSyncEventChannel, subscriptionId: id))
    }

    func test_removedListener_isNoLongerCalled() throws {
        let received = PromiseOutcome<Void>()
        let added = attachLikeTheBridge(try hybrid.addListener(event: kSyncEventChannel) { _ in
            received.record(.success(()))
        })
        let id = try XCTUnwrap(try added.value?.get())
        // On the main thread, as here, `emit` calls listeners synchronously.
        engine.emitter.emit(.now(type: "QUEUE_CLEARED"))
        XCTAssertEqual(received.calls, 1)

        _ = attachLikeTheBridge(try hybrid.removeListener(event: kSyncEventChannel, subscriptionId: id))
        engine.emitter.emit(.now(type: "QUEUE_CLEARED"))

        XCTAssertEqual(received.calls, 1, "No call after the removal")
    }

    func test_deadLetterCalls_areSettledOnReturn() throws {
        assertResolvedOnReturn(try hybrid.listDeadLetter())
        assertRejectedOnReturn(try hybrid.replayDeadLetter(id: "01HZZZZZZZZZZZZZZZZZZZZZZZ"))
        assertRejectedOnReturn(try hybrid.discardDeadLetter(id: "01HZZZZZZZZZZZZZZZZZZZZZZZ"))
    }

    // MARK: - What they settle with

    func test_getSyncConfig_echoesTheNetworkHealthOptionsOnlyWhenSet() async throws {
        let unset = try XCTUnwrap(try attachLikeTheBridge(try hybrid.getSyncConfig()).value?.get())
        XCTAssertNil(unset.minNetworkQuality)
        XCTAssertNil(unset.healthCheckUrl)
        XCTAssertNil(unset.healthCheckTtlMs)
        XCTAssertNil(unset.healthCheckBackoffMs)

        var options = SyncOptionsSnapshot.sample()
        options.minNetworkQuality = .poor
        options.healthCheckUrl = "https://health.test/ping"
        options.healthCheckTtlMs = 0
        options.healthCheckBackoffMs = 1e300
        await engine.configure(options)

        let set = try XCTUnwrap(try attachLikeTheBridge(try hybrid.getSyncConfig()).value?.get())
        XCTAssertEqual(set.minNetworkQuality?.stringValue, "POOR")
        XCTAssertEqual(set.healthCheckUrl, "https://health.test/ping")
        XCTAssertEqual(set.healthCheckTtlMs, 0)
        XCTAssertEqual(set.healthCheckBackoffMs, 1e300, "Echoed as set; clamping happens at use")
    }

    // MARK: - Helpers

    private func assertResolvedOnReturn<T>(_ promise: Promise<T>,
                                           file: StaticString = #filePath,
                                           line: UInt = #line) {
        let outcome = attachLikeTheBridge(promise)
        guard case .success? = outcome.value else {
            return XCTFail("Must have resolved inside the attach, got \(String(describing: outcome.value))",
                           file: file, line: line)
        }
        XCTAssertEqual(outcome.calls, 1, file: file, line: line)
    }

    private func assertRejectedOnReturn<T>(_ promise: Promise<T>,
                                           file: StaticString = #filePath,
                                           line: UInt = #line) {
        let outcome = attachLikeTheBridge(promise)
        guard case .failure? = outcome.value else {
            return XCTFail("Must have rejected inside the attach, got \(String(describing: outcome.value))",
                           file: file, line: line)
        }
        XCTAssertEqual(outcome.calls, 1, file: file, line: line)
    }
}
