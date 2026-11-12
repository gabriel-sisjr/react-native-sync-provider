import { describe, expect, it } from '@jest/globals';

import { SyncError } from '../../errors/SyncError';
import { SyncErrorCode } from '../../errors/SyncErrorCode';
import {
  BackoffStrategy,
  NetworkQuality,
  SyncStrategy,
} from '../../types/enums';
import type { SyncOptions } from '../../types/sync';
import { validateSyncOptions } from '../../utils/validateSyncOptions';

const BASE: SyncOptions = {
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy: {
    maxAttempts: 3,
    backoff: BackoffStrategy.EXPONENTIAL,
    baseDelayMs: 100,
    maxDelayMs: 5_000,
    jitter: false,
    retryOnStatusCodes: [500],
  },
};

/** Run the validator and return what it threw, or `undefined`. */
function thrownBy(options: unknown): SyncError | undefined {
  try {
    validateSyncOptions(options as SyncOptions);
    return undefined;
  } catch (err) {
    expect(err).toBeInstanceOf(SyncError);
    return err as SyncError;
  }
}

describe('validateSyncOptions — accepted options', () => {
  it('accepts options that set none of the checked fields', () => {
    expect(thrownBy(BASE)).toBeUndefined();
  });

  it('accepts the checked fields set to undefined', () => {
    expect(
      thrownBy({
        ...BASE,
        minNetworkQuality: undefined,
        healthCheckUrl: undefined,
        healthCheckTtlMs: undefined,
        healthCheckBackoffMs: undefined,
      })
    ).toBeUndefined();
  });

  it.each(Object.values(NetworkQuality))(
    'accepts minNetworkQuality %s',
    (quality) => {
      expect(thrownBy({ ...BASE, minNetworkQuality: quality })).toBeUndefined();
    }
  );

  it.each([
    'https://api.example.com/health',
    'http://10.0.2.2:8080/health?probe=1',
    'HTTPS://API.EXAMPLE.COM/HEALTH',
  ])('accepts healthCheckUrl %s', (url) => {
    expect(thrownBy({ ...BASE, healthCheckUrl: url })).toBeUndefined();
  });

  it.each([0, 1, 1.5, 30_000, 60_000, Number.MAX_SAFE_INTEGER])(
    'accepts %p for both health-check durations',
    (ms) => {
      expect(
        thrownBy({ ...BASE, healthCheckTtlMs: ms, healthCheckBackoffMs: ms })
      ).toBeUndefined();
    }
  );

  it('leaves the other fields to native, as before', () => {
    expect(
      thrownBy({ ...BASE, batchSize: -5, maxQueueSize: Number.NaN })
    ).toBeUndefined();
  });
});

describe('validateSyncOptions — options that are not an object', () => {
  it.each([null, undefined, 'options', 42])(
    'rejects %p with INVALID_PAYLOAD on the "options" field',
    (options) => {
      const error = thrownBy(options);

      expect(error?.code).toBe(SyncErrorCode.INVALID_PAYLOAD);
      expect(error?.validationErrors).toEqual([
        {
          field: 'options',
          reason: 'configureSync(options) requires a SyncOptions object.',
        },
      ]);
    }
  );
});

describe('validateSyncOptions — minNetworkQuality', () => {
  it.each(['UNKNOWN', 'excellent', '', 3, null])(
    'rejects %p with INVALID_PAYLOAD and records the value',
    (quality) => {
      const error = thrownBy({ ...BASE, minNetworkQuality: quality });

      expect(error?.code).toBe(SyncErrorCode.INVALID_PAYLOAD);
      expect(error?.message).toBe(
        'SyncOptions.minNetworkQuality must be one of OFFLINE, POOR, MODERATE, GOOD, EXCELLENT.'
      );
      expect(error?.validationErrors).toEqual([
        {
          field: 'minNetworkQuality',
          reason: error?.message,
          value: quality,
        },
      ]);
    }
  );
});

describe('validateSyncOptions — healthCheckUrl', () => {
  it.each([
    '',
    'ftp://files.example.com/health',
    'api.example.com/health',
    '/health',
    'https://',
    'https://api.example.com/he alth',
    42,
    null,
  ])('rejects %p with INVALID_URL', (url) => {
    const error = thrownBy({ ...BASE, healthCheckUrl: url });

    expect(error?.code).toBe(SyncErrorCode.INVALID_URL);
    expect(error?.validationErrors).toEqual([
      {
        field: 'healthCheckUrl',
        reason: 'SyncOptions.healthCheckUrl must be an absolute http(s) URL.',
      },
    ]);
  });

  it('never echoes the URL, which can carry a token', () => {
    const error = thrownBy({
      ...BASE,
      healthCheckUrl: 'https//api.example.com/health?token=s3cr3t',
    });

    expect(error?.code).toBe(SyncErrorCode.INVALID_URL);
    expect(error?.message).not.toContain('s3cr3t');
    expect(error?.validationErrors?.[0]).not.toHaveProperty('value');
    expect(JSON.stringify(error?.validationErrors)).not.toContain('s3cr3t');
  });
});

describe.each(['healthCheckTtlMs', 'healthCheckBackoffMs'] as const)(
  'validateSyncOptions — %s',
  (field) => {
    it.each([
      -1,
      -0.001,
      Number.NaN,
      Number.POSITIVE_INFINITY,
      Number.NEGATIVE_INFINITY,
      '60000',
      null,
    ])('rejects %p with INVALID_PAYLOAD and records the value', (value) => {
      const error = thrownBy({ ...BASE, [field]: value });

      expect(error?.code).toBe(SyncErrorCode.INVALID_PAYLOAD);
      expect(error?.message).toBe(
        `SyncOptions.${field} must be a finite number of at least 0.`
      );
      expect(error?.validationErrors).toEqual([
        { field, reason: error?.message, value },
      ]);
    });
  }
);

describe('validateSyncOptions — several issues', () => {
  it('lists every issue in field order and throws the first one', () => {
    const error = thrownBy({
      ...BASE,
      healthCheckBackoffMs: -1,
      healthCheckTtlMs: Number.NaN,
      healthCheckUrl: 'nope',
      minNetworkQuality: 'FAST',
    });

    expect(error?.code).toBe(SyncErrorCode.INVALID_PAYLOAD);
    expect(error?.message).toContain('minNetworkQuality');
    expect(error?.validationErrors?.map((issue) => issue.field)).toEqual([
      'minNetworkQuality',
      'healthCheckUrl',
      'healthCheckTtlMs',
      'healthCheckBackoffMs',
    ]);
  });

  it('throws INVALID_URL when the URL is the first issue', () => {
    const error = thrownBy({
      ...BASE,
      healthCheckUrl: 'nope',
      healthCheckTtlMs: -1,
    });

    expect(error?.code).toBe(SyncErrorCode.INVALID_URL);
    expect(error?.validationErrors?.map((issue) => issue.field)).toEqual([
      'healthCheckUrl',
      'healthCheckTtlMs',
    ]);
  });

  it('keeps the internal code and message off the public issues', () => {
    const error = thrownBy({ ...BASE, healthCheckTtlMs: -1 });

    for (const issue of error?.validationErrors ?? []) {
      expect(Object.keys(issue).sort()).toEqual(['field', 'reason', 'value']);
    }
  });
});
