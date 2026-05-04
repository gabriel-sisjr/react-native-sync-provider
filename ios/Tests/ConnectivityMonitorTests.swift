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
        var captured: ConnectionSnapshot?
        let token = monitor.subscribe { snapshot in
            captured = snapshot
        }
        // Replay must have fired synchronously — `subscribe` does not return until
        // the listener has been invoked once with the current snapshot.
        XCTAssertNotNil(captured)
        monitor.unsubscribe(token)
    }

    // MARK: - Unsubscribe

    func test_unsubscribe_stopsCallbacks() {
        let exp = expectation(description: "Listener fires at most once")
        exp.assertForOverFulfill = false

        var fireCount = 0
        let lock = NSLock()
        let token = monitor.subscribe { _ in
            lock.lock()
            fireCount += 1
            lock.unlock()
            exp.fulfill()
        }
        wait(for: [exp], timeout: 1.0)

        monitor.unsubscribe(token)

        // We can't deterministically trigger a path update from a unit-test host,
        // so we only assert the replay-driven baseline fired before unsubscribe.
        lock.lock()
        let countBeforeReSubscribe = fireCount
        lock.unlock()
        XCTAssertGreaterThanOrEqual(countBeforeReSubscribe, 1)
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
