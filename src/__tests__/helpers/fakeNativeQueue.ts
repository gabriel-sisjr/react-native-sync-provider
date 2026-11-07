import {
  ConnectionStatus,
  ConnectionType,
  HttpMethod,
  NetworkQuality,
} from '../../types/enums';
import type { SyncItem, SyncResult } from '../../types/sync';

/**
 * Stateful stand-in for the native queue.
 *
 * The default mock in `setup-minimal.ts` answers each method independently, so
 * it cannot express anything that depends on queue state over time. This fake
 * models what the native engines actually do:
 *
 * - `getQueueSize()` and `getPendingItems()` see `PENDING` rows only, including
 *   rows still cooling down.
 * - A cycle claims `PENDING` rows with `nextRetryAt <= now` and flips them to
 *   `IN_FLIGHT` while `isSyncing()` is `true`.
 * - `flush()` while paused is a no-op, and `flush()` does not check connectivity,
 *   so an offline attempt fails and spends retry budget.
 *
 * Time comes from `Date.now()` and `setTimeout`, so use it with fake timers to
 * get a moving clock.
 */

export type RowStatus = 'PENDING' | 'IN_FLIGHT' | 'FAILED';

export interface FakeRow {
  id: string;
  status: RowStatus;
  attempts: number;
  nextRetryAt: number;
}

/** What a cycle does with a dispatched row. */
export type DispatchOutcome = 'success' | 'failure' | { retryInMs: number };

export interface FakeNativeQueueOptions {
  /** Rows to start with. Defaults fill in `PENDING`, `0` attempts, no cooldown. */
  rows?: Array<Partial<FakeRow> & { id: string }>;
  /** Outcome per dispatched row. Defaults to `'success'`. */
  dispatch?: (row: FakeRow) => DispatchOutcome;
  /** Maximum rows claimed per cycle. Defaults to no limit. */
  batchSize?: number;
  /** How long a cycle takes, in ms. Defaults to `0`. */
  cycleMs?: number;
  /**
   * What `flush()` does during a running cycle: `'wait'` queues behind it, as
   * the Android mutex does, and `'stale'` returns the previous result at once,
   * as iOS does. Defaults to `'wait'`.
   */
  flushDuringCycle?: 'wait' | 'stale';
}

export interface FakeNativeQueue {
  rows: FakeRow[];
  offline: boolean;
  paused: boolean;
  /** Number of cycles run, whoever started them. */
  cycles(): number;
  /** Number of attempts spent across every row. */
  attemptsSpent(): number;
  /** Run a cycle nobody called `flush()` for, like the native retry timer. */
  runForeignCycle(): Promise<SyncResult>;
}

interface NativeMock {
  flush: jest.Mock;
  getConnectionStatus: jest.Mock;
  getPendingItems: jest.Mock;
  getQueueSize: jest.Mock;
  isSyncing: jest.Mock;
}

const OFFLINE_RETRY_MS = 1_000;

function emptyResult(): SyncResult {
  return {
    startedAt: Date.now(),
    finishedAt: Date.now(),
    successCount: 0,
    failureCount: 0,
    succeededIds: [],
    failedIds: [],
    errors: {},
  };
}

function toSyncItem(row: FakeRow): SyncItem {
  return {
    id: row.id,
    method: HttpMethod.POST,
    url: `https://api.example.com/${row.id}`,
    createdAt: 1,
    attempts: row.attempts,
    nextRetryAt: row.nextRetryAt,
  };
}

/** Wire a stateful fake queue into the `__syncMock` hybrid. */
export function installFakeNativeQueue(
  options: FakeNativeQueueOptions = {}
): FakeNativeQueue {
  const {
    batchSize = Number.POSITIVE_INFINITY,
    cycleMs = 0,
    dispatch = () => 'success',
    flushDuringCycle = 'wait',
  } = options;
  const mock = (globalThis as unknown as { __syncMock: NativeMock }).__syncMock;

  let cycleCount = 0;
  let runningCycle: Promise<SyncResult> | null = null;
  let lastResult = emptyResult();

  const fake: FakeNativeQueue = {
    rows: (options.rows ?? []).map((row) => ({
      status: 'PENDING',
      attempts: 0,
      nextRetryAt: 0,
      ...row,
    })),
    offline: false,
    paused: false,
    cycles: () => cycleCount,
    attemptsSpent: () =>
      fake.rows.reduce((total, row) => total + row.attempts, 0),
    runForeignCycle: () => startCycle(),
  };

  const outcomeFor = (row: FakeRow): DispatchOutcome =>
    fake.offline ? { retryInMs: OFFLINE_RETRY_MS } : dispatch(row);

  const runCycle = async (): Promise<SyncResult> => {
    cycleCount += 1;
    const claimed = fake.rows
      .filter((row) => row.status === 'PENDING')
      .filter((row) => row.nextRetryAt <= Date.now())
      .slice(0, batchSize);
    for (const row of claimed) {
      row.status = 'IN_FLIGHT';
    }
    if (cycleMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, cycleMs));
    }
    const result = emptyResult();
    for (const row of claimed) {
      row.attempts += 1;
      const outcome = outcomeFor(row);
      if (outcome === 'success') {
        fake.rows.splice(fake.rows.indexOf(row), 1);
        result.successCount += 1;
        result.succeededIds.push(row.id);
      } else if (outcome === 'failure') {
        row.status = 'FAILED';
        result.failureCount += 1;
        result.failedIds.push(row.id);
      } else {
        row.status = 'PENDING';
        row.nextRetryAt = Date.now() + outcome.retryInMs;
      }
    }
    return result;
  };

  const startCycle = (): Promise<SyncResult> => {
    const cycle = runCycle().then((result) => {
      runningCycle = null;
      lastResult = result;
      return result;
    });
    runningCycle = cycle;
    return cycle;
  };

  mock.getQueueSize.mockImplementation(
    async () => fake.rows.filter((row) => row.status === 'PENDING').length
  );
  mock.getPendingItems.mockImplementation(async () =>
    fake.rows.filter((row) => row.status === 'PENDING').map(toSyncItem)
  );
  mock.isSyncing.mockImplementation(async () => runningCycle !== null);
  mock.getConnectionStatus.mockImplementation(async () => ({
    status: fake.offline
      ? ConnectionStatus.DISCONNECTED
      : ConnectionStatus.CONNECTED,
    type: fake.offline ? ConnectionType.NONE : ConnectionType.WIFI,
    networkQuality: fake.offline ? NetworkQuality.OFFLINE : NetworkQuality.GOOD,
  }));
  mock.flush.mockImplementation(async () => {
    if (fake.paused) {
      return emptyResult();
    }
    if (runningCycle !== null) {
      if (flushDuringCycle === 'stale') {
        return lastResult;
      }
      await runningCycle;
    }
    return startCycle();
  });

  return fake;
}
