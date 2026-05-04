import Foundation
@testable import SyncProvider

final class MockSyncEventEmitter: SyncEventEmitter, @unchecked Sendable {
    private let recordingLock = NSLock()
    private var recorded: [SyncEventPayload] = []

    override func emit(_ payload: SyncEventPayload) {
        recordingLock.lock()
        recorded.append(payload)
        recordingLock.unlock()
        super.emit(payload)
    }

    /// Snapshot of every payload emitted since construction or `clear()`.
    var emittedEvents: [SyncEventPayload] {
        recordingLock.lock()
        defer { recordingLock.unlock() }
        return recorded
    }

    /// Reset the recording buffer.
    func clear() {
        recordingLock.lock()
        recorded.removeAll()
        recordingLock.unlock()
    }

    /// Convenience: filter by event type.
    func events(ofType type: String) -> [SyncEventPayload] {
        return emittedEvents.filter { $0.type == type }
    }
}
