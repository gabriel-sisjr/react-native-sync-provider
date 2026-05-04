import { act, renderHook, waitFor } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';

import { useConnection } from '../../hooks/useConnection';
import {
  ConnectionStatus,
  ConnectionType,
  SyncEventType,
} from '../../types/enums';

interface SyncMock {
  getConnectionStatus: jest.Mock;
  __emit: (event: unknown) => void;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

describe('useConnection', () => {
  it('returns inert initial state synchronously', () => {
    const { result } = renderHook(() => useConnection());
    expect(result.current.status).toBe(ConnectionStatus.UNKNOWN);
    expect(result.current.type).toBe(ConnectionType.UNKNOWN);
    expect(result.current.isOnline).toBe(false);
    expect(result.current.isMetered).toBe(false);
  });

  it('mirrors the initial snapshot from getConnectionStatus()', async () => {
    const mock = getMock();
    mock.getConnectionStatus.mockResolvedValueOnce({
      status: ConnectionStatus.CONNECTED,
      type: ConnectionType.WIFI,
      isInternetReachable: true,
      isExpensive: false,
    });

    const { result } = renderHook(() => useConnection());

    await waitFor(() => {
      expect(result.current.status).toBe(ConnectionStatus.CONNECTED);
    });
    expect(result.current.type).toBe(ConnectionType.WIFI);
    expect(result.current.isOnline).toBe(true);
    expect(result.current.isMetered).toBe(false);
  });

  it('updates on CONNECTION_CHANGED events', async () => {
    const mock = getMock();
    mock.getConnectionStatus.mockResolvedValueOnce({
      status: ConnectionStatus.CONNECTED,
      type: ConnectionType.WIFI,
    });

    const { result } = renderHook(() => useConnection());
    await waitFor(() =>
      expect(result.current.status).toBe(ConnectionStatus.CONNECTED)
    );

    mock.getConnectionStatus.mockResolvedValueOnce({
      status: ConnectionStatus.METERED,
      type: ConnectionType.CELLULAR,
    });

    await act(async () => {
      mock.__emit({
        type: SyncEventType.CONNECTION_CHANGED,
        timestamp: Date.now(),
        connectionStatus: ConnectionStatus.METERED,
      });
    });

    await waitFor(() => {
      expect(result.current.status).toBe(ConnectionStatus.METERED);
    });
    expect(result.current.type).toBe(ConnectionType.CELLULAR);
    expect(result.current.isOnline).toBe(true);
    expect(result.current.isMetered).toBe(true);
  });
});
