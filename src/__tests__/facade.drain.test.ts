import { useEffect, useRef, StrictMode } from 'react';
import { renderHook } from '@testing-library/react-native';
import { afterEach, beforeEach, describe, expect, it } from '@jest/globals';

import { cancelDrain, drainUntilEmpty, SyncErrorCode } from '../index';
import { ConnectionStatus, ConnectionType } from '../types/enums';
import type { DrainResult } from '../types/drain';
import type { SyncResult } from '../types/sync';

import { installFakeNativeQueue } from './helpers/fakeNativeQueue';

interface SyncMock {
  flush: jest.Mock;
  getConnectionStatus: jest.Mock;
  getPendingItems: jest.Mock;
  getQueueSize: jest.Mock;
  isSyncing: jest.Mock;
}

const getMock = (): SyncMock =>
  (globalThis as unknown as { __syncMock: SyncMock }).__syncMock;

const EMPTY_RESULT: SyncResult = {
  startedAt: 1,
  finishedAt: 2,
  successCount: 0,
  failureCount: 0,
  succeededIds: [],
  failedIds: [],
  errors: {},
};

const IDLE_WAIT_MS = 250;
const DEFAULT_MAX_ITERATIONS = 32;
const DEFAULT_TIMEOUT_MS = 60_000;

interface Finished {
  result: DrainResult;
  elapsedMs: number;
}

/** Run a drain to completion on the moving fake clock. */
async function runDrain(
  options?: Parameters<typeof drainUntilEmpty>[0]
): Promise<Finished> {
  const startedAt = Date.now();
  const drain = drainUntilEmpty(options);
  await jest.runAllTimersAsync();
  const result = await drain;
  return { result, elapsedMs: Date.now() - startedAt };
}

beforeEach(() => {
  jest.useFakeTimers();
});

afterEach(async () => {
  // The drain lock is module state. Release it here, so a test that fails with
  // a drain still running reports itself instead of failing every later test.
  cancelDrain();
  jest.useRealTimers();
  await new Promise((resolve) => setImmediate(resolve));
});

describe('cancelDrain', () => {
  it('is a harmless no-op when no drain is active', () => {
    expect(() => cancelDrain()).not.toThrow();
  });
});

describe('drainUntilEmpty stop reasons', () => {
  it('returns EMPTY without flushing when the queue is already empty', async () => {
    installFakeNativeQueue();

    const { result } = await runDrain();

    expect(result).toEqual({
      iterations: 0,
      itemsDispatched: 0,
      queueRemaining: 0,
      stoppedReason: 'EMPTY',
    });
    expect(getMock().flush).not.toHaveBeenCalled();
  });

  it('flushes until empty, counting items that left the queue', async () => {
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      batchSize: 2,
    });

    const { result, elapsedMs } = await runDrain();

    expect(result).toEqual({
      iterations: 2,
      itemsDispatched: 3,
      queueRemaining: 0,
      stoppedReason: 'EMPTY',
    });
    expect(fake.rows).toHaveLength(0);
    expect(elapsedMs).toBe(0);
  });

  it('does not count a row that failed permanently as remaining', async () => {
    const fake = installFakeNativeQueue({
      rows: [{ id: 'ok' }, { id: 'bad' }, { id: 'ok2' }],
      dispatch: (row) => (row.id === 'bad' ? 'failure' : 'success'),
    });

    const { result } = await runDrain();

    expect(result.stoppedReason).toBe('EMPTY');
    expect(result.iterations).toBe(1);
    expect(fake.rows.map((row) => row.status)).toEqual(['FAILED']);
  });

  it('stops with MAX_ITERATIONS, without flushing past the bound', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const { result } = await runDrain({ maxIterations: 3 });

    expect(result).toEqual({
      iterations: 3,
      itemsDispatched: 0,
      queueRemaining: 1,
      stoppedReason: 'MAX_ITERATIONS',
    });
    expect(getMock().flush).toHaveBeenCalledTimes(3);
  });

  it('reports the real queue size with maxIterations: 0', async () => {
    installFakeNativeQueue({ rows: [{ id: 'a' }, { id: 'b' }] });

    const { result } = await runDrain({ maxIterations: 0 });

    expect(result).toEqual({
      iterations: 0,
      itemsDispatched: 0,
      queueRemaining: 2,
      stoppedReason: 'MAX_ITERATIONS',
    });
    expect(getMock().flush).not.toHaveBeenCalled();
  });

  it('stops with TIMEOUT exactly at the deadline', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const { result, elapsedMs } = await runDrain({ timeoutMs: 1_000 });

    expect(result.stoppedReason).toBe('TIMEOUT');
    expect(result.queueRemaining).toBe(1);
    expect(elapsedMs).toBe(1_000);
  });

  it('stops with CANCELLED when cancelDrain() runs during a flush', async () => {
    installFakeNativeQueue({ rows: [{ id: 'a' }] });
    const mock = getMock();
    mock.flush.mockImplementation(async () => {
      cancelDrain();
      return EMPTY_RESULT;
    });

    const { result } = await runDrain();

    expect(result.stoppedReason).toBe('CANCELLED');
    expect(result.iterations).toBe(1);
    expect(mock.flush).toHaveBeenCalledTimes(1);
  });
});

describe('drainUntilEmpty and retry cooldowns', () => {
  it('waits out a cooling item instead of spinning through maxIterations', async () => {
    const cooldownMs = 10_000;
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a', attempts: 1, nextRetryAt: Date.now() + cooldownMs }],
    });

    const { result, elapsedMs } = await runDrain();

    expect(result).toEqual({
      iterations: 2,
      itemsDispatched: 1,
      queueRemaining: 0,
      stoppedReason: 'EMPTY',
    });
    expect(elapsedMs).toBe(cooldownMs);
    expect(fake.rows).toHaveLength(0);
  });

  it('sleeps until the soonest cooldown, then the next one', async () => {
    const now = Date.now();
    installFakeNativeQueue({
      rows: [
        { id: 'later', attempts: 1, nextRetryAt: now + 5_000 },
        { id: 'sooner', attempts: 1, nextRetryAt: now + 2_000 },
      ],
    });

    const { result, elapsedMs } = await runDrain();

    expect(result.stoppedReason).toBe('EMPTY');
    expect(result.itemsDispatched).toBe(2);
    // A cycle at each deadline, plus an empty one before each sleep: the first
    // finds everything cooling, and a productive cycle is followed at once by
    // another, because more items may be eligible.
    expect(result.iterations).toBe(4);
    expect(elapsedMs).toBe(5_000);
  });

  it('drives a failing item through its retries as each cooldown ends', async () => {
    const delays = [1_000, 2_000];
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a' }],
      dispatch: () => {
        const retryInMs = delays.shift();
        return retryInMs === undefined ? 'success' : { retryInMs };
      },
    });

    const { result, elapsedMs } = await runDrain();

    expect(result.stoppedReason).toBe('EMPTY');
    expect(result.iterations).toBe(3);
    expect(elapsedMs).toBe(3_000);
    expect(fake.rows).toHaveLength(0);
  });

  it('re-checks after a sleep instead of flushing on its way out of it', async () => {
    const cooldownMs = 2_000;
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a', attempts: 1, nextRetryAt: Date.now() + cooldownMs }],
      cycleMs: 100,
    });
    // Like the native retry timer, this fires at the moment the drain wakes.
    setTimeout(() => {
      void fake.runForeignCycle();
    }, cooldownMs);

    const { result } = await runDrain();

    expect(result.stoppedReason).toBe('EMPTY');
    expect(getMock().flush).toHaveBeenCalledTimes(1);
    expect(fake.cycles()).toBe(2);
  });

  it('stops at once with TIMEOUT when the cooldown ends at the deadline', async () => {
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a', attempts: 1, nextRetryAt: Date.now() + 5_000 }],
    });

    const { result, elapsedMs } = await runDrain({ timeoutMs: 5_000 });

    expect(result).toEqual({
      iterations: 1,
      itemsDispatched: 0,
      queueRemaining: 1,
      stoppedReason: 'TIMEOUT',
    });
    expect(elapsedMs).toBe(0);
    expect(fake.attemptsSpent()).toBe(1);
  });

  it('stops at once with TIMEOUT when the cooldown ends past the deadline', async () => {
    installFakeNativeQueue({
      rows: [{ id: 'a', attempts: 1, nextRetryAt: Date.now() + 90_000 }],
    });

    const { result, elapsedMs } = await runDrain();

    expect(result.stoppedReason).toBe('TIMEOUT');
    expect(elapsedMs).toBe(0);
  });

  it('keeps delayBetweenCyclesMs as a minimum when it exceeds the cooldown', async () => {
    installFakeNativeQueue({
      rows: [{ id: 'a', attempts: 1, nextRetryAt: Date.now() + 100 }],
    });

    const { result, elapsedMs } = await runDrain({
      delayBetweenCyclesMs: 700,
    });

    expect(result.stoppedReason).toBe('EMPTY');
    expect(elapsedMs).toBe(700);
  });
});

describe('drainUntilEmpty with no progress and nothing cooling', () => {
  it('cannot spin: a paused engine gets one flush per idle wait', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;
    const mock = getMock();

    const drain = drainUntilEmpty();
    await jest.advanceTimersByTimeAsync(IDLE_WAIT_MS * 4);

    expect(mock.flush).toHaveBeenCalledTimes(5);

    cancelDrain();
    await drain;
  });

  it('uses every iteration and skips the sleep after the last one', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const { result, elapsedMs } = await runDrain();

    expect(result.stoppedReason).toBe('MAX_ITERATIONS');
    expect(result.iterations).toBe(DEFAULT_MAX_ITERATIONS);
    expect(elapsedMs).toBe((DEFAULT_MAX_ITERATIONS - 1) * IDLE_WAIT_MS);
  });

  it('honors a delayBetweenCyclesMs above the idle wait', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const { elapsedMs } = await runDrain({
      maxIterations: 3,
      delayBetweenCyclesMs: 1_000,
    });

    expect(elapsedMs).toBe(2_000);
  });

  it('keeps the idle wait when delayBetweenCyclesMs is below it', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const { elapsedMs } = await runDrain({
      maxIterations: 3,
      delayBetweenCyclesMs: 10,
    });

    expect(elapsedMs).toBe(2 * IDLE_WAIT_MS);
  });
});

describe('drainUntilEmpty pacing between productive cycles', () => {
  it('pauses for delayBetweenCyclesMs, but not after the last cycle', async () => {
    installFakeNativeQueue({
      rows: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
      batchSize: 1,
    });

    const { result, elapsedMs } = await runDrain({
      delayBetweenCyclesMs: 1_000,
    });

    expect(result.stoppedReason).toBe('EMPTY');
    expect(result.iterations).toBe(3);
    expect(elapsedMs).toBe(2_000);
  });
});

describe('drainUntilEmpty with a running cycle', () => {
  it('waits for it without flushing or using an iteration', async () => {
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a' }, { id: 'b' }],
      cycleMs: 1_000,
      flushDuringCycle: 'stale',
    });
    void fake.runForeignCycle();

    const { result, elapsedMs } = await runDrain({ maxIterations: 1 });

    expect(result).toEqual({
      iterations: 0,
      itemsDispatched: 0,
      queueRemaining: 0,
      stoppedReason: 'EMPTY',
    });
    expect(elapsedMs).toBeGreaterThanOrEqual(1_000);
    expect(getMock().flush).not.toHaveBeenCalled();
  });

  it('does not report EMPTY while the last item is in flight', async () => {
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a' }],
      cycleMs: 1_000,
    });
    void fake.runForeignCycle();
    expect(await getMock().getQueueSize()).toBe(0);

    const drain = drainUntilEmpty();
    await jest.advanceTimersByTimeAsync(500);
    expect(fake.rows).toHaveLength(1);

    await jest.runAllTimersAsync();
    expect((await drain).stoppedReason).toBe('EMPTY');
    expect(fake.rows).toHaveLength(0);
  });

  it('keeps going when an in-flight item comes back cooling', async () => {
    let failedOnce = false;
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a' }],
      cycleMs: 500,
      dispatch: () => {
        if (failedOnce) {
          return 'success';
        }
        failedOnce = true;
        return { retryInMs: 3_000 };
      },
    });
    void fake.runForeignCycle();

    const { result } = await runDrain();

    expect(result.stoppedReason).toBe('EMPTY');
    expect(result.itemsDispatched).toBe(1);
    expect(fake.rows).toHaveLength(0);
  });

  it('re-reads isSyncing after a zero size before reporting EMPTY', async () => {
    const mock = getMock();
    mock.getQueueSize.mockResolvedValueOnce(0).mockResolvedValueOnce(0);
    mock.isSyncing
      .mockResolvedValueOnce(false) // before the size read
      .mockResolvedValueOnce(true) // a cycle started right after it
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(false);

    const { result } = await runDrain();

    expect(result.stoppedReason).toBe('EMPTY');
    expect(mock.getQueueSize).toHaveBeenCalledTimes(2);
    expect(mock.isSyncing).toHaveBeenCalledTimes(4);
  });

  it('survives a stale flush that races a cycle starting', async () => {
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a' }],
      cycleMs: 1_000,
      flushDuringCycle: 'stale',
    });
    const mock = getMock();
    // The cycle starts after the pre-flush check, so this flush comes back stale.
    mock.isSyncing.mockResolvedValueOnce(false);
    mock.getConnectionStatus.mockImplementationOnce(async () => {
      void fake.runForeignCycle();
      return {
        status: ConnectionStatus.CONNECTED,
        type: ConnectionType.WIFI,
      };
    });

    const { result } = await runDrain();

    expect(result.stoppedReason).toBe('EMPTY');
    expect(result.iterations).toBe(1);
    expect(fake.cycles()).toBe(1);
  });
});

describe('drainUntilEmpty hard stops', () => {
  const hangFlush = () => {
    const mock = getMock();
    let release: (result: SyncResult) => void = () => undefined;
    let fail: (error: Error) => void = () => undefined;
    mock.flush.mockImplementation(
      () =>
        new Promise<SyncResult>((resolve, reject) => {
          release = resolve;
          fail = reject;
        })
    );
    return {
      release: () => release(EMPTY_RESULT),
      fail: (e: Error) => fail(e),
    };
  };

  it('ends a hung flush at timeoutMs and releases the lock', async () => {
    installFakeNativeQueue({ rows: [{ id: 'a' }] });
    hangFlush();

    const { result, elapsedMs } = await runDrain({ timeoutMs: 50 });

    expect(result).toEqual({
      iterations: 1,
      itemsDispatched: 0,
      queueRemaining: 1,
      stoppedReason: 'TIMEOUT',
    });
    expect(elapsedMs).toBe(50);

    getMock().getQueueSize.mockResolvedValue(0);
    expect((await runDrain()).result.stoppedReason).toBe('EMPTY');
  });

  it('ends a hung flush on cancelDrain() at once and releases the lock', async () => {
    installFakeNativeQueue({ rows: [{ id: 'a' }] });
    hangFlush();

    const startedAt = Date.now();
    const drain = drainUntilEmpty();
    await jest.advanceTimersByTimeAsync(100);
    cancelDrain();
    const result = await drain;

    expect(result.stoppedReason).toBe('CANCELLED');
    expect(Date.now() - startedAt).toBe(100);

    getMock().getQueueSize.mockResolvedValue(0);
    expect((await runDrain()).result.stoppedReason).toBe('EMPTY');
  });

  it('ignores a flush that settles after the drain stopped', async () => {
    installFakeNativeQueue({ rows: [{ id: 'a' }] });
    const flush = hangFlush();

    const { result } = await runDrain({ timeoutMs: 50 });
    flush.fail(new Error('late native failure'));
    await jest.advanceTimersByTimeAsync(10);

    expect(result.stoppedReason).toBe('TIMEOUT');
  });

  it('polls instead of flushing again while an abandoned cycle still runs', async () => {
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a' }, { id: 'b' }],
      cycleMs: 5_000,
    });
    void fake.runForeignCycle();

    const first = await runDrain({ timeoutMs: 100 });
    expect(first.result.stoppedReason).toBe('TIMEOUT');

    const second = await runDrain();
    expect(second.result.stoppedReason).toBe('EMPTY');
    expect(fake.cycles()).toBe(1);
  });

  it('ends a delay at once on cancelDrain()', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const startedAt = Date.now();
    const drain = drainUntilEmpty({ delayBetweenCyclesMs: 600 });
    await jest.advanceTimersByTimeAsync(30);
    cancelDrain();

    expect((await drain).stoppedReason).toBe('CANCELLED');
    expect(Date.now() - startedAt).toBe(30);
  });

  it('ends a cooldown sleep at once on cancelDrain()', async () => {
    installFakeNativeQueue({
      rows: [{ id: 'a', attempts: 1, nextRetryAt: Date.now() + 30_000 }],
    });

    const startedAt = Date.now();
    const drain = drainUntilEmpty();
    await jest.advanceTimersByTimeAsync(100);
    cancelDrain();

    expect((await drain).stoppedReason).toBe('CANCELLED');
    expect(Date.now() - startedAt).toBe(100);
  });

  it('ends the wait for a running cycle at once on cancelDrain()', async () => {
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a' }],
      cycleMs: 30_000,
    });
    void fake.runForeignCycle();

    const startedAt = Date.now();
    const drain = drainUntilEmpty();
    await jest.advanceTimersByTimeAsync(100);
    cancelDrain();

    expect((await drain).stoppedReason).toBe('CANCELLED');
    expect(Date.now() - startedAt).toBe(100);
  });

  it('does not sleep past the deadline', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const { result, elapsedMs } = await runDrain({
      timeoutMs: 50,
      delayBetweenCyclesMs: 500,
    });

    expect(result.stoppedReason).toBe('TIMEOUT');
    expect(elapsedMs).toBe(50);
  });

  it('stops before any native call when the deadline has already passed', async () => {
    installFakeNativeQueue({ rows: [{ id: 'a' }] });
    const mock = getMock();

    const { result } = await runDrain({ timeoutMs: 0 });

    expect(result).toEqual({
      iterations: 0,
      itemsDispatched: 0,
      queueRemaining: 0,
      stoppedReason: 'TIMEOUT',
    });
    expect(mock.isSyncing).not.toHaveBeenCalled();
    expect(mock.flush).not.toHaveBeenCalled();
  });

  it('never loses a cancel that lands between two waits', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    // The gap between two waits lasts a few microtasks. Cancelling at every
    // depth of the first pass is what reaches it, whatever its exact position.
    for (let depth = 0; depth < 60; depth += 1) {
      const drain = drainUntilEmpty();
      for (let hop = 0; hop < depth; hop += 1) {
        await Promise.resolve();
      }
      cancelDrain();
      await jest.runAllTimersAsync();

      expect((await drain).stoppedReason).toBe('CANCELLED');
    }
  });

  it('leaves no timer behind on any stop', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const drain = drainUntilEmpty();
    await jest.advanceTimersByTimeAsync(100);
    cancelDrain();
    await drain;

    expect(jest.getTimerCount()).toBe(0);
  });
});

describe('drainUntilEmpty option handling', () => {
  it.each([
    ['NaN', Number.NaN],
    ['Infinity', Number.POSITIVE_INFINITY],
  ])('falls back to the default maxIterations for %s', async (_name, value) => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const { result } = await runDrain({ maxIterations: value });

    expect(result.stoppedReason).toBe('MAX_ITERATIONS');
    expect(result.iterations).toBe(DEFAULT_MAX_ITERATIONS);
  });

  it.each([
    ['NaN', Number.NaN],
    ['Infinity', Number.POSITIVE_INFINITY],
  ])('falls back to the default timeoutMs for %s', async (_name, value) => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.offline = true;

    const { result, elapsedMs } = await runDrain({ timeoutMs: value });

    expect(result.stoppedReason).toBe('TIMEOUT');
    expect(elapsedMs).toBe(DEFAULT_TIMEOUT_MS);
  });

  it('ignores a non-finite delayBetweenCyclesMs', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const { elapsedMs } = await runDrain({
      maxIterations: 2,
      delayBetweenCyclesMs: Number.NaN,
    });

    expect(elapsedMs).toBe(IDLE_WAIT_MS);
  });

  it('does not overflow the timer with a very long finite timeoutMs', async () => {
    installFakeNativeQueue({
      rows: [{ id: 'a', attempts: 1, nextRetryAt: Date.now() + 1_000 }],
    });

    const { result } = await runDrain({ timeoutMs: Number.MAX_SAFE_INTEGER });

    expect(result.stoppedReason).toBe('EMPTY');
  });
});

describe('drainUntilEmpty offline', () => {
  it('never flushes, so no attempt is spent and no item fails', async () => {
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a' }, { id: 'b' }],
    });
    fake.offline = true;

    const { result, elapsedMs } = await runDrain({ timeoutMs: 20_000 });

    expect(result).toEqual({
      iterations: 0,
      itemsDispatched: 0,
      queueRemaining: 2,
      stoppedReason: 'TIMEOUT',
    });
    expect(elapsedMs).toBe(20_000);
    expect(getMock().flush).not.toHaveBeenCalled();
    expect(fake.attemptsSpent()).toBe(0);
    expect(fake.rows.map((row) => row.status)).toEqual(['PENDING', 'PENDING']);
  });

  it('flushes as soon as connectivity returns', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.offline = true;

    const drain = drainUntilEmpty();
    await jest.advanceTimersByTimeAsync(1_000);
    expect(getMock().flush).not.toHaveBeenCalled();

    fake.offline = false;
    await jest.runAllTimersAsync();

    expect((await drain).stoppedReason).toBe('EMPTY');
    expect(fake.rows).toHaveLength(0);
  });

  it('stops flushing when connectivity drops mid-drain', async () => {
    const fake = installFakeNativeQueue({
      rows: [{ id: 'a' }, { id: 'b' }],
      batchSize: 1,
    });
    const mock = getMock();
    mock.flush.mockImplementationOnce(async () => {
      fake.rows.shift();
      fake.offline = true;
      return EMPTY_RESULT;
    });

    const { result } = await runDrain({ timeoutMs: 2_000 });

    expect(result.stoppedReason).toBe('TIMEOUT');
    expect(mock.flush).toHaveBeenCalledTimes(1);
    expect(fake.attemptsSpent()).toBe(0);
  });

  it.each([ConnectionStatus.UNKNOWN, ConnectionStatus.METERED])(
    'flushes when the status is %s',
    async (status) => {
      installFakeNativeQueue({ rows: [{ id: 'a' }] });
      getMock().getConnectionStatus.mockResolvedValue({
        status,
        type: ConnectionType.UNKNOWN,
      });

      const { result } = await runDrain();

      expect(result.stoppedReason).toBe('EMPTY');
    }
  );
});

describe('drainUntilEmpty concurrency and rejections', () => {
  it('throws DRAIN_ALREADY_ACTIVE for a concurrent drain', async () => {
    installFakeNativeQueue();

    const first = drainUntilEmpty(); // the lock is taken synchronously

    await expect(drainUntilEmpty()).rejects.toMatchObject({
      code: SyncErrorCode.DRAIN_ALREADY_ACTIVE,
    });

    await jest.runAllTimersAsync();
    expect((await first).stoppedReason).toBe('EMPTY');
  });

  it('allows a new drain once a cancelled one has settled, not before', async () => {
    const fake = installFakeNativeQueue({ rows: [{ id: 'a' }] });
    fake.paused = true;

    const first = drainUntilEmpty();
    cancelDrain();

    await expect(drainUntilEmpty()).rejects.toMatchObject({
      code: SyncErrorCode.DRAIN_ALREADY_ACTIVE,
    });

    expect((await first).stoppedReason).toBe('CANCELLED');

    fake.paused = false;
    expect((await runDrain()).result.stoppedReason).toBe('EMPTY');
  });

  it('re-throws a mid-drain flush rejection and releases the lock', async () => {
    const mock = getMock();
    mock.getQueueSize.mockResolvedValue(5);
    mock.flush
      .mockResolvedValueOnce(EMPTY_RESULT)
      .mockRejectedValueOnce(new Error('boom'));

    // The rejection only comes after the idle wait, so advance the clock first.
    const drain = drainUntilEmpty().catch((error: unknown) => error);
    await jest.runAllTimersAsync();

    expect(await drain).toMatchObject({ code: SyncErrorCode.NETWORK_ERROR });

    mock.getQueueSize.mockResolvedValue(0);
    expect((await runDrain()).result.stoppedReason).toBe('EMPTY');
  });

  it.each(['isSyncing', 'getQueueSize', 'getConnectionStatus'] as const)(
    're-throws a %s rejection and releases the lock',
    async (method) => {
      const mock = getMock();
      mock.getQueueSize.mockResolvedValue(1);
      mock[method].mockRejectedValueOnce(new Error(`${method} failed`));

      await expect(drainUntilEmpty()).rejects.toThrow(`${method} failed`);

      mock.getQueueSize.mockResolvedValue(0);
      expect((await runDrain()).result.stoppedReason).toBe('EMPTY');
    }
  );

  it('re-throws a getPendingItems rejection and releases the lock', async () => {
    const mock = getMock();
    installFakeNativeQueue({
      rows: [{ id: 'a', attempts: 1, nextRetryAt: Date.now() + 1_000 }],
    });
    mock.getPendingItems.mockRejectedValueOnce(new Error('read failed'));

    await expect(drainUntilEmpty()).rejects.toThrow('read failed');

    mock.getQueueSize.mockResolvedValue(0);
    expect((await runDrain()).result.stoppedReason).toBe('EMPTY');
  });
});

describe('the documented effect pattern', () => {
  beforeEach(() => {
    jest.useRealTimers();
  });

  it('survives a StrictMode double mount with no rejection', async () => {
    installFakeNativeQueue({ rows: [{ id: 'a' }] });
    const rejections: unknown[] = [];
    let effectRuns = 0;

    const useDrainOnMount = () => {
      const previousDrain = useRef<Promise<unknown>>(Promise.resolve());

      useEffect(() => {
        effectRuns += 1;
        const drain = previousDrain.current.then(() => drainUntilEmpty());
        previousDrain.current = drain.catch(() => undefined);
        drain.catch((error) => rejections.push(error));
        return () => cancelDrain();
      }, []);

      return previousDrain;
    };

    const { result } = renderHook(() => useDrainOnMount(), {
      wrapper: StrictMode,
    });
    await result.current.current;

    expect(effectRuns).toBe(2);
    expect(rejections).toEqual([]);
    expect(await getMock().getQueueSize()).toBe(0);
  });
});
