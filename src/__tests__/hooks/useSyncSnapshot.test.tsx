import { act, renderHook, waitFor } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';

import { SyncErrorCode } from '../../errors/SyncErrorCode';
import { useSyncSnapshot } from '../../hooks/useSyncSnapshot';
import {
  ConnectionStatus,
  ConnectionType,
  HttpMethod,
  NetworkQuality,
  SyncEventType,
} from '../../types/enums';
import type {
  SyncDeadLetterItem,
  SyncItem,
  SyncResult,
} from '../../types/sync';

interface SyncMock {
  getQueueSize: jest.Mock;
  getPendingItems: jest.Mock;
  listDeadLetter: jest.Mock;
  isSyncing: jest.Mock;
  getLastSyncResult: jest.Mock;
  getConnectionStatus: jest.Mock;
  addListener: jest.Mock;
  removeListener: jest.Mock;
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

const makeDeadLetter = (
  id: string,
  overrides: Partial<SyncDeadLetterItem> = {}
): SyncDeadLetterItem => ({
  id,
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  createdAt: 1,
  attempts: 5,
  lastAttemptAt: 2,
  lastErrorCode: SyncErrorCode.MAX_ATTEMPTS_EXCEEDED,
  deadLetteredAt: 3,
  ...overrides,
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

  it('leaves networkQuality and deadLetter undefined until their first read', () => {
    const { result } = renderHook(() => useSyncSnapshot());

    expect(result.current.networkQuality).toBeUndefined();
    expect(result.current.deadLetter).toBeUndefined();
    expect(result.current.connection.networkQuality).toBe(
      NetworkQuality.OFFLINE
    );
  });

  it('populates networkQuality and deadLetter from their first read', async () => {
    const mock = getMock();
    const rows = [makeDeadLetter('dead-1'), makeDeadLetter('dead-2')];
    mock.listDeadLetter.mockResolvedValueOnce(rows);

    const { result } = renderHook(() => useSyncSnapshot());

    await waitFor(() => expect(result.current.deadLetter).toEqual(rows));
    expect(result.current.networkQuality).toBe(NetworkQuality.GOOD);
    expect(result.current.connection.networkQuality).toBe(NetworkQuality.GOOD);
    expect(mock.listDeadLetter).toHaveBeenCalledTimes(1);
  });

  it('shows an empty dead-letter queue as an empty list, not undefined', async () => {
    const { result } = renderHook(() => useSyncSnapshot());

    await waitFor(() => expect(result.current.deadLetter).toEqual([]));
  });

  it('follows a CONNECTION_CHANGED that changes only the network quality', async () => {
    const mock = getMock();

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() =>
      expect(result.current.networkQuality).toBe(NetworkQuality.GOOD)
    );
    await drainSubscription();

    mock.getConnectionStatus.mockResolvedValueOnce({
      status: ConnectionStatus.CONNECTED,
      type: ConnectionType.WIFI,
      networkQuality: NetworkQuality.POOR,
    });

    await act(async () => {
      mock.__emit({
        type: SyncEventType.CONNECTION_CHANGED,
        timestamp: 1,
        connectionStatus: ConnectionStatus.CONNECTED,
      });
    });

    await waitFor(() =>
      expect(result.current.networkQuality).toBe(NetworkQuality.POOR)
    );
    expect(result.current.connection).toMatchObject({
      status: ConnectionStatus.CONNECTED,
      isOnline: true,
      networkQuality: NetworkQuality.POOR,
    });
  });

  it.each([
    SyncEventType.ITEM_DEAD_LETTERED,
    SyncEventType.ITEM_REMOVED,
    SyncEventType.ITEM_ENQUEUED,
    SyncEventType.QUEUE_CLEARED,
  ])('reads the dead-letter queue again on %s', async (type) => {
    const mock = getMock();

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() => expect(result.current.deadLetter).toEqual([]));
    await drainSubscription();

    const rows = [makeDeadLetter('dead-1')];
    mock.listDeadLetter.mockResolvedValueOnce(rows);

    await act(async () => {
      mock.__emit({ type, timestamp: 1, itemId: 'dead-1' });
    });

    await waitFor(() => expect(result.current.deadLetter).toEqual(rows));
    expect(mock.listDeadLetter).toHaveBeenCalledTimes(2);
  });

  it.each([
    SyncEventType.ITEM_FAILED,
    SyncEventType.SYNC_SUCCEEDED,
    SyncEventType.SYNC_FAILED,
    SyncEventType.CONNECTION_CHANGED,
  ])('does not read the dead-letter queue again on %s', async (type) => {
    const mock = getMock();

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() => expect(result.current.deadLetter).toEqual([]));
    await drainSubscription();

    await act(async () => {
      mock.__emit({
        type,
        timestamp: 1,
        connectionStatus: ConnectionStatus.CONNECTED,
      });
    });

    expect(mock.listDeadLetter).toHaveBeenCalledTimes(1);
  });

  it('reads the queue again on ITEM_DEAD_LETTERED, since the item left it', async () => {
    const mock = getMock();
    mock.getQueueSize.mockResolvedValueOnce(1);
    mock.getPendingItems.mockResolvedValueOnce([makeItem('a')]);

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() => expect(result.current.size).toBe(1));
    await drainSubscription();

    mock.getQueueSize.mockResolvedValueOnce(0);
    mock.getPendingItems.mockResolvedValueOnce([]);

    await act(async () => {
      mock.__emit({
        type: SyncEventType.ITEM_DEAD_LETTERED,
        timestamp: 1,
        itemId: 'a',
        errorCode: SyncErrorCode.DEAD_LETTERED,
        metadata: { lastErrorCode: SyncErrorCode.SERVER_ERROR },
      });
    });

    await waitFor(() => expect(result.current.size).toBe(0));
    expect(result.current.items).toEqual([]);
  });

  it('keeps the same deadLetter list when a read finds the same rows', async () => {
    const mock = getMock();
    mock.listDeadLetter.mockImplementation(async () => [
      makeDeadLetter('dead-1'),
    ]);

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() => expect(result.current.deadLetter).toHaveLength(1));
    await drainSubscription();
    const shown = result.current.deadLetter;

    await act(async () => {
      mock.__emit({
        type: SyncEventType.ITEM_ENQUEUED,
        timestamp: 1,
        itemId: 'x',
      });
    });
    await drainSubscription();

    expect(mock.listDeadLetter).toHaveBeenCalledTimes(2);
    // Each native read returns a new array: the hook kept the old one.
    expect(result.current.deadLetter).toBe(shown);
  });

  it('replaces the list when the same id comes back as a new dead letter', async () => {
    const mock = getMock();
    mock.listDeadLetter.mockResolvedValueOnce([
      makeDeadLetter('dead-1', { deadLetteredAt: 10 }),
    ]);

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() => expect(result.current.deadLetter).toHaveLength(1));
    await drainSubscription();

    const again = [makeDeadLetter('dead-1', { deadLetteredAt: 20 })];
    mock.listDeadLetter.mockResolvedValueOnce(again);

    await act(async () => {
      mock.__emit({
        type: SyncEventType.ITEM_DEAD_LETTERED,
        timestamp: 2,
        itemId: 'dead-1',
      });
    });

    await waitFor(() => expect(result.current.deadLetter).toEqual(again));
  });

  it('applies only the latest dead-letter read when reads finish out of order', async () => {
    const mock = getMock();

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() => expect(result.current.deadLetter).toEqual([]));
    await drainSubscription();

    let finishStaleRead: (rows: SyncDeadLetterItem[]) => void = () => {};
    mock.listDeadLetter
      .mockImplementationOnce(
        () =>
          new Promise<SyncDeadLetterItem[]>((resolve) => {
            finishStaleRead = resolve;
          })
      )
      .mockResolvedValueOnce([]);

    await act(async () => {
      // A failed item is dead-lettered, then replayed at once.
      mock.__emit({
        type: SyncEventType.ITEM_DEAD_LETTERED,
        timestamp: 1,
        itemId: 'a',
      });
      mock.__emit({
        type: SyncEventType.ITEM_ENQUEUED,
        timestamp: 2,
        itemId: 'a',
      });
    });
    await act(async () => {
      finishStaleRead([makeDeadLetter('a')]);
    });
    await drainSubscription();

    expect(mock.listDeadLetter).toHaveBeenCalledTimes(3);
    expect(result.current.deadLetter).toEqual([]);
  });

  it('keeps the last dead-letter list when a read fails, without reporting an error', async () => {
    const mock = getMock();
    const rows = [makeDeadLetter('dead-1')];
    mock.listDeadLetter.mockResolvedValueOnce(rows);

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() => expect(result.current.deadLetter).toEqual(rows));
    await drainSubscription();

    mock.listDeadLetter.mockRejectedValueOnce(new Error('store unavailable'));

    await act(async () => {
      mock.__emit({
        type: SyncEventType.QUEUE_CLEARED,
        timestamp: 1,
      });
    });
    await drainSubscription();

    expect(mock.listDeadLetter).toHaveBeenCalledTimes(2);
    expect(result.current.deadLetter).toEqual(rows);
    expect(result.current.error).toBeNull();
  });

  it('stays undefined when the first dead-letter read fails', async () => {
    const mock = getMock();
    mock.listDeadLetter.mockRejectedValueOnce(new Error('store unavailable'));

    const { result } = renderHook(() => useSyncSnapshot());
    await waitFor(() =>
      expect(result.current.networkQuality).toBe(NetworkQuality.GOOD)
    );
    await drainSubscription();

    expect(result.current.deadLetter).toBeUndefined();
    expect(result.current.error).toBeNull();
  });

  it('re-renders a bounded number of times for one dead-letter event', async () => {
    const mock = getMock();

    let renderCount = 0;
    const { result } = renderHook(() => {
      renderCount++;
      return useSyncSnapshot();
    });
    await waitFor(() => expect(result.current.deadLetter).toEqual([]));
    await drainSubscription();
    const baseline = renderCount;

    mock.listDeadLetter.mockResolvedValueOnce([makeDeadLetter('a')]);
    await act(async () => {
      mock.__emit({
        type: SyncEventType.ITEM_DEAD_LETTERED,
        timestamp: 1,
        itemId: 'a',
      });
    });
    await waitFor(() => expect(result.current.deadLetter).toHaveLength(1));

    // One render for the queue read, one for the new list, at most.
    expect(renderCount - baseline).toBeLessThanOrEqual(2);
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
