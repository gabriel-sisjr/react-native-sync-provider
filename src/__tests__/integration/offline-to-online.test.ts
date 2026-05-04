import { describe, expect, it } from '@jest/globals';

import {
  addSyncEventListener,
  enqueue,
  flush,
  getQueueSize,
  removeSyncEventListener,
} from '../../index';
import { ConnectionStatus, HttpMethod, SyncEventType } from '../../types/enums';
import type { SyncEvent } from '../../types/sync';

interface SyncMock {
  enqueue: jest.Mock;
  flush: jest.Mock;
  getQueueSize: jest.Mock;
  __emit: (event: unknown) => void;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

describe('integration: offline → online drain', () => {
  it('enqueues while offline, then drains on reconnect', async () => {
    const mock = getMock();
    let pending = 0;
    mock.enqueue.mockImplementation(async () => {
      pending += 1;
      return `id-${pending}`;
    });
    mock.getQueueSize.mockImplementation(async () => pending);
    mock.flush.mockImplementation(async () => {
      const drained = pending;
      pending = 0;
      return {
        startedAt: 1,
        finishedAt: 2,
        successCount: drained,
        failureCount: 0,
        succeededIds: Array.from({ length: drained }, (_, i) => `id-${i + 1}`),
        failedIds: [],
        errors: {},
      };
    });

    const events: SyncEvent[] = [];
    const sub = await addSyncEventListener((e) => {
      events.push(e);
    });

    await enqueue({
      method: HttpMethod.POST,
      url: 'https://api.example.com/a',
    });
    await enqueue({
      method: HttpMethod.POST,
      url: 'https://api.example.com/b',
    });
    await enqueue({
      method: HttpMethod.POST,
      url: 'https://api.example.com/c',
    });

    expect(await getQueueSize()).toBe(3);

    mock.__emit({
      type: SyncEventType.CONNECTION_CHANGED,
      timestamp: 1,
      connectionStatus: ConnectionStatus.CONNECTED,
    });

    const result = await flush();
    expect(result.successCount).toBe(3);
    expect(result.failureCount).toBe(0);
    expect(await getQueueSize()).toBe(0);

    await removeSyncEventListener(sub);
    expect(
      events.some((e) => e.type === SyncEventType.CONNECTION_CHANGED)
    ).toBe(true);
  });
});
