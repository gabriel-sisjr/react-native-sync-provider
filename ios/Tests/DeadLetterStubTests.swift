import XCTest
import NitroModules
@testable import SyncProvider

/// The dead-letter methods before there is a dead-letter store: listing finds
/// nothing, and replay and discard reject instead of pretending to succeed.
///
/// Every promise is observed the way the generated bridge observes it, with a
/// listener attached right after the call returns, and must settle within a
/// deadline: one that never calls its listener fails the test instead of
/// hanging the suite.
final class DeadLetterStubTests: XCTestCase {
    private var fixture: EngineFixture!
    private var engine: SyncEngine!
    private var hybrid: HybridSyncProvider!

    private let unknownId = "01HZZZZZZZZZZZZZZZZZZZZZZZ"

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

    func test_list_findsNothing() async throws {
        let outcome = try await settle(hybrid.listDeadLetter())

        XCTAssertEqual(try outcome?.get().count, 0)
    }

    func test_replay_rejectsAsNotImplemented() async throws {
        let outcome = try await settle(hybrid.replayDeadLetter(id: unknownId))

        assertNotImplemented(outcome, "Dead-letter replay is not implemented yet.")
    }

    func test_discard_rejectsAsNotImplemented() async throws {
        let outcome = try await settle(hybrid.discardDeadLetter(id: unknownId))

        assertNotImplemented(outcome, "Dead-letter discard is not implemented yet.")
    }

    func test_rejectedCalls_leaveTheQueueAlone() async throws {
        let id = try await engine.storage.enqueue(input: .sample())

        _ = try await settle(hybrid.replayDeadLetter(id: id))
        _ = try await settle(hybrid.discardDeadLetter(id: id))

        let queued = try await engine.storage.getQueued()
        XCTAssertEqual(queued.map(\.id), [id])
    }

    // MARK: - Helpers

    private func assertNotImplemented<T>(_ outcome: Result<T, Error>?,
                                         _ message: String,
                                         file: StaticString = #filePath,
                                         line: UInt = #line) {
        guard case let .failure(error)? = outcome else {
            return XCTFail("Expected a rejection, got \(String(describing: outcome))", file: file, line: line)
        }
        XCTAssertEqual((error as? SyncProviderError)?.codeName, "INVALID_PAYLOAD", file: file, line: line)
        XCTAssertEqual(error.localizedDescription, message, file: file, line: line)
    }
}
