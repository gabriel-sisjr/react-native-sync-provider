import { describe, expect, it } from '@jest/globals';

import {
  configureSync,
  getSyncConfig,
  SyncError,
  SyncErrorCode,
} from '../index';
import { BackoffStrategy, NetworkQuality, SyncStrategy } from '../types/enums';
import type { SyncOptions } from '../types/sync';

interface SyncMock {
  configureSync: jest.Mock;
  getSyncConfig: jest.Mock;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

const BASE: SyncOptions = {
  strategy: SyncStrategy.AUTOMATIC,
  retryPolicy: {
    maxAttempts: 5,
    backoff: BackoffStrategy.EXPONENTIAL,
    baseDelayMs: 1_000,
    maxDelayMs: 60_000,
    jitter: true,
    retryOnStatusCodes: [408, 425, 429, 500, 502, 503, 504],
  },
};

describe('configureSync — network quality and health-check options', () => {
  it('forwards valid options to native unchanged', async () => {
    const options: SyncOptions = {
      ...BASE,
      minNetworkQuality: NetworkQuality.MODERATE,
      healthCheckUrl: 'https://api.example.com/health',
      healthCheckTtlMs: 0,
      healthCheckBackoffMs: 45_000,
    };

    await expect(configureSync(options)).resolves.toBeUndefined();

    expect(getMock().configureSync).toHaveBeenCalledTimes(1);
    expect(getMock().configureSync.mock.calls[0]?.[0]).toBe(options);
  });

  it('still forwards options that set none of them', async () => {
    await configureSync(BASE);

    expect(getMock().configureSync).toHaveBeenCalledWith(BASE);
  });

  it.each([
    [
      'an invalid healthCheckUrl',
      { healthCheckUrl: 'api.example.com/health' },
      SyncErrorCode.INVALID_URL,
      'healthCheckUrl',
    ],
    [
      'an unknown minNetworkQuality',
      { minNetworkQuality: 'FAST' },
      SyncErrorCode.INVALID_PAYLOAD,
      'minNetworkQuality',
    ],
    [
      'a negative healthCheckTtlMs',
      { healthCheckTtlMs: -1 },
      SyncErrorCode.INVALID_PAYLOAD,
      'healthCheckTtlMs',
    ],
    [
      'an infinite healthCheckBackoffMs',
      { healthCheckBackoffMs: Number.POSITIVE_INFINITY },
      SyncErrorCode.INVALID_PAYLOAD,
      'healthCheckBackoffMs',
    ],
  ])(
    'rejects %s before any native call',
    async (_label, invalid, code, field) => {
      const error = await configureSync({
        ...BASE,
        ...(invalid as Partial<SyncOptions>),
      }).catch((e: unknown) => e);

      expect(error).toBeInstanceOf(SyncError);
      expect(error).toMatchObject({ code });
      expect((error as SyncError).validationErrors?.[0]?.field).toBe(field);
      expect(getMock().configureSync).not.toHaveBeenCalled();
    }
  );

  it('rejects options that are not an object before any native call', async () => {
    await expect(
      configureSync(null as unknown as SyncOptions)
    ).rejects.toMatchObject({
      code: SyncErrorCode.INVALID_PAYLOAD,
      validationErrors: [expect.objectContaining({ field: 'options' })],
    });
    expect(getMock().configureSync).not.toHaveBeenCalled();
  });

  it('still maps a native rejection to INVALID_PAYLOAD without validationErrors', async () => {
    getMock().configureSync.mockRejectedValueOnce(
      new Error('INVALID_URL: rejected natively')
    );

    const error = await configureSync({
      ...BASE,
      healthCheckUrl: 'https://api.example.com/health',
    }).catch((e: unknown) => e);

    expect(error).toMatchObject({
      code: SyncErrorCode.INVALID_PAYLOAD,
      message: 'INVALID_URL: rejected natively',
    });
    expect((error as SyncError).validationErrors).toBeUndefined();
  });

  it('getSyncConfig returns the options native echoes, without filling defaults', async () => {
    const echoed: SyncOptions = {
      ...BASE,
      healthCheckUrl: 'https://api.example.com/health',
    };
    getMock().getSyncConfig.mockResolvedValueOnce(echoed);

    const config = await getSyncConfig();

    expect(config).toEqual(echoed);
    expect(config).not.toHaveProperty('healthCheckTtlMs');
    expect(config).not.toHaveProperty('healthCheckBackoffMs');
    expect(config).not.toHaveProperty('minNetworkQuality');
  });
});
