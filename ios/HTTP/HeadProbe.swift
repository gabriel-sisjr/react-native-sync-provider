//
//  HeadProbe.swift
//  SyncProvider
//
//  One HEAD request, sent on a URLSession of its own, reporting the final HTTP
//  status and how long the request took. Shared by the network-quality probe
//  and the health check. Internal: nothing here is part of the bridge.
//

import Foundation

/// What one HEAD probe observed.
enum HeadProbeResult: Sendable, Equatable {
    /// Why a probe got no HTTP response.
    enum FailureKind: String, Sendable, Equatable {
        /// No response within the probe's timeout.
        case timeout
        /// Any other transport failure: no route, DNS, refused connection,
        /// TLS, too many redirects, or the caller cancelling the probe.
        case network
        /// The URL cannot be probed: it is not an absolute http or https URL
        /// with a host. No request is sent for it.
        case invalidURL
    }

    /// The final HTTP response, after any redirects. Every status counts,
    /// 4xx and 5xx included: what a status means is up to the caller.
    /// `latencyMs` is whole milliseconds, truncated, spanning the whole task:
    /// connection setup and every redirect hop included.
    case response(statusCode: Int, latencyMs: Int)

    /// No HTTP response. `message` is for logs and never contains the URL,
    /// which may carry a secret in its query.
    case failure(kind: FailureKind, message: String)

    /// The final status, or `nil` when there was no response.
    var statusCode: Int? {
        guard case let .response(statusCode, _) = self else { return nil }
        return statusCode
    }

    /// The probe's latency, or `nil` when there was no response.
    var latencyMs: Int? {
        guard case let .response(_, latencyMs) = self else { return nil }
        return latencyMs
    }

    /// `true` for a final 2xx status: what the health check counts as healthy.
    var isHealthy: Bool {
        guard let statusCode else { return false }
        return (200..<300).contains(statusCode)
    }
}

/// Sends one HEAD request and reports what came back.
///
/// The seam callers are tested through: a fake can answer instantly, with no
/// URL loading at all.
protocol HeadProbing: Sendable {
    /// Sends one HEAD to `url`, giving up after `timeoutMs`.
    ///
    /// Never throws: every outcome, failures included, is a ``HeadProbeResult``.
    func head(_ url: URL, timeoutMs: Int) async -> HeadProbeResult
}

/// The production ``HeadProbing``: one HEAD per call, on a session of its own.
///
/// The session is never the dispatcher's. That one waits for connectivity, so
/// a probe sent through it while offline would hang until a link came back
/// instead of failing within its timeout. This one fails at once when there is
/// no route, never answers from a cache, stores no cookies or credentials, and
/// carries none of the library's default headers: the request is a bare HEAD.
///
/// Calls share the session, so sequential probes to one host can reuse its
/// connection. The first probe on a cold connection also pays for DNS, TCP and
/// TLS, which the later ones skip. Callers that probe the same host should
/// share one instance: two instances never share a connection.
///
/// Latency comes from `URLSessionTaskMetrics`, not from the process uptime
/// clocks, which are Required Reason APIs that would need a Privacy Manifest
/// entry.
final class HeadProbe: HeadProbing {
    /// The longest timeout a call can ask for; longer ones are cut to this.
    static let maxTimeoutMs = 60_000

    /// The probe's own session. Internal so tests can check the configuration
    /// it really runs with.
    let session: URLSession

    /// - Parameter protocolClasses: when set, replaces the session's URL
    ///   loading protocols. Tests pass a `URLProtocol` stub; production passes
    ///   nothing. The rest of the configuration cannot be changed.
    init(protocolClasses: [AnyClass]? = nil) {
        session = URLSession(configuration: Self.makeConfiguration(protocolClasses: protocolClasses))
    }

    deinit {
        session.finishTasksAndInvalidate()
    }

    /// The configuration every probe session runs with.
    static func makeConfiguration(protocolClasses: [AnyClass]? = nil) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        // Fail now when there is no route, instead of waiting for one.
        configuration.waitsForConnectivity = false
        // An answer comes from the network or not at all: there is no local
        // cache, and caches on the way are asked to go to the origin.
        configuration.urlCache = nil
        configuration.requestCachePolicy = cachePolicy
        // Nothing is stored, sent or answered on the app's behalf.
        configuration.httpCookieStorage = nil
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        configuration.httpAdditionalHeaders = nil
        // Metered and Low Data Mode links are graded by the caller. Refusing
        // them here would turn them into probe failures instead.
        configuration.allowsCellularAccess = true
        configuration.allowsExpensiveNetworkAccess = true
        configuration.allowsConstrainedNetworkAccess = true
        // Each request carries its own timeout. These only bound it, and the
        // resource timeout also bounds a task that follows redirects.
        let ceiling = TimeInterval(maxTimeoutMs) / 1000
        configuration.timeoutIntervalForRequest = ceiling
        configuration.timeoutIntervalForResource = ceiling
        if let protocolClasses {
            configuration.protocolClasses = protocolClasses
        }
        return configuration
    }

    // MARK: - Probing

    func head(_ url: URL, timeoutMs: Int) async -> HeadProbeResult {
        await headWithMetrics(url, timeoutMs: timeoutMs).result
    }

    /// ``head(_:timeoutMs:)``, plus the task metrics its latency was read from.
    ///
    /// `metrics` is `nil` when no request was sent, or in the unexpected case
    /// where the session delivered none before the request finished. Internal
    /// so tests can check where a latency came from.
    func headWithMetrics(_ url: URL,
                         timeoutMs: Int) async -> (result: HeadProbeResult, metrics: URLSessionTaskMetrics?) {
        guard Self.isProbeable(url) else {
            return (.failure(kind: .invalidURL,
                             message: "The probe needs an absolute http or https URL with a host"), nil)
        }
        guard !Task.isCancelled else {
            return (Self.cancelled, nil)
        }

        let timeout = Self.timeoutInterval(forMs: timeoutMs)
        let delegate = HeadProbeTaskDelegate(timeout: timeout)
        // Only used when the session delivers no metrics. Wall-clock time
        // needs no privacy declaration, unlike the uptime clocks.
        let sentAt = Date()

        do {
            let (_, response) = try await session.data(for: Self.makeRequest(url: url, timeout: timeout),
                                                       delegate: delegate)
            let metrics = delegate.metrics
            guard let http = response as? HTTPURLResponse else {
                return (.failure(kind: .network, message: "The probe got a response that is not HTTP"), metrics)
            }
            let latencyMs: Int
            if let metrics {
                latencyMs = Self.latencyMs(of: metrics.taskInterval.duration)
            } else {
                SyncLogger.debug("HEAD probe finished without task metrics; timing it with the wall clock",
                                 category: "connectivity")
                latencyMs = Self.latencyMs(of: Date().timeIntervalSince(sentAt))
            }
            return (.response(statusCode: http.statusCode, latencyMs: latencyMs), metrics)
        } catch {
            return (Self.failure(for: error, timeout: timeout), delegate.metrics)
        }
    }

    // MARK: - Helpers

    /// `true` for an absolute http or https URL with a host: the only URLs a
    /// probe is sent to, redirect targets included.
    static func isProbeable(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
            return false
        }
        guard let host = url.host, !host.isEmpty else {
            return false
        }
        return true
    }

    /// Never answer from a cache. Beyond skipping local data, this policy has
    /// the loader ask proxies and CDNs to go to the origin (current iOS sends
    /// `Cache-Control: no-cache` for it), so neither a probe's latency nor a
    /// health check can come from a cached copy.
    static let cachePolicy = URLRequest.CachePolicy.reloadIgnoringLocalAndRemoteCacheData

    /// A bare HEAD to `url`: no body, no cookies, no headers of ours.
    static func makeRequest(url: URL, timeout: TimeInterval) -> URLRequest {
        var request = URLRequest(url: url, cachePolicy: cachePolicy, timeoutInterval: timeout)
        request.httpMethod = "HEAD"
        request.httpShouldHandleCookies = false
        return request
    }

    /// What a redirect hop is followed with, given the request the loader
    /// proposes for it: a fresh HEAD to its URL, so nothing the loader copied
    /// or changed travels on, and a 303 does not turn into a GET that
    /// downloads a body. `nil` refuses the hop when its target could not have
    /// been probed directly, which makes the redirect the final response.
    static func redirectRequest(to proposed: URLRequest, timeout: TimeInterval) -> URLRequest? {
        guard let url = proposed.url, isProbeable(url) else { return nil }
        return makeRequest(url: url, timeout: timeout)
    }

    /// `timeoutMs` as a request timeout, kept between 1 ms and
    /// ``maxTimeoutMs``.
    static func timeoutInterval(forMs timeoutMs: Int) -> TimeInterval {
        TimeInterval(min(max(timeoutMs, 1), maxTimeoutMs)) / 1000
    }

    /// Whole milliseconds in `seconds`, truncated. Never negative.
    static func latencyMs(of seconds: TimeInterval) -> Int {
        guard seconds > 0 else { return 0 }
        return Int(min(seconds * 1000, Double(Int32.max)))
    }

    private static let cancelled = HeadProbeResult.failure(kind: .network, message: "The probe was cancelled")

    /// Maps what `URLSession` threw onto a failure. The message is built from
    /// the error alone, never from the URL.
    static func failure(for error: Error, timeout: TimeInterval) -> HeadProbeResult {
        if error is CancellationError {
            return cancelled
        }
        guard let urlError = error as? URLError else {
            return .failure(kind: .network, message: "The probe failed: \(error.localizedDescription)")
        }
        switch urlError.code {
        case .timedOut:
            return .failure(kind: .timeout,
                            message: "The probe got no response within \(Int((timeout * 1000).rounded())) ms")
        case .badURL, .unsupportedURL:
            return .failure(kind: .invalidURL,
                            message: "The probe URL cannot be loaded: \(urlError.localizedDescription)")
        case .cancelled:
            return cancelled
        default:
            return .failure(kind: .network, message: "The probe failed: \(urlError.localizedDescription)")
        }
    }
}

/// The per-task delegate of one probe. Keeps the task's metrics, and turns
/// every redirect hop into a HEAD built like the first request.
private final class HeadProbeTaskDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let timeout: TimeInterval
    private var collected: URLSessionTaskMetrics?

    init(timeout: TimeInterval) {
        self.timeout = timeout
    }

    /// The task's metrics, once the session has delivered them.
    var metrics: URLSessionTaskMetrics? {
        lock.withLock { collected }
    }

    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    didFinishCollecting metrics: URLSessionTaskMetrics) {
        lock.withLock { collected = metrics }
    }

    /// See ``HeadProbe/redirectRequest(to:timeout:)``.
    func urlSession(_ session: URLSession,
                    task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(HeadProbe.redirectRequest(to: request, timeout: timeout))
    }
}
