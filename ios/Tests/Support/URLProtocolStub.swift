import Foundation

/// Closure that receives the intercepted `URLRequest` and returns either
/// a canned response tuple or throws to simulate a transport failure.
typealias URLProtocolHandler = (URLRequest) throws -> (HTTPURLResponse, Data)

/// `URLProtocol` subclass used by the dispatcher tests. Registers a single
/// global handler — set it before each test, clear it in `tearDown`.
final class URLProtocolStub: URLProtocol {
    private static let lock = NSLock()
    private static var _handler: URLProtocolHandler?
    private static var _capturedRequests: [URLRequest] = []

    /// Install a handler for the next batch of requests. Pass `nil` to clear.
    static func setHandler(_ handler: URLProtocolHandler?) {
        lock.lock()
        _handler = handler
        _capturedRequests.removeAll()
        lock.unlock()
    }

    /// All requests intercepted since the last `setHandler` call. Includes the
    /// HTTP body via `httpBodyStream` capture below.
    static var capturedRequests: [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return _capturedRequests
    }

    /// Helper for tests that need the body bytes — `URLSession` strips
    /// `httpBody` when it crosses into protocol space, but `httpBodyStream`
    /// is preserved.
    static func bodyData(for request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 1024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }

    // MARK: - URLProtocol

    override class func canInit(with request: URLRequest) -> Bool {
        return true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        return request
    }

    override func startLoading() {
        URLProtocolStub.lock.lock()
        URLProtocolStub._capturedRequests.append(request)
        let handler = URLProtocolStub._handler
        URLProtocolStub.lock.unlock()

        guard let handler = handler else {
            client?.urlProtocol(self,
                                didFailWithError: NSError(domain: "URLProtocolStub",
                                                          code: -1,
                                                          userInfo: [NSLocalizedDescriptionKey: "No handler installed"]))
            return
        }

        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

extension URLSession {
    /// Build a `URLSession` whose only protocol handler is `URLProtocolStub`.
    /// Used by dispatcher tests to redirect every `data(for:)` / `dataTask`
    /// call into the stub.
    static func stubbed(timeout: TimeInterval = 5) -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [URLProtocolStub.self]
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }
}
