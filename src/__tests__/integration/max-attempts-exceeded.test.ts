import { describe, expect, it } from '@jest/globals';

import { flush, SyncErrorCode } from '../../index';

interface SyncMock {
  flush: jest.Mock;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

describe('integration: max attempts exceeded', () => {
  it('surfaces per-item MAX_ATTEMPTS_EXCEEDED via SyncResult.errors', async () => {
    const mock = getMock();
    mock.flush.mockResolvedValueOnce({
      startedAt: 1,
      finishedAt: 9,
      successCount: 0,
      failureCount: 2,
      succeededIds: [],
      failedIds: ['x', 'y'],
      errors: {
        x: SyncErrorCode.MAX_ATTEMPTS_EXCEEDED,
        y: SyncErrorCode.MAX_ATTEMPTS_EXCEEDED,
      },
    });

    const res = await flush();
    expect(res.failureCount).toBe(2);
    expect(res.failedIds).toEqual(['x', 'y']);
    expect(res.errors).toEqual({
      x: SyncErrorCode.MAX_ATTEMPTS_EXCEEDED,
      y: SyncErrorCode.MAX_ATTEMPTS_EXCEEDED,
    });
  });

  it('rewraps a thrown MAX_ATTEMPTS_EXCEEDED as a SyncError', async () => {
    const mock = getMock();
    const err = Object.assign(new Error('exceeded'), {
      code: SyncErrorCode.MAX_ATTEMPTS_EXCEEDED,
    });
    mock.flush.mockRejectedValueOnce(err);

    await expect(flush()).rejects.toMatchObject({
      // Non-SyncError rejection → wrapped with NETWORK_ERROR fallback.
      code: SyncErrorCode.NETWORK_ERROR,
    });
  });
});
