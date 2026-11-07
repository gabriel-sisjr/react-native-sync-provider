import XCTest
@testable import SyncProvider

/// The network-health options of `SyncOptions`: checked before `configureSync`
/// applies anything, kept exactly as set (an unset option stays unset, so
/// `getSyncConfig()` echoes only what was set), persisted for a cold start,
/// and clamped before any integer conversion.
final class NetworkHealthOptionsTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suite: String!
    private var secrets: InMemorySecretStore!
    private var store: SyncConfigStore!

    private let healthURL = "https://health.test/ping?token=secret-token"
    private let maxOptionMs: Int64 = 9_007_199_254_740_991

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

    private func configured(quality: NetworkQualityLevel? = .moderate,
                            url: String? = "https://health.test/ping?token=secret-token",
                            ttlMs: Double? = 15_000,
                            backoffMs: Double? = 5_000) -> SyncOptionsSnapshot {
        var options = SyncOptionsSnapshot.sample()
        options.minNetworkQuality = quality
        options.healthCheckUrl = url
        options.healthCheckTtlMs = ttlMs
        options.healthCheckBackoffMs = backoffMs
        return options
    }

    private func reload() -> LoadedSyncConfig {
        SyncConfigStore(defaults: defaults, secrets: secrets).load()
    }

    private func storeRecord(_ json: String) {
        defaults.set(Data(json.utf8), forKey: "config.v1")
    }

    // MARK: - Validation

    func test_usableValues_pass() throws {
        try SyncOptionsSnapshot.sample().validateNetworkHealth()
        try configured().validateNetworkHealth()
        try configured(url: "http://10.0.0.2:8080/health", ttlMs: 0, backoffMs: 0).validateNetworkHealth()
        try configured(url: "HTTPS://Health.test/h", ttlMs: 1e300, backoffMs: .greatestFiniteMagnitude)
            .validateNetworkHealth()
        for quality in NetworkQualityLevel.allCases {
            try configured(quality: quality).validateNetworkHealth()
        }
    }

    func test_unusableHealthCheckUrl_isInvalidURL_withoutTheURLInTheMessage() {
        let urls = ["", "health", "/health", "ftp://health.test/x", "file:///tmp/health",
                    "mailto:ops@health.test", "https://", "https://:8080", "http:///health",
                    "https://bad host.test/x?token=secret-token"]
        for url in urls {
            XCTAssertThrowsError(try configured(url: url).validateNetworkHealth(), url) { error in
                let providerError = error as? SyncProviderError
                XCTAssertEqual(providerError?.codeName, "INVALID_URL", url)
                XCTAssertEqual(providerError?.message, "SyncOptions.healthCheckUrl must be an absolute http(s) URL.")
                XCTAssertFalse(error.localizedDescription.contains("secret-token"), url)
            }
        }
    }

    func test_badDurations_areInvalidPayload() {
        for value in [-1, -0.5, .nan, .infinity, -.infinity] as [Double] {
            XCTAssertThrowsError(try configured(ttlMs: value).validateNetworkHealth(), "\(value)") { error in
                XCTAssertEqual((error as? SyncProviderError)?.codeName, "INVALID_PAYLOAD")
                XCTAssertTrue(error.localizedDescription.hasPrefix(
                    "SyncOptions.healthCheckTtlMs must be a finite number of at least 0"))
            }
            XCTAssertThrowsError(try configured(backoffMs: value).validateNetworkHealth(), "\(value)") { error in
                XCTAssertEqual((error as? SyncProviderError)?.codeName, "INVALID_PAYLOAD")
                XCTAssertTrue(error.localizedDescription.hasPrefix(
                    "SyncOptions.healthCheckBackoffMs must be a finite number of at least 0"))
            }
        }
    }

    /// Same order as the facade: the URL, then the TTL, then the backoff.
    func test_violations_areReportedInFieldOrder() {
        XCTAssertThrowsError(try configured(url: "ftp://x", ttlMs: -1, backoffMs: -1).validateNetworkHealth()) {
            XCTAssertEqual(($0 as? SyncProviderError)?.codeName, "INVALID_URL")
        }
        XCTAssertThrowsError(try configured(ttlMs: -1, backoffMs: -1).validateNetworkHealth()) {
            XCTAssertTrue($0.localizedDescription.contains("healthCheckTtlMs"))
        }
    }

    // MARK: - Clamping

    func test_unsetDurations_useTheDefaults() {
        let unset = SyncOptionsSnapshot.sample()

        XCTAssertEqual(unset.effectiveHealthCheckTtlMs, 60_000)
        XCTAssertEqual(unset.effectiveHealthCheckBackoffMs, 30_000)
        XCTAssertNil(unset.healthCheckTtlMs, "The default is filled in at use, not stored")
        XCTAssertNil(unset.healthCheckBackoffMs)
    }

    func test_durations_areWholeMillisecondsClampedBeforeConversion() {
        XCTAssertEqual(configured(ttlMs: 0, backoffMs: 0).effectiveHealthCheckTtlMs, 0)
        XCTAssertEqual(configured(ttlMs: 1_500.9).effectiveHealthCheckTtlMs, 1_500)
        XCTAssertEqual(configured(backoffMs: 1e300).effectiveHealthCheckBackoffMs, maxOptionMs)
        XCTAssertEqual(configured(ttlMs: .greatestFiniteMagnitude).effectiveHealthCheckTtlMs, maxOptionMs)
        // Values validation rejects still convert without trapping.
        XCTAssertEqual(SyncOptionsSnapshot.wholeMs(.infinity, default: 7), maxOptionMs)
        XCTAssertEqual(SyncOptionsSnapshot.wholeMs(-5, default: 7), 0)
        XCTAssertEqual(SyncOptionsSnapshot.wholeMs(.nan, default: 7), 7)
        XCTAssertEqual(SyncOptionsSnapshot.wholeMs(nil, default: 7), 7)
    }

    func test_clampedDuration_addsToATimestampWithoutOverflow() {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)

        let (_, overflow) = nowMs.addingReportingOverflow(configured(backoffMs: 1e308).effectiveHealthCheckBackoffMs)

        XCTAssertFalse(overflow)
    }

    // MARK: - Persistence

    func test_everyNetworkHealthOption_survivesARoundTrip() {
        store.save(configured())

        let loaded = reload()

        XCTAssertEqual(loaded.options, configured())
        XCTAssertEqual(loaded.options.minNetworkQuality, .moderate)
        XCTAssertEqual(loaded.options.healthCheckUrl, healthURL)
        XCTAssertEqual(loaded.options.healthCheckTtlMs, 15_000)
        XCTAssertEqual(loaded.options.healthCheckBackoffMs, 5_000)
        XCTAssertEqual(loaded.source, .persisted)
    }

    func test_everyQuality_survivesARoundTrip() {
        for quality in NetworkQualityLevel.allCases {
            store.save(configured(quality: quality))

            XCTAssertEqual(reload().options.minNetworkQuality, quality)
        }
    }

    /// An unset option is written as nothing, so it reads back unset rather
    /// than as its default: `getSyncConfig()` then has nothing to echo.
    func test_unsetOptions_stayUnsetAndAreNotWritten() throws {
        store.save(.sample())

        let loaded = reload()
        XCTAssertNil(loaded.options.minNetworkQuality)
        XCTAssertNil(loaded.options.healthCheckUrl)
        XCTAssertNil(loaded.options.healthCheckTtlMs)
        XCTAssertNil(loaded.options.healthCheckBackoffMs)
        let data = try XCTUnwrap(defaults.data(forKey: "config.v1"))
        let record = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        for key in ["minNetworkQuality", "healthCheckUrl", "healthCheckTtlMs", "healthCheckBackoffMs"] {
            XCTAssertNil(record[key], key)
        }
    }

    func test_clearingTheOptions_isPersisted() {
        store.save(configured())
        store.save(.sample())

        XCTAssertEqual(reload().options, .sample())
    }

    /// Exactly what the previous version wrote: it knew none of these keys.
    func test_aRecordFromBeforeTheseOptions_stillLoads() {
        storeRecord("""
        {"strategy":"MANUAL","maxAttempts":3,"backoff":"LINEAR","baseDelayMs":100,"maxDelayMs":1000,\
        "jitter":false,"retryOnStatusCodes":[503],"batchSize":5,"requestTimeoutMs":2000,\
        "maxQueueSize":50,"persistQueue":true,"hasDefaultHeaders":false}
        """)

        let loaded = reload()

        XCTAssertEqual(loaded.source, .persisted)
        XCTAssertEqual(loaded.options.strategy, "MANUAL")
        XCTAssertEqual(loaded.options.batchSize, 5)
        XCTAssertNil(loaded.options.minNetworkQuality)
        XCTAssertNil(loaded.options.healthCheckUrl)
        XCTAssertNil(loaded.options.healthCheckTtlMs)
        XCTAssertNil(loaded.options.healthCheckBackoffMs)
    }

    /// The record is outside the app's control once written. A value the
    /// checks would reject is dropped on load, as if unset, and the rest of
    /// the configuration still applies. A huge but valid value is kept and
    /// clamped at use, so it cannot trap at every launch.
    func test_damagedStoredValues_areDroppedInsteadOfStoppingTheEngine() {
        storeRecord("""
        {"strategy":"AUTOMATIC","maxAttempts":5,"backoff":"EXPONENTIAL","baseDelayMs":1000,"maxDelayMs":60000,\
        "jitter":true,"retryOnStatusCodes":[500],"batchSize":7,"requestTimeoutMs":30000,\
        "maxQueueSize":100,"persistQueue":true,"hasDefaultHeaders":false,\
        "minNetworkQuality":"SUPERB","healthCheckUrl":"ftp://health.test/x",\
        "healthCheckTtlMs":-5,"healthCheckBackoffMs":1e308}
        """)

        let loaded = reload()

        XCTAssertEqual(loaded.source, .persisted)
        XCTAssertEqual(loaded.options.batchSize, 7, "The rest of the record still applies")
        XCTAssertNil(loaded.options.minNetworkQuality)
        XCTAssertNil(loaded.options.healthCheckUrl)
        XCTAssertNil(loaded.options.healthCheckTtlMs)
        XCTAssertEqual(loaded.options.effectiveHealthCheckTtlMs, 60_000)
        XCTAssertEqual(loaded.options.healthCheckBackoffMs, 1e308)
        XCTAssertEqual(loaded.options.effectiveHealthCheckBackoffMs, maxOptionMs)
    }

    // MARK: - Engine

    func test_configure_appliesNowAndReachesAColdStart() async {
        let fixture = EngineFixture()
        defer { fixture.tearDown() }
        let engine = await fixture.makeEngine()

        await engine.configure(configured())

        XCTAssertEqual(engine.currentOptions(), configured())
        let cold = await fixture.makeColdEngine()
        XCTAssertEqual(cold.currentOptions(), configured(), "A BGTask launch reads the same options")
        XCTAssertEqual(cold.configSource(), .persisted)
    }

    func test_configureWithoutTheOptions_unsetsThemForAColdStartToo() async {
        let fixture = EngineFixture()
        defer { fixture.tearDown() }
        let engine = await fixture.makeEngine()
        await engine.configure(configured())

        await engine.configure(.sample())

        XCTAssertNil(engine.currentOptions().healthCheckUrl)
        let cold = await fixture.makeColdEngine()
        XCTAssertNil(cold.currentOptions().minNetworkQuality)
        XCTAssertNil(cold.currentOptions().healthCheckUrl)
        XCTAssertNil(cold.currentOptions().healthCheckTtlMs)
        XCTAssertNil(cold.currentOptions().healthCheckBackoffMs)
    }
}
