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

/** Lets every pending promise chain run, without moving the fake clock. */
const settle = async () => {
  await act(async () => {
    for (let i = 0; i < 10; i += 1) {
      await Promise.resolve();
    }
  });
};

const emitStatus = async (status: ConnectionStatus) => {
  await act(async () => {
    getMock().__emit({
      type: SyncEventType.CONNECTION_CHANGED,
      timestamp: 1,
      connectionStatus: status,
    });
  });
  await settle();
};

const renderReconnectHook = (flushOnMetered = false) =>
  renderHook(() =>
    useAutoSync({
      enabled: true,
      intervalMs: 60_000,
      flushOnReconnect: true,
      flushOnMetered,
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
    // The status at mount is the starting point: a transition needs a change.
    mock.getConnectionStatus.mockResolvedValue(
      connection(ConnectionStatus.DISCONNECTED, ConnectionType.NONE)
    );

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

  describe('reconnect trigger', () => {
    beforeEach(() => {
      getMock().isSyncing.mockResolvedValue(false);
    });

    it('does not flush on a quality-only change right after mount', async () => {
      const mock = getMock();
      // Already CONNECTED at mount: native reports no event for that state.
      renderReconnectHook();
      await settle();

      // The first measurement changed only `networkQuality`.
      await emitStatus(ConnectionStatus.CONNECTED);

      expect(mock.getConnectionStatus).toHaveBeenCalledTimes(1);
      expect(mock.flush).not.toHaveBeenCalled();
    });

    it('flushes once on a real offline-to-online transition, not on the quality-only changes after it', async () => {
      const mock = getMock();
      mock.getConnectionStatus.mockResolvedValue(
        connection(ConnectionStatus.DISCONNECTED, ConnectionType.NONE)
      );
      renderReconnectHook();
      await settle();

      await emitStatus(ConnectionStatus.CONNECTED);
      expect(mock.flush).toHaveBeenCalledTimes(1);

      await emitStatus(ConnectionStatus.CONNECTED);
      await emitStatus(ConnectionStatus.CONNECTED);
      expect(mock.flush).toHaveBeenCalledTimes(1);

      await emitStatus(ConnectionStatus.DISCONNECTED);
      await emitStatus(ConnectionStatus.CONNECTED);
      expect(mock.flush).toHaveBeenCalledTimes(2);
    });

    it('still flushes on a change from METERED to CONNECTED', async () => {
      const mock = getMock();
      mock.getConnectionStatus.mockResolvedValue(
        connection(ConnectionStatus.METERED, ConnectionType.CELLULAR)
      );
      renderReconnectHook();
      await settle();

      await emitStatus(ConnectionStatus.CONNECTED);

      expect(mock.flush).toHaveBeenCalledTimes(1);
    });

    it('with flushOnMetered, flushes on a change to METERED but not on a METERED quality-only change', async () => {
      const mock = getMock();
      mock.getConnectionStatus.mockResolvedValue(
        connection(ConnectionStatus.METERED, ConnectionType.CELLULAR)
      );
      renderReconnectHook(true);
      await settle();

      await emitStatus(ConnectionStatus.METERED);
      expect(mock.flush).not.toHaveBeenCalled();

      await emitStatus(ConnectionStatus.DISCONNECTED);
      await emitStatus(ConnectionStatus.METERED);
      expect(mock.flush).toHaveBeenCalledTimes(1);
    });

    it.each([
      [ConnectionStatus.DISCONNECTED, 1],
      [ConnectionStatus.CONNECTED, 0],
    ])(
      'judges an event that arrives before the starting status (%s) is read against it',
      async (startingStatus, flushes) => {
        const mock = getMock();
        let resolveStart: (value: unknown) => void = () => undefined;
        mock.getConnectionStatus.mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              resolveStart = resolve;
            })
        );
        renderReconnectHook();
        await settle();

        // The listener is registered; the starting status is still unread.
        await emitStatus(ConnectionStatus.CONNECTED);
        expect(mock.flush).not.toHaveBeenCalled();

        await act(async () => {
          resolveStart(connection(startingStatus));
        });
        await settle();

        expect(mock.flush).toHaveBeenCalledTimes(flushes);
      }
    );

    it('only records the first event when the starting status cannot be read', async () => {
      const mock = getMock();
      mock.getConnectionStatus.mockRejectedValueOnce(
        new Error('native rejected')
      );
      renderReconnectHook();
      await settle();

      await emitStatus(ConnectionStatus.CONNECTED);
      expect(mock.flush).not.toHaveBeenCalled();

      await emitStatus(ConnectionStatus.DISCONNECTED);
      await emitStatus(ConnectionStatus.CONNECTED);
      expect(mock.flush).toHaveBeenCalledTimes(1);
    });

    it('reads the starting status again when it is enabled again', async () => {
      const mock = getMock();
      mock.getConnectionStatus.mockResolvedValue(
        connection(ConnectionStatus.DISCONNECTED, ConnectionType.NONE)
      );
      const { rerender } = renderHook(
        ({ enabled }: { enabled: boolean }) =>
          useAutoSync({ enabled, intervalMs: 60_000, flushOnReconnect: true }),
        { initialProps: { enabled: true } }
      );
      await settle();

      rerender({ enabled: false });
      await settle();
      // The device came online while the hook was off.
      mock.getConnectionStatus.mockResolvedValue(
        connection(ConnectionStatus.CONNECTED)
      );
      rerender({ enabled: true });
      await settle();

      await emitStatus(ConnectionStatus.CONNECTED);

      expect(mock.getConnectionStatus).toHaveBeenCalledTimes(2);
      expect(mock.flush).not.toHaveBeenCalled();
    });

    it('reads no starting status when flushOnReconnect is false', async () => {
      const mock = getMock();
      renderHook(() =>
        useAutoSync({
          enabled: true,
          intervalMs: 60_000,
          flushOnReconnect: false,
        })
      );
      await settle();

      await emitStatus(ConnectionStatus.CONNECTED);

      expect(mock.getConnectionStatus).not.toHaveBeenCalled();
      expect(mock.flush).not.toHaveBeenCalled();
    });
  });
});
