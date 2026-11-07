import { act, renderHook, waitFor } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';

import { SyncErrorCode } from '../../errors/SyncErrorCode';
import { useOfflineQueue } from '../../hooks/useOfflineQueue';
import {
  ConnectionStatus,
  ConnectionType,
  HttpMethod,
  NetworkQuality,
  SyncEventType,
} from '../../types/enums';
import type { SyncItem } from '../../types/sync';

interface SyncMock {
  getQueueSize: jest.Mock;
  getPendingItems: jest.Mock;
  getConnectionStatus: jest.Mock;
  listDeadLetter: jest.Mock;
  __emit: (event: unknown) => void;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

const makeItem = (id: string): SyncItem => ({
  id,
  method: HttpMethod.POST,
  url: 'https://api.example.com/events',
  createdAt: 1,
  attempts: 0,
  nextRetryAt: 0,
});

/** Drain the async subscription so it has landed before emitting events. */
async function drainSubscription(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('useOfflineQueue', () => {
  it('reports an offline quality before the first connection read', () => {
    const { result } = renderHook(() => useOfflineQueue());

    expect(result.current.connection).toEqual({
      status: ConnectionStatus.UNKNOWN,
      type: ConnectionType.UNKNOWN,
      isOnline: false,
      isMetered: false,
      networkQuality: NetworkQuality.OFFLINE,
    });
  });

  it('mirrors networkQuality into connection', async () => {
    const { result } = renderHook(() => useOfflineQueue());

    await waitFor(() =>
      expect(result.current.connection.networkQuality).toBe(NetworkQuality.GOOD)
    );
    expect(result.current.connection.isOnline).toBe(true);
  });

  it('follows a CONNECTION_CHANGED that changes only the network quality', async () => {
    const mock = getMock();
    const { result } = renderHook(() => useOfflineQueue());
    await waitFor(() =>
      expect(result.current.connection.networkQuality).toBe(NetworkQuality.GOOD)
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
      expect(result.current.connection.networkQuality).toBe(NetworkQuality.POOR)
    );
  });

  it('refreshes the queue when ITEM_DEAD_LETTERED fires', async () => {
    const mock = getMock();
    mock.getQueueSize.mockResolvedValueOnce(1);
    mock.getPendingItems.mockResolvedValueOnce([makeItem('a')]);

    const { result } = renderHook(() => useOfflineQueue());
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
        metadata: { lastErrorCode: SyncErrorCode.MAX_ATTEMPTS_EXCEEDED },
      });
    });

    await waitFor(() => expect(result.current.size).toBe(0));
    expect(result.current.items).toEqual([]);
    expect(result.current.isWaitingForConnection).toBe(false);
    expect(mock.listDeadLetter).not.toHaveBeenCalled();
  });
});
