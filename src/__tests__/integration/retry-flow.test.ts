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

    // `b` went back to wait for a retry (`ITEM_RETRYING`), so the first result
    // does not list it: `failedIds` and `errors` hold permanent failures only,
    // and those are dead letters that no later flush sends.
    mock.flush
      .mockResolvedValueOnce({
        startedAt: 1,
        finishedAt: 2,
        successCount: 1,
        failureCount: 0,
        succeededIds: ['a'],
        failedIds: [],
        errors: {},
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
    expect(first.failureCount).toBe(0);
    expect(first.succeededIds).toEqual(['a']);
    expect(first.failedIds).toEqual([]);
    expect(first.errors).toEqual({});

    const second = await flush();
    expect(second.failureCount).toBe(0);
    expect(second.successCount).toBe(1);
    expect(second.succeededIds).toEqual(['b']);
  });
});
