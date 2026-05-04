import { afterEach, beforeEach, describe, expect, it } from '@jest/globals';

import { BackoffStrategy } from '../../types/enums';
import type { RetryPolicy } from '../../types/sync';
import { computeNextDelayMs } from '../../utils/retryBackoff';

const basePolicy: RetryPolicy = {
  maxAttempts: 5,
  backoff: BackoffStrategy.EXPONENTIAL,
  baseDelayMs: 1_000,
  maxDelayMs: 60_000,
  jitter: false,
  retryOnStatusCodes: [503],
};

describe('computeNextDelayMs', () => {
  describe('LINEAR', () => {
    it('returns base * attempt', () => {
      const policy: RetryPolicy = {
        ...basePolicy,
        backoff: BackoffStrategy.LINEAR,
      };
      expect(computeNextDelayMs({ attempt: 1, policy })).toBe(1_000);
      expect(computeNextDelayMs({ attempt: 3, policy })).toBe(3_000);
    });
  });

  describe('EXPONENTIAL', () => {
    it('returns base * 2^(attempt - 1)', () => {
      expect(computeNextDelayMs({ attempt: 1, policy: basePolicy })).toBe(
        1_000
      );
      expect(computeNextDelayMs({ attempt: 2, policy: basePolicy })).toBe(
        2_000
      );
      expect(computeNextDelayMs({ attempt: 3, policy: basePolicy })).toBe(
        4_000
      );
    });

    it('clamps to maxDelayMs', () => {
      const policy: RetryPolicy = { ...basePolicy, maxDelayMs: 5_000 };
      expect(computeNextDelayMs({ attempt: 10, policy })).toBe(5_000);
    });
  });

  describe('FIBONACCI', () => {
    it('returns base * fib(attempt) with fib(1)=fib(2)=1', () => {
      const policy: RetryPolicy = {
        ...basePolicy,
        backoff: BackoffStrategy.FIBONACCI,
        baseDelayMs: 1,
        maxDelayMs: 1_000,
      };
      expect(computeNextDelayMs({ attempt: 1, policy })).toBe(1);
      expect(computeNextDelayMs({ attempt: 2, policy })).toBe(1);
      expect(computeNextDelayMs({ attempt: 3, policy })).toBe(2);
      expect(computeNextDelayMs({ attempt: 5, policy })).toBe(5);
      expect(computeNextDelayMs({ attempt: 7, policy })).toBe(13);
    });
  });

  describe('attempt clamping', () => {
    it('treats attempt <= 0 as 1', () => {
      expect(computeNextDelayMs({ attempt: 0, policy: basePolicy })).toBe(
        1_000
      );
      expect(computeNextDelayMs({ attempt: -3, policy: basePolicy })).toBe(
        1_000
      );
    });

    it('floors fractional attempts', () => {
      expect(computeNextDelayMs({ attempt: 2.9, policy: basePolicy })).toBe(
        2_000
      );
    });
  });

  describe('jitter', () => {
    let randomSpy: jest.SpiedFunction<typeof Math.random>;

    beforeEach(() => {
      randomSpy = jest.spyOn(Math, 'random');
    });

    afterEach(() => {
      randomSpy.mockRestore();
    });

    it('multiplies the raw delay by [0.75, 1.25]', () => {
      randomSpy.mockReturnValue(0); // factor = 0.75
      const lower = computeNextDelayMs({
        attempt: 1,
        policy: { ...basePolicy, jitter: true, baseDelayMs: 1_000 },
      });
      expect(lower).toBe(750);

      randomSpy.mockReturnValue(0.999999); // factor ≈ 1.25
      const upper = computeNextDelayMs({
        attempt: 1,
        policy: { ...basePolicy, jitter: true, baseDelayMs: 1_000 },
      });
      expect(upper).toBeGreaterThanOrEqual(1_249);
      expect(upper).toBeLessThanOrEqual(1_250);
    });
  });

  describe('maxDelayMs <= 0', () => {
    it('does not clamp when cap is non-positive', () => {
      const policy: RetryPolicy = { ...basePolicy, maxDelayMs: 0 };
      expect(computeNextDelayMs({ attempt: 4, policy })).toBe(8_000);
    });
  });

  describe('unknown backoff', () => {
    it('falls back to exponential', () => {
      const policy = {
        ...basePolicy,
        backoff: 'NOPE' as unknown as RetryPolicy['backoff'],
      };
      expect(computeNextDelayMs({ attempt: 3, policy })).toBe(4_000);
    });
  });
});
