//
//  CallbackPayloads.swift
//  SyncProvider
//
//  What native hands the JS callbacks and reads back from their answers, as
//  Sendable Swift values. `HybridSyncProvider` translates them to and from the
//  generated C++ structs, so the engine and the actors below it never touch
//  those, as `SyncOptionsSnapshot` already does for the options.
//

import Foundation

/// What the retry decider is asked about: an attempt that failed with a
/// retryable error while budget was left.
struct RetryDeciderRequest: Sendable, Equatable {
    let itemId: String
    /// The 1-based attempt that just failed, as on `ITEM_RETRYING`.
    let attempt: Int32
    /// That attempt's code: `SERVER_ERROR`, `TIMEOUT` or `NETWORK_ERROR`, say.
    let errorCode: String
    /// The HTTP status, when an answer came back.
    let statusCode: Int?
    let url: String
    let method: String
    let metadata: [String: String]?
}

/// What the header resolver is asked for.
struct HeaderResolverRequest: Sendable, Equatable {
    let itemId: String
    /// The 1-based attempt about to be made.
    let attempt: Int32
    let url: String
    let method: String
    let metadata: [String: String]?
}

/// What `beforeDispatch` sees of the request about to go out.
struct BeforeDispatchRequest: Sendable, Equatable {
    let itemId: String
    /// The 1-based attempt about to be made.
    let attempt: Int32
    let url: String
    let method: String
    /// The merged headers that will be sent: `defaultHeaders`, then the
    /// resolver's, then the item's. The `Content-Type` that `contentType`
    /// supplies when no layer sets one is not in it.
    let headers: [String: String]
    let contentType: String?
    let metadata: [String: String]?
}

/// How a request that went out ended, as `afterDispatch` reports it.
enum DispatchOutcomeKind: Sendable, Equatable {
    case succeeded
    case retrying
    case failed
}

/// What `afterDispatch` is told about one request that went out. Never its
/// headers or its body.
struct AfterDispatchReport: Sendable, Equatable {
    let itemId: String
    /// The 1-based attempt that was made.
    let attempt: Int32
    let outcome: DispatchOutcomeKind
    let statusCode: Int?
    let errorCode: String?
    let url: String
    let method: String
    let metadata: [String: String]?
    /// The request's own duration, in whole milliseconds.
    let durationMs: Int64
}

/// The retry decider's answer.
struct RetryDecisionValue: Sendable, Equatable {
    enum Action: Sendable, Equatable {
        case retry
        case drop
        case deadLetter
    }

    let action: Action
    /// As JS gave it. Only a `retry` reads it, and only a finite value of at
    /// least 0 replaces the policy delay.
    let delayMs: Double?
}

/// `beforeDispatch`'s answer.
enum BeforeDispatchActionValue: Sendable, Equatable {
    /// Send the request as it is.
    case proceed
    /// Send nothing now: the item is deferred.
    case skip
}

/// One answer to a callback request, as `answerCallback` received it.
struct CallbackReply: Sendable, Equatable {
    enum Status: Sendable, Equatable {
        case resolved
        case failed
    }

    let status: Status
    let decision: RetryDecisionValue?
    let headers: [String: String]?
    let beforeDispatch: BeforeDispatchActionValue?
    /// Why the callback failed, for the log.
    let message: String?

    init(status: Status,
         decision: RetryDecisionValue? = nil,
         headers: [String: String]? = nil,
         beforeDispatch: BeforeDispatchActionValue? = nil,
         message: String? = nil) {
        self.status = status
        self.decision = decision
        self.headers = headers
        self.beforeDispatch = beforeDispatch
        self.message = message
    }

    /// A FAILED answer.
    static func failed(_ message: String?) -> CallbackReply {
        CallbackReply(status: .failed, message: message)
    }
}

/// The characters a header the resolver supplies may hold, as OkHttp checks
/// them on Android: a name of `!` to `~` (0x21-0x7E), never empty, and a value
/// of TAB or of space to `~` (0x09, 0x20-0x7E). `defaultHeaders` and item
/// headers are not checked here: they keep the behavior they had.
enum HTTPHeaderRules {
    static func isValidName(_ name: String) -> Bool {
        !name.isEmpty && name.unicodeScalars.allSatisfy { (0x21...0x7E).contains($0.value) }
    }

    static func isValidValue(_ value: String) -> Bool {
        value.unicodeScalars.allSatisfy { $0.value == 0x09 || (0x20...0x7E).contains($0.value) }
    }
}
