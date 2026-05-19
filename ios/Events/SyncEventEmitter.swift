//
//  SyncEventEmitter.swift
//  SyncProvider
//
//  Bridges Swift-side `SyncEvent` notifications back to JS via the Nitro
//  listener pattern (`addListener` returns an id, `removeListener(id)`
//  cancels). Internally maintains a thread-safe map of subscriber callbacks.
//

import Foundation

/// Channel name accepted by `addListener` / `removeListener`. Kept in sync
/// with the JS facade.
let kSyncEventChannel = "sync-event"

/// Shape used internally by everything that needs to emit events. Translated
/// to Nitro's `SyncEvent` struct at the boundary.
struct SyncEventPayload: Sendable {
    let type: String                 // SyncEventType raw string
    let timestamp: Double             // epoch ms
    let itemId: String?
    let progress: Double?
    let errorCode: String?            // SyncErrorCode raw string
    let statusCode: Double?
    let attempt: Double?
    let connectionStatus: String?     // ConnectionStatus raw string
    let metadata: [String: String]?

    static func now(type: String,
                    itemId: String? = nil,
                    progress: Double? = nil,
                    errorCode: String? = nil,
                    statusCode: Double? = nil,
                    attempt: Double? = nil,
                    connectionStatus: String? = nil,
                    metadata: [String: String]? = nil) -> SyncEventPayload {
        SyncEventPayload(
            type: type,
            timestamp: Date().timeIntervalSince1970 * 1000,
            itemId: itemId,
            progress: progress,
            errorCode: errorCode,
            statusCode: statusCode,
            attempt: attempt,
            connectionStatus: connectionStatus,
            metadata: metadata
        )
    }
}

/// Thread-safe broker between the Swift side and the JS-registered
/// listeners.
final class SyncEventEmitter: @unchecked Sendable {
    private let lock = NSLock()
    private var listeners: [String: [String: (SyncEventPayload) -> Void]] = [:]

    init() {}

    /// Register a JS callback for a channel. Returns a subscription id.
    /// - Throws: `SyncProviderError(.invalidPayload)` for unknown channels.
    func addListener(channel: String,
                     callback: @escaping (SyncEventPayload) -> Void) throws -> String {
        guard channel == kSyncEventChannel else {
            throw SyncProviderError(code: .invalidPayload,
                                    message: "Unknown event channel '\(channel)'. Use '\(kSyncEventChannel)'.")
        }
        let id = UUID().uuidString
        lock.withLock {
            var bucket = listeners[channel] ?? [:]
            bucket[id] = callback
            listeners[channel] = bucket
        }
        return id
    }

    /// Remove a previously registered listener. No-op if the id is unknown.
    func removeListener(channel: String, id: String) {
        lock.withLock {
            var bucket = listeners[channel] ?? [:]
            bucket.removeValue(forKey: id)
            if bucket.isEmpty {
                listeners.removeValue(forKey: channel)
            } else {
                listeners[channel] = bucket
            }
        }
    }

    /// Snapshot + invoke listeners on the main thread (consistent with React
    /// Native event delivery).
    func emit(_ payload: SyncEventPayload) {
        let snapshot = lock.withLock {
            listeners[kSyncEventChannel] ?? [:]
        }

        guard !snapshot.isEmpty else { return }

        let invoke = {
            for cb in snapshot.values {
                cb(payload)
            }
        }
        if Thread.isMainThread {
            invoke()
        } else {
            DispatchQueue.main.async(execute: invoke)
        }
    }

    /// Drop all listeners. Used during teardown / tests.
    func removeAll() {
        lock.withLock {
            listeners.removeAll()
        }
    }
}
