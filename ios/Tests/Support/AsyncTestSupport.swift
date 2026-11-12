import Foundation
@testable import SyncProvider

/// How long a test waits for something that must happen, such as a promise
/// settling or a held call arriving, before it fails instead of hanging.
///
/// Every wait bounded by it returns as soon as the awaited thing happens, so
/// a passing run never pays for it; only a real hang waits this long. It is
/// generous because shared CI runners stall: on a GitHub macOS runner, one
/// thread took 3 s to print 15 consecutive log lines, and a test that takes
/// about 20 ms took 19 s, which a 2 s wait could not survive.
///
/// Use it for waits on something that must happen, not for checks that
/// something does not happen within a window.
let livenessTimeout: TimeInterval = 30

extension UserDefaults {
    /// A unique, empty domain. Call `removeSuite` when done with it.
    static func ephemeral() -> (defaults: UserDefaults, suite: String) {
        let suite = "syncprovider.tests.\(UUID().uuidString)"
        return (UserDefaults(suiteName: suite)!, suite)
    }
}

/// Canned HTTP responses for `URLProtocolStub`.
func stubResponse(_ status: Int) -> URLProtocolHandler {
    { request in
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        return (response, Data())
    }
}

/// Events collected from another thread, in order.
final class RequestLog: @unchecked Sendable {
    private let lock = NSLock()
    private var stamps: [Int64] = []

    func record(at timeMs: Int64 = 0) {
        lock.withLock { stamps.append(timeMs) }
    }

    var times: [Int64] {
        lock.withLock { stamps }
    }

    var gaps: [Int64] {
        let times = self.times
        return zip(times.dropFirst(), times).map { $0 - $1 }
    }
}
