import XCTest
@testable import SyncProvider

final class SyncConfigStoreTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suite: String!
    private var secrets: InMemorySecretStore!
    private var store: SyncConfigStore!

    private let token = "Bearer super-secret-token"

    override func setUp() {
        super.setUp()
        (defaults, suite) = UserDefaults.ephemeral()
        secrets = InMemorySecretStore()
        store = SyncConfigStore(defaults: defaults, secrets: secrets)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    private func sample() -> SyncOptionsSnapshot {
        SyncOptionsSnapshot(strategy: "OPPORTUNISTIC",
                            retryPolicy: RetryPolicyValue(maxAttempts: 7,
                                                          backoff: .fibonacci,
                                                          baseDelayMs: 250,
                                                          maxDelayMs: 9_000,
                                                          jitter: false,
                                                          retryOnStatusCodes: [418, 503]),
                            batchSize: 3,
                            requestTimeoutMs: 1_234,
                            maxQueueSize: 42,
                            persistQueue: false,
                            defaultHeaders: ["Authorization": token, "X-Tenant": "acme"])
    }

    // MARK: - Round trip

    func test_nothingStored_loadsDefaults() {
        let loaded = store.load()

        XCTAssertEqual(loaded.options, .default)
        XCTAssertEqual(loaded.source, .defaults)
        XCTAssertFalse(loaded.credentialsUnavailable)
        XCTAssertFalse(loaded.paused)
    }

    func test_everyOption_survivesARoundTrip() {
        store.save(sample())

        let loaded = SyncConfigStore(defaults: defaults, secrets: secrets).load()

        XCTAssertEqual(loaded.options, sample())
        XCTAssertEqual(loaded.source, .persisted)
        XCTAssertFalse(loaded.credentialsUnavailable)
    }

    // MARK: - Secrets stay out of UserDefaults

    func test_secretValues_neverReachUserDefaults() throws {
        store.save(sample())

        let domain = try XCTUnwrap(defaults.persistentDomain(forName: suite))
        let plist = try PropertyListSerialization.data(fromPropertyList: domain, format: .xml, options: 0)
        var written = String(decoding: plist, as: UTF8.self)
        for case let data as Data in domain.values {
            written += String(decoding: data, as: UTF8.self)
        }
        XCTAssertFalse(written.contains("super-secret-token"))
        XCTAssertFalse(written.contains("acme"))
        XCTAssertFalse(written.contains("Authorization"), "header names are not stored there either")
        XCTAssertTrue(written.contains("hasDefaultHeaders"))
        XCTAssertEqual(secrets.stored?["Authorization"], token)
    }

    // MARK: - Logout

    func test_configuringWithoutHeaders_deletesTheSecret() {
        store.save(sample())
        XCTAssertNotNil(secrets.stored)

        var loggedOut = sample()
        loggedOut.defaultHeaders = [:]
        store.save(loggedOut)

        XCTAssertNil(secrets.stored)
        let loaded = store.load()
        XCTAssertTrue(loaded.options.defaultHeaders.isEmpty)
        XCTAssertFalse(loaded.credentialsUnavailable)
    }

    // MARK: - Fresh install

    /// Keychain items survive an uninstall; UserDefaults do not.
    func test_freshInstall_clearsTheOrphanedSecret() {
        secrets.seed(["Authorization": "Bearer from-a-previous-install"])

        let loaded = store.load()

        XCTAssertNil(secrets.stored)
        XCTAssertTrue(loaded.options.defaultHeaders.isEmpty)
        XCTAssertEqual(loaded.source, .defaults)
    }

    /// Before the first unlock neither UserDefaults nor the Keychain is
    /// readable. An empty domain then does not mean "fresh install".
    func test_lockedDevice_doesNotDiscardTheSecret() {
        secrets.seed(["Authorization": token])
        secrets.failReads(with: errSecInteractionNotAllowed)

        _ = store.load()

        secrets.failReads(with: nil)
        XCTAssertEqual(secrets.stored?["Authorization"], token)
    }

    // MARK: - Unrecoverable secret

    func test_unreadableSecret_isReportedAsUnavailable() {
        store.save(sample())
        secrets.failReads(with: errSecInteractionNotAllowed)

        let loaded = store.load()

        XCTAssertTrue(loaded.credentialsUnavailable)
        XCTAssertTrue(loaded.options.defaultHeaders.isEmpty)
        XCTAssertEqual(loaded.source, .persisted)
    }

    /// Backup restored on another device: `ThisDeviceOnly` items do not come.
    func test_missingSecret_isReportedAsUnavailable() throws {
        store.save(sample())
        try secrets.deleteDefaultHeaders()

        XCTAssertTrue(store.load().credentialsUnavailable)
    }

    func test_failedKeychainWrite_keepsDeclaringTheHeaders() {
        secrets.failWrites(with: errSecNotAvailable)

        store.save(sample())

        XCTAssertTrue(store.load().credentialsUnavailable, "a cold start must not send without them")
    }

    // MARK: - Pause

    func test_pausedFlag_survives() {
        store.setPaused(true)
        XCTAssertTrue(SyncConfigStore(defaults: defaults, secrets: secrets).load().paused)

        store.setPaused(false)
        XCTAssertFalse(SyncConfigStore(defaults: defaults, secrets: secrets).load().paused)
    }

    // MARK: - Corruption

    func test_unreadableRecord_fallsBackToDefaults() {
        defaults.set(Data("not json".utf8), forKey: "config.v1")

        let loaded = store.load()

        XCTAssertEqual(loaded.options, .default)
        XCTAssertEqual(loaded.source, .defaults)
    }

    // MARK: - Callback timeout

    func test_callbackTimeoutMs_survivesARoundTrip_andIsAbsentWhileUnset() throws {
        store.save(sample())
        let unsetRecord = try XCTUnwrap(defaults.data(forKey: "config.v1"))
        XCTAssertFalse(String(decoding: unsetRecord, as: UTF8.self).contains("callbackTimeoutMs"),
                       "an unset value is not written")

        var options = sample()
        options.callbackTimeoutMs = 2_000.5
        store.save(options)

        XCTAssertEqual(SyncConfigStore(defaults: defaults, secrets: secrets).load().options.callbackTimeoutMs, 2_000.5)
    }

    /// The record is outside the app's control once written: a stored value
    /// `configureSync` would reject loads as unset, and the rest still applies.
    func test_options_callbackTimeoutMs_damagedStoredValue_loadsUnset() {
        for damaged in ["\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "0", "-5", "0.5"] {
            defaults.set(Data("""
            {"strategy":"MANUAL","maxAttempts":3,"backoff":"LINEAR","baseDelayMs":100,"maxDelayMs":1000,\
            "jitter":false,"retryOnStatusCodes":[503],"batchSize":5,"requestTimeoutMs":2000,\
            "maxQueueSize":50,"persistQueue":true,"hasDefaultHeaders":false,"callbackTimeoutMs":\(damaged)}
            """.utf8), forKey: "config.v1")

            let loaded = SyncConfigStore(defaults: defaults, secrets: secrets).load()

            XCTAssertEqual(loaded.source, .persisted, damaged)
            XCTAssertNil(loaded.options.callbackTimeoutMs, damaged)
            XCTAssertEqual(loaded.options.batchSize, 5, "the rest of the record still applies, \(damaged)")
            XCTAssertEqual(loaded.options.toDispatcherConfig().callbackTimeoutMs, 3_000, damaged)
        }
    }
}
