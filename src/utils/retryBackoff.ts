import { BackoffStrategy } from '../types/enums';
import type { RetryPolicy } from '../types/sync';

/**
 * Arguments for {@link computeNextDelayMs}.
 */
export interface ComputeNextDelayArgs {
  /**
   * 1-based attempt number for the *next* retry. The first retry is
   * `attempt === 1`. Values <= 0 are clamped to 1.
   */
  attempt: number;
  /** Active retry policy. */
  policy: RetryPolicy;
}

/**
 * Compute the delay (in integer milliseconds) before the next retry, given an
 * attempt number and a {@link RetryPolicy}.
 *
 * Mirrors the formulas declared on {@link BackoffStrategy}:
 *   - `LINEAR`     → `base * attempt`
 *   - `EXPONENTIAL`→ `base * 2^(attempt - 1)`
 *   - `FIBONACCI`  → `base * fib(attempt)` (with `fib(1) = fib(2) = 1`)
 *
 * Jitter, when enabled, multiplies the result by a uniform `[0.75, 1.25]`
 * factor to spread synchronized retries. The final value is clamped to
 * `policy.maxDelayMs` and rounded to the nearest integer ms.
 *
 * @param args — see {@link ComputeNextDelayArgs}.
 * @returns the next delay in integer ms (>= 0, <= `policy.maxDelayMs`).
 *
 * @example
 * computeNextDelayMs({
 *   attempt: 3,
 *   policy: {
 *     maxAttempts: 5,
 *     backoff: BackoffStrategy.EXPONENTIAL,
 *     baseDelayMs: 1000,
 *     maxDelayMs: 60_000,
 *     jitter: false,
 *     retryOnStatusCodes: [503],
 *   },
 * }); // → 4000
 *
 * @internal
 */
export function computeNextDelayMs({
  attempt,
  policy,
}: ComputeNextDelayArgs): number {
  const safeAttempt = Math.max(1, Math.floor(attempt));
  const base = Math.max(0, policy.baseDelayMs);

  let raw: number;
  switch (policy.backoff) {
    case BackoffStrategy.LINEAR:
      raw = base * safeAttempt;
      break;
    case BackoffStrategy.EXPONENTIAL:
      raw = base * Math.pow(2, safeAttempt - 1);
      break;
    case BackoffStrategy.FIBONACCI:
      raw = base * fibonacci(safeAttempt);
      break;
    default: {
      // Treat any unknown strategy as exponential; never throw — backoff is a
      // hot path on the dispatch loop.
      raw = base * Math.pow(2, safeAttempt - 1);
    }
  }

  if (policy.jitter) {
    // Uniform ±25% around `raw`.
    const factor = 0.75 + Math.random() * 0.5;
    raw = raw * factor;
  }

  const cap = policy.maxDelayMs > 0 ? policy.maxDelayMs : raw;
  const clamped = Math.min(Math.max(0, raw), cap);
  return Math.round(clamped);
}

/**
 * Iterative Fibonacci with `fib(1) = fib(2) = 1`.
 *
 * Iterative (vs recursive) keeps the function safe for high `attempt` values
 * — even though `RetryPolicy.maxAttempts` typically caps growth, callers
 * occasionally pass large probes in tests.
 *
 * @internal
 */
function fibonacci(n: number): number {
  if (n <= 0) return 0;
  if (n <= 2) return 1;
  let prev = 1;
  let curr = 1;
  for (let i = 3; i <= n; i += 1) {
    const next = prev + curr;
    prev = curr;
    curr = next;
  }
  return curr;
}
