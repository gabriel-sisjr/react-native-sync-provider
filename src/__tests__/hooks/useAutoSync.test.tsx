import { act, renderHook } from '@testing-library/react-native';
import { afterEach, beforeEach, describe, expect, it } from '@jest/globals';

import { useAutoSync } from '../../hooks/useAutoSync';
import {
  ConnectionStatus,
  ConnectionType,
  SyncEventType,
} from '../../types/enums';

interface SyncMock {
  flush: jest.Mock;
  getConnectionStatus: jest.Mock;
  isSyncing: jest.Mock;
  __emit: (event: unknown) => void;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

const connection = (
  status: ConnectionStatus,
  type: ConnectionType = ConnectionType.WIFI
) => ({ status, type });

const advance = async (ms: number) => {
  await act(async () => {
    jest.advanceTimersByTime(ms);
    await Promise.resolve();
    await Promise.resolve();
  });
};

const renderForegroundHook = () =>
  renderHook(() =>
    useAutoSync({
      enabled: true,
      intervalMs: 60_000,
      flushOnReconnect: false,
      flushOnAppForeground: true,
    })
  );

const returnToForeground = async () => {
  await act(async () => {
    (
      globalThis as unknown as {
        simulateAppStateChange: (s: string) => void;
      }
    ).simulateAppStateChange('active');
    await Promise.resolve();
    await Promise.resolve();
  });
};

describe('useAutoSync', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('does nothing when disabled', () => {
    renderHook(() => useAutoSync({ enabled: false }));
    jest.advanceTimersByTime(120_000);
    expect(getMock().flush).not.toHaveBeenCalled();
  });

  it('flushes on the configured interval when enabled', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);

    renderHook(() =>
      useAutoSync({ enabled: true, intervalMs: 1_000, flushOnReconnect: false })
    );

    // Allow microtasks/event listener registration to settle.
    await act(async () => {
      jest.advanceTimersByTime(0);
    });

    await act(async () => {
      jest.advanceTimersByTime(1_000);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(mock.flush).toHaveBeenCalled();
  });

  it('skips flush while a cycle is already in flight', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(true);

    renderHook(() =>
      useAutoSync({ enabled: true, intervalMs: 1_000, flushOnReconnect: false })
    );

    await act(async () => {
      jest.advanceTimersByTime(2_000);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(mock.flush).not.toHaveBeenCalled();
  });

  it('does not flush on interval ticks while DISCONNECTED', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);
    mock.getConnectionStatus.mockResolvedValue(
      connection(ConnectionStatus.DISCONNECTED, ConnectionType.NONE)
    );

    renderHook(() =>
      useAutoSync({ enabled: true, intervalMs: 1_000, flushOnReconnect: false })
    );

    await advance(3_000);

    expect(mock.getConnectionStatus).toHaveBeenCalledTimes(3);
    expect(mock.flush).not.toHaveBeenCalled();
  });

  it('ticks again once the device is back online', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);
    mock.getConnectionStatus.mockResolvedValue(
      connection(ConnectionStatus.DISCONNECTED, ConnectionType.NONE)
    );

    renderHook(() =>
      useAutoSync({ enabled: true, intervalMs: 1_000, flushOnReconnect: false })
    );

    await advance(1_000);
    expect(mock.flush).not.toHaveBeenCalled();

    mock.getConnectionStatus.mockResolvedValue(
      connection(ConnectionStatus.CONNECTED)
    );
    await advance(1_000);

    expect(mock.flush).toHaveBeenCalledTimes(1);
  });

  it.each([ConnectionStatus.UNKNOWN, ConnectionStatus.METERED])(
    'still flushes on interval ticks while %s',
    async (status) => {
      const mock = getMock();
      mock.isSyncing.mockResolvedValue(false);
      mock.getConnectionStatus.mockResolvedValue(connection(status));

      renderHook(() =>
        useAutoSync({
          enabled: true,
          intervalMs: 1_000,
          flushOnReconnect: false,
        })
      );

      await advance(1_000);

      expect(mock.flush).toHaveBeenCalledTimes(1);
    }
  );

  it('skips the tick when the connection status cannot be read', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);
    mock.getConnectionStatus.mockRejectedValue(new Error('native rejected'));

    renderHook(() =>
      useAutoSync({ enabled: true, intervalMs: 1_000, flushOnReconnect: false })
    );

    await advance(1_000);

    expect(mock.flush).not.toHaveBeenCalled();
  });

  it('keeps the reconnect flush while interval ticks are skipped', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);
    // Stays DISCONNECTED on purpose: the event alone must trigger the flush.
    mock.getConnectionStatus.mockResolvedValue(
      connection(ConnectionStatus.DISCONNECTED, ConnectionType.NONE)
    );

    renderHook(() =>
      useAutoSync({ enabled: true, intervalMs: 1_000, flushOnReconnect: true })
    );

    await advance(1_000);
    expect(mock.flush).not.toHaveBeenCalled();

    await act(async () => {
      mock.__emit({
        type: SyncEventType.CONNECTION_CHANGED,
        timestamp: 1,
        connectionStatus: ConnectionStatus.CONNECTED,
      });
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(mock.flush).toHaveBeenCalledTimes(1);
  });

  it('flushes on CONNECTED transition when flushOnReconnect=true', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);

    renderHook(() =>
      useAutoSync({
        enabled: true,
        intervalMs: 60_000,
        flushOnReconnect: true,
      })
    );

    // Let the listener registration promise settle.
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    await act(async () => {
      mock.__emit({
        type: SyncEventType.CONNECTION_CHANGED,
        timestamp: 1,
        connectionStatus: ConnectionStatus.CONNECTED,
      });
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(mock.flush).toHaveBeenCalled();
  });

  it('ignores METERED transitions unless flushOnMetered=true', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);

    renderHook(() =>
      useAutoSync({
        enabled: true,
        intervalMs: 60_000,
        flushOnReconnect: true,
        flushOnMetered: false,
      })
    );

    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    await act(async () => {
      mock.__emit({
        type: SyncEventType.CONNECTION_CHANGED,
        timestamp: 1,
        connectionStatus: ConnectionStatus.METERED,
      });
      await Promise.resolve();
    });

    expect(mock.flush).not.toHaveBeenCalled();
  });

  it('flushes on AppState=active when flushOnAppForeground=true', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);

    renderHook(() =>
      useAutoSync({
        enabled: true,
        intervalMs: 60_000,
        flushOnReconnect: false,
        flushOnAppForeground: true,
      })
    );

    await act(async () => {
      await Promise.resolve();
    });

    await act(async () => {
      (
        globalThis as unknown as {
          simulateAppStateChange: (s: string) => void;
        }
      ).simulateAppStateChange('active');
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(mock.flush).toHaveBeenCalled();
  });

  it('does not flush on AppState=active while DISCONNECTED', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);
    mock.getConnectionStatus.mockResolvedValue(
      connection(ConnectionStatus.DISCONNECTED, ConnectionType.NONE)
    );

    renderForegroundHook();
    await returnToForeground();

    expect(mock.getConnectionStatus).toHaveBeenCalledTimes(1);
    expect(mock.flush).not.toHaveBeenCalled();
  });

  it('flushes on the next AppState=active once the device is back online', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);
    mock.getConnectionStatus.mockResolvedValue(
      connection(ConnectionStatus.DISCONNECTED, ConnectionType.NONE)
    );

    renderForegroundHook();
    await returnToForeground();
    expect(mock.flush).not.toHaveBeenCalled();

    mock.getConnectionStatus.mockResolvedValue(
      connection(ConnectionStatus.CONNECTED)
    );
    await returnToForeground();

    expect(mock.flush).toHaveBeenCalledTimes(1);
  });

  it.each([ConnectionStatus.UNKNOWN, ConnectionStatus.METERED])(
    'still flushes on AppState=active while %s',
    async (status) => {
      const mock = getMock();
      mock.isSyncing.mockResolvedValue(false);
      mock.getConnectionStatus.mockResolvedValue(connection(status));

      renderForegroundHook();
      await returnToForeground();

      expect(mock.flush).toHaveBeenCalledTimes(1);
    }
  );

  it('skips the foreground flush when the connection status cannot be read', async () => {
    const mock = getMock();
    mock.isSyncing.mockResolvedValue(false);
    mock.getConnectionStatus.mockRejectedValue(new Error('native rejected'));

    renderForegroundHook();
    await returnToForeground();

    expect(mock.flush).not.toHaveBeenCalled();
  });
});
