import { act, renderHook, waitFor } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';

import { useSyncStatus } from '../../hooks/useSyncStatus';
import { SyncEventType } from '../../types/enums';
import type { SyncResult } from '../../types/sync';

interface SyncMock {
  flush: jest.Mock;
  pauseSync: jest.Mock;
  resumeSync: jest.Mock;
  isSyncing: jest.Mock;
  getLastSyncResult: jest.Mock;
  __emit: (event: unknown) => void;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

const successResult = (): SyncResult => ({
  startedAt: 1,
  finishedAt: 2,
  successCount: 3,
  failureCount: 0,
  succeededIds: ['a', 'b', 'c'],
  failedIds: [],
  errors: {},
});

describe('useSyncStatus', () => {
  it('returns inert defaults on mount', () => {
    const { result } = renderHook(() => useSyncStatus());
    expect(result.current.isSyncing).toBe(false);
    expect(result.current.isPaused).toBe(false);
    expect(result.current.progress).toBeNull();
    expect(result.current.lastResult).toBeNull();
  });

  it('hydrates lastResult when getLastSyncResult returns non-sentinel', async () => {
    const mock = getMock();
    const expected = successResult();
    mock.getLastSyncResult.mockResolvedValueOnce(expected);

    const { result } = renderHook(() => useSyncStatus());

    await waitFor(() => expect(result.current.lastResult).toEqual(expected));
  });

  it('reflects SYNC_STARTED → SYNC_PROGRESS → SYNC_SUCCEEDED transitions', async () => {
    const mock = getMock();
    mock.getLastSyncResult.mockResolvedValue({
      startedAt: 0,
      finishedAt: 0,
      successCount: 0,
      failureCount: 0,
      succeededIds: [],
      failedIds: [],
      errors: {},
    });

    const { result } = renderHook(() => useSyncStatus());

    // Wait for async addListener subscription to land before emitting.
    await waitFor(() => expect(mock.flush).toBeDefined());
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    await act(async () => {
      mock.__emit({ type: SyncEventType.SYNC_STARTED, timestamp: 1 });
    });
    await waitFor(() => expect(result.current.isSyncing).toBe(true));
    expect(result.current.progress).toBe(0);

    await act(async () => {
      mock.__emit({
        type: SyncEventType.SYNC_PROGRESS,
        timestamp: 2,
        progress: 0.5,
      });
    });
    expect(result.current.progress).toBe(0.5);

    const ok = successResult();
    mock.getLastSyncResult.mockResolvedValueOnce(ok);

    await act(async () => {
      mock.__emit({ type: SyncEventType.SYNC_SUCCEEDED, timestamp: 3 });
    });

    await waitFor(() => expect(result.current.lastResult).toEqual(ok));
    expect(result.current.isSyncing).toBe(false);
    expect(result.current.progress).toBeNull();
  });

  it('mirrors PAUSED/RESUMED events into isPaused', async () => {
    const mock = getMock();
    const { result } = renderHook(() => useSyncStatus());

    // Wait for async addListener subscription to land before emitting.
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    await act(async () => {
      mock.__emit({ type: SyncEventType.PAUSED, timestamp: 1 });
    });
    await waitFor(() => expect(result.current.isPaused).toBe(true));

    await act(async () => {
      mock.__emit({ type: SyncEventType.RESUMED, timestamp: 2 });
    });
    await waitFor(() => expect(result.current.isPaused).toBe(false));
  });

  it('forwards flush/pause/resume to the native layer', async () => {
    const mock = getMock();
    const ok = successResult();
    mock.flush.mockResolvedValueOnce(ok);

    const { result } = renderHook(() => useSyncStatus());

    let returned: SyncResult | undefined;
    await act(async () => {
      returned = await result.current.flush();
    });
    expect(returned).toEqual(ok);
    expect(mock.flush).toHaveBeenCalledTimes(1);

    await act(async () => {
      await result.current.pause();
    });
    expect(mock.pauseSync).toHaveBeenCalledTimes(1);

    await act(async () => {
      await result.current.resume();
    });
    expect(mock.resumeSync).toHaveBeenCalledTimes(1);
  });
});
