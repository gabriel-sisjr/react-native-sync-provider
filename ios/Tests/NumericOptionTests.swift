import XCTest
import NitroModules
@testable import SyncProvider

/// Numbers that `configureSync` and `getSyncHistory` accept but `Int(_:)`
/// cannot hold: NaN, ±Infinity, 2^63 or more. JS validates none of them, so
/// none may trap, neither at the call nor at a later launch, and the
/// configuration a cold start reads is the one the call applied.
final class NumericOptionEngineTests: XCTestCase {
    private var fixture: EngineFixture!

    private let twoTo63 = 9_223_372_036_854_775_808.0
    private let int32Max = 2_147_483_647

    override func setUp() {
        super.setUp()
        fixture = EngineFixture()
    }

    override func tearDown() {
        fixture.tearDown()
        fixture = nil
        super.tearDown()
    }

    // MARK: - A saved value no engine start could convert

    /// What an install that configured `maxQueueSize: 2 ** 63`,
    /// `batchSize: 1e300` and `requestTimeoutMs: Number.MAX_VALUE` has on
    /// disk: the save ran before the conversion that trapped, so every launch
    /// after it trapped too, a BGTask launch included, until the app's data
    /// was cleared.
    func test_anEngineStart_overSavedNumbersOf2To63AndMore_doesNotTrap() async throws {
        var saved = SyncOptionsSnapshot.sample()
        saved.maxQueueSize = twoTo63
        saved.batchSize = 1e300
        saved.requestTimeoutMs = .greatestFiniteMagnitude
        SyncConfigStore(defaults: fixture.defaults, secrets: fixture.secrets).save(saved)

        let engine = await fixture.makeColdEngine()

        let capacity = await engine.storage.capacity
        XCTAssertEqual(capacity, int32Max)
        let config = await engine.dispatcher.currentConfig()
        XCTAssertEqual(config.batchSize, int32Max)
        XCTAssertEqual(config.requestTimeoutMs, int32Max)
        let options = engine.currentOptions()
        XCTAssertEqual(options.maxQueueSize, twoTo63, "Kept as saved; converted at use")
        XCTAssertEqual(options.batchSize, 1e300)
        XCTAssertEqual(options.requestTimeoutMs, .greatestFiniteMagnitude)
        let id = try await engine.storage.enqueue(input: .sample())
        XCTAssertFalse(id.isEmpty)
    }

    /// The record exactly as 0.2.1 wrote it, before the network-health keys.
    func test_anEngineStart_overA021RecordWithA2To63Cap_doesNotTrap() async throws {
        fixture.defaults.set(Data("""
        {"strategy":"AUTOMATIC","maxAttempts":5,"backoff":"EXPONENTIAL","baseDelayMs":1000,"maxDelayMs":60000,\
        "jitter":true,"retryOnStatusCodes":[503],"batchSize":25,"requestTimeoutMs":30000,\
        "maxQueueSize":9.223372036854775808e18,"persistQueue":true,"hasDefaultHeaders":false}
        """.utf8), forKey: "config.v1")

        let engine = await fixture.makeColdEngine()

        XCTAssertEqual(engine.configSource(), .persisted)
        let capacity = await engine.storage.capacity
        XCTAssertEqual(capacity, int32Max)
        let config = await engine.dispatcher.currentConfig()
        XCTAssertEqual(config.batchSize, 25)
        XCTAssertEqual(config.requestTimeoutMs, 30_000)
        XCTAssertEqual(config.retryPolicy.maxAttempts, 5)
        XCTAssertEqual(config.retryPolicy.retryOnStatusCodes, [503])
    }

    // MARK: - configure, then a restart

    private enum Field: CaseIterable {
        case batchSize, requestTimeoutMs, maxQueueSize
    }

    /// Each of NaN, +Infinity and -Infinity, in each field: the call applies
    /// it, and a fresh engine reads back the very same number, not the record
    /// of an earlier call that the encoder could not replace.
    func test_nonFiniteNumbers_applyNow_andComeBackAfterARestart() async throws {
        for field in Field.allCases {
            for value in [Double.nan, .infinity, -.infinity] {
                let fixture = EngineFixture()
                defer { fixture.tearDown() }
                let label = "\(field) = \(value)"
                let engine = await fixture.makeEngine()
                await engine.configure(.sample(batchSize: 3, maxQueueSize: 4))

                var options = SyncOptionsSnapshot.sample(batchSize: 3, maxQueueSize: 4)
                switch field {
                case .batchSize: options.batchSize = value
                case .requestTimeoutMs: options.requestTimeoutMs = value
                case .maxQueueSize: options.maxQueueSize = value
                }
                await engine.configure(options)

                assertSameNumbers(engine.currentOptions(), options, "\(label), in this process")
                engine.shutdown()
                let cold = await fixture.makeColdEngine()
                assertSameNumbers(cold.currentOptions(), options, "\(label), after a restart")

                let config = await cold.dispatcher.currentConfig()
                let capacity = await cold.storage.capacity
                switch field {
                case .batchSize:
                    XCTAssertEqual(config.batchSize, value == .infinity ? int32Max : 1, label)
                case .requestTimeoutMs:
                    XCTAssertEqual(config.requestTimeoutMs, value == .infinity ? int32Max : 1_000, label)
                case .maxQueueSize:
                    XCTAssertEqual(capacity, value == .infinity ? int32Max : 1, label)
                }
            }
        }
    }

    /// `getSyncConfig()` hands back what the call set, as Android does.
    func test_getSyncConfig_echoesNonFiniteNumbers_afterARestart() async throws {
        let engine = await fixture.makeEngine()
        var options = SyncOptionsSnapshot.sample()
        options.batchSize = .nan
        options.requestTimeoutMs = .infinity
        options.maxQueueSize = -.infinity
        await engine.configure(options)
        engine.shutdown()

        let hybrid = HybridSyncProvider(engine: await fixture.makeColdEngine())
        let echoed = try XCTUnwrap(try attachLikeTheBridge(try hybrid.getSyncConfig()).value?.get())

        XCTAssertTrue(echoed.batchSize?.isNaN == true)
        XCTAssertEqual(echoed.requestTimeoutMs, .infinity)
        XCTAssertEqual(echoed.maxQueueSize, -.infinity)
    }

    // MARK: - The bridge

    private func syncOptions(maxAttempts: Double = 5,
                             baseDelayMs: Double = 1_000,
                             maxDelayMs: Double = 60_000,
                             statusCodes: [Double] = [503]) -> SyncOptions {
        SyncOptions(strategy: .automatic,
                    retryPolicy: RetryPolicy(maxAttempts: maxAttempts,
                                             backoff: .exponential,
                                             baseDelayMs: baseDelayMs,
                                             maxDelayMs: maxDelayMs,
                                             jitter: false,
                                             retryOnStatusCodes: statusCodes),
                    batchSize: nil,
                    requestTimeoutMs: nil,
                    maxQueueSize: nil,
                    persistQueue: nil,
                    defaultHeaders: nil,
                    minNetworkQuality: nil,
                    healthCheckUrl: nil,
                    healthCheckTtlMs: nil,
                    healthCheckBackoffMs: nil)
    }

    /// Every `retryPolicy` number went through `Int(_:)` in the bridge,
    /// before anything was checked or saved.
    func test_configureSync_takesNonFiniteRetryNumbers() async throws {
        let engine = await fixture.makeEngine()
        let hybrid = HybridSyncProvider(engine: engine)
        let options = syncOptions(maxAttempts: .infinity,
                                  baseDelayMs: .nan,
                                  maxDelayMs: -.infinity,
                                  statusCodes: [.nan, .infinity, -.infinity, twoTo63, 1e300, 503.7])

        let result = await settle(try hybrid.configureSync(options: options))

        XCTAssertNoThrow(try result?.get())
        let policy = await engine.dispatcher.currentConfig().retryPolicy
        XCTAssertEqual(policy.maxAttempts, int32Max)
        XCTAssertEqual(policy.baseDelayMs, 0)
        XCTAssertEqual(policy.retryOnStatusCodes, [503])
        let echoed = try XCTUnwrap(try attachLikeTheBridge(try hybrid.getSyncConfig()).value?.get())
        XCTAssertEqual(echoed.retryPolicy.maxAttempts, .infinity)
        XCTAssertTrue(echoed.retryPolicy.baseDelayMs.isNaN)
        XCTAssertEqual(echoed.retryPolicy.maxDelayMs, -.infinity)
        XCTAssertEqual(echoed.retryPolicy.retryOnStatusCodes.count, 6, "Echoed as set")
    }

    func test_configureSync_takesRetryNumbersOf2To63AndMore() async throws {
        let engine = await fixture.makeEngine()
        let hybrid = HybridSyncProvider(engine: engine)

        let result = await settle(try hybrid.configureSync(options: syncOptions(maxAttempts: twoTo63,
                                                                                baseDelayMs: 1e300,
                                                                                maxDelayMs: twoTo63)))

        XCTAssertNoThrow(try result?.get())
        let policy = await engine.dispatcher.currentConfig().retryPolicy
        XCTAssertEqual(policy.maxAttempts, int32Max)
        XCTAssertEqual(policy.baseDelayMs, 9_007_199_254_740_991)
        XCTAssertEqual(policy.maxDelayMs, 9_007_199_254_740_991)
    }

    func test_getSyncHistory_takesAnyLimit() async throws {
        let engine = await fixture.makeEngine()
        for index in 0..<3 {
            try await engine.storage.recordResult(StoredSyncResult(id: "R\(index)",
                                                                   startedAt: Int64(index),
                                                                   finishedAt: Int64(index),
                                                                   itemsAttempted: 0,
                                                                   itemsSucceeded: 0,
                                                                   itemsFailed: 0,
                                                                   errorMessage: nil))
        }
        let hybrid = HybridSyncProvider(engine: engine)

        for (limit, expected) in [(Double.infinity, 3), (twoTo63, 3), (1e300, 3), (.nan, 3),
                                  (-.infinity, 3), (-1, 3), (0, 3), (1.5, 1), (2, 2)] {
            let result = await settle(try hybrid.getSyncHistory(limit: limit))
            XCTAssertEqual(try result?.get().count, expected, "limit \(limit)")
        }
    }

    // MARK: - Helpers

    /// Equal numbers, NaN included: compared by bit pattern.
    private func assertSameNumbers(_ actual: SyncOptionsSnapshot, _ expected: SyncOptionsSnapshot,
                                   _ message: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(actual.batchSize.bitPattern, expected.batchSize.bitPattern,
                       "batchSize, \(message)", file: file, line: line)
        XCTAssertEqual(actual.requestTimeoutMs.bitPattern, expected.requestTimeoutMs.bitPattern,
                       "requestTimeoutMs, \(message)", file: file, line: line)
        XCTAssertEqual(actual.maxQueueSize.bitPattern, expected.maxQueueSize.bitPattern,
                       "maxQueueSize, \(message)", file: file, line: line)
        XCTAssertEqual(actual.retryPolicy, expected.retryPolicy, "retryPolicy, \(message)", file: file, line: line)
        XCTAssertEqual(actual.strategy, expected.strategy, message, file: file, line: line)
    }
}

/// The conversion itself, field by field, over the inputs that used to trap
/// and the ones that never did. The expected values are Android's for the
/// same inputs, except where the iOS rules for valid numbers apply.
final class NumericOptionTests: XCTestCase {
    private let twoTo63 = 9_223_372_036_854_775_808.0
    private let int32Max = 2_147_483_647
    private let delayCeiling = 9_007_199_254_740_991

    func test_count_isAtLeastOne_andAt32BitsMost() {
        let cases: [(Double, Int)] = [(.nan, 1), (.infinity, int32Max), (-.infinity, 1), (twoTo63, int32Max),
                                      (1e300, int32Max), (-1, 1), (0, 1), (1.5, 1), (0.9, 1), (25, 25),
                                      (2_147_483_647, int32Max), (2_147_483_648, int32Max), (-1e300, 1)]
        for (input, expected) in cases {
            XCTAssertEqual(NumericOption.count(input), expected, "\(input)")
        }
    }

    func test_timeoutMs_keepsTheIOSRules_inTheRange() {
        let cases: [(Double, Int)] = [(.nan, 1_000), (.infinity, int32Max), (-.infinity, 1_000), (twoTo63, int32Max),
                                      (1e300, int32Max), (-1, -1), (0, 0), (1.5, 1), (30_000, 30_000),
                                      (2_147_483_648, int32Max), (-2_147_483_648, -2_147_483_648),
                                      (-2_147_483_649, 1_000)]
        for (input, expected) in cases {
            XCTAssertEqual(NumericOption.timeoutMs(input), expected, "\(input)")
        }
    }

    /// A large finite delay is valid on both platforms: only what `Int(_:)`
    /// cannot hold changes.
    func test_delayMs_changesOnlyWhatCannotConvert() {
        let cases: [(Double, Int)] = [(.nan, 0), (.infinity, delayCeiling), (-.infinity, 0), (twoTo63, delayCeiling),
                                      (1e300, delayCeiling), (-1e300, 0), (-1, -1), (0, 0), (1.5, 1),
                                      (3e9, 3_000_000_000), (9.2e18, 9_200_000_000_000_000_000)]
        for (input, expected) in cases {
            XCTAssertEqual(NumericOption.delayMs(input), expected, "\(input)")
        }
    }

    /// An entry that could match no status is dropped; both platforms
    /// truncate the rest, so 503.7 still matches 503.
    func test_statusCodes_dropWhatMatchesNothing() {
        XCTAssertEqual(NumericOption.statusCodes([.nan, .infinity, -.infinity, twoTo63, 1e300,
                                                  2_147_483_648, -1, 0, 1.5, 503.7, 429]),
                       [-1, 0, 1, 503, 429])
    }

    func test_historyLimit_zeroMeansAll() {
        let cases: [(Double, Int)] = [(.nan, 0), (.infinity, int32Max), (-.infinity, 0), (twoTo63, int32Max),
                                      (1e300, int32Max), (-1, 0), (0, 0), (1.5, 1), (0.5, 0), (20, 20)]
        for (input, expected) in cases {
            XCTAssertEqual(NumericOption.historyLimit(input), expected, "\(input)")
        }
    }

    // MARK: - The retry policy

    private func policy(maxAttempts: Double = 5, base: Double = 1_000, max cap: Double = 60_000,
                        jitter: Bool = false, codes: [Double] = [503]) -> RetryPolicyValue {
        RetryPolicyValue(rawMaxAttempts: maxAttempts, backoff: .exponential, rawBaseDelayMs: base,
                         rawMaxDelayMs: cap, jitter: jitter, rawRetryOnStatusCodes: codes)
    }

    private func delays(_ policy: RetryPolicyValue) -> [Int] {
        (1...4).map { RetryPolicyEvaluator.nextDelayMs(attempt: $0, policy: policy, random: { 0.999 }) }
    }

    func test_retryPolicy_keepsTheNumbersAsGiven() {
        let given = policy(maxAttempts: .infinity, base: .nan, max: -.infinity, codes: [.nan, 503.7])

        XCTAssertEqual(given.rawMaxAttempts, .infinity)
        XCTAssertTrue(given.rawBaseDelayMs.isNaN)
        XCTAssertEqual(given.rawMaxDelayMs, -.infinity)
        XCTAssertEqual(given.rawRetryOnStatusCodes.count, 2)
        XCTAssertTrue(given.rawRetryOnStatusCodes[0].isNaN)
        XCTAssertEqual(given.maxAttempts, int32Max)
        XCTAssertEqual(given.retryOnStatusCodes, [503])
    }

    /// Android's result for a NaN cap: every delay 0, an immediate retry.
    func test_aNaNCap_makesEveryDelayZero() {
        XCTAssertEqual(delays(policy(max: .nan)), [0, 0, 0, 0])
        XCTAssertEqual(delays(policy(max: .nan, jitter: true)), [0, 0, 0, 0])
    }

    /// +Infinity is no cap on both platforms; -Infinity is no cap on iOS, by
    /// the rule that a cap of 0 or less is none.
    func test_anInfiniteCap_capsNothing() {
        XCTAssertEqual(delays(policy(max: .infinity)), [1_000, 2_000, 4_000, 8_000])
        XCTAssertEqual(delays(policy(max: -.infinity)), [1_000, 2_000, 4_000, 8_000])
    }

    func test_anInvalidBase_retriesAtOnceOrWaitsTheCeiling() {
        XCTAssertEqual(delays(policy(base: .nan)), [0, 0, 0, 0])
        XCTAssertEqual(delays(policy(base: -.infinity)), [0, 0, 0, 0])
        XCTAssertEqual(delays(policy(base: .infinity, max: 0)), [delayCeiling, delayCeiling, delayCeiling, delayCeiling])
        // iOS honors a cap below the base; Android raises the cap to the base.
        XCTAssertEqual(delays(policy(base: .infinity)), [60_000, 60_000, 60_000, 60_000])
    }

    func test_aWholeNumberPolicy_isUnchanged() {
        let whole = RetryPolicyValue(maxAttempts: 7, backoff: .linear, baseDelayMs: 250, maxDelayMs: 9_000,
                                     jitter: false, retryOnStatusCodes: [418, 503])

        XCTAssertEqual(whole.maxAttempts, 7)
        XCTAssertEqual(whole.baseDelayMs, 250)
        XCTAssertEqual(whole.maxDelayMs, 9_000)
        XCTAssertEqual(whole.retryOnStatusCodes, [418, 503])
        XCTAssertEqual(whole, RetryPolicyValue(rawMaxAttempts: 7, backoff: .linear, rawBaseDelayMs: 250,
                                               rawMaxDelayMs: 9_000, jitter: false, rawRetryOnStatusCodes: [418, 503]))
    }
}

/// What the configuration record keeps, read back by a fresh store as a cold
/// start does.
final class SyncConfigStoreNumbersTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suite: String!
    private var secrets: InMemorySecretStore!

    override func setUp() {
        super.setUp()
        (defaults, suite) = UserDefaults.ephemeral()
        secrets = InMemorySecretStore()
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    private func store() -> SyncConfigStore {
        SyncConfigStore(defaults: defaults, secrets: secrets)
    }

    private func options(_ value: Double) -> SyncOptionsSnapshot {
        SyncOptionsSnapshot(strategy: "AUTOMATIC",
                            retryPolicy: RetryPolicyValue(rawMaxAttempts: value, backoff: .fibonacci,
                                                          rawBaseDelayMs: value, rawMaxDelayMs: value,
                                                          jitter: true, rawRetryOnStatusCodes: [value, 503]),
                            batchSize: value,
                            requestTimeoutMs: value,
                            maxQueueSize: value,
                            persistQueue: true,
                            defaultHeaders: [:])
    }

    private func bits(_ options: SyncOptionsSnapshot) -> [UInt64] {
        let policy = options.retryPolicy
        return [options.batchSize, options.requestTimeoutMs, options.maxQueueSize, policy.rawMaxAttempts,
                policy.rawBaseDelayMs, policy.rawMaxDelayMs].map(\.bitPattern)
            + policy.rawRetryOnStatusCodes.map(\.bitPattern)
    }

    /// Every number, NaN, ±Infinity and 2^63 or more included, comes back
    /// with the same bits.
    func test_everyNumber_survivesTheRecord() {
        for value in [Double.nan, .infinity, -.infinity, 9_223_372_036_854_775_808, 1e300, 1.5, -1] {
            store().save(options(value))

            let loaded = store().load()

            XCTAssertEqual(loaded.source, .persisted, "\(value)")
            XCTAssertEqual(bits(loaded.options), bits(options(value)), "\(value)")
        }
    }

    /// The encoder used to refuse a NaN or an Infinity: the save was skipped
    /// and the next launch read the record before it.
    func test_aNonFiniteConfiguration_replacesThePreviousRecord() {
        store().save(options(5))
        store().save(options(.infinity))

        XCTAssertEqual(store().load().options.batchSize, .infinity)
    }

    /// The v0.3 record: whole retry numbers and the network-health keys.
    func test_aRecordWithWholeRetryNumbers_stillLoads() {
        defaults.set(Data("""
        {"strategy":"MANUAL","maxAttempts":3,"backoff":"LINEAR","baseDelayMs":100,"maxDelayMs":1000,\
        "jitter":false,"retryOnStatusCodes":[503,429],"batchSize":5,"requestTimeoutMs":2000,\
        "maxQueueSize":50,"persistQueue":true,"hasDefaultHeaders":false,"minNetworkQuality":"GOOD",\
        "healthCheckUrl":"https://health.test/ping","healthCheckTtlMs":5000,"healthCheckBackoffMs":1000}
        """.utf8), forKey: "config.v1")

        let loaded = store().load()

        XCTAssertEqual(loaded.source, .persisted)
        XCTAssertEqual(loaded.options.retryPolicy,
                       RetryPolicyValue(maxAttempts: 3, backoff: .linear, baseDelayMs: 100, maxDelayMs: 1_000,
                                        jitter: false, retryOnStatusCodes: [503, 429]))
        XCTAssertEqual(loaded.options.batchSize, 5)
        XCTAssertEqual(loaded.options.minNetworkQuality, .good)
        XCTAssertEqual(loaded.options.healthCheckUrl, "https://health.test/ping")
    }

    /// The fields 0.2.1 and v0.3 decode, with their types: a record of whole
    /// numbers written now is still one they read.
    private struct EarlierRecord: Decodable {
        let maxAttempts: Int
        let baseDelayMs: Int
        let maxDelayMs: Int
        let retryOnStatusCodes: [Int]
        let batchSize: Double
    }

    func test_aRecordOfWholeNumbers_isStillReadableByAnEarlierBuild() throws {
        store().save(SyncOptionsSnapshot.default)

        let data = try XCTUnwrap(defaults.data(forKey: "config.v1"))
        let earlier = try JSONDecoder().decode(EarlierRecord.self, from: data)

        XCTAssertEqual(earlier.maxAttempts, 5)
        XCTAssertEqual(earlier.baseDelayMs, 1_000)
        XCTAssertEqual(earlier.maxDelayMs, 60_000)
        XCTAssertEqual(earlier.retryOnStatusCodes, [408, 425, 429, 500, 502, 503, 504])
        XCTAssertEqual(earlier.batchSize, 25)
    }
}
