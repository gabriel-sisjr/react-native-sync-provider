import Foundation
@testable import SyncProvider

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
