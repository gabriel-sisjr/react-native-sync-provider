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
/// this type by the dispatch helpers — JS receives a real `SyncError` with a
/// `code` matching `SyncErrorCode` and a localized message.
struct SyncProviderError: LocalizedError, CustomNSError {
    /// Stable error code understood by the JS facade.
    let code: SyncErrorCode

    /// Human-readable message. Forwarded to JS as the error description.
    let message: String

    /// Optional underlying error for debugging (not forwarded across bridge).
    let underlying: Error?

    init(code: SyncErrorCode, message: String, underlying: Error? = nil) {
        self.code = code
        self.message = message
        self.underlying = underlying
    }

    var errorDescription: String? {
        return message
    }

    // MARK: CustomNSError — controls how Nitro/JS sees the bridged NSError.

    static var errorDomain: String { "com.gabriel-sisjr.syncprovider" }

    var errorCode: Int {
        // Stable per-case integers. Values are not part of the public API —
        // consumers should use `SyncErrorCode` strings instead.
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
        @unknown default: return 0
        }
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
/// onto a `SyncProviderError` understood by the JS layer.
///
/// This is the single conversion point used by `SyncProvider.swift` — every
/// public bridge method runs its body inside a `do/catch` that funnels
/// failures through this helper.
@inline(__always)
func wrapAsSyncProviderError(_ error: Error) -> SyncProviderError {
    if let already = error as? SyncProviderError {
        return already
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
