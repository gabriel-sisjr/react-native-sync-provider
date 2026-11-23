import XCTest
@testable import SyncProvider

/// The one gateway native waits for JS through. A request is registered
/// before its callback is called, and its wait ends exactly once: with the
/// answer, at its timeout, at a cancellation, at a call that throws, or when
/// its owner goes away. Whatever arrives after that does nothing.
///
/// The timer is manual: a timeout fires only when a test fires it, so a wait
/// that ends shows what ended it.
final class CallbackGatewayTests: XCTestCase {
    private var timer: ManualCallbackTimer!
    private var gateway: CallbackGateway!

    override func setUp() {
        super.setUp()
        timer = ManualCallbackTimer()
        gateway = CallbackGateway(timer: timer)
    }

    override func tearDown() {
        gateway = nil
        timer = nil
        super.tearDown()
    }

    // MARK: - The answer

    func test_gateway_answerBeforeTimeout_resolves() async {
        let gateway = self.gateway!

        let outcome = await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { id in
            gateway.answer(id, .decision(.drop))
        }

        XCTAssertEqual(outcome, .answered(RetryDecisionValue(action: .drop, delayMs: nil)))
        XCTAssertEqual(gateway.pendingCount, 0)
        XCTAssertEqual(timer.delays, [3_000], "The timeout was armed before the call")
        timer.fireAll()
        XCTAssertEqual(gateway.pendingCount, 0, "The timeout of an answered request does nothing")
    }

    /// The facade answers on the JS thread, never on the thread that called it.
    func test_anAnswerFromAnotherThread_endsTheWait() async {
        let gateway = self.gateway!

        let outcome = await gateway.requestHeaders(owner: 1, timeoutMs: 3_000) { id in
            DispatchQueue.global().async {
                gateway.answer(id, .headers(["Authorization": "Bearer fresh"]))
            }
        }

        XCTAssertEqual(outcome, .answered(["Authorization": "Bearer fresh"]))
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    func test_gateway_wrongKindPayload_isFailed() async {
        let gateway = self.gateway!

        let decider = await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { id in
            gateway.answer(id, .headers(["X-A": "b"]))
        }
        let resolver = await gateway.requestHeaders(owner: 1, timeoutMs: 3_000) { id in
            gateway.answer(id, .decision(.retry))
        }
        let before = await gateway.requestBeforeDispatch(owner: 1, timeoutMs: 3_000) { id in
            gateway.answer(id, .resolvedEmpty)
        }

        XCTAssertTrue(isInvalid(decider), "\(decider)")
        XCTAssertTrue(isInvalid(resolver), "\(resolver)")
        XCTAssertTrue(isInvalid(before), "\(before)")
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    /// The field of the request's kind is what counts; the others are ignored.
    func test_aPayloadOfTheRightKind_withOtherFieldsToo_isUsed() async {
        let gateway = self.gateway!
        let mixed = CallbackReply(status: .resolved,
                                  decision: RetryDecisionValue(action: .deadLetter, delayMs: nil),
                                  headers: ["X-A": "b"],
                                  beforeDispatch: .skip)

        let decider = await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { gateway.answer($0, mixed) }
        let resolver = await gateway.requestHeaders(owner: 1, timeoutMs: 3_000) { gateway.answer($0, mixed) }
        let before = await gateway.requestBeforeDispatch(owner: 1, timeoutMs: 3_000) { gateway.answer($0, mixed) }

        XCTAssertEqual(decider, .answered(RetryDecisionValue(action: .deadLetter, delayMs: nil)))
        XCTAssertEqual(resolver, .answered(["X-A": "b"]))
        XCTAssertEqual(before, .answered(.skip))
    }

    func test_aFailedAnswer_fallsBackAtOnce() async {
        let gateway = self.gateway!

        let outcome = await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { id in
            gateway.answer(id, .failed("the decider threw"))
        }

        XCTAssertEqual(outcome, .skipped(.failed(message: "the decider threw")))
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    /// An empty map is a valid answer: the resolver supplies nothing.
    func test_resolverHeaders_mustUseOnlyTheCharactersHTTPAllows() async {
        let gateway = self.gateway!
        let usable: [[String: String]] = [
            [:],
            ["Authorization": "Bearer a.b-c_d~e", "X-Empty": ""],
            ["X-Tab": "one\ttwo", "X-Symbols": "!#$%&'*+-.^_`|~"]
        ]
        let unusable: [[String: String]] = [
            ["": "value"],
            ["Bad Name": "value"],
            ["X-Ação": "value"],
            ["X-Del\u{7F}": "value"],
            ["X-A": "line\nbreak"],
            ["X-A": "carriage\rreturn"],
            ["X-A": "café"],
            ["X-A": "nul\u{0}"]
        ]

        for headers in usable {
            let outcome = await gateway.requestHeaders(owner: 1, timeoutMs: 3_000) { gateway.answer($0, .headers(headers)) }
            XCTAssertEqual(outcome, .answered(headers), "\(headers.keys.sorted())")
        }
        for headers in unusable {
            let outcome = await gateway.requestHeaders(owner: 1, timeoutMs: 3_000) { gateway.answer($0, .headers(headers)) }
            XCTAssertTrue(isInvalid(outcome), "\(headers.keys.sorted()) was accepted")
        }
    }

    func test_gateway_secondAnswer_noop() async {
        let gateway = self.gateway!
        let ids = Recorder<String>()

        let outcome = await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { id in
            ids.record(id)
            gateway.answer(id, .decision(.drop))
            gateway.answer(id, .decision(.retry))
        }
        gateway.answer(ids.values.first ?? "", .decision(.deadLetter))

        XCTAssertEqual(outcome, .answered(RetryDecisionValue(action: .drop, delayMs: nil)), "The first answer wins")
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    func test_anUnknownId_isANoOp() async {
        let gateway = self.gateway!
        gateway.answer("decider:999999", .decision(.drop))
        XCTAssertEqual(gateway.pendingCount, 0)

        let outcome = await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { id in
            gateway.answer("decider:999999", .decision(.drop))
            gateway.answer(id, .decision(.retry, delayMs: 250))
        }

        XCTAssertEqual(outcome, .answered(RetryDecisionValue(action: .retry, delayMs: 250)))
    }

    // MARK: - No answer

    func test_gateway_noAnswer_timesOut() async {
        let gateway = self.gateway!
        let timer = self.timer!

        // JS never answers, and the request's time runs out while it waits.
        let outcome = await gateway.requestDecision(owner: 1, timeoutMs: 1_234) { _ in
            timer.fireAll()
        }

        XCTAssertEqual(outcome, .skipped(.timedOut(afterMs: 1_234)))
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    func test_aLateAnswer_isANoOp() async {
        let gateway = self.gateway!
        let timer = self.timer!
        let ids = Recorder<String>()
        let timedOut = await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { id in
            ids.record(id)
            timer.fireAll()
        }
        XCTAssertEqual(timedOut, .skipped(.timedOut(afterMs: 3_000)))

        gateway.answer(ids.values.first ?? "", .decision(.drop))

        XCTAssertEqual(gateway.pendingCount, 0)
        let next = await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { gateway.answer($0, .decision(.retry)) }
        XCTAssertEqual(next, .answered(RetryDecisionValue(action: .retry, delayMs: nil)))
    }

    /// The production timer ends a wait no answer ends.
    func test_theProductionTimer_endsAWaitAtItsTimeout() async {
        let gateway = CallbackGateway(timer: DispatchCallbackTimer())
        let ended = expectation(description: "the wait ended")
        let start = DispatchTime.now().uptimeNanoseconds
        let wait = Task { () -> CallbackOutcome<RetryDecisionValue> in
            let outcome = await gateway.requestDecision(owner: 1, timeoutMs: 50) { _ in }
            ended.fulfill()
            return outcome
        }

        await fulfillment(of: [ended], timeout: livenessTimeout)

        let elapsedMs = (DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
        let outcome = await wait.value
        XCTAssertEqual(outcome, .skipped(.timedOut(afterMs: 50)))
        XCTAssertGreaterThanOrEqual(elapsedMs, 45, "It waited for the timeout")
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    /// The production timer lets a timeout go as soon as its request ends, not
    /// at its deadline: an answered request, even at the longest
    /// `callbackTimeoutMs`, keeps nothing of its timeout, and a timeout that
    /// fires lets go once it has run.
    func test_ios_theProductionTimer_freesATimeout_whenItsRequestEnds() async throws {
        let timer = ProbedCallbackTimer()
        let gateway = CallbackGateway(timer: timer)
        let longest = NumericOption.int32Max

        let answered = await gateway.requestDecision(owner: 1, timeoutMs: longest) { id in
            gateway.answer(id, .decision(.drop))
        }
        gateway.notifyAfterDispatch(owner: 1, timeoutMs: longest) { id in
            gateway.answer(id, .resolvedEmpty)
        }
        let timedOut = await gateway.requestHeaders(owner: 1, timeoutMs: 1) { _ in }

        XCTAssertEqual(answered, .answered(RetryDecisionValue(action: .drop, delayMs: nil)))
        XCTAssertEqual(timedOut, .skipped(.timedOut(afterMs: 1)))
        XCTAssertEqual(timer.scheduledCount, 3)
        let deadline = Date().addingTimeInterval(livenessTimeout)
        while timer.heldCount > 0, Date() < deadline {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(timer.heldCount, 0, "A timeout still holds what it captured")
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    // MARK: - The call

    func test_gateway_invokeThrows_isFailed() async {
        // The void callbacks Nitro generates cannot throw; the gateway still
        // treats a call that throws as a failed one.
        let outcome = await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { _ in
            throw FakeCallbackError()
        }

        guard case .skipped(.invokeFailed) = outcome else {
            return XCTFail("Expected a failed call, got \(outcome)")
        }
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    func test_requestIds_areUnique_andNameTheirKind() async {
        let gateway = self.gateway!
        let ids = Recorder<String>()

        _ = await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { ids.record($0); gateway.answer($0, .decision(.retry)) }
        _ = await gateway.requestHeaders(owner: 1, timeoutMs: 3_000) { ids.record($0); gateway.answer($0, .headers([:])) }
        _ = await gateway.requestBeforeDispatch(owner: 1, timeoutMs: 3_000) { ids.record($0); gateway.answer($0, .before(.proceed)) }
        gateway.notifyAfterDispatch(owner: 1, timeoutMs: 3_000) { ids.record($0) }

        XCTAssertEqual(ids.values.map { $0.split(separator: ":").first.map(String.init) },
                       ["decider", "resolver", "before", "after"])
        XCTAssertEqual(Set(ids.values).count, 4)
    }

    // MARK: - Cancellation

    func test_gateway_cancellation_endsWait() async {
        let gateway = self.gateway!
        let sent = expectation(description: "the callback was called")
        let wait = Task { await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { _ in sent.fulfill() } }
        await fulfillment(of: [sent], timeout: livenessTimeout)

        // A BGTask whose time expired cancels the task that waits.
        wait.cancel()

        let outcome = await wait.value
        XCTAssertEqual(outcome, .skipped(.cancelled), "Ended at once, not by the timeout")
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    func test_aTaskCancelledBeforeItsRequest_sendsNothing() async {
        let gateway = self.gateway!
        let sends = Recorder<String>()

        let wait = Task { () -> CallbackOutcome<RetryDecisionValue> in
            withUnsafeCurrentTask { $0?.cancel() }
            return await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { sends.record($0) }
        }

        let outcome = await wait.value
        XCTAssertEqual(outcome, .skipped(.cancelled))
        XCTAssertEqual(sends.values, [], "JS was not called")
        XCTAssertEqual(timer.delays, [], "Nothing was scheduled")
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    /// The answer, the timeout and a cancellation, all at once, many times. A
    /// second resume of a checked continuation would crash the test process.
    func test_aRaceOfAnswerTimeoutAndCancellation_endsEachWaitOnce() async {
        for _ in 0..<200 {
            let timer = ManualCallbackTimer()
            let gateway = CallbackGateway(timer: timer)
            let ids = Recorder<String>()
            let sent = expectation(description: "sent")
            let wait = Task {
                await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { id in
                    ids.record(id)
                    sent.fulfill()
                }
            }
            await fulfillment(of: [sent], timeout: livenessTimeout)
            let id = ids.values.first ?? ""

            DispatchQueue.concurrentPerform(iterations: 3) { lane in
                switch lane {
                case 0: gateway.answer(id, .decision(.drop))
                case 1: timer.fireAll()
                default: wait.cancel()
                }
            }

            _ = await wait.value
            XCTAssertEqual(gateway.pendingCount, 0)
        }
    }

    // MARK: - Fire and forget

    func test_aNotifiedCallback_isNeverWaitedFor_andItsEntryEndsWithItsAnswerOrTimeout() {
        let ids = Recorder<String>()

        gateway.notifyAfterDispatch(owner: 1, timeoutMs: 3_000) { ids.record($0) }
        XCTAssertEqual(gateway.pendingCount, 1, "It returned before any answer")
        gateway.answer(ids.values.last ?? "", .resolvedEmpty)
        XCTAssertEqual(gateway.pendingCount, 0)

        gateway.notifyAfterDispatch(owner: 1, timeoutMs: 3_000) { ids.record($0) }
        gateway.answer(ids.values.last ?? "", .failed("the hook threw"))
        XCTAssertEqual(gateway.pendingCount, 0, "A FAILED answer is logged and dropped")

        gateway.notifyAfterDispatch(owner: 1, timeoutMs: 3_000) { _ in }
        XCTAssertEqual(gateway.pendingCount, 1)
        timer.fireAll()
        XCTAssertEqual(gateway.pendingCount, 0, "No answer: dropped at the timeout")

        gateway.notifyAfterDispatch(owner: 1, timeoutMs: 3_000) { _ in throw FakeCallbackError() }
        XCTAssertEqual(gateway.pendingCount, 0, "A call that throws leaves nothing behind")
    }

    // MARK: - Owners

    func test_retiringAnOwner_endsOnlyItsWaits_atOnce() async {
        let gateway = self.gateway!
        let ids = Recorder<String>()
        let sentByOld = expectation(description: "the old owner's callback was called")
        let sentByNew = expectation(description: "the new owner's callback was called")
        let old = Task { await gateway.requestDecision(owner: 1, timeoutMs: 3_000) { _ in sentByOld.fulfill() } }
        let new = Task {
            await gateway.requestHeaders(owner: 2, timeoutMs: 3_000) { id in
                ids.record(id)
                sentByNew.fulfill()
            }
        }
        await fulfillment(of: [sentByOld, sentByNew], timeout: livenessTimeout)

        gateway.retireOwner(1)

        let oldOutcome = await old.value
        XCTAssertEqual(oldOutcome, .skipped(.ownerGone))
        XCTAssertEqual(gateway.pendingCount, 1, "The other owner's wait goes on")
        gateway.answer(ids.values.first ?? "", .headers(["X-A": "b"]))
        let newOutcome = await new.value
        XCTAssertEqual(newOutcome, .answered(["X-A": "b"]))
    }

    /// An install can be read just before its hybrid goes away, and it stays
    /// readable after, orphaned, until a newer hybrid attaches: a request for
    /// a retired owner ends at once, and JS is not called.
    func test_aRequestForARetiredOwner_neverCallsJS() async {
        let gateway = self.gateway!
        let sends = Recorder<String>()
        gateway.retireOwner(3)
        gateway.retireOwners(below: 2)

        let tornDown = await gateway.requestDecision(owner: 3, timeoutMs: 3_000) { sends.record($0) }
        let evicted = await gateway.requestHeaders(owner: 1, timeoutMs: 3_000) { sends.record($0) }
        gateway.notifyAfterDispatch(owner: 1, timeoutMs: 3_000) { sends.record($0) }
        let current = await gateway.requestBeforeDispatch(owner: 2, timeoutMs: 3_000) { id in
            sends.record(id)
            gateway.answer(id, .before(.proceed))
        }

        XCTAssertEqual(tornDown, .skipped(.ownerGone))
        XCTAssertEqual(evicted, .skipped(.ownerGone))
        XCTAssertEqual(current, .answered(.proceed), "A live owner is not affected")
        XCTAssertEqual(sends.values.count, 1, "Only the live owner's callback was called")
        XCTAssertEqual(timer.delays, [3_000], "Only the live owner's request was armed")
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    /// An engine that shuts down ends every wait, and every later request.
    func test_retiringEveryOwner_endsEveryWait_andEveryLaterRequest() async {
        let gateway = self.gateway!
        let sent = expectation(description: "the callback was called")
        let wait = Task { await gateway.requestDecision(owner: 7, timeoutMs: 3_000) { _ in sent.fulfill() } }
        await fulfillment(of: [sent], timeout: livenessTimeout)

        gateway.retireAll()

        let pending = await wait.value
        XCTAssertEqual(pending, .skipped(.ownerGone))
        let sends = Recorder<String>()
        let later = await gateway.requestDecision(owner: 8, timeoutMs: 3_000) { sends.record($0) }
        XCTAssertEqual(later, .skipped(.ownerGone))
        XCTAssertEqual(sends.values, [])
        XCTAssertEqual(gateway.pendingCount, 0)
    }

    // MARK: - Helpers

    private func isInvalid<Value>(_ outcome: CallbackOutcome<Value>) -> Bool {
        if case .skipped(.invalid) = outcome {
            return true
        }
        return false
    }
}

/// The production timer, with one probe object added to the captures of each
/// timeout it schedules, so a test sees when a timeout is let go.
private final class ProbedCallbackTimer: CallbackTimer, @unchecked Sendable {
    private final class Probe: Sendable {}

    private struct Watch {
        weak var probe: Probe?
    }

    private let base = DispatchCallbackTimer()
    private let lock = NSLock()
    private var watches: [Watch] = []

    /// Every timeout scheduled so far.
    var scheduledCount: Int {
        lock.withLock { watches.count }
    }

    /// The timeouts that still hold what they captured.
    var heldCount: Int {
        lock.withLock { watches.filter { $0.probe != nil }.count }
    }

    func schedule(afterMs delayMs: Int, _ fire: @escaping @Sendable () -> Void) -> CallbackTimerToken {
        let probe = Probe()
        lock.withLock { watches.append(Watch(probe: probe)) }
        return base.schedule(afterMs: delayMs) {
            withExtendedLifetime(probe) {}
            fire()
        }
    }
}
