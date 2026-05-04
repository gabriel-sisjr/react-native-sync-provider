import { describe, expect, it } from '@jest/globals';

import {
  addSyncEventListener,
  clearQueue,
  clearSyncHistory,
  configureSync,
  disableBackgroundSync,
  enableBackgroundSync,
  enqueue,
  enqueueBatch,
  flush,
  getConnectionStatus,
  getLastSyncResult,
  getPendingItems,
  getQueueSize,
  getSyncConfig,
  getSyncHistory,
  isBackgroundSyncEnabled,
  isSyncing,
  pauseSync,
  removeItem,
  removeSyncEventListener,
  resumeSync,
  SyncError,
  SyncErrorCode,
  SYNC_EVENT_CHANNEL,
} from '../index';
import { BackoffStrategy, HttpMethod, SyncStrategy } from '../types/enums';

interface SyncMock {
  enqueue: jest.Mock;
  enqueueBatch: jest.Mock;
  removeItem: jest.Mock;
  clearQueue: jest.Mock;
  getQueueSize: jest.Mock;
  getPendingItems: jest.Mock;
  flush: jest.Mock;
  pauseSync: jest.Mock;
  resumeSync: jest.Mock;
  isSyncing: jest.Mock;
  configureSync: jest.Mock;
  getSyncConfig: jest.Mock;
  getLastSyncResult: jest.Mock;
  getSyncHistory: jest.Mock;
  clearSyncHistory: jest.Mock;
  getConnectionStatus: jest.Mock;
  enableBackgroundSync: jest.Mock;
  disableBackgroundSync: jest.Mock;
  isBackgroundSyncEnabled: jest.Mock;
  addListener: jest.Mock;
  removeListener: jest.Mock;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

describe('facade — queue ops', () => {
  it('enqueue forwards a validated input to the native layer', async () => {
    const mock = getMock();
    mock.enqueue.mockResolvedValueOnce('id-123');
    const id = await enqueue({
      method: HttpMethod.POST,
      url: 'https://api.example.com/x',
    });
    expect(id).toBe('id-123');
    expect(mock.enqueue).toHaveBeenCalledTimes(1);
  });

  it('enqueue throws SyncError(INVALID_URL) before reaching native', async () => {
    const mock = getMock();
    await expect(
      enqueue({ method: HttpMethod.POST, url: 'not-a-url' })
    ).rejects.toMatchObject({
      code: SyncErrorCode.INVALID_URL,
    });
    expect(mock.enqueue).not.toHaveBeenCalled();
  });

  it('enqueueBatch validates every item and forwards the array', async () => {
    const mock = getMock();
    mock.enqueueBatch.mockResolvedValueOnce(['a', 'b']);
    const ids = await enqueueBatch([
      { method: HttpMethod.POST, url: 'https://a.test/x' },
      { method: HttpMethod.POST, url: 'https://b.test/y' },
    ]);
    expect(ids).toEqual(['a', 'b']);
  });

  it('enqueueBatch rejects non-array input with INVALID_PAYLOAD', async () => {
    await expect(
      enqueueBatch('nope' as unknown as Parameters<typeof enqueueBatch>[0])
    ).rejects.toMatchObject({ code: SyncErrorCode.INVALID_PAYLOAD });
  });

  it('removeItem / clearQueue / getQueueSize / getPendingItems pass through', async () => {
    const mock = getMock();
    mock.removeItem.mockResolvedValueOnce(true);
    mock.clearQueue.mockResolvedValueOnce(undefined);
    mock.getQueueSize.mockResolvedValueOnce(7);
    mock.getPendingItems.mockResolvedValueOnce([]);

    expect(await removeItem('x')).toBe(true);
    await expect(clearQueue()).resolves.toBeUndefined();
    expect(await getQueueSize()).toBe(7);
    expect(await getPendingItems()).toEqual([]);
  });
});

describe('facade — sync ops', () => {
  it('flush returns the native SyncResult', async () => {
    const mock = getMock();
    const expected = {
      startedAt: 1,
      finishedAt: 2,
      successCount: 1,
      failureCount: 0,
      succeededIds: ['a'],
      failedIds: [],
      errors: {},
    };
    mock.flush.mockResolvedValueOnce(expected);
    expect(await flush()).toEqual(expected);
  });

  it('flush rewraps non-SyncError rejections with NETWORK_ERROR', async () => {
    const mock = getMock();
    mock.flush.mockRejectedValueOnce(new Error('socket reset'));
    await expect(flush()).rejects.toMatchObject({
      code: SyncErrorCode.NETWORK_ERROR,
    });
  });

  it('flush preserves an already-typed SyncError', async () => {
    const mock = getMock();
    const inner = new SyncError(SyncErrorCode.UNAUTHORIZED, 'forbidden');
    mock.flush.mockRejectedValueOnce(inner);
    await expect(flush()).rejects.toBe(inner);
  });

  it('pauseSync / resumeSync / isSyncing pass through', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValueOnce(true);
    await expect(pauseSync()).resolves.toBeUndefined();
    await expect(resumeSync()).resolves.toBeUndefined();
    expect(await isSyncing()).toBe(true);
  });
});

describe('facade — config + history + connection + bg', () => {
  it('configureSync / getSyncConfig round-trip the options shape', async () => {
    const mock = getMock();
    const options = {
      strategy: SyncStrategy.AUTOMATIC,
      retryPolicy: {
        maxAttempts: 4,
        backoff: BackoffStrategy.EXPONENTIAL,
        baseDelayMs: 500,
        maxDelayMs: 30_000,
        jitter: true,
        retryOnStatusCodes: [503],
      },
    };
    await configureSync(options);
    expect(mock.configureSync).toHaveBeenCalledWith(options);

    mock.getSyncConfig.mockResolvedValueOnce(options);
    expect(await getSyncConfig()).toEqual(options);
  });

  it('getLastSyncResult translates the sentinel to undefined', async () => {
    const mock = getMock();
    mock.getLastSyncResult.mockResolvedValueOnce({
      startedAt: 0,
      finishedAt: 0,
      successCount: 0,
      failureCount: 0,
      succeededIds: [],
      failedIds: [],
      errors: {},
    });
    expect(await getLastSyncResult()).toBeUndefined();
  });

  it('getLastSyncResult returns the result when not the sentinel', async () => {
    const mock = getMock();
    const real = {
      startedAt: 1,
      finishedAt: 2,
      successCount: 1,
      failureCount: 0,
      succeededIds: ['a'],
      failedIds: [],
      errors: {},
    };
    mock.getLastSyncResult.mockResolvedValueOnce(real);
    expect(await getLastSyncResult()).toEqual(real);
  });

  it('getSyncHistory passes 0 when limit omitted', async () => {
    const mock = getMock();
    mock.getSyncHistory.mockResolvedValueOnce([]);
    await getSyncHistory();
    expect(mock.getSyncHistory).toHaveBeenCalledWith(0);

    mock.getSyncHistory.mockResolvedValueOnce([]);
    await getSyncHistory(20);
    expect(mock.getSyncHistory).toHaveBeenLastCalledWith(20);
  });

  it('clearSyncHistory passes through', async () => {
    const mock = getMock();
    await clearSyncHistory();
    expect(mock.clearSyncHistory).toHaveBeenCalledTimes(1);
  });

  it('getConnectionStatus passes through', async () => {
    const mock = getMock();
    mock.getConnectionStatus.mockResolvedValueOnce({
      status: 'CONNECTED',
      type: 'WIFI',
    });
    expect((await getConnectionStatus()).status).toBe('CONNECTED');
  });

  it('enableBackgroundSync rewraps native rejection with BACKGROUND_TASK_REGISTRATION_FAILED', async () => {
    const mock = getMock();
    mock.enableBackgroundSync.mockRejectedValueOnce(new Error('plist'));
    await expect(
      enableBackgroundSync({ minimumIntervalMs: 60_000 })
    ).rejects.toMatchObject({
      code: SyncErrorCode.BACKGROUND_TASK_REGISTRATION_FAILED,
    });

    mock.disableBackgroundSync.mockResolvedValueOnce(undefined);
    await expect(disableBackgroundSync()).resolves.toBeUndefined();

    mock.isBackgroundSyncEnabled.mockResolvedValueOnce(true);
    expect(await isBackgroundSyncEnabled()).toBe(true);
  });
});

describe('facade — listeners', () => {
  it('addSyncEventListener subscribes on the canonical channel', async () => {
    const mock = getMock();
    mock.addListener.mockResolvedValueOnce('sub-1');
    const id = await addSyncEventListener(() => undefined);
    expect(id).toBe('sub-1');
    expect(mock.addListener).toHaveBeenCalledTimes(1);
    expect(mock.addListener.mock.calls[0]?.[0]).toBe(SYNC_EVENT_CHANNEL);
  });

  it('removeSyncEventListener forwards the subscription id', async () => {
    const mock = getMock();
    await removeSyncEventListener('sub-1');
    expect(mock.removeListener).toHaveBeenCalledWith(
      SYNC_EVENT_CHANNEL,
      'sub-1'
    );
  });
});
