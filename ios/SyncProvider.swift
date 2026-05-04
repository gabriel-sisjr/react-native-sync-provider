//
//  SyncProvider.swift
//  SyncProvider
//
//  Concrete implementation of the Nitro-generated `HybridSyncProviderSpec`.
//  This file is the JS↔native bridge surface — it owns the singletons (Core
//  Data stack, queue storage, dispatcher, connectivity monitor, event
//  emitter, background sync manager, recovery manager) and translates every
//  generated type to and from the internal Sendable representations.
//

import Foundation
import NitroModules

/// The class name *must* match `nitro.json → autolinking.SyncProvider.ios.implementationClassName`.
///
/// Although Nitro instantiates this class via the generated factory glue
/// (so internal access would be enough for that path), we keep the class
/// `public` so the host AppDelegate can call ``handleBackgroundURLSessionEvents(identifier:completionHandler:)``.
public final class SyncProvider: HybridSyncProviderSpec {
    // MARK: - Components

    private let storage: SyncQueueStorage
    private let dispatcher: SyncDispatcher
    private let connectivity: ConnectivityMonitor
    private let emitter: SyncEventEmitter
    private let backgroundManager: BackgroundSyncManager
    private let recovery: RecoveryManager

    // MARK: - In-memory snapshot of the active config

    private let configLock = NSLock()
    private var lastSyncOptions: SyncOptionsSnapshot

    // MARK: - Init

    public override init() {
        let storage = SyncQueueStorage()
        let emitter = SyncEventEmitter()
        let connectivity = ConnectivityMonitor.shared

        self.storage = storage
        self.emitter = emitter
        self.connectivity = connectivity
        self.dispatcher = SyncDispatcher(storage: storage,
                                         emitter: emitter,
                                         connectivity: connectivity,
                                         config: .default)
        self.backgroundManager = BackgroundSyncManager(dispatcher: dispatcher, emitter: emitter)
        self.recovery = RecoveryManager(storage: storage, emitter: emitter)
        self.lastSyncOptions = SyncOptionsSnapshot.default

        super.init()

        // Make this instance reachable from the AppDelegate hook.
        SyncProvider.weakInstance = self

        // Eagerly start the connectivity monitor so the first read returns a
        // real snapshot rather than the .unknown default.
        connectivity.start()

        // Forward connectivity changes to the JS layer as `CONNECTION_CHANGED`
        // events. The `ConnectivityMonitor` replays the current snapshot
        // synchronously on subscribe, but that snapshot is `.unknown` until
        // the first NWPathMonitor update — emitting it once primes the JS
        // layer with the same baseline as `getConnectionStatus()`.
        connectivity.subscribe { [weak self] snapshot in
            self?.emitter.emit(.now(type: "CONNECTION_CHANGED",
                                    connectionStatus: snapshot.status.rawValue))
        }

        // Register BGTask handlers as early as possible. `enableBackgroundSync`
        // is the consumer-controlled toggle; registering only flips on the
        // dispatch slot, it does not schedule anything.
        backgroundManager.registerHandlers()

        // Recovery on launch: detached so we do not block the bridge init.
        let recoveryRef = recovery
        Task.detached {
            await recoveryRef.recoverPendingItems()
        }
    }

    // MARK: - Queue ops

    public func enqueue(item: SyncItemInput) throws -> Promise<String> {
        return Promise.async { [weak self] in
            guard let self = self else {
                throw SyncProviderError(code: .nativeModuleUnavailable, message: "SyncProvider deallocated")
            }
            do {
                let input = try Self.translate(input: item)
                let id = try await self.storage.enqueue(input: input)
                self.emitter.emit(.now(type: "ITEM_ENQUEUED", itemId: id))
                return id
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    public func enqueueBatch(items: [SyncItemInput]) throws -> Promise<[String]> {
        return Promise.async { [weak self] in
            guard let self = self else {
                throw SyncProviderError(code: .nativeModuleUnavailable, message: "SyncProvider deallocated")
            }
            do {
                let inputs = try items.map(Self.translate(input:))
                let ids = try await self.storage.enqueueBatch(inputs: inputs)
                for id in ids {
                    self.emitter.emit(.now(type: "ITEM_ENQUEUED", itemId: id))
                }
                return ids
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    public func removeItem(id: String) throws -> Promise<Bool> {
        return Promise.async { [weak self] in
            guard let self = self else { return false }
            do {
                let removed = try await self.storage.remove(id: id)
                if removed {
                    self.emitter.emit(.now(type: "ITEM_REMOVED", itemId: id))
                }
                return removed
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    public func clearQueue() throws -> Promise<Void> {
        return Promise.async { [weak self] in
            guard let self = self else { return }
            do {
                try await self.storage.clear()
                self.emitter.emit(.now(type: "QUEUE_CLEARED"))
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    public func getQueueSize() throws -> Promise<Double> {
        return Promise.async { [weak self] in
            guard let self = self else { return 0 }
            do {
                let size = try await self.storage.getSize()
                return Double(size)
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    public func getPendingItems() throws -> Promise<[SyncItem]> {
        return Promise.async { [weak self] in
            guard let self = self else { return [] }
            do {
                let stored = try await self.storage.getAll()
                return stored.map(Self.translate(stored:))
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    // MARK: - Sync ops

    public func flush() throws -> Promise<SyncResult> {
        return Promise.async { [weak self] in
            guard let self = self else {
                return Self.emptySyncResult()
            }
            do {
                let result = try await self.dispatcher.flush()
                return Self.translate(result: result)
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    public func pauseSync() throws -> Promise<Void> {
        return Promise.async { [weak self] in
            guard let self = self else { return }
            await self.dispatcher.setPaused(true)
            self.emitter.emit(.now(type: "PAUSED"))
        }
    }

    public func resumeSync() throws -> Promise<Void> {
        return Promise.async { [weak self] in
            guard let self = self else { return }
            await self.dispatcher.setPaused(false)
            self.emitter.emit(.now(type: "RESUMED"))
        }
    }

    public func isSyncing() throws -> Promise<Bool> {
        return Promise.async { [weak self] in
            guard let self = self else { return false }
            return await self.dispatcher.cycleRunning()
        }
    }

    // MARK: - Config ops

    public func configureSync(options: SyncOptions) throws -> Promise<Void> {
        return Promise.async { [weak self] in
            guard let self = self else { return }
            let snapshot = Self.translate(options: options)
            self.configLock.lock()
            self.lastSyncOptions = snapshot
            self.configLock.unlock()

            await self.storage.updateCapacity(Int(snapshot.maxQueueSize))
            await self.dispatcher.updateConfig(snapshot.toDispatcherConfig())
        }
    }

    public func getSyncConfig() throws -> Promise<SyncOptions> {
        return Promise.async { [weak self] in
            guard let self = self else {
                return Self.translate(snapshot: .default)
            }
            self.configLock.lock()
            let snapshot = self.lastSyncOptions
            self.configLock.unlock()
            return Self.translate(snapshot: snapshot)
        }
    }

    // MARK: - History ops

    public func getLastSyncResult() throws -> Promise<SyncResult> {
        return Promise.async { [weak self] in
            guard let self = self else { return Self.emptySyncResult() }
            do {
                let history = try await self.storage.getHistory(limit: 1)
                if let latest = history.first {
                    return Self.translate(result: latest)
                }
                return Self.emptySyncResult()
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    public func getSyncHistory(limit: Double) throws -> Promise<[SyncResult]> {
        return Promise.async { [weak self] in
            guard let self = self else { return [] }
            do {
                let safeLimit = Int(max(0, limit))
                let history = try await self.storage.getHistory(limit: safeLimit)
                return history.map(Self.translate(result:))
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    public func clearSyncHistory() throws -> Promise<Void> {
        return Promise.async { [weak self] in
            guard let self = self else { return }
            do {
                try await self.storage.clearHistory()
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    // MARK: - Connection

    public func getConnectionStatus() throws -> Promise<ConnectionState> {
        return Promise.async { [weak self] in
            guard let self = self else {
                return Self.translate(snapshot: .unknown)
            }
            return Self.translate(snapshot: self.connectivity.current)
        }
    }

    // MARK: - Background sync

    public func enableBackgroundSync(options: BackgroundSyncOptions) throws -> Promise<Void> {
        return Promise.async { [weak self] in
            guard let self = self else { return }
            let value = Self.translate(options: options)
            do {
                try self.backgroundManager.enable(options: value)
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    public func disableBackgroundSync() throws -> Promise<Void> {
        return Promise.async { [weak self] in
            self?.backgroundManager.disable()
        }
    }

    public func isBackgroundSyncEnabled() throws -> Promise<Bool> {
        return Promise.async { [weak self] in
            return self?.backgroundManager.isEnabled ?? false
        }
    }

    // MARK: - Events

    public func addListener(event: String,
                            callback: @escaping (_ event: SyncEvent) -> Void) throws -> Promise<String> {
        return Promise.async { [weak self] in
            guard let self = self else {
                throw SyncProviderError(code: .nativeModuleUnavailable, message: "SyncProvider deallocated")
            }
            do {
                let id = try self.emitter.addListener(channel: event) { payload in
                    callback(Self.translate(payload: payload))
                }
                return id
            } catch {
                throw wrapAsSyncProviderError(error)
            }
        }
    }

    public func removeListener(event: String, subscriptionId: String) throws -> Promise<Void> {
        return Promise.async { [weak self] in
            self?.emitter.removeListener(channel: event, id: subscriptionId)
        }
    }

    // MARK: - Public helper for AppDelegate

    /// Forwards `application(_:handleEventsForBackgroundURLSession:completionHandler:)`
    /// into the dispatcher. Consumer apps call this from their AppDelegate so
    /// the system knows when our background session has finished delivering
    /// events.
    ///
    /// - Parameters:
    ///   - identifier: the background `URLSession` identifier reported by the
    ///     system. Forwarded as-is.
    ///   - completionHandler: the system-supplied completion handler. Must be
    ///     invoked exactly once after the session finishes flushing events.
    @objc public static func handleBackgroundURLSessionEvents(
        identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        if let live = SyncProvider.weakInstance {
            live.dispatcher.handleBackgroundURLSessionEvents(
                identifier: identifier,
                completionHandler: completionHandler
            )
        } else {
            // No live instance yet — invoke the handler so the system does
            // not stall. The next launch will recover any pending uploads.
            completionHandler()
        }
    }

    /// Weak reference to the most recently created instance, used solely by
    /// the AppDelegate hook. The Nitro runtime owns the strong reference.
    fileprivate static weak var weakInstance: SyncProvider?
}

// MARK: - Translation helpers

extension SyncProvider {
    /// Sendable mirror of `SyncOptions` used to keep the Nitro C++ struct out
    /// of the `actor` boundary.
    struct SyncOptionsSnapshot: Sendable, Equatable {
        var strategy: String
        var retryPolicy: RetryPolicyValue
        var batchSize: Double
        var requestTimeoutMs: Double
        var maxQueueSize: Double
        var persistQueue: Bool
        var defaultHeaders: [String: String]

        static let `default` = SyncOptionsSnapshot(
            strategy: "AUTOMATIC",
            retryPolicy: .default,
            batchSize: 25,
            requestTimeoutMs: 30_000,
            maxQueueSize: Double(kDefaultQueueCapacity),
            persistQueue: true,
            defaultHeaders: [:]
        )

        func toDispatcherConfig() -> DispatcherConfig {
            DispatcherConfig(retryPolicy: retryPolicy,
                             requestTimeoutMs: Int(requestTimeoutMs),
                             batchSize: Int(batchSize),
                             defaultHeaders: defaultHeaders)
        }
    }

    static func translate(input: SyncItemInput) throws -> SyncQueueStorage.SyncItemInputData {
        let url = input.url
        if url.isEmpty {
            throw SyncProviderError.invalidPayload("url is empty")
        }
        guard let parsed = URL(string: url),
              let scheme = parsed.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              parsed.host != nil else {
            throw SyncProviderError.invalidURL(url)
        }
        return SyncQueueStorage.SyncItemInputData(
            method: input.method.stringValue,
            url: url,
            headers: input.headers,
            body: input.body,
            contentType: input.contentType,
            priority: (input.priority?.stringValue) ?? "NORMAL",
            metadata: input.metadata
        )
    }

    static func translate(stored: StoredSyncItem) -> SyncItem {
        let method = HttpMethod(fromString: stored.method) ?? .post
        let priority = SyncPriority(fromString: stored.priority)
        return SyncItem(id: stored.id,
                        method: method,
                        url: stored.url,
                        headers: stored.headers,
                        body: stored.body,
                        contentType: stored.contentType,
                        priority: priority,
                        createdAt: Double(stored.createdAt),
                        metadata: stored.metadata)
    }

    static func translate(result: StoredSyncResult) -> SyncResult {
        SyncResult(startedAt: Double(result.startedAt),
                   finishedAt: Double(result.finishedAt),
                   successCount: Double(result.itemsSucceeded),
                   failureCount: Double(result.itemsFailed),
                   succeededIds: [],
                   failedIds: [],
                   errors: [:])
    }

    static func emptySyncResult() -> SyncResult {
        SyncResult(startedAt: 0,
                   finishedAt: 0,
                   successCount: 0,
                   failureCount: 0,
                   succeededIds: [],
                   failedIds: [],
                   errors: [:])
    }

    static func translate(options: SyncOptions) -> SyncOptionsSnapshot {
        let retry = options.retryPolicy
        let policy = RetryPolicyValue(
            maxAttempts: Int(retry.maxAttempts),
            backoff: RetryPolicyValue.Backoff(rawValue: retry.backoff.stringValue) ?? .exponential,
            baseDelayMs: Int(retry.baseDelayMs),
            maxDelayMs: Int(retry.maxDelayMs),
            jitter: retry.jitter,
            retryOnStatusCodes: retry.retryOnStatusCodes.map { Int($0) }
        )
        return SyncOptionsSnapshot(
            strategy: options.strategy.stringValue,
            retryPolicy: policy,
            batchSize: options.batchSize ?? 25,
            requestTimeoutMs: options.requestTimeoutMs ?? 30_000,
            maxQueueSize: options.maxQueueSize ?? Double(kDefaultQueueCapacity),
            persistQueue: options.persistQueue ?? true,
            defaultHeaders: options.defaultHeaders ?? [:]
        )
    }

    static func translate(snapshot: SyncOptionsSnapshot) -> SyncOptions {
        let strategy = SyncStrategy(fromString: snapshot.strategy) ?? .automatic
        let backoff = BackoffStrategy(fromString: snapshot.retryPolicy.backoff.rawValue) ?? .exponential
        let retryPolicy = RetryPolicy(
            maxAttempts: Double(snapshot.retryPolicy.maxAttempts),
            backoff: backoff,
            baseDelayMs: Double(snapshot.retryPolicy.baseDelayMs),
            maxDelayMs: Double(snapshot.retryPolicy.maxDelayMs),
            jitter: snapshot.retryPolicy.jitter,
            retryOnStatusCodes: snapshot.retryPolicy.retryOnStatusCodes.map { Double($0) }
        )
        return SyncOptions(strategy: strategy,
                           retryPolicy: retryPolicy,
                           batchSize: snapshot.batchSize,
                           requestTimeoutMs: snapshot.requestTimeoutMs,
                           maxQueueSize: snapshot.maxQueueSize,
                           persistQueue: snapshot.persistQueue,
                           defaultHeaders: snapshot.defaultHeaders)
    }

    static func translate(snapshot: ConnectionSnapshot) -> ConnectionState {
        let status = ConnectionStatus(fromString: snapshot.status.rawValue) ?? .unknown
        let type = ConnectionType(fromString: snapshot.type.rawValue) ?? .unknown
        return ConnectionState(status: status,
                               type: type,
                               isInternetReachable: snapshot.isInternetReachable,
                               isExpensive: snapshot.isExpensive)
    }

    static func translate(options: BackgroundSyncOptions) -> BackgroundSyncOptionsValue {
        BackgroundSyncOptionsValue(
            minimumIntervalMs: options.minimumIntervalMs,
            requiresCharging: options.requiresCharging,
            requiresUnmeteredNetwork: options.requiresUnmeteredNetwork,
            requiresDeviceIdle: options.requiresDeviceIdle,
            taskIdentifier: options.taskIdentifier
        )
    }

    static func translate(payload: SyncEventPayload) -> SyncEvent {
        let type = SyncEventType(fromString: payload.type) ?? .syncStarted
        let errorCode = payload.errorCode.flatMap { SyncErrorCode(fromString: $0) }
        let connStatus = payload.connectionStatus.flatMap { ConnectionStatus(fromString: $0) }
        return SyncEvent(type: type,
                         timestamp: payload.timestamp,
                         itemId: payload.itemId,
                         progress: payload.progress,
                         errorCode: errorCode,
                         statusCode: payload.statusCode,
                         attempt: payload.attempt,
                         connectionStatus: connStatus,
                         metadata: payload.metadata)
    }
}

