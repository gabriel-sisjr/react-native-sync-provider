import { describe, expect, it } from '@jest/globals';

import {
  discardDeadLetter,
  listDeadLetter,
  replayDeadLetter,
  SyncError,
  SyncErrorCode,
} from '../index';
import { HttpMethod, SyncPriority } from '../types/enums';
import type { SyncDeadLetterItem } from '../types/sync';

interface SyncMock {
  listDeadLetter: jest.Mock;
  replayDeadLetter: jest.Mock;
  discardDeadLetter: jest.Mock;
  removeItem: jest.Mock;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

const deadLetter = (
  id: string,
  overrides: Partial<SyncDeadLetterItem> = {}
): SyncDeadLetterItem => ({
  id,
  method: HttpMethod.POST,
  url: `https://api.example.com/${id}`,
  createdAt: 1_700_000_000_000,
  attempts: 5,
  lastAttemptAt: 1_700_000_100_000,
  lastErrorCode: SyncErrorCode.MAX_ATTEMPTS_EXCEEDED,
  deadLetteredAt: 1_700_000_100_001,
  ...overrides,
});

describe('listDeadLetter', () => {
  it('resolves with an empty list when there are no dead letters', async () => {
    await expect(listDeadLetter()).resolves.toEqual([]);
    expect(getMock().listDeadLetter).toHaveBeenCalledTimes(1);
  });

  it('returns the native list verbatim, in native order', async () => {
    const rows = [
      deadLetter('b', { deadLetteredAt: 1 }),
      deadLetter('a', {
        deadLetteredAt: 2,
        headers: { 'X-Trace': 't-1' },
        body: '{"k":1}',
        contentType: 'application/json',
        priority: SyncPriority.HIGH,
        metadata: { screen: 'checkout' },
        lastErrorCode: SyncErrorCode.SERVER_ERROR,
        lastErrorMessage: 'HTTP 422 Unprocessable Entity',
      }),
      // Migrated from an earlier version: no message, no attempt time.
      deadLetter('c', { deadLetteredAt: 3, lastAttemptAt: 0, attempts: 0 }),
    ];
    getMock().listDeadLetter.mockResolvedValueOnce(rows);

    const result = await listDeadLetter();

    expect(result).toEqual(rows);
    expect(result.map((row) => row.id)).toEqual(['b', 'a', 'c']);
    expect(result[2]?.lastErrorMessage).toBeUndefined();
  });

  it('rewraps a native rejection with INVALID_PAYLOAD, keeping message and cause', async () => {
    const nativeError = new Error('store unavailable');
    getMock().listDeadLetter.mockRejectedValueOnce(nativeError);

    const error = await listDeadLetter().catch((e: unknown) => e);

    expect(error).toBeInstanceOf(SyncError);
    expect(error).toMatchObject({
      code: SyncErrorCode.INVALID_PAYLOAD,
      message: 'store unavailable',
      cause: nativeError,
    });
    expect((error as SyncError).validationErrors).toBeUndefined();
  });

  it('preserves an already-typed SyncError', async () => {
    const inner = new SyncError(SyncErrorCode.NETWORK_ERROR, 'typed');
    getMock().listDeadLetter.mockRejectedValueOnce(inner);

    await expect(listDeadLetter()).rejects.toBe(inner);
  });
});

describe.each([
  ['replayDeadLetter', replayDeadLetter],
  ['discardDeadLetter', discardDeadLetter],
] as const)('%s', (name, run) => {
  const nativeMock = () => getMock()[name];

  it('forwards the id to native and resolves with undefined', async () => {
    await expect(run('01HZX0DEADLETTER0000000001')).resolves.toBeUndefined();

    expect(nativeMock()).toHaveBeenCalledTimes(1);
    expect(nativeMock()).toHaveBeenCalledWith('01HZX0DEADLETTER0000000001');
  });

  it('forwards any id unchanged, like removeItem: an unknown id is for native to ignore', async () => {
    await expect(run('')).resolves.toBeUndefined();
    await expect(run('not-a-ulid')).resolves.toBeUndefined();

    expect(nativeMock().mock.calls).toEqual([[''], ['not-a-ulid']]);
  });

  it('rewraps a native rejection with INVALID_PAYLOAD, keeping message and cause', async () => {
    const nativeError = new Error('QUEUE_FULL: the queue is at maxQueueSize');
    nativeMock().mockRejectedValueOnce(nativeError);

    const error = await run('x').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(SyncError);
    expect(error).toMatchObject({
      code: SyncErrorCode.INVALID_PAYLOAD,
      message: 'QUEUE_FULL: the queue is at maxQueueSize',
      cause: nativeError,
    });
  });

  it('falls back to a generic message for a rejection without one', async () => {
    nativeMock().mockRejectedValueOnce('not an Error');

    await expect(run('x')).rejects.toMatchObject({
      code: SyncErrorCode.INVALID_PAYLOAD,
      message: 'Native call failed with code INVALID_PAYLOAD.',
    });
  });

  it('preserves an already-typed SyncError', async () => {
    const inner = new SyncError(SyncErrorCode.QUEUE_FULL, 'full');
    nativeMock().mockRejectedValueOnce(inner);

    await expect(run('x')).rejects.toBe(inner);
  });

  it('does not go through removeItem', async () => {
    await run('x');

    expect(getMock().removeItem).not.toHaveBeenCalled();
  });
});
