import { act, renderHook } from '@testing-library/react-native';
import { afterEach, beforeEach, describe, expect, it } from '@jest/globals';

import { useAutoSync } from '../../hooks/useAutoSync';
import { ConnectionStatus, SyncEventType } from '../../types/enums';

interface SyncMock {
  flush: jest.Mock;
  isSyncing: jest.Mock;
  __emit: (event: unknown) => void;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

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
});
