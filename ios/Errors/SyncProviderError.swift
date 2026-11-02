//
//  SyncProviderError.swift
//  SyncProvider
//
//  Internal Swift error type used by the iOS implementation. Wraps a
//  `SyncErrorCode` (the Nitro enum) with a human-readable message so that we
//  can throw structured errors from any layer and have a single conversion
//  point at the bridge surface.
//

import Foundation

/// Errors thrown by the SyncProvider native layer.
///
/// All thrown errors that cross the JS bridge are converted to instances of
/// this type by `wrapAsSyncProviderError`. Nitro then reduces the error to a
/// single string, so `code` does not reach JS as a value. The facade's
/// `wrapNativeError` keeps that string as the message and assigns the
/// `SyncErrorCode` chosen at the call site, without parsing the string.
struct SyncProviderError: LocalizedError, CustomNSError {
    /// Stable error code. JS reads it from sync events, never from a thrown error.
    let code: SyncErrorCode

    /// Human-readable message, returned by `errorDescription`.
    let message: String

    /// Optional underlying error for debugging. JS gets no separate value for
    /// it, but its text is part of the string Nitro sends across the bridge.
    let underlying: Error?

    init(code: SyncErrorCode, message: String, underlying: Error? = nil) {
        self.code = code
        self.message = message
        self.underlying = underlying
    }

    var errorDescription: String? {
        return message
    }

    // MARK: CustomNSError — affects native `NSError` observers only, not Nitro or JS.

    static var errorDomain: String { "com.gabriel-sisjr.syncprovider" }

    var errorCode: Int {
        // Stable per-case integers, visible only to native `NSError` observers.
        // Values are not part of the public API.
        switch code {
        case .networkError: return 1
        case .serverError: return 2
        case .invalidPayload: return 3
        case .queueFull: return 4
        case .nativeModuleUnavailable: return 5
        case .backgroundTaskRegistrationFailed: return 6
        case .invalidUrl: return 7
        case .unauthorized: return 8
        case .timeout: return 9
        case .maxAttemptsExceeded: return 10
        case .duplicateItem: return 11
        case .drainAlreadyActive: return 12
        @unknown default: return 0
        }
    }

    /// `code` as its wire string (for example `"QUEUE_FULL"`).
    ///
    /// `SyncErrorCode` is a Nitro C++ enum, so `code` itself is invisible to
    /// modules without the generated bridge headers, the XCTest bundle among
    /// them. This is the Swift-only view of the same value.
    var codeName: String {
        code.stringValue
    }

    var errorUserInfo: [String: Any] {
        var info: [String: Any] = [
            NSLocalizedDescriptionKey: message,
            "code": code.stringValue
        ]
        if let underlying = underlying {
            info[NSUnderlyingErrorKey] = underlying as NSError
        }
        return info
    }

    // MARK: Convenience factories

    static func invalidPayload(_ reason: String) -> SyncProviderError {
        SyncProviderError(code: .invalidPayload, message: "Invalid payload: \(reason)")
    }

    static func invalidURL(_ value: String) -> SyncProviderError {
        SyncProviderError(code: .invalidUrl, message: "Invalid URL: \(value)")
    }

    static func queueFull(limit: Int) -> SyncProviderError {
        SyncProviderError(code: .queueFull, message: "Queue is full (limit \(limit) items)")
    }

    static func backgroundRegistrationFailed(_ reason: String) -> SyncProviderError {
        SyncProviderError(code: .backgroundTaskRegistrationFailed,
                          message: "Background task registration failed: \(reason)")
    }
}

/// Map an arbitrary Swift / NSError thrown anywhere in the iOS implementation
/// onto a `SyncProviderError`.
///
/// This is the single conversion point used by `SyncProvider.swift` — every
/// public bridge method runs its body inside a `do/catch` that funnels
/// failures through this helper.
@inline(__always)
func wrapAsSyncProviderError(_ error: Error) -> SyncProviderError {
    if let already = error as? SyncProviderError {
        return already
    }

    // The queue store could not be opened: no operation can succeed, so this
    // is "module unavailable", not a bad payload.
    if let storeError = error as? CoreDataStackError {
        return SyncProviderError(code: .nativeModuleUnavailable,
                                 message: storeError.localizedDescription,
                                 underlying: error)
    }

    if let urlError = error as? URLError {
        switch urlError.code {
        case .timedOut:
            return SyncProviderError(code: .timeout, message: "Request timed out", underlying: error)
        case .notConnectedToInternet, .networkConnectionLost,
             .dataNotAllowed, .internationalRoamingOff,
             .cannotConnectToHost, .cannotFindHost:
            return SyncProviderError(code: .networkError,
                                     message: "Network error: \(urlError.localizedDescription)",
                                     underlying: error)
        case .badURL, .unsupportedURL:
            return SyncProviderError(code: .invalidUrl,
                                     message: "Invalid URL: \(urlError.localizedDescription)",
                                     underlying: error)
        default:
            return SyncProviderError(code: .networkError,
                                     message: urlError.localizedDescription,
                                     underlying: error)
        }
    }

    return SyncProviderError(code: .invalidPayload,
                             message: error.localizedDescription,
                             underlying: error)
}
