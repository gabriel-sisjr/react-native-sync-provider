import XCTest
@testable import SyncProvider

/// What ``HeadProbeStub`` does with one request.
private enum HeadStubAction {
    /// Answers with `status`, after `afterMs`.
    case respond(status: Int, afterMs: Int = 0)
    /// Answers with a `status` redirect to `location`, resolved against the
    /// request URL, after `afterMs`.
    case redirect(to: String, status: Int, afterMs: Int = 0)
    /// Fails the load with `error`.
    case fail(Error)
    /// Never answers: only a timeout or a cancellation ends the load.
    case hang
}

/// A `URLProtocol` registered only on the probe sessions of these tests, so the
/// shared `URLProtocolStub` and its handler are never touched.
private final class HeadProbeStub: URLProtocol {
    typealias Handler = @Sendable (URLRequest) -> HeadStubAction

    private static let lock = NSLock()
    private static var handler: Handler?
    private static var captured: [URLRequest] = []

    private let stateLock = NSLock()
    private var stopped = false

    /// Installs `handler` for the next requests and forgets earlier ones.
    static func install(_ handler: Handler?) {
        lock.withLock {
            self.handler = handler
            captured.removeAll()
        }
    }

    /// Every request started since the last ``install(_:)``, one per hop.
    static var requests: [URLRequest] {
        lock.withLock { captured }
    }

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        let handler = Self.lock.withLock { () -> Handler? in
            Self.captured.append(request)
            return Self.handler
        }
        guard let handler, let url = request.url else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }

        switch handler(request) {
        case let .respond(status, afterMs):
            deliver(afterMs: afterMs) { $0.respond(url: url, status: status) }
        case let .redirect(location, status, afterMs):
            deliver(afterMs: afterMs) { $0.redirect(from: url, to: location, status: status) }
        case let .fail(error):
            client?.urlProtocol(self, didFailWithError: error)
        case .hang:
            break
        }
    }

    override func stopLoading() {
        stateLock.withLock { stopped = true }
    }

    /// Runs `step` now, or after `afterMs` on the thread that started the load,
    /// in the run loop mode it was running in: where a `URLProtocol` must talk
    /// to its client. A load stopped in the meantime gets nothing.
    private func deliver(afterMs: Int, _ step: @escaping (HeadProbeStub) -> Void) {
        guard afterMs > 0 else {
            step(self)
            return
        }
        let runLoop = CFRunLoopGetCurrent()
        let mode: CFString = CFRunLoopCopyCurrentMode(runLoop)?.rawValue ?? CFRunLoopMode.defaultMode.rawValue
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(afterMs)) { [weak self] in
            CFRunLoopPerformBlock(runLoop, mode) {
                guard let self, !self.stateLock.withLock({ self.stopped }) else { return }
                step(self)
            }
            CFRunLoopWakeUp(runLoop)
        }
    }

    private func respond(url: URL, status: Int) {
        guard let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                             headerFields: nil) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocolDidFinishLoading(self)
    }

    private func redirect(from url: URL, to location: String, status: Int) {
        guard let target = URL(string: location, relativeTo: url)?.absoluteURL,
              let response = HTTPURLResponse(url: url,
                                             statusCode: status,
                                             httpVersion: "HTTP/1.1",
                                             headerFields: ["Location": target.absoluteString]) else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        // The loader asks the task's delegate, stops this load, and starts a
        // new one, through this stub again, for the request the delegate chose.
        client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: target), redirectResponse: response)
    }
}

/// The HEAD probe, driven through a `URLProtocol` stub.
///
/// What the stub yields: `URLSession` still collects `URLSessionTaskMetrics`
/// for a stubbed load, and delivers them to the per-task delegate before
/// `data(for:delegate:)` returns, so every `.response` here is timed from
/// metrics. `taskInterval` runs from task creation to completion, so a delayed
/// answer shows up in it in full, and so does every redirect hop.
/// `redirectCount` counts the hops followed. `transactionMetrics` has one
/// entry per hop, but under a stub it is of no use for timing: the fetch type
/// is `.unknown`, `response` is `nil`, and only `fetchStartDate` is set.
final class HeadProbeTests: XCTestCase {
    private var probe: HeadProbe!

    /// The query stands in for a secret a consumer's URL may carry.
    private let healthURL = URL(string: "https://probe.test/health?token=secret-token")!

    override func setUp() {
        super.setUp()
        HeadProbeStub.install(nil)
        probe = HeadProbe(protocolClasses: [HeadProbeStub.self])
    }

    override func tearDown() {
        probe = nil
        HeadProbeStub.install(nil)
        super.tearDown()
    }

    // MARK: - Session

    func test_session_failsFastAndKeepsNothing() {
        assertHardened(HeadProbe.makeConfiguration(), "built configuration")
        assertHardened(probe.session.configuration, "stubbed session")
        assertHardened(HeadProbe().session.configuration, "production session")
        XCTAssertNil(HeadProbe().session.configuration.protocolClasses?.first { $0 == HeadProbeStub.self },
                     "Production must keep the system loaders")
    }

    private func assertHardened(_ configuration: URLSessionConfiguration,
                                _ label: String,
                                file: StaticString = #filePath,
                                line: UInt = #line) {
        XCTAssertNil(configuration.identifier, "\(label): must not be a background session", file: file, line: line)
        XCTAssertFalse(configuration.waitsForConnectivity, "\(label): must fail fast", file: file, line: line)
        XCTAssertNil(configuration.urlCache, label, file: file, line: line)
        XCTAssertEqual(configuration.requestCachePolicy, .reloadIgnoringLocalAndRemoteCacheData,
                       label, file: file, line: line)
        XCTAssertNil(configuration.httpCookieStorage, label, file: file, line: line)
        XCTAssertFalse(configuration.httpShouldSetCookies, label, file: file, line: line)
        XCTAssertEqual(configuration.httpCookieAcceptPolicy, .never, label, file: file, line: line)
        XCTAssertNil(configuration.urlCredentialStorage, label, file: file, line: line)
        XCTAssertTrue(configuration.httpAdditionalHeaders?.isEmpty ?? true, label, file: file, line: line)
        XCTAssertTrue(configuration.allowsExpensiveNetworkAccess, label, file: file, line: line)
        XCTAssertTrue(configuration.allowsConstrainedNetworkAccess, label, file: file, line: line)
    }

    // MARK: - Request

    func test_request_isABareHeadWithTheProbeTimeout() async throws {
        // A cookie the app holds for the host must not travel with the probe.
        let cookie = try XCTUnwrap(HTTPCookie(properties: [.domain: "probe.test",
                                                           .path: "/",
                                                           .name: "session",
                                                           .value: "app-cookie"]))
        HTTPCookieStorage.shared.setCookie(cookie)
        defer { HTTPCookieStorage.shared.deleteCookie(cookie) }
        HeadProbeStub.install { _ in .respond(status: 204) }

        _ = await probe.head(healthURL, timeoutMs: 1_500)

        let request = try XCTUnwrap(HeadProbeStub.requests.first)
        XCTAssertEqual(HeadProbeStub.requests.count, 1)
        XCTAssertEqual(request.url, healthURL)
        XCTAssertEqual(request.httpMethod, "HEAD")
        XCTAssertNil(request.httpBody)
        XCTAssertNil(request.httpBodyStream)
        XCTAssertEqual(request.timeoutInterval, 1.5, accuracy: 0.000_1)
        XCTAssertEqual(request.cachePolicy, .reloadIgnoringLocalAndRemoteCacheData)
        XCTAssertFalse(request.httpShouldHandleCookies)
        XCTAssertEqual(request.allHTTPHeaderFields ?? [:], [:], "No auth, default or cookie headers")
    }

    func test_timeout_isClampedToAUsableRange() {
        XCTAssertEqual(HeadProbe.timeoutInterval(forMs: 2_000), 2.0)
        XCTAssertEqual(HeadProbe.timeoutInterval(forMs: 0), 0.001)
        XCTAssertEqual(HeadProbe.timeoutInterval(forMs: -5), 0.001)
        XCTAssertEqual(HeadProbe.timeoutInterval(forMs: 10_000_000), TimeInterval(HeadProbe.maxTimeoutMs) / 1000)
    }

    // MARK: - Responses

    func test_anyStatus_isAResponse() async throws {
        for status in [200, 204, 304, 401, 404, 429, 500, 503] {
            HeadProbeStub.install { _ in .respond(status: status) }

            let (result, metrics) = await probe.headWithMetrics(healthURL, timeoutMs: 2_000)

            guard case let .response(statusCode, latencyMs) = result else {
                return XCTFail("HTTP \(status) must be a response, got \(result)")
            }
            XCTAssertEqual(statusCode, status)
            XCTAssertGreaterThanOrEqual(latencyMs, 0)
            XCTAssertNotNil(metrics, "HTTP \(status): latency must come from task metrics")
            XCTAssertEqual(result.isHealthy, (200..<300).contains(status), "HTTP \(status)")
            XCTAssertEqual(result.statusCode, status)
            XCTAssertEqual(result.latencyMs, latencyMs)
        }
    }

    func test_latency_isTheTaskIntervalFromMetrics() async throws {
        HeadProbeStub.install { _ in .respond(status: 200, afterMs: 250) }

        let calledAt = Date()
        let (result, metrics) = await probe.headWithMetrics(healthURL, timeoutMs: 2_000)
        let wallMs = Date().timeIntervalSince(calledAt) * 1000

        let taskMetrics = try XCTUnwrap(metrics, "The stub still yields task metrics")
        let latencyMs = try XCTUnwrap(result.latencyMs)
        XCTAssertEqual(latencyMs, HeadProbe.latencyMs(of: taskMetrics.taskInterval.duration))
        XCTAssertGreaterThanOrEqual(latencyMs, 250, "The stub's delay is inside the task interval")
        XCTAssertLessThanOrEqual(Double(latencyMs), wallMs + 1, "The task interval is inside the call")
    }

    func test_latency_isWholeMillisecondsAndNeverNegative() {
        XCTAssertEqual(HeadProbe.latencyMs(of: 0.199_9), 199)
        XCTAssertEqual(HeadProbe.latencyMs(of: 0.001_5), 1)
        XCTAssertEqual(HeadProbe.latencyMs(of: 0), 0)
        XCTAssertEqual(HeadProbe.latencyMs(of: -0.5), 0)
        XCTAssertEqual(HeadProbe.latencyMs(of: .nan), 0)
        XCTAssertEqual(HeadProbe.latencyMs(of: .infinity), Int(Int32.max))
    }

    func test_concurrentProbes_eachGetTheirOwnAnswer() async throws {
        HeadProbeStub.install { request in
            request.url?.path == "/slow" ? .respond(status: 503, afterMs: 150) : .respond(status: 200)
        }
        let probe = try XCTUnwrap(probe)

        async let slow = probe.head(URL(string: "https://probe.test/slow")!, timeoutMs: 2_000)
        async let fast = probe.head(URL(string: "https://probe.test/fast")!, timeoutMs: 2_000)
        let (slowResult, fastResult) = await (slow, fast)

        XCTAssertEqual(slowResult.statusCode, 503)
        XCTAssertEqual(fastResult.statusCode, 200)
        XCTAssertGreaterThanOrEqual(try XCTUnwrap(slowResult.latencyMs), 150)
    }

    // MARK: - Redirects

    func test_redirect_isFollowedAsAHead() async throws {
        for status in [301, 302, 303, 307, 308] {
            HeadProbeStub.install { request in
                request.url?.path == "/old" ? .redirect(to: "/health", status: status) : .respond(status: 200)
            }

            let (result, metrics) = await probe.headWithMetrics(URL(string: "https://probe.test/old")!,
                                                                timeoutMs: 1_000)

            XCTAssertEqual(result.statusCode, 200, "A \(status) redirect must be followed")
            let requests = HeadProbeStub.requests
            XCTAssertEqual(requests.map { $0.url?.path }, ["/old", "/health"], "HTTP \(status)")
            XCTAssertEqual(requests.map(\.httpMethod), ["HEAD", "HEAD"], "HTTP \(status): every hop is a HEAD")
            XCTAssertEqual(requests.last?.timeoutInterval ?? 0, 1.0, accuracy: 0.000_1, "HTTP \(status)")
            XCTAssertEqual(requests.last?.allHTTPHeaderFields ?? [:], [:], "HTTP \(status)")
            XCTAssertEqual(metrics?.redirectCount, 1, "HTTP \(status)")
        }
    }

    func test_redirectChain_isFollowedToTheEndAndTimedWhole() async throws {
        HeadProbeStub.install { request in
            switch request.url?.path {
            case "/a": return .redirect(to: "https://other.test/b", status: 302, afterMs: 100)
            case "/b": return .redirect(to: "/c", status: 307)
            default: return .respond(status: 503, afterMs: 100)
            }
        }

        let (result, metrics) = await probe.headWithMetrics(URL(string: "https://probe.test/a")!, timeoutMs: 1_000)

        XCTAssertEqual(result.statusCode, 503, "The final hop's status is the result")
        XCTAssertEqual(HeadProbeStub.requests.map { $0.url?.absoluteString },
                       ["https://probe.test/a", "https://other.test/b", "https://other.test/c"])
        XCTAssertEqual(metrics?.redirectCount, 2)
        XCTAssertGreaterThanOrEqual(try XCTUnwrap(result.latencyMs), 200, "Latency spans the first and the last hop")
    }

    func test_redirectHop_isRebuiltAsABareHead() throws {
        // What the loader may propose for a 303: a GET that kept the old headers.
        var proposed = URLRequest(url: URL(string: "https://other.test/health")!)
        proposed.httpMethod = "GET"
        proposed.setValue("Bearer leaked", forHTTPHeaderField: "Authorization")
        proposed.httpBody = Data("{}".utf8)

        let hop = try XCTUnwrap(HeadProbe.redirectRequest(to: proposed, timeout: 0.75))

        XCTAssertEqual(hop.url, proposed.url)
        XCTAssertEqual(hop.httpMethod, "HEAD")
        XCTAssertEqual(hop.allHTTPHeaderFields ?? [:], [:])
        XCTAssertNil(hop.httpBody)
        XCTAssertEqual(hop.timeoutInterval, 0.75)
        XCTAssertEqual(hop.cachePolicy, .reloadIgnoringLocalAndRemoteCacheData)
        XCTAssertFalse(hop.httpShouldHandleCookies)
    }

    /// A refused hop makes the redirect the final response, as `URLSession`
    /// documents. A `URLProtocol` cannot show that end to end: after a refusal
    /// the loader waits for the protocol to finish the redirect response itself,
    /// so the stubbed probe would only time out. The decision is checked here.
    func test_redirectToATargetThatCannotBeProbed_isRefused() throws {
        for target in ["ftp://probe.test/health", "file:///tmp/health", "mailto:ops@probe.test", "https:///health"] {
            let proposed = URLRequest(url: try XCTUnwrap(URL(string: target), target))

            XCTAssertNil(HeadProbe.redirectRequest(to: proposed, timeout: 1), target)
        }
        XCTAssertNotNil(HeadProbe.redirectRequest(to: URLRequest(url: URL(string: "http://probe.test/h")!), timeout: 1))
    }

    // MARK: - Failures

    func test_serverThatNeverAnswers_timesOutWithinTheProbeTimeout() async throws {
        HeadProbeStub.install { _ in .hang }

        let calledAt = Date()
        let result = await probe.head(healthURL, timeoutMs: 400)
        let elapsed = Date().timeIntervalSince(calledAt)

        guard case let .failure(kind, message) = result else {
            return XCTFail("Expected a timeout, got \(result)")
        }
        XCTAssertEqual(kind, .timeout)
        XCTAssertFalse(message.contains("secret-token"), "The message must not carry the URL")
        XCTAssertGreaterThanOrEqual(elapsed, 0.35, "Must wait for the probe timeout")
        XCTAssertLessThan(elapsed, 3, "Must not wait for the session's ceiling")
    }

    func test_timedOutError_isATimeout() async throws {
        HeadProbeStub.install { _ in .fail(URLError(.timedOut)) }

        let result = await probe.head(healthURL, timeoutMs: 2_000)

        XCTAssertEqual(result, .failure(kind: .timeout, message: "The probe got no response within 2000 ms"))
    }

    func test_transportErrors_areNetworkFailures() async throws {
        let errors: [Error] = [
            URLError(.notConnectedToInternet),
            URLError(.networkConnectionLost),
            URLError(.cannotFindHost),
            URLError(.cannotConnectToHost),
            URLError(.dnsLookupFailed),
            URLError(.secureConnectionFailed),
            URLError(.serverCertificateUntrusted),
            URLError(.httpTooManyRedirects),
            URLError(.appTransportSecurityRequiresSecureConnection),
            NSError(domain: "HeadProbeTests", code: 7, userInfo: [NSLocalizedDescriptionKey: "boom"]),
        ]
        for error in errors {
            HeadProbeStub.install { _ in .fail(error) }

            let result = await probe.head(healthURL, timeoutMs: 2_000)

            guard case let .failure(kind, message) = result else {
                return XCTFail("\(error) must be a failure, got \(result)")
            }
            XCTAssertEqual(kind, .network, "\(error)")
            XCTAssertFalse(message.isEmpty, "\(error)")
            XCTAssertFalse(message.contains("secret-token"), "\(error): the message must not carry the URL")
            XCTAssertNil(result.latencyMs)
            XCTAssertFalse(result.isHealthy)
        }
    }

    func test_urlThatCannotBeProbed_isRefusedWithoutARequest() async throws {
        HeadProbeStub.install { _ in .respond(status: 200) }
        let urls = ["ftp://probe.test/health",
                    "file:///tmp/health",
                    "mailto:ops@probe.test",
                    "data:text/plain,ok",
                    "health",
                    "https:///health"]

        for string in urls {
            let url = try XCTUnwrap(URL(string: string), string)

            let result = await probe.head(url, timeoutMs: 2_000)

            guard case let .failure(kind, _) = result else {
                return XCTFail("\(string) must be refused, got \(result)")
            }
            XCTAssertEqual(kind, .invalidURL, string)
        }
        XCTAssertTrue(HeadProbeStub.requests.isEmpty, "Nothing may be sent for an unusable URL")
    }

    func test_unloadableURLErrors_areInvalidURL() async throws {
        for code in [URLError.Code.badURL, .unsupportedURL] {
            HeadProbeStub.install { _ in .fail(URLError(code)) }

            let result = await probe.head(healthURL, timeoutMs: 2_000)

            guard case let .failure(kind, _) = result else {
                return XCTFail("\(code) must be a failure, got \(result)")
            }
            XCTAssertEqual(kind, .invalidURL, "\(code)")
        }
    }

    // MARK: - Cancellation

    func test_probeCancelledBeforeItStarts_sendsNothing() async throws {
        HeadProbeStub.install { _ in .respond(status: 200) }
        let probe = try XCTUnwrap(probe)
        let url = healthURL

        let task = Task { () -> HeadProbeResult in
            withUnsafeCurrentTask { $0?.cancel() }
            return await probe.head(url, timeoutMs: 2_000)
        }
        let result = await task.value

        XCTAssertEqual(result, .failure(kind: .network, message: "The probe was cancelled"))
        XCTAssertTrue(HeadProbeStub.requests.isEmpty)
    }

    func test_probeCancelledInFlight_isANetworkFailure() async throws {
        HeadProbeStub.install { _ in .hang }
        let probe = try XCTUnwrap(probe)
        let url = healthURL

        let task = Task { await probe.head(url, timeoutMs: 10_000) }
        let deadline = Date().addingTimeInterval(5)
        while HeadProbeStub.requests.isEmpty, Date() < deadline {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(HeadProbeStub.requests.count, 1, "The probe must be in flight before it is cancelled")
        let cancelledAt = Date()
        task.cancel()
        let result = await task.value

        XCTAssertEqual(result, .failure(kind: .network, message: "The probe was cancelled"))
        XCTAssertLessThan(Date().timeIntervalSince(cancelledAt), 3, "Cancelling must end the request")
    }
}
