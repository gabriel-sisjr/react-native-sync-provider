import { describe, expect, it } from '@jest/globals';

import { flush } from '../../index';

interface SyncMock {
  flush: jest.Mock;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

describe('integration: retry-then-succeed', () => {
  it('reports a successful drain after a transient retry sequence', async () => {
    const mock = getMock();

    mock.flush
      .mockResolvedValueOnce({
        startedAt: 1,
        finishedAt: 2,
        successCount: 1,
        failureCount: 1,
        succeededIds: ['a'],
        failedIds: ['b'],
        errors: { b: 'NETWORK_ERROR' },
      })
      .mockResolvedValueOnce({
        startedAt: 3,
        finishedAt: 4,
        successCount: 1,
        failureCount: 0,
        succeededIds: ['b'],
        failedIds: [],
        errors: {},
      });

    const first = await flush();
    expect(first.failureCount).toBe(1);
    expect(first.errors).toEqual({ b: 'NETWORK_ERROR' });

    const second = await flush();
    expect(second.failureCount).toBe(0);
    expect(second.successCount).toBe(1);
    expect(second.succeededIds).toEqual(['b']);
  });
});
