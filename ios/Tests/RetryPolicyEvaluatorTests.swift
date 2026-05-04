import XCTest
@testable import SyncProvider

final class RetryPolicyEvaluatorTests: XCTestCase {
    // MARK: - Linear backoff (no jitter)

    func test_linear_noJitter_growsLinearly() {
        let policy = RetryPolicyValue(maxAttempts: 5,
                                      backoff: .linear,
                                      baseDelayMs: 1_000,
                                      maxDelayMs: 60_000,
                                      jitter: false,
                                      retryOnStatusCodes: [])
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 1, policy: policy), 1_000)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 2, policy: policy), 2_000)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 3, policy: policy), 3_000)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 4, policy: policy), 4_000)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 5, policy: policy), 5_000)
    }

    // MARK: - Exponential backoff (no jitter)

    func test_exponential_noJitter_doublesEachAttempt() {
        let policy = RetryPolicyValue(maxAttempts: 5,
                                      backoff: .exponential,
                                      baseDelayMs: 100,
                                      maxDelayMs: 60_000,
                                      jitter: false,
                                      retryOnStatusCodes: [])
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 1, policy: policy), 100)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 2, policy: policy), 200)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 3, policy: policy), 400)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 4, policy: policy), 800)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 5, policy: policy), 1_600)
    }

    // MARK: - Fibonacci backoff

    func test_fibonacci_noJitter_followsSequence() {
        let policy = RetryPolicyValue(maxAttempts: 6,
                                      backoff: .fibonacci,
                                      baseDelayMs: 100,
                                      maxDelayMs: 60_000,
                                      jitter: false,
                                      retryOnStatusCodes: [])
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 1, policy: policy), 100)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 2, policy: policy), 100)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 3, policy: policy), 200)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 4, policy: policy), 300)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 5, policy: policy), 500)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 6, policy: policy), 800)
    }

    // MARK: - Cap

    func test_maxDelay_capsExponentialOutput() {
        let policy = RetryPolicyValue(maxAttempts: 10,
                                      backoff: .exponential,
                                      baseDelayMs: 1_000,
                                      maxDelayMs: 5_000,
                                      jitter: false,
                                      retryOnStatusCodes: [])
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 4, policy: policy), 5_000)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 5, policy: policy), 5_000)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 10, policy: policy), 5_000)
    }

    // MARK: - Jitter

    func test_jitter_keepsResultWithin75to125PercentOfRaw() {
        // Raw exponential value at attempt 3 with base 1000 = 4000ms.
        // Jitter factor is in [0.75, 1.25), so result must be in [3000, 5000) — capped at maxDelay.
        let policy = RetryPolicyValue(maxAttempts: 5,
                                      backoff: .exponential,
                                      baseDelayMs: 1_000,
                                      maxDelayMs: 60_000,
                                      jitter: true,
                                      retryOnStatusCodes: [])
        for _ in 0..<200 {
            let delay = RetryPolicyEvaluator.nextDelayMs(attempt: 3, policy: policy)
            XCTAssertGreaterThanOrEqual(delay, 3_000)
            XCTAssertLessThan(delay, 5_000)
        }
    }

    func test_jitter_withCap_clampsHighEnd() {
        let policy = RetryPolicyValue(maxAttempts: 5,
                                      backoff: .exponential,
                                      baseDelayMs: 10_000,
                                      maxDelayMs: 1_000,
                                      jitter: true,
                                      retryOnStatusCodes: [])
        for _ in 0..<50 {
            let delay = RetryPolicyEvaluator.nextDelayMs(attempt: 5, policy: policy)
            XCTAssertLessThanOrEqual(delay, 1_000)
        }
    }

    // MARK: - Defensive inputs

    func test_attemptZero_treatedAsAttemptOne() {
        let policy = RetryPolicyValue(maxAttempts: 5,
                                      backoff: .linear,
                                      baseDelayMs: 200,
                                      maxDelayMs: 60_000,
                                      jitter: false,
                                      retryOnStatusCodes: [])
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 0, policy: policy), 200)
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: -10, policy: policy), 200)
    }

    func test_negativeBase_clampedToZero() {
        let policy = RetryPolicyValue(maxAttempts: 3,
                                      backoff: .linear,
                                      baseDelayMs: -500,
                                      maxDelayMs: 60_000,
                                      jitter: false,
                                      retryOnStatusCodes: [])
        XCTAssertEqual(RetryPolicyEvaluator.nextDelayMs(attempt: 2, policy: policy), 0)
    }

    // MARK: - shouldRetry predicate

    func test_shouldRetry_returnsTrue_forListedStatusCodes() {
        let policy = RetryPolicyValue.default
        XCTAssertTrue(RetryPolicyEvaluator.shouldRetry(statusCode: 408, policy: policy))
        XCTAssertTrue(RetryPolicyEvaluator.shouldRetry(statusCode: 425, policy: policy))
        XCTAssertTrue(RetryPolicyEvaluator.shouldRetry(statusCode: 429, policy: policy))
        XCTAssertTrue(RetryPolicyEvaluator.shouldRetry(statusCode: 500, policy: policy))
        XCTAssertTrue(RetryPolicyEvaluator.shouldRetry(statusCode: 502, policy: policy))
        XCTAssertTrue(RetryPolicyEvaluator.shouldRetry(statusCode: 503, policy: policy))
        XCTAssertTrue(RetryPolicyEvaluator.shouldRetry(statusCode: 504, policy: policy))
    }

    func test_shouldRetry_returnsFalse_forUnlistedCodes() {
        let policy = RetryPolicyValue.default
        XCTAssertFalse(RetryPolicyEvaluator.shouldRetry(statusCode: 200, policy: policy))
        XCTAssertFalse(RetryPolicyEvaluator.shouldRetry(statusCode: 400, policy: policy))
        XCTAssertFalse(RetryPolicyEvaluator.shouldRetry(statusCode: 401, policy: policy))
        XCTAssertFalse(RetryPolicyEvaluator.shouldRetry(statusCode: 404, policy: policy))
        XCTAssertFalse(RetryPolicyEvaluator.shouldRetry(statusCode: 501, policy: policy))
    }

    // MARK: - Performance baseline (must compute in < 100µs)

    func test_perf_nextDelayMs_isFast() {
        let policy = RetryPolicyValue.default
        measure {
            for i in 1...10 {
                _ = RetryPolicyEvaluator.nextDelayMs(attempt: i, policy: policy)
            }
        }
    }
}
