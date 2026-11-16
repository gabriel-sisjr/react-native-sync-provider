//
//  NumericOption.swift
//  SyncProvider
//
//  The one conversion from a JS number to the integer a numeric option is
//  used as. JS validates none of these options, and `Int(_:)` traps on NaN,
//  on ±Infinity and on any value it cannot hold.
//

import Foundation

/// Converts the numbers of `SyncOptions`, `RetryPolicy` and `getSyncHistory`
/// to the integers they are used as, without trapping.
///
/// The options keep the numbers as the app passed them: that is what is
/// persisted and what `getSyncConfig()` echoes, as on Android. They are
/// converted here at every use, so a value saved by an earlier build that
/// could not convert it (`maxQueueSize: 2 ** 63` trapped every launch) now
/// starts the engine. For the inputs Android also accepts, the results are
/// Android's:
///
/// - A number is invalid when it is NaN or ±Infinity. A count, a timeout, a
///   status code and a history limit are also invalid when, truncated toward
///   zero, they fall outside the 32-bit range; a delay only at 2^63 or more,
///   where `Int(_:)` would trap.
/// - A valid number keeps the iOS behavior: fractions truncate, and the
///   iOS-only rules stay, such as a `maxDelayMs` of 0 or less meaning no cap
///   and no 1 s floor on the timeout.
enum NumericOption {
    /// The largest 32-bit integer: 2147483647.
    static let int32Max = Int(Int32.max)

    /// The ceiling of a delay in milliseconds: 2^53 - 1, the largest integer
    /// a JS number holds exactly. ``RetryPolicyEvaluator`` caps every delay
    /// it computes there too.
    static let maxDelayMs = 9_007_199_254_740_991

    /// 2^63, the first magnitude `Int(_:)` cannot hold.
    private static let twoTo63: Double = 9_223_372_036_854_775_808

    /// `value` truncated toward zero, or `nil` when it is not finite or the
    /// result does not fit 32 bits.
    static func int32(_ value: Double) -> Int? {
        guard value.isFinite else { return nil }
        let whole = value.rounded(.towardZero)
        guard whole >= Double(Int32.min), whole <= Double(Int32.max) else { return nil }
        return Int(whole)
    }

    /// `batchSize`, `maxQueueSize` and `retryPolicy.maxAttempts`: at least 1.
    /// NaN, -Infinity and anything below 1 give 1; +Infinity and 2^31 or more
    /// give 2147483647.
    static func count(_ value: Double) -> Int {
        if let whole = int32(value) {
            return max(1, whole)
        }
        return value > 0 ? int32Max : 1
    }

    /// `requestTimeoutMs`, in milliseconds. NaN, -Infinity and anything below
    /// -2^31 give 1000; +Infinity and 2^31 or more give 2147483647. Anything
    /// in between is used as it is, truncated: iOS has no 1 s floor.
    static func timeoutMs(_ value: Double) -> Int {
        if let whole = int32(value) {
            return whole
        }
        return value > 0 ? int32Max : 1_000
    }

    /// A retry delay in milliseconds (`baseDelayMs`, `maxDelayMs`). NaN,
    /// -Infinity and anything at or below -2^63 give 0; +Infinity and 2^63 or
    /// more give ``maxDelayMs``. Anything else is used as it is, truncated.
    static func delayMs(_ value: Double) -> Int {
        guard !value.isNaN else { return 0 }
        guard value > -twoTo63, value < twoTo63 else {
            return value > 0 ? maxDelayMs : 0
        }
        return Int(value.rounded(.towardZero))
    }

    /// `retryPolicy.retryOnStatusCodes`, truncated. An entry that is invalid
    /// is dropped: it could match no status.
    static func statusCodes(_ values: [Double]) -> [Int] {
        values.compactMap(int32)
    }

    /// The `getSyncHistory` limit, where 0 means every result. NaN,
    /// -Infinity and anything below 1 give 0; +Infinity and 2^31 or more give
    /// 2147483647.
    static func historyLimit(_ value: Double) -> Int {
        if let whole = int32(value) {
            return max(0, whole)
        }
        return value > 0 ? int32Max : 0
    }
}
