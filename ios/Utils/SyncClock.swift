//
//  SyncClock.swift
//  SyncProvider
//
//  The clock behind retry cooldowns. It is injected so cooldown behavior can be
//  tested by moving time explicitly rather than by waiting for it to pass.
//

import Foundation

/// Wall-clock time in epoch milliseconds, the unit of `nextRetryAt`.
protocol SyncClock: Sendable {
    func nowMs() -> Int64
}

/// The device clock.
struct SystemClock: SyncClock {
    func nowMs() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1000).rounded())
    }
}
