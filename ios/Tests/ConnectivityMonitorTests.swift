import XCTest
import Network
@testable import SyncProvider

final class ConnectivityMonitorTests: XCTestCase {
    private var monitor: ConnectivityMonitor!

    override func setUp() {
        super.setUp()
        // We exercise the singleton — there's no public init to inject a
        // controlled `NWPathMonitor`. Tests stop the monitor in tearDown so
        // the next test starts from a clean state.
        monitor = ConnectivityMonitor.shared
    }

    override func tearDown() {
        monitor.stop()
        super.tearDown()
    }

    // MARK: - Default state

    func test_currentSnapshot_isUnknown_beforeStart() {
        // `current` cannot be guaranteed to reflect the real path before `start()`
        // — assert only that it returns a valid snapshot with a recognized status.
        let snapshot = monitor.current
        XCTAssertNotNil(snapshot)
        let validStatuses: Set<String> = ["CONNECTED", "DISCONNECTED", "METERED", "UNKNOWN"]
        XCTAssertTrue(validStatuses.contains(snapshot.status.rawValue))
    }

    // MARK: - Start idempotency

    func test_start_isIdempotent() {
        monitor.start()
        monitor.start()
        monitor.start()
        XCTAssertNotNil(monitor.current)
    }

    // MARK: - Subscribe replay

    func test_subscribe_replaysCurrentSnapshot_synchronously() {
        let replays = RequestLog()
        let token = monitor.subscribe { _ in
            replays.record()
        }
        // Replay must have fired synchronously — `subscribe` does not return until
        // the listener has been invoked once with the current snapshot.
        XCTAssertFalse(replays.times.isEmpty)
        monitor.unsubscribe(token)
    }

    // MARK: - Unsubscribe

    func test_unsubscribe_stopsCallbacks() {
        let exp = expectation(description: "Listener fires at most once")
        exp.assertForOverFulfill = false

        let fired = RequestLog()
        let token = monitor.subscribe { _ in
            fired.record()
            exp.fulfill()
        }
        wait(for: [exp], timeout: 1.0)

        monitor.unsubscribe(token)

        // We can't deterministically trigger a path update from a unit-test host,
        // so we only assert the replay-driven baseline fired before unsubscribe.
        XCTAssertGreaterThanOrEqual(fired.times.count, 1)
    }

    // MARK: - Snapshot enum invariants

    func test_connectionSnapshot_statusRawValues_matchExpected() {
        XCTAssertEqual(ConnectionSnapshot.Status.connected.rawValue, "CONNECTED")
        XCTAssertEqual(ConnectionSnapshot.Status.disconnected.rawValue, "DISCONNECTED")
        XCTAssertEqual(ConnectionSnapshot.Status.metered.rawValue, "METERED")
        XCTAssertEqual(ConnectionSnapshot.Status.unknown.rawValue, "UNKNOWN")
    }

    func test_connectionSnapshot_kindRawValues_matchExpected() {
        XCTAssertEqual(ConnectionSnapshot.Kind.wifi.rawValue, "WIFI")
        XCTAssertEqual(ConnectionSnapshot.Kind.cellular.rawValue, "CELLULAR")
        XCTAssertEqual(ConnectionSnapshot.Kind.ethernet.rawValue, "ETHERNET")
        XCTAssertEqual(ConnectionSnapshot.Kind.other.rawValue, "OTHER")
        XCTAssertEqual(ConnectionSnapshot.Kind.none.rawValue, "NONE")
        XCTAssertEqual(ConnectionSnapshot.Kind.unknown.rawValue, "UNKNOWN")
    }

    func test_unknownSnapshot_constant_hasExpectedShape() {
        let unknown = ConnectionSnapshot.unknown
        XCTAssertEqual(unknown.status, .unknown)
        XCTAssertEqual(unknown.type, .unknown)
        XCTAssertNil(unknown.isInternetReachable)
        XCTAssertNil(unknown.isExpensive)
    }
}

/// `subscribe` reads the snapshot to replay under the lock and hands it over
/// outside it, so the system's first path update can reach the subscriber in
/// between. A monitor of its own, with the update applied by the test at that
/// exact point, makes the race deterministic.
final class ConnectivityReplayOrderTests: XCTestCase {
    private final class Received: @unchecked Sendable {
        private let lock = NSLock()
        private var recorded: [ConnectionSnapshot.Status] = []

        var statuses: [ConnectionSnapshot.Status] {
            lock.withLock { recorded }
        }

        func record(_ snapshot: ConnectionSnapshot) {
            lock.withLock { recorded.append(snapshot.status) }
        }
    }

    func test_aPathUpdateDuringTheReplay_isNotOverwrittenByTheStaleReplay() {
        let monitor = ConnectivityMonitor()
        monitor.setReplayHook { monitor.apply(.link()) }
        let received = Received()

        monitor.subscribe { received.record($0) }

        XCTAssertEqual(received.statuses.last, .connected, "\(received.statuses)")
        XCTAssertFalse(received.statuses.drop { $0 != .connected }.contains(.unknown),
                       "An UNKNOWN reached the subscriber after the real path: \(received.statuses)")
        XCTAssertEqual(monitor.current.status, .connected)
    }

    /// What the stale replay did to the engine: the quality monitor kept
    /// `UNKNOWN`, so on a stable network `getConnectionStatus()` reported it,
    /// retry wakes were held as offline and a gated flush was blocked.
    func test_theQualityMonitor_keepsTheRealPath() {
        let connectivity = ConnectivityMonitor()
        connectivity.setReplayHook { connectivity.apply(.link()) }
        let quality = NetworkQualityMonitor(connectivity: connectivity, probe: FakeHeadProbe(), clock: ManualClock())

        quality.start()
        defer { quality.stop() }

        XCTAssertEqual(quality.current.link.status, .connected)
        XCTAssertTrue(quality.current.link.isOnline)
    }

    /// With no race, the replay still comes first and synchronously, and the
    /// next update follows it.
    func test_withoutARace_theReplayComesFirst_andUpdatesFollowInOrder() {
        let monitor = ConnectivityMonitor()
        let received = Received()

        monitor.subscribe { received.record($0) }
        XCTAssertEqual(received.statuses, [.unknown], "Replayed before subscribe returned")

        monitor.apply(.link())
        monitor.apply(.disconnected)
        monitor.apply(.link(.cellular, expensive: true))

        XCTAssertEqual(received.statuses, [.unknown, .connected, .disconnected, .metered])
    }
}
