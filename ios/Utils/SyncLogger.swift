//
//  SyncLogger.swift
//  SyncProvider
//
//  Thin wrapper around `os.log` so the rest of the codebase has a single
//  call-site for logging. Debug logs are gated by `#if DEBUG`; errors and
//  faults are always emitted. Verbose mode is opt-in by the JS-side config.
//

import Foundation
import os.log

/// Centralized logger for the SyncProvider iOS implementation.
///
/// Keep all log calls funnelled through this enum so log subsystem and
/// category strings are consistent and easy to filter in Console.app.
enum SyncLogger {
    private static let subsystem = "com.gabriel-sisjr.syncprovider"

    /// Cached `OSLog` instances per category to avoid recreating them.
    private static let categories: [String: OSLog] = [
        "core-data": OSLog(subsystem: subsystem, category: "core-data"),
        "dispatcher": OSLog(subsystem: subsystem, category: "dispatcher"),
        "queue": OSLog(subsystem: subsystem, category: "queue"),
        "background": OSLog(subsystem: subsystem, category: "background"),
        "events": OSLog(subsystem: subsystem, category: "events"),
        "connectivity": OSLog(subsystem: subsystem, category: "connectivity"),
        "recovery": OSLog(subsystem: subsystem, category: "recovery"),
        "general": OSLog(subsystem: subsystem, category: "general")
    ]

    /// Toggle verbose mode at runtime. When `true`, debug-level messages are
    /// emitted regardless of `#if DEBUG`. Set by `configureSync` when the JS
    /// side requests it.
    static var verbose: Bool = false

    static func debug(_ message: @autoclosure () -> String, category: String = "general") {
        #if DEBUG
        let log = categories[category] ?? categories["general"]!
        os_log("%{public}@", log: log, type: .debug, message())
        #else
        if verbose {
            let log = categories[category] ?? categories["general"]!
            os_log("%{public}@", log: log, type: .debug, message())
        }
        #endif
    }

    static func info(_ message: @autoclosure () -> String, category: String = "general") {
        let log = categories[category] ?? categories["general"]!
        os_log("%{public}@", log: log, type: .info, message())
    }

    static func error(_ message: @autoclosure () -> String, category: String = "general") {
        let log = categories[category] ?? categories["general"]!
        os_log("%{public}@", log: log, type: .error, message())
    }

    static func fault(_ message: @autoclosure () -> String, category: String = "general") {
        let log = categories[category] ?? categories["general"]!
        os_log("%{public}@", log: log, type: .fault, message())
    }
}
