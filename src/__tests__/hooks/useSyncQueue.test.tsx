import { act, renderHook, waitFor } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';

import { useSyncQueue } from '../../hooks/useSyncQueue';
import { HttpMethod, SyncEventType } from '../../types/enums';
import type { SyncItem } from '../../types/sync';

interface SyncMock {
  enqueue: jest.Mock;
  enqueueBatch: jest.Mock;
  removeItem: jest.Mock;
  clearQueue: jest.Mock;
  getQueueSize: jest.Mock;
  getPendingItems: jest.Mock;
  listDeadLetter: jest.Mock;
  replayDeadLetter: jest.Mock;
  discardDeadLetter: jest.Mock;
  __emit: (event: unknown) => void;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

const makeItem = (id: string, overrides: Partial<SyncItem> = {}): SyncItem => ({
  id,
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  createdAt: Date.now(),
  attempts: 0,
  nextRetryAt: 0,
  ...overrides,
});

describe('useSyncQueue', () => {
  it('hydrates size and items from the native layer', async () => {
    const mock = getMock();
    const items = [makeItem('a'), makeItem('b')];
    mock.getQueueSize.mockResolvedValue(2);
    mock.getPendingItems.mockResolvedValue(items);

    const { result } = renderHook(() => useSyncQueue());

    await waitFor(() => expect(result.current.size).toBe(2));
    expect(result.current.items).toEqual(items);
    expect(result.current.error).toBeNull();
  });

  it('refreshes when ITEM_ENQUEUED fires', async () => {
    const mock = getMock();
    mock.getQueueSize.mockResolvedValueOnce(0);
    mock.getPendingItems.mockResolvedValueOnce([]);

    const { result } = renderHook(() => useSyncQueue());
    await waitFor(() => expect(result.current.size).toBe(0));

    mock.getQueueSize.mockResolvedValueOnce(1);
    mock.getPendingItems.mockResolvedValueOnce([makeItem('x')]);

    await act(async () => {
      mock.__emit({
        type: SyncEventType.ITEM_ENQUEUED,
        timestamp: Date.now(),
        itemId: 'x',
      });
    });

    await waitFor(() => expect(result.current.size).toBe(1));
    expect(result.current.items).toHaveLength(1);
  });

  it('refreshes when ITEM_DEAD_LETTERED fires, since the item left the queue', async () => {
    const mock = getMock();
    mock.getQueueSize.mockResolvedValueOnce(1);
    mock.getPendingItems.mockResolvedValueOnce([makeItem('a')]);

    const { result } = renderHook(() => useSyncQueue());
    await waitFor(() => expect(result.current.size).toBe(1));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    mock.getQueueSize.mockResolvedValueOnce(0);
    mock.getPendingItems.mockResolvedValueOnce([]);

    await act(async () => {
      mock.__emit({
        type: SyncEventType.ITEM_DEAD_LETTERED,
        timestamp: Date.now(),
        itemId: 'a',
        errorCode: 'DEAD_LETTERED',
        metadata: { lastErrorCode: 'SERVER_ERROR' },
      });
    });

    await waitFor(() => expect(result.current.size).toBe(0));
    expect(result.current.items).toEqual([]);
    expect(mock.getQueueSize).toHaveBeenCalledTimes(2);
  });

  it('keeps dead letters out of items and never reads or changes them', async () => {
    const mock = getMock();

    const { result } = renderHook(() => useSyncQueue());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    await act(async () => {
      mock.__emit({
        type: SyncEventType.ITEM_DEAD_LETTERED,
        timestamp: Date.now(),
        itemId: 'a',
      });
    });
    await waitFor(() => expect(mock.getQueueSize).toHaveBeenCalledTimes(2));

    expect(mock.listDeadLetter).not.toHaveBeenCalled();
    expect(mock.replayDeadLetter).not.toHaveBeenCalled();
    expect(mock.discardDeadLetter).not.toHaveBeenCalled();
  });

  it('forwards enqueue/clearQueue to the native layer', async () => {
    const mock = getMock();
    mock.enqueue.mockResolvedValueOnce('new-id');
    mock.clearQueue.mockResolvedValueOnce(undefined);

    const { result } = renderHook(() => useSyncQueue());

    await waitFor(() => expect(result.current.size).toBe(0));

    let id: string | undefined;
    await act(async () => {
      id = await result.current.enqueue({
        method: HttpMethod.POST,
        url: 'https://api.example.com/x',
      });
    });
    expect(id).toBe('new-id');
    expect(mock.enqueue).toHaveBeenCalledTimes(1);

    await act(async () => {
      await result.current.clearQueue();
    });
    expect(mock.clearQueue).toHaveBeenCalledTimes(1);
  });

  it('captures errors from refresh into result.error', async () => {
    const mock = getMock();
    mock.getQueueSize.mockRejectedValueOnce(new Error('boom'));

    const { result } = renderHook(() => useSyncQueue());

    await waitFor(() => {
      expect(result.current.error).not.toBeNull();
    });
  });
});
