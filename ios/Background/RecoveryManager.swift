//
//  RecoveryManager.swift
//  SyncProvider
//
//  Resets every IN_FLIGHT row back to PENDING on app launch. Items can get
//  stuck in IN_FLIGHT if the app was suspended / killed mid-dispatch — when
//  the user (or the OS) brings the app back up we want them eligible for the
//  next flush cycle.
//

import Foundation

/// Lightweight wrapper that delegates to ``SyncQueueStorage.resetInFlightToPending()``.
struct RecoveryManager {
    let storage: SyncQueueStorage
    let emitter: SyncEventEmitter

    init(storage: SyncQueueStorage, emitter: SyncEventEmitter) {
        self.storage = storage
        self.emitter = emitter
    }

    /// Atomically reset every IN_FLIGHT row to PENDING. Logs the reset count
    /// for diagnostics.
    func recoverPendingItems() async {
        do {
            let count = try await storage.resetInFlightToPending()
            if count > 0 {
                SyncLogger.info("Recovery: reset \(count) IN_FLIGHT items to PENDING",
                                category: "recovery")
                emitter.emit(.now(type: "RESUMED",
                                  metadata: ["recovered": String(count)]))
            } else {
                SyncLogger.debug("Recovery: no IN_FLIGHT items found", category: "recovery")
            }
        } catch {
            SyncLogger.error("Recovery failed: \(error.localizedDescription)",
                             category: "recovery")
        }
    }
}
