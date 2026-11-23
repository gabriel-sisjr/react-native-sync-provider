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
        // The JS callbacks: never a header, a body or a context, and messages
        // from JS cut to `kCallbackLogMessageMaxLength` characters, as a
        // private part only.
        "callbacks": OSLog(subsystem: subsystem, category: "callbacks"),
        "general": OSLog(subsystem: subsystem, category: "general")
    ]

    /// Manual switch for verbose logging. When `true`, debug-level messages are
    /// emitted in release builds too. It defaults to `false` and nothing in the
    /// library sets it; `configureSync` has no option for it.
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

    /// Something went wrong outside the library, in a JS callback say, and
    /// the library carried on. The unified log's default level.
    static func warning(_ message: @autoclosure () -> String, category: String = "general") {
        let log = categories[category] ?? categories["general"]!
        os_log("%{public}@", log: log, type: .default, message())
    }

    /// ``info(_:category:)``, with `detail` as the line's private part.
    static func info(_ message: @autoclosure () -> String, private detail: String?, category: String = "general") {
        write(message(), private: detail, type: .info, category: category)
    }

    /// ``warning(_:category:)``, with `detail` as the line's private part.
    static func warning(_ message: @autoclosure () -> String, private detail: String?, category: String = "general") {
        write(message(), private: detail, type: .default, category: category)
    }

    static func error(_ message: @autoclosure () -> String, category: String = "general") {
        let log = categories[category] ?? categories["general"]!
        os_log("%{public}@", log: log, type: .error, message())
    }

    static func fault(_ message: @autoclosure () -> String, category: String = "general") {
        let log = categories[category] ?? categories["general"]!
        os_log("%{public}@", log: log, type: .fault, message())
    }

    /// One line: `message`, then `detail`, which the device log redacts as
    /// `<private>` unless private data is enabled for it. For text from JS,
    /// which can carry a user's data, an email or a token say.
    private static func write(_ message: String, private detail: String?, type: OSLogType, category: String) {
        let log = categories[category] ?? categories["general"]!
        if let detail {
            os_log("%{public}@ Message: %{private}@", log: log, type: type, message, detail)
        } else {
            os_log("%{public}@", log: log, type: type, message)
        }
    }
}
