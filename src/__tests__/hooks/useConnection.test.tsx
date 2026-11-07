import { act, renderHook, waitFor } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';

import { useConnection } from '../../hooks/useConnection';
import {
  ConnectionStatus,
  ConnectionType,
  NetworkQuality,
  SyncEventType,
} from '../../types/enums';
import type { ConnectionState } from '../../types/connection';

interface SyncMock {
  getConnectionStatus: jest.Mock;
  __emit: (event: unknown) => void;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

/** Drain the async subscription so it has landed before emitting events. */
async function drainSubscription(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

const connectionChanged = (status: ConnectionStatus) => ({
  type: SyncEventType.CONNECTION_CHANGED,
  timestamp: Date.now(),
  connectionStatus: status,
});

describe('useConnection', () => {
  it('returns inert initial state synchronously', () => {
    const { result } = renderHook(() => useConnection());
    expect(result.current.status).toBe(ConnectionStatus.UNKNOWN);
    expect(result.current.type).toBe(ConnectionType.UNKNOWN);
    expect(result.current.isOnline).toBe(false);
    expect(result.current.isMetered).toBe(false);
    expect(result.current.networkQuality).toBe(NetworkQuality.OFFLINE);
  });

  it('mirrors networkQuality from the initial snapshot', async () => {
    const mock = getMock();
    mock.getConnectionStatus.mockResolvedValueOnce({
      status: ConnectionStatus.METERED,
      type: ConnectionType.CELLULAR,
      networkQuality: NetworkQuality.MODERATE,
    });

    const { result } = renderHook(() => useConnection());

    await waitFor(() =>
      expect(result.current.networkQuality).toBe(NetworkQuality.MODERATE)
    );
    expect(result.current).toEqual({
      status: ConnectionStatus.METERED,
      type: ConnectionType.CELLULAR,
      isOnline: true,
      isMetered: true,
      networkQuality: NetworkQuality.MODERATE,
    });
  });

  it('follows a CONNECTION_CHANGED that changes only the network quality', async () => {
    const mock = getMock();
    const { result } = renderHook(() => useConnection());
    await waitFor(() =>
      expect(result.current.networkQuality).toBe(NetworkQuality.GOOD)
    );
    await drainSubscription();

    mock.getConnectionStatus.mockResolvedValueOnce({
      status: ConnectionStatus.CONNECTED,
      type: ConnectionType.WIFI,
      networkQuality: NetworkQuality.EXCELLENT,
    });

    await act(async () => {
      mock.__emit(connectionChanged(ConnectionStatus.CONNECTED));
    });

    await waitFor(() =>
      expect(result.current.networkQuality).toBe(NetworkQuality.EXCELLENT)
    );
    expect(result.current.status).toBe(ConnectionStatus.CONNECTED);
  });

  it('keeps the same result object when a snapshot changes nothing it shows', async () => {
    const mock = getMock();
    const { result } = renderHook(() => useConnection());
    await waitFor(() =>
      expect(result.current.status).toBe(ConnectionStatus.CONNECTED)
    );
    await drainSubscription();
    const shown = result.current;

    // Only a field the hook does not expose changed.
    mock.getConnectionStatus.mockResolvedValueOnce({
      status: ConnectionStatus.CONNECTED,
      type: ConnectionType.WIFI,
      networkQuality: NetworkQuality.GOOD,
      isInternetReachable: false,
    });

    await act(async () => {
      mock.__emit(connectionChanged(ConnectionStatus.CONNECTED));
    });
    await drainSubscription();

    expect(mock.getConnectionStatus).toHaveBeenCalledTimes(2);
    expect(result.current).toBe(shown);
  });

  describe('when the snapshot cannot be read again after an event', () => {
    const renderWith = async (initial: ConnectionState) => {
      const mock = getMock();
      mock.getConnectionStatus.mockResolvedValueOnce(initial);
      const rendered = renderHook(() => useConnection());
      await waitFor(() =>
        expect(rendered.result.current.status).toBe(initial.status)
      );
      await drainSubscription();
      mock.getConnectionStatus.mockRejectedValueOnce(new Error('gone'));
      return rendered;
    };

    it('reports OFFLINE once the event says the link is gone', async () => {
      const { result } = await renderWith({
        status: ConnectionStatus.CONNECTED,
        type: ConnectionType.WIFI,
        networkQuality: NetworkQuality.EXCELLENT,
      });

      await act(async () => {
        getMock().__emit(connectionChanged(ConnectionStatus.DISCONNECTED));
      });

      await waitFor(() =>
        expect(result.current.status).toBe(ConnectionStatus.DISCONNECTED)
      );
      expect(result.current).toMatchObject({
        type: ConnectionType.WIFI,
        isOnline: false,
        networkQuality: NetworkQuality.OFFLINE,
      });
    });

    it.each([
      [ConnectionStatus.CONNECTED, NetworkQuality.GOOD],
      [ConnectionStatus.METERED, NetworkQuality.MODERATE],
    ])(
      'reports the unmeasured quality when it comes back %s',
      async (status, quality) => {
        const { result } = await renderWith({
          status: ConnectionStatus.DISCONNECTED,
          type: ConnectionType.NONE,
          networkQuality: NetworkQuality.OFFLINE,
        });

        await act(async () => {
          getMock().__emit(connectionChanged(status));
        });

        await waitFor(() => expect(result.current.status).toBe(status));
        expect(result.current.isOnline).toBe(true);
        expect(result.current.networkQuality).toBe(quality);
      }
    );

    it('keeps the last known quality while it stays online', async () => {
      const { result } = await renderWith({
        status: ConnectionStatus.CONNECTED,
        type: ConnectionType.WIFI,
        networkQuality: NetworkQuality.POOR,
      });

      await act(async () => {
        getMock().__emit(connectionChanged(ConnectionStatus.METERED));
      });

      await waitFor(() =>
        expect(result.current.status).toBe(ConnectionStatus.METERED)
      );
      expect(result.current.networkQuality).toBe(NetworkQuality.POOR);
    });
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
