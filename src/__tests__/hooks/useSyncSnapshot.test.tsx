import { act, renderHook, waitFor } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';

import { useSyncSnapshot } from '../../hooks/useSyncSnapshot';
import { ConnectionStatus, HttpMethod, SyncEventType } from '../../types/enums';
import type { SyncItem, SyncResult } from '../../types/sync';

interface SyncMock {
  getQueueSize: jest.Mock;
  getPendingItems: jest.Mock;
  isSyncing: jest.Mock;
  getLastSyncResult: jest.Mock;
  getConnectionStatus: jest.Mock;
  addListener: jest.Mock;
  removeListener: jest.Mock;
  __emit: (event: unknown) => void;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

const makeItem = (id: string): SyncItem => ({
  id,
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  createdAt: Date.now(),
});

const successResult = (): SyncResult => ({
  startedAt: 1,
  finishedAt: 2,
  successCount: 3,
  failureCount: 0,
  succeededIds: ['a', 'b', 'c'],
  failedIds: [],
  errors: {},
});

/**
 * Drain the async `addSyncEventListener` subscription so it has actually
 * landed before the test emits events. Mirrors the two-tick pattern used by
 * the other hook tests.
 */
async function drainSubscription(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('useSyncSnapshot', () => {
  it('hydrates initial state from the native mock defaults', async () => {
    const mock = getMock();
    const items = [makeItem('a'), makeItem('b')];
    mock.getQueueSize.mockResolvedValue(2);
    mock.getPendingItems.mockResolvedValue(items);

    const { result } = renderHook(() => useSyncSnapshot());

    await waitFor(() => expect(result.current.size).toBe(2));
    expect(result.current.items).toEqual(items);
    expect(result.current.connection.status).toBe(ConnectionStatus.CONNECTED);
    expect(result.current.connection.isOnline).toBe(true);
    expect(result.current.isSyncing).toBe(false);
    expect(result.current.isPaused).toBe(false);
    expect(result.current.progress).toBeNull();
    expect(result.current.lastResult).toBeNull();
    expect(result.current.error).toBeNull();
  });

  it('refreshes size/items when ITEM_ENQUEUED fires', async () => {
    const mock = getMock();
    mock.getQueueSize.mockResolvedValueOnce(0);
    mock.getPendingItems.mockResolvedValueOnce([]);

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() => expect(result.current.size).toBe(0));
    await drainSubscription();

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

  it('toggles isSyncing and updates lastResult on SYNC_STARTED → SYNC_SUCCEEDED', async () => {
    const mock = getMock();

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() => expect(mock.isSyncing).toBeDefined());
    await drainSubscription();

    await act(async () => {
      mock.__emit({ type: SyncEventType.SYNC_STARTED, timestamp: 1 });
    });
    await waitFor(() => expect(result.current.isSyncing).toBe(true));
    expect(result.current.progress).toBe(0);

    const ok = successResult();
    mock.getLastSyncResult.mockResolvedValueOnce(ok);

    await act(async () => {
      mock.__emit({ type: SyncEventType.SYNC_SUCCEEDED, timestamp: 2 });
    });

    await waitFor(() => expect(result.current.lastResult).toEqual(ok));
    expect(result.current.isSyncing).toBe(false);
    expect(result.current.progress).toBeNull();
  });

  it('refreshes connection on CONNECTION_CHANGED', async () => {
    const mock = getMock();

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() =>
      expect(result.current.connection.status).toBe(ConnectionStatus.CONNECTED)
    );
    await drainSubscription();

    mock.getConnectionStatus.mockResolvedValueOnce({
      status: 'DISCONNECTED',
      type: 'NONE',
      isInternetReachable: false,
      isExpensive: false,
    });

    await act(async () => {
      mock.__emit({
        type: SyncEventType.CONNECTION_CHANGED,
        timestamp: 1,
        connectionStatus: ConnectionStatus.DISCONNECTED,
      });
    });

    await waitFor(() =>
      expect(result.current.connection.status).toBe(
        ConnectionStatus.DISCONNECTED
      )
    );
    expect(result.current.connection.isOnline).toBe(false);
  });

  it('mirrors PAUSED/RESUMED into isPaused', async () => {
    const mock = getMock();

    const { result } = renderHook(() => useSyncSnapshot());
    await drainSubscription();

    await act(async () => {
      mock.__emit({ type: SyncEventType.PAUSED, timestamp: 1 });
    });
    await waitFor(() => expect(result.current.isPaused).toBe(true));

    await act(async () => {
      mock.__emit({ type: SyncEventType.RESUMED, timestamp: 2 });
    });
    await waitFor(() => expect(result.current.isPaused).toBe(false));
  });

  it('derives isWaitingForConnection when offline with pending items', async () => {
    const mock = getMock();
    mock.getQueueSize.mockResolvedValue(3);
    mock.getPendingItems.mockResolvedValue([makeItem('a')]);
    mock.getConnectionStatus.mockResolvedValue({
      status: 'DISCONNECTED',
      type: 'NONE',
      isInternetReachable: false,
      isExpensive: false,
    });

    const { result } = renderHook(() => useSyncSnapshot());

    await waitFor(() =>
      expect(result.current.isWaitingForConnection).toBe(true)
    );
    expect(result.current.connection.isOnline).toBe(false);
    expect(result.current.size).toBe(3);
  });

  it('leaves networkQuality and deadLetter undefined in v0.2', async () => {
    const { result } = renderHook(() => useSyncSnapshot());

    await waitFor(() => expect(result.current.size).toBe(0));
    expect(result.current.networkQuality).toBeUndefined();
    expect(result.current.deadLetter).toBeUndefined();
  });

  it('does not cause a per-field re-render storm for a single event (single-subscription guarantee)', async () => {
    const mock = getMock();

    let renderCount = 0;
    const { result } = renderHook(() => {
      renderCount++;
      return useSyncSnapshot();
    });

    // Let the initial fetches + subscription settle, then snapshot the count.
    await waitFor(() => expect(result.current.connection.isOnline).toBe(true));
    await drainSubscription();
    const baseline = renderCount;

    // SYNC_STARTED touches three pieces of state (isSyncing, progress, and a
    // queue refresh). React 18 batches the synchronous setState calls inside
    // the single listener callback, so this must NOT add three separate
    // renders — a bounded handful at most.
    await act(async () => {
      mock.__emit({ type: SyncEventType.SYNC_STARTED, timestamp: 1 });
    });
    await waitFor(() => expect(result.current.isSyncing).toBe(true));

    const rendersForOneEvent = renderCount - baseline;
    expect(rendersForOneEvent).toBeGreaterThan(0);
    // Far fewer than "one render per derived field" (the snapshot exposes 9+
    // fields). A single batched event must not approach that.
    expect(rendersForOneEvent).toBeLessThanOrEqual(2);
  });

  it('registers exactly one native listener and cleans it up on unmount (single-subscription)', async () => {
    const mock = getMock();

    const { unmount } = renderHook(() => useSyncSnapshot());

    // Let the mount fetches + async subscription settle.
    await drainSubscription();

    // Exactly one native listener registered — proves single-subscription.
    expect(mock.addListener).toHaveBeenCalledTimes(1);

    unmount();
    await act(async () => {
      await Promise.resolve();
    });

    // Exactly one cleanup.
    expect(mock.removeListener).toHaveBeenCalledTimes(1);
  });
});
