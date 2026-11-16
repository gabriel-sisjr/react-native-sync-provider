import BackgroundTasks
import CoreData
import XCTest
@testable import SyncProvider

/// A fired BGTask whose time runs out. iOS calls `expirationHandler` shortly
/// before the end and may kill an app that has not called
/// `setTaskCompleted(success:)` by then, so the task must be completed exactly
/// once on every path, and the flush must stop.
///
/// Requests go to a protocol that holds them until they are cancelled, so a
/// test fires the expiry while one is out, deterministically. Real BGTask
/// launches and a real background `URLSession` are not covered here.
final class BackgroundTaskExpiryTests: XCTestCase {
    private var stack: CoreDataStack!
    private var storage: SyncQueueStorage!
    private var emitter: AwaitingEmitter!
    private var delegate: BackgroundURLSessionDelegate!
    private var delegateQueue: OperationQueue!
    private var backgroundSession: URLSession!
    private var dispatcher: SyncDispatcher!
    private var manager: BackgroundSyncManager!
    private var defaults: UserDefaults!
    private var suite: String!

    override func setUp() {
        super.setUp()
        HeldRequestProtocol.reset()
        stack = InMemoryCoreDataStack.make()
        storage = SyncQueueStorage(dao: SyncItemDao(stack: stack), capacity: 10)
        emitter = AwaitingEmitter()
        (defaults, suite) = UserDefaults.ephemeral()
    }

    override func tearDown() {
        delegateQueue?.isSuspended = false
        HeldRequestProtocol.reset()
        defaults.removePersistentDomain(forName: suite)
        manager = nil
        dispatcher = nil
        super.tearDown()
    }

    // MARK: - Fixture

    /// A manager with background sync on, over a dispatcher whose sessions
    /// both go to ``HeldRequestProtocol``. `appActive` picks the session: the
    /// foreground one while active, the background one otherwise.
    private func makeManager(appActive: Bool, stack: CoreDataStack? = nil) throws -> BackgroundSyncManager {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HeldRequestProtocol.self]
        delegate = BackgroundURLSessionDelegate()
        delegateQueue = OperationQueue()
        delegateQueue.maxConcurrentOperationCount = 1
        backgroundSession = URLSession(configuration: configuration, delegate: delegate, delegateQueue: delegateQueue)
        let storage = stack.map { SyncQueueStorage(dao: SyncItemDao(stack: $0), capacity: 10) } ?? self.storage!
        dispatcher = SyncDispatcher(storage: storage,
                                    emitter: emitter,
                                    connectivity: FakeConnectivity(),
                                    // Long enough that no session timeout ends a held request.
                                    config: DispatcherConfig(retryPolicy: .default,
                                                             requestTimeoutMs: 600_000,
                                                             batchSize: 25,
                                                             defaultHeaders: [:]),
                                    foregroundSession: URLSession(configuration: configuration),
                                    backgroundSession: backgroundSession,
                                    backgroundDelegate: delegate,
                                    isAppActive: { appActive })
        let manager = BackgroundSyncManager(dispatcher: dispatcher,
                                            emitter: emitter,
                                            scheduler: FakeBackgroundTaskScheduler(),
                                            store: BackgroundSyncOptionsStore(defaults: defaults))
        try manager.enable(options: BackgroundSyncOptionsValue(minimumIntervalMs: 60_000,
                                                               requiresCharging: nil,
                                                               requiresUnmeteredNetwork: nil,
                                                               requiresDeviceIdle: nil,
                                                               foregroundOnly: nil,
                                                               taskIdentifier: nil))
        self.manager = manager
        return manager
    }

    private enum Handler {
        case refresh, processing

        var kind: String {
            switch self {
            case .refresh: return "refresh"
            case .processing: return "processing"
            }
        }

        func fire(_ manager: BackgroundSyncManager, _ task: FakeBackgroundTask) {
            switch self {
            case .refresh: manager.handleAppRefresh(task: task)
            case .processing: manager.handleProcessing(task: task)
            }
        }
    }

    /// Fires `handler` and returns once its request reached the protocol.
    private func fireWithARequestOut(_ handler: Handler, on manager: BackgroundSyncManager) async -> FakeBackgroundTask {
        let started = expectation(description: "the request is out")
        HeldRequestProtocol.expectNextStart(started)
        let task = FakeBackgroundTask()
        handler.fire(manager, task)
        await fulfillment(of: [started], timeout: livenessTimeout)
        return task
    }

    private func row(_ id: String) async throws -> StoredSyncItem? {
        try await storage.getAll().first { $0.id == id }
    }

    private static func nowMs() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    /// What every expired window must leave behind. `sentAfter`: a time taken
    /// before the item was claimed.
    private func assertExpired(_ task: FakeBackgroundTask, kind: String, itemId id: String, sentAfter: Int64,
                               file: StaticString = #filePath, line: UInt = #line) async throws {
        await emitter.wait(for: "BACKGROUND_SYNC_COMPLETED", in: self)
        XCTAssertEqual(task.completedResults, [false], "completed once, with false", file: file, line: line)

        let failed = emitter.events(ofType: "SYNC_FAILED")
        XCTAssertEqual(failed.count, 1, file: file, line: line)
        XCTAssertNil(failed.first?.errorCode, "a cancelled cycle failed nothing", file: file, line: line)
        XCTAssertNil(failed.first?.metadata, file: file, line: line)
        let completed = emitter.events(ofType: "BACKGROUND_SYNC_COMPLETED")
        XCTAssertEqual(completed.count, 1, file: file, line: line)
        XCTAssertNil(completed.first?.errorCode, file: file, line: line)
        XCTAssertEqual(completed.first?.metadata, ["kind": kind, "ok": "false"], file: file, line: line)
        XCTAssertEqual(emitter.emittedEvents.map(\.type).filter { $0.hasPrefix("ITEM_") }, [],
                       "no outcome for the request that was cut off", file: file, line: line)

        let stored = try await row(id)
        XCTAssertEqual(stored?.status, "PENDING", "back in the queue, not IN_FLIGHT", file: file, line: line)
        XCTAssertEqual(stored?.attempts, 0, "the attempt the claim spent is refunded", file: file, line: line)
        // The request may have reached the server: the claim's time stays.
        let lastAttemptAt = try XCTUnwrap(stored?.lastAttemptAt, "the claim's time is kept", file: file, line: line)
        XCTAssertGreaterThanOrEqual(lastAttemptAt, sentAfter, file: file, line: line)
        XCTAssertLessThanOrEqual(lastAttemptAt, Self.nowMs(), file: file, line: line)
        XCTAssertEqual(stored?.nextRetryAt, 0, "no cooldown", file: file, line: line)
        let letters = try await storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0, file: file, line: line)
        let syncing = await dispatcher.cycleRunning()
        XCTAssertFalse(syncing, "isSyncing() is false again", file: file, line: line)
    }

    /// After an expiry, in the same process, a flush runs a real cycle.
    private func assertAFollowingFlushSends(_ id: String,
                                            file: StaticString = #filePath, line: UInt = #line) async throws {
        HeldRequestProtocol.answerNewRequests(with: 200)
        emitter.clear()

        let result = try await dispatcher.flush()

        XCTAssertEqual(result.itemsSucceeded, 1, file: file, line: line)
        XCTAssertEqual(emitter.emittedEvents.map(\.type).filter { $0 != "SYNC_PROGRESS" },
                       ["SYNC_STARTED", "ITEM_SUCCEEDED", "SYNC_SUCCEEDED"], file: file, line: line)
        let remaining = try await row(id)
        XCTAssertNil(remaining, file: file, line: line)
    }

    // MARK: - Expiry

    private func expireDuringAForegroundRequest(_ handler: Handler) async throws {
        let manager = try makeManager(appActive: true)
        let id = try await storage.enqueue(input: .sample())
        let sentAfter = Self.nowMs()
        let task = await fireWithARequestOut(handler, on: manager)

        task.expirationHandler?()

        XCTAssertEqual(task.completedResults, [false], "completed at once, before the time runs out")
        try await assertExpired(task, kind: handler.kind, itemId: id, sentAfter: sentAfter)
        try await assertAFollowingFlushSends(id)
    }

    func test_refreshExpiryDuringAForegroundRequest_completesOnce_andGivesTheItemBack() async throws {
        try await expireDuringAForegroundRequest(.refresh)
    }

    func test_processingExpiryDuringAForegroundRequest_completesOnce_andGivesTheItemBack() async throws {
        try await expireDuringAForegroundRequest(.processing)
    }

    func test_expiryDuringABackgroundUpload_completesOnce_andGivesTheItemBack() async throws {
        let manager = try makeManager(appActive: false)
        let id = try await storage.enqueue(input: .sample())
        let sentAfter = Self.nowMs()
        let task = await fireWithARequestOut(.processing, on: manager)

        task.expirationHandler?()

        XCTAssertEqual(task.completedResults, [false], "completed at once, before the time runs out")
        try await assertExpired(task, kind: "processing", itemId: id, sentAfter: sentAfter)
        try await assertAFollowingFlushSends(id)
    }

    /// The cancelled upload's completion arrives late, after its item was sent
    /// again: it must not end the new upload's wait. The delegate queue is
    /// held so the late completion lands right after the new upload started.
    func test_aCancelledUploadsLateCompletion_doesNotEndTheNextUpload() async throws {
        let manager = try makeManager(appActive: false)
        delegateQueue.isSuspended = true
        let id = try await storage.enqueue(input: .sample())
        let task = await fireWithARequestOut(.processing, on: manager)
        task.expirationHandler?()
        await emitter.wait(for: "BACKGROUND_SYNC_COMPLETED", in: self)

        HeldRequestProtocol.answerNewRequests(with: 200)
        let resent = expectation(description: "the item is sent again")
        HeldRequestProtocol.expectNextStart(resent)
        emitter.clear()
        let flush = Task { try await self.dispatcher.flush() }
        await fulfillment(of: [resent], timeout: livenessTimeout)
        delegateQueue.isSuspended = false

        let result = try await flush.value
        XCTAssertEqual(result.itemsSucceeded, 1)
        XCTAssertEqual(emitter.events(ofType: "ITEM_RETRYING").count, 0,
                       "the old upload's cancellation was taken for the new one's answer")
        let remaining = try await row(id)
        XCTAssertNil(remaining)
    }

    /// Expiry while the handler still waits for launch recovery: the flush
    /// never starts, and nothing is sent.
    func test_expiryBeforeTheFlushStarts_completesOnce_andSendsNothing() async throws {
        let manager = try makeManager(appActive: true)
        let gate = Gate()
        manager.setPrepare { await gate.wait() }
        HeldRequestProtocol.answerNewRequests(with: 200)
        let id = try await storage.enqueue(input: .sample())
        let task = FakeBackgroundTask()
        manager.handleProcessing(task: task)

        task.expirationHandler?()
        XCTAssertEqual(task.completedResults, [false], "completed at once")
        gate.open()

        await emitter.wait(for: "BACKGROUND_SYNC_COMPLETED", in: self)
        XCTAssertEqual(task.completedResults, [false])
        XCTAssertEqual(emitter.emittedEvents.map(\.type), ["BACKGROUND_SYNC_STARTED", "BACKGROUND_SYNC_COMPLETED"])
        XCTAssertEqual(emitter.events(ofType: "BACKGROUND_SYNC_COMPLETED").first?.metadata,
                       ["kind": "processing", "ok": "false"])
        XCTAssertEqual(HeldRequestProtocol.startedCount, 0)
        let stored = try await row(id)
        XCTAssertEqual(stored?.status, "PENDING")
        XCTAssertEqual(stored?.attempts, 0)
    }

    /// The expiry lands as the first item's success is reported. The cycle
    /// claims nothing more. Behind it, an item whose budget is spent and an
    /// item whose URL is not http(s) would each end in the dead-letter store
    /// without a request, and neither path checks for a cancellation itself.
    func test_anExpiryBetweenTwoItems_leavesTheItemsBehindItQueued() async throws {
        let manager = try makeManager(appActive: true)
        HeldRequestProtocol.answerNewRequests(with: 200)
        let answered = try await storage.enqueue(input: .sample(priority: "HIGH"))
        let spent = try await storage.enqueue(input: .sample())
        for _ in 0..<RetryPolicyValue.default.maxAttempts {
            try await storage.markInFlight(id: spent)
            try await storage.markPendingForRetry(id: spent, errorCode: "SERVER_ERROR", retryDelayMs: 0)
        }
        let invalid = try await storage.enqueue(input: .sample(url: "ftp://api.test/items"))
        let spentBefore = try await row(spent)
        let task = FakeBackgroundTask()
        emitter.onNext("ITEM_SUCCEEDED") { task.expirationHandler?() }

        manager.handleProcessing(task: task)
        await emitter.wait(for: "BACKGROUND_SYNC_COMPLETED", in: self)

        XCTAssertEqual(task.completedResults, [false])
        let answeredRow = try await row(answered)
        XCTAssertNil(answeredRow, "answered before the expiry")
        let spentRow = try await row(spent)
        XCTAssertEqual(spentRow?.status, "PENDING")
        XCTAssertEqual(spentRow?.attempts, spentBefore?.attempts)
        XCTAssertEqual(spentRow?.lastAttemptAt, spentBefore?.lastAttemptAt)
        let invalidRow = try await row(invalid)
        XCTAssertEqual(invalidRow?.status, "PENDING")
        XCTAssertEqual(invalidRow?.attempts, 0, "never claimed")
        XCTAssertNil(invalidRow?.lastAttemptAt)
        let letters = try await storage.getDeadLetters()
        XCTAssertEqual(letters.count, 0)
        XCTAssertEqual(emitter.emittedEvents.map(\.type).filter { $0.hasPrefix("ITEM_") }, ["ITEM_SUCCEEDED"])
        let failed = emitter.events(ofType: "SYNC_FAILED")
        XCTAssertEqual(failed.count, 1)
        XCTAssertNil(failed.first?.errorCode, "a cancelled cycle failed nothing")
        XCTAssertEqual(HeldRequestProtocol.startedCount, 1, "only the first item was sent")
    }

    /// A cancelled cycle still ends as cancelled when giving its row back
    /// fails: no code on its SYNC_FAILED, and the row waits IN_FLIGHT for
    /// launch recovery.
    func test_anExpiryWhoseReleaseFails_stillEndsWithoutACode() async throws {
        let faults = SaveFaults()
        let faulty = InMemoryCoreDataStack.make(saveFaults: faults)
        let manager = try makeManager(appActive: true, stack: faulty)
        let queue = SyncQueueStorage(dao: SyncItemDao(stack: faulty), capacity: 10)
        let id = try await queue.enqueue(input: .sample())
        let task = await fireWithARequestOut(.processing, on: manager)
        faults.fail(1)

        task.expirationHandler?()
        await emitter.wait(for: "BACKGROUND_SYNC_COMPLETED", in: self)

        XCTAssertEqual(task.completedResults, [false])
        XCTAssertEqual(faults.failures, 1, "the release was the write that failed")
        let failed = emitter.events(ofType: "SYNC_FAILED")
        XCTAssertEqual(failed.count, 1)
        XCTAssertNil(failed.first?.errorCode, "a cancelled cycle failed nothing")
        let stored = try await queue.getAll().first { $0.id == id }
        XCTAssertEqual(stored?.status, "IN_FLIGHT", "left for launch recovery")
    }

    // MARK: - Exactly once on the other paths

    func test_aFlushThatReturns_completesOnce_evenWhenTheExpiryComesLate() async throws {
        let manager = try makeManager(appActive: true)
        HeldRequestProtocol.answerNewRequests(with: 200)
        _ = try await storage.enqueue(input: .sample())
        let task = FakeBackgroundTask()

        manager.handleProcessing(task: task)
        await emitter.wait(for: "BACKGROUND_SYNC_COMPLETED", in: self)
        task.expirationHandler?()

        XCTAssertEqual(task.completedResults, [true])
        XCTAssertEqual(emitter.events(ofType: "BACKGROUND_SYNC_COMPLETED").first?.metadata, ["kind": "processing"])
    }

    func test_aFlushThatThrows_completesOnce_evenWhenTheExpiryComesLate() async throws {
        struct Broken: Error {}
        let broken = CoreDataStack(load: {
            (NSPersistentContainer(name: "Broken", managedObjectModel: NSManagedObjectModel()), Broken())
        })
        let manager = try makeManager(appActive: true, stack: broken)
        let task = FakeBackgroundTask()

        manager.handleAppRefresh(task: task)
        await emitter.wait(for: "BACKGROUND_SYNC_COMPLETED", in: self)
        task.expirationHandler?()

        XCTAssertEqual(task.completedResults, [false])
        XCTAssertEqual(emitter.events(ofType: "BACKGROUND_SYNC_COMPLETED").first?.metadata,
                       ["kind": "refresh", "ok": "false"])
        XCTAssertEqual(emitter.events(ofType: "SYNC_FAILED").first?.errorCode, "NETWORK_ERROR",
                       "a cycle that dies of a cause other than a SyncProviderError")
    }

    // MARK: - Letting go of the task

    /// A task holds its expiration handler, and the handler holds what
    /// completes the task. A `BGTask` drops its handler when it completes;
    /// this fake never does, as a `BGTask` never drops a handler set on it
    /// after it completed.
    func test_aWindowThatCompleted_letsGoOfItsTask() async throws {
        let manager = try makeManager(appActive: true)
        weak var released: FakeBackgroundTask?
        do {
            let task = FakeBackgroundTask()
            released = task
            let work = try XCTUnwrap(manager.handleProcessing(task: task))
            await work.value
            XCTAssertEqual(task.completedResults, [true])
            XCTAssertNotNil(task.expirationHandler, "the fake keeps its handler")
        }
        await assertFreed { released }
    }

    func test_aWindowThatExpired_letsGoOfItsTask() async throws {
        let manager = try makeManager(appActive: true)
        let gate = Gate()
        manager.setPrepare { await gate.wait() }
        weak var released: FakeBackgroundTask?
        do {
            let task = FakeBackgroundTask()
            released = task
            let work = try XCTUnwrap(manager.handleProcessing(task: task))
            task.expirationHandler?()
            gate.open()
            await work.value
            XCTAssertEqual(task.completedResults, [false])
        }
        await assertFreed { released }
    }

    /// Waits up to 2 s for `object()` to be `nil`: a finished `Task` frees
    /// what its closure captured shortly after `value` returns.
    private func assertFreed(_ object: () -> AnyObject?,
                             file: StaticString = #filePath, line: UInt = #line) async {
        var waits = 0
        while object() != nil, waits < 200 {
            try? await Task.sleep(nanoseconds: 10_000_000)
            waits += 1
        }
        XCTAssertNil(object(), "the window's task is still held after it completed", file: file, line: line)
    }

    // MARK: - Routing

    /// A task identifier is unique only within one session: a task restored
    /// from an earlier process can carry the identifier of the upload being
    /// waited on. Its completion, a 200 here, must not end that wait, nor be
    /// recorded for the waiting item while that item's own request is out.
    func test_aCompletionForAnotherTaskWithTheSameIdentifier_doesNotEndTheWait() async throws {
        _ = try makeManager(appActive: false)
        let id = try await storage.enqueue(input: .sample(url: "https://api.test/waiting"))
        let started = expectation(description: "the waiting item's upload is out")
        HeldRequestProtocol.expectNextStart(started)
        let flush = Task { try await self.dispatcher.flush() }
        await fulfillment(of: [started], timeout: livenessTimeout)
        let tasks = await backgroundSession.allTasks
        let waiting = try XCTUnwrap(tasks.first { $0.taskDescription == id })

        let other = try await completedTask(withIdentifier: waiting.taskIdentifier, status: 200)
        other.taskDescription = "ANOTHER-ITEM"
        delegate.urlSession(backgroundSession, task: other, didCompleteWithError: nil)
        flush.cancel()

        guard case .failure(let error) = await flush.result else {
            return XCTFail("the other task's 200 ended the wait and was recorded for \(id)")
        }
        XCTAssertTrue(error is CancellationError, "\(error)")
        XCTAssertEqual(emitter.events(ofType: "ITEM_SUCCEEDED").count, 0)
        let stored = try await row(id)
        XCTAssertEqual(stored?.status, "PENDING", "released by the cancellation, still to send")
    }

    /// A task of another session, with `identifier` and a real `status`
    /// response: what a restored task's completion carries.
    private func completedTask(withIdentifier identifier: Int, status: Int) async throws -> URLSessionTask {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [AnswerAtOnceProtocol.self]
        AnswerAtOnceProtocol.status = status
        let finished = expectation(description: "the other task completed")
        let signal = CompletionSignal(finished)
        let session = URLSession(configuration: configuration, delegate: signal, delegateQueue: nil)
        let body = FileManager.default.temporaryDirectory.appendingPathComponent("routing-\(UUID().uuidString).body")
        try Data("{}".utf8).write(to: body)
        let request = URLRequest(url: try XCTUnwrap(URL(string: "https://api.test/other")))
        var spare: [URLSessionTask] = []
        var task = session.uploadTask(with: request, fromFile: body)
        while task.taskIdentifier < identifier {
            spare.append(task)
            task = session.uploadTask(with: request, fromFile: body)
        }
        try XCTSkipUnless(task.taskIdentifier == identifier, "no identifier collision could be built")
        task.resume()
        await fulfillment(of: [finished], timeout: livenessTimeout)
        withExtendedLifetime(spare) {}
        try? FileManager.default.removeItem(at: body)
        XCTAssertEqual((task.response as? HTTPURLResponse)?.statusCode, status)
        return task
    }
}

// MARK: - Support

/// Answers every request at once with ``status``.
private final class AnswerAtOnceProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var answer = 200

    static var status: Int {
        get { lock.withLock { answer } }
        set { lock.withLock { answer = newValue } }
    }

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let url = request.url,
              let response = HTTPURLResponse(url: url, statusCode: Self.status, httpVersion: "HTTP/1.1",
                                             headerFields: nil) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data())
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// Fulfills an expectation when its session's task completes.
private final class CompletionSignal: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let expectation: XCTestExpectation

    init(_ expectation: XCTestExpectation) {
        self.expectation = expectation
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        expectation.fulfill()
    }
}

/// Holds every request until it is cancelled, or answers new ones at once
/// with a status when told to. A held request is never answered later: the
/// tests only ever cancel one.
private final class HeldRequestProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var answer: Int?
    private static var nextStart: XCTestExpectation?
    private static var started = 0

    static var startedCount: Int {
        lock.withLock { started }
    }

    static func reset() {
        lock.withLock {
            answer = nil
            nextStart = nil
            started = 0
        }
    }

    /// Answer every request that starts from now on with `status`.
    static func answerNewRequests(with status: Int?) {
        lock.withLock { answer = status }
    }

    /// Fulfill `expectation` when the next request starts.
    static func expectNextStart(_ expectation: XCTestExpectation) {
        lock.withLock { nextStart = expectation }
    }

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        let (status, expectation) = Self.lock.withLock { () -> (Int?, XCTestExpectation?) in
            Self.started += 1
            defer { Self.nextStart = nil }
            return (Self.answer, Self.nextStart)
        }
        if let status, let url = request.url,
           let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil) {
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data())
            client?.urlProtocolDidFinishLoading(self)
        }
        expectation?.fulfill()
    }

    override func stopLoading() {}
}

/// Records every event, and lets a test await one of a type.
private final class AwaitingEmitter: SyncEventEmitter, @unchecked Sendable {
    private let lock = NSLock()
    private var recorded: [SyncEventPayload] = []
    private var waiters: [(type: String, expectation: XCTestExpectation)] = []
    private var hooks: [(type: String, body: () -> Void)] = []

    override func emit(_ payload: SyncEventPayload) {
        let (due, bodies) = lock.withLock { () -> ([XCTestExpectation], [() -> Void]) in
            recorded.append(payload)
            let matching = waiters.filter { $0.type == payload.type }.map(\.expectation)
            waiters.removeAll { $0.type == payload.type }
            let hooked = hooks.filter { $0.type == payload.type }.map(\.body)
            hooks.removeAll { $0.type == payload.type }
            return (matching, hooked)
        }
        due.forEach { $0.fulfill() }
        bodies.forEach { $0() }
        super.emit(payload)
    }

    /// Runs `body` once, on the emitting thread, right after the next event
    /// of `type` is recorded.
    func onNext(_ type: String, _ body: @escaping () -> Void) {
        lock.withLock { hooks.append((type, body)) }
    }

    var emittedEvents: [SyncEventPayload] {
        lock.withLock { recorded }
    }

    func events(ofType type: String) -> [SyncEventPayload] {
        emittedEvents.filter { $0.type == type }
    }

    func clear() {
        lock.withLock { recorded.removeAll() }
    }

    /// Returns once an event of `type` was emitted, failing the test instead
    /// of hanging when none comes within ``livenessTimeout``.
    func wait(for type: String, in test: XCTestCase) async {
        let expectation = test.expectation(description: "\(type) is emitted")
        let already = lock.withLock { () -> Bool in
            if recorded.contains(where: { $0.type == type }) { return true }
            waiters.append((type, expectation))
            return false
        }
        if already {
            expectation.fulfill()
        }
        await test.fulfillment(of: [expectation], timeout: livenessTimeout)
    }
}

/// Holds whoever waits until it is opened.
private final class Gate: @unchecked Sendable {
    private let lock = NSLock()
    private var isOpen = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func wait() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            let open = lock.withLock { () -> Bool in
                if isOpen { return true }
                waiters.append(continuation)
                return false
            }
            if open {
                continuation.resume()
            }
        }
    }

    func open() {
        let released = lock.withLock { () -> [CheckedContinuation<Void, Never>] in
            isOpen = true
            defer { waiters.removeAll() }
            return waiters
        }
        released.forEach { $0.resume() }
    }
}

/// The wait of one background upload ends exactly once, whichever of its
/// completion and a cancellation comes first. A second resume of its checked
/// continuation would trap and crash the test process.
final class BackgroundUploadWaitTests: XCTestCase {
    private let outcome = BackgroundDispatchOutcome(itemId: "X", statusCode: 200, error: nil)

    /// Runs `body` with the wait installed, and returns what it resumed with.
    private func awaitWait(_ wait: BackgroundUploadWait,
                           after body: @escaping @Sendable () -> Void) async -> (installed: Bool, result: Int?) {
        var installed = false
        let result = await withCheckedContinuation { (continuation: CheckedContinuation<BackgroundDispatchOutcome?, Never>) in
            installed = wait.install(continuation)
            body()
        }
        return (installed, result?.statusCode)
    }

    func test_theCompletionFirst_thenTheCancellation_resumesOnceWithTheCompletion() async {
        let wait = BackgroundUploadWait()

        let (installed, status) = await awaitWait(wait) { [outcome] in
            wait.finish(outcome)
            wait.cancel()
        }

        XCTAssertTrue(installed)
        XCTAssertEqual(status, 200)
    }

    func test_theCancellationFirst_thenTheCompletion_resumesOnceWithNothing() async {
        let wait = BackgroundUploadWait()

        let (installed, status) = await awaitWait(wait) { [outcome] in
            wait.cancel()
            wait.finish(outcome)
        }

        XCTAssertTrue(installed)
        XCTAssertNil(status)
    }

    /// A task cancelled before the wait began: the upload must not start.
    func test_aCancellationBeforeTheWait_resumesAtOnce_andRefusesTheUpload() async {
        let wait = BackgroundUploadWait()
        wait.cancel()

        let (installed, status) = await awaitWait(wait) { [outcome] in
            wait.finish(outcome)
        }

        XCTAssertFalse(installed, "the upload must not start")
        XCTAssertNil(status)
    }

    /// Both at once, from two threads, many times.
    func test_aRaceBetweenTheTwo_resumesOnce() async {
        for _ in 0..<200 {
            let wait = BackgroundUploadWait()
            let outcome = self.outcome
            _ = await awaitWait(wait) {
                DispatchQueue.global().async { wait.finish(outcome) }
                DispatchQueue.global().async { wait.cancel() }
            }
        }
    }
}

/// Completions are routed by task, not by item: a cancelled upload's
/// completion can arrive after its item was sent again.
final class BackgroundURLSessionDelegateRoutingTests: XCTestCase {
    /// Identifiers are unique only within a session: two sessions both start
    /// at the same one.
    func test_anotherSessionsTaskWithTheSameIdentifier_doesNotReachTheHandler() throws {
        let delegate = BackgroundURLSessionDelegate()
        let waitingSession = URLSession(configuration: .ephemeral)
        let otherSession = URLSession(configuration: .ephemeral)
        defer {
            waitingSession.invalidateAndCancel()
            otherSession.invalidateAndCancel()
        }
        let body = FileManager.default.temporaryDirectory.appendingPathComponent("routing-\(UUID().uuidString).body")
        try Data("{}".utf8).write(to: body)
        defer { try? FileManager.default.removeItem(at: body) }
        let request = URLRequest(url: try XCTUnwrap(URL(string: "https://api.test/items")))
        let waiting = waitingSession.uploadTask(with: request, fromFile: body)
        waiting.taskDescription = "WAITING"
        let other = otherSession.uploadTask(with: request, fromFile: body)
        other.taskDescription = "OTHER"
        try XCTSkipUnless(other.taskIdentifier == waiting.taskIdentifier, "the sessions numbered their tasks apart")
        let calls = Calls()
        delegate.registerCompletion(for: waiting) { _ in calls.record() }

        delegate.urlSession(otherSession, task: other, didCompleteWithError: nil)
        XCTAssertEqual(calls.value, 0, "another session's task reached the handler")

        delegate.urlSession(waitingSession, task: waiting, didCompleteWithError: nil)
        XCTAssertEqual(calls.value, 1, "its own task still does")
    }

    private final class Calls: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        var value: Int { lock.withLock { count } }
        func record() { lock.withLock { count += 1 } }
    }

    func test_anOldTasksCompletion_doesNotReachTheNewTaskOfTheSameItem() throws {
        let delegate = BackgroundURLSessionDelegate()
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let body = FileManager.default.temporaryDirectory.appendingPathComponent("routing-\(UUID().uuidString).body")
        try Data("{}".utf8).write(to: body)
        defer { try? FileManager.default.removeItem(at: body) }
        let request = URLRequest(url: try XCTUnwrap(URL(string: "https://api.test/items")))
        let old = session.uploadTask(with: request, fromFile: body)
        old.taskDescription = "X"
        let new = session.uploadTask(with: request, fromFile: body)
        new.taskDescription = "X"
        let oldCalls = Calls()
        let newCalls = Calls()
        delegate.registerCompletion(for: old) { _ in oldCalls.record() }
        delegate.unregisterCompletion(for: old)
        delegate.registerCompletion(for: new) { _ in newCalls.record() }

        delegate.urlSession(session, task: old, didCompleteWithError: URLError(.cancelled))

        XCTAssertEqual(oldCalls.value, 0, "unregistered")
        XCTAssertEqual(newCalls.value, 0, "the old task's completion reached the new task")

        delegate.urlSession(session, task: new, didCompleteWithError: nil)
        delegate.urlSession(session, task: new, didCompleteWithError: nil)

        XCTAssertEqual(newCalls.value, 1, "called once, for its own task")
    }
}
