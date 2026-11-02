import XCTest
@testable import SyncProvider

/// The timer's logic runs against a scheduler the test controls, so deciding
/// what fires never depends on real time passing.
final class RetryWakeTimerTests: XCTestCase {
    /// Captures scheduled work instead of running it.
    private final class ManualSchedule: @unchecked Sendable {
        private let lock = NSLock()
        private var entries: [(delayMs: Int, work: DispatchWorkItem)] = []

        func add(delayMs: Int, work: DispatchWorkItem) {
            lock.withLock { entries.append((delayMs, work)) }
        }

        var delays: [Int] { lock.withLock { entries.map(\.delayMs) } }

        func runAll() {
            let pending = lock.withLock { entries.map(\.work) }
            pending.forEach { $0.perform() }
        }
    }

    private var schedule: ManualSchedule!
    private var timer: RetryWakeTimer!
    private var fired: RequestLog!

    override func setUp() {
        super.setUp()
        let schedule = ManualSchedule()
        self.schedule = schedule
        timer = RetryWakeTimer(schedule: { schedule.add(delayMs: $0, work: $1) })
        fired = RequestLog()
    }

    /// Runs everything scheduled, then waits for the wake to finish.
    private func runAndWaitForWake() async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            let fired = self.fired!
            timer.arm(afterMs: 5) {
                fired.record()
                done.resume()
            }
            schedule.runAll()
        }
    }

    func test_armedWake_firesOnce_andDisarms() async {
        XCTAssertFalse(timer.isArmed)

        await runAndWaitForWake()

        XCTAssertEqual(fired.times.count, 1)
        XCTAssertFalse(timer.isArmed, "a single-shot timer disarms itself")
    }

    func test_armingReportsArmed_untilItFires() {
        timer.arm(afterMs: 10) {}
        XCTAssertTrue(timer.isArmed)
    }

    func test_newArm_replacesThePreviousOne() async {
        let replaced = RequestLog()
        timer.arm(afterMs: 60_000) { replaced.record() }

        await runAndWaitForWake()

        XCTAssertEqual(fired.times.count, 1)
        XCTAssertTrue(replaced.times.isEmpty, "only one wake may be pending")
        XCTAssertFalse(timer.isArmed)
    }

    func test_cancel_dropsThePendingWake() {
        timer.arm(afterMs: 10) { XCTFail("a cancelled wake must not fire") }

        timer.cancel()
        schedule.runAll()

        XCTAssertFalse(timer.isArmed)
    }

    func test_negativeDelay_clampsToZero() {
        timer.arm(afterMs: -5_000) {}
        XCTAssertEqual(schedule.delays, [0])
    }

    /// `DispatchTimeInterval` takes an `Int`. A longer wait wakes early and the
    /// next cycle re-arms for the remainder.
    func test_hugeDelay_isBounded() {
        timer.arm(afterMs: .max) {}
        XCTAssertEqual(schedule.delays, [Int(Int32.max)])
    }

    /// The handler runs after disarming, so it can arm the next wake. This is
    /// how a timer-driven cycle re-arms itself.
    func test_fireHandler_canRearm() async {
        let timer = self.timer!
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            timer.arm(afterMs: 1) {
                timer.arm(afterMs: 2) {}
                done.resume()
            }
            schedule.runAll()
        }

        XCTAssertTrue(timer.isArmed)
        XCTAssertEqual(schedule.delays, [1, 2])
    }

    /// The default scheduler, on real time. Waits for the event; the timeout
    /// is only reached when the wake never fires.
    func test_defaultSchedule_firesForReal() async {
        let timer = RetryWakeTimer()
        let wake = expectation(description: "wake")

        timer.arm(afterMs: 1) { wake.fulfill() }

        await fulfillment(of: [wake], timeout: 120)
    }
}
