import { act, renderHook } from '@testing-library/react-native';
import { describe, expect, it } from '@jest/globals';

import { useSyncEvents } from '../../hooks/useSyncEvents';
import { SyncEventType } from '../../types/enums';
import type { SyncEvent } from '../../types/sync';

interface SyncMock {
  __emit: (event: unknown) => void;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

describe('useSyncEvents', () => {
  it('does not subscribe when enabled=false', async () => {
    const onEvent = jest.fn();
    renderHook(() => useSyncEvents({ enabled: false, onEvent }));

    await act(async () => {
      getMock().__emit({ type: SyncEventType.SYNC_STARTED, timestamp: 1 });
      await Promise.resolve();
    });

    expect(onEvent).not.toHaveBeenCalled();
  });

  it('calls onEvent for every emitted event when types is omitted', async () => {
    const onEvent = jest.fn();
    renderHook(() => useSyncEvents({ onEvent }));

    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    await act(async () => {
      getMock().__emit({ type: SyncEventType.SYNC_STARTED, timestamp: 1 });
      getMock().__emit({ type: SyncEventType.SYNC_SUCCEEDED, timestamp: 2 });
      await Promise.resolve();
    });

    expect(onEvent).toHaveBeenCalledTimes(2);
  });

  it('filters events by types when supplied', async () => {
    const onEvent = jest.fn();
    renderHook(() =>
      useSyncEvents({
        types: [SyncEventType.ITEM_FAILED],
        onEvent,
      })
    );

    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    await act(async () => {
      getMock().__emit({ type: SyncEventType.SYNC_STARTED, timestamp: 1 });
      getMock().__emit({
        type: SyncEventType.ITEM_FAILED,
        timestamp: 2,
        itemId: 'x',
      });
      await Promise.resolve();
    });

    expect(onEvent).toHaveBeenCalledTimes(1);
    const call = onEvent.mock.calls[0]?.[0] as SyncEvent | undefined;
    expect(call?.type).toBe(SyncEventType.ITEM_FAILED);
  });
});
