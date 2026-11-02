/**
 * Process-wide drain loop backing {@link drainUntilEmpty} / {@link cancelDrain}.
 *
 * Only one bounded drain may run at a time. This module owns that single piece
 * of mutable state and the loop itself; the facade passes in the native
 * operations, which keeps this file free of a `src/index.tsx` import cycle.
 *
 * The module is intentionally side-effect-free at import time (only
 * declarations, no top-level execution) so it stays compatible with the
 * package's `"sideEffects": false` flag and remains tree-shakeable.
 *
 * @internal
 */

import { SyncError } from '../errors/SyncError';
import { SyncErrorCode } from '../errors/SyncErrorCode';
import type { ConnectionState } from '../types/connection';
import type {
  DrainOptions,
  DrainResult,
  DrainStoppedReason,
} from '../types/drain';
import { ConnectionStatus } from '../types/enums';
import type { SyncItem } from '../types/sync';

const DRAIN_DEFAULT_MAX_ITERATIONS = 32;
const DRAIN_DEFAULT_TIMEOUT_MS = 60_000;

/** Shortest wait when nothing can happen yet, so the loop cannot spin. */
const DRAIN_IDLE_WAIT_MS = 250;

/**
 * Longest delay `setTimeout` honors. Anything larger overflows its 32-bit
 * signed delay and fires almost immediately, which would turn a very long
 * `timeoutMs` into an instant `TIMEOUT`.
 */
const MAX_TIMER_DELAY_MS = 2_147_483_647;

/** Native operations the loop drives. Supplied by the facade. */
export interface DrainNativeOps {
  flush(): Promise<unknown>;
  getConnectionStatus(): Promise<ConnectionState>;
  getPendingItems(): Promise<SyncItem[]>;
  getQueueSize(): Promise<number>;
  isSyncing(): Promise<boolean>;
}

/** Unwinds the loop when the deadline passes or the drain is cancelled. */
class DrainStop {
  readonly reason: DrainStoppedReason;

  constructor(reason: DrainStoppedReason) {
    this.reason = reason;
  }
}

interface DrainControllerState {
  /** `true` from the start of {@link runDrain} until it settles. */
  active: boolean;
  /** `true` once {@link cancelActiveDrain} runs during an active drain. */
  cancelled: boolean;
  /** Wakes every wait in progress when the drain is cancelled. */
  cancelWaiters: Set<() => void>;
}

const state: DrainControllerState = {
  active: false,
  cancelled: false,
  cancelWaiters: new Set(),
};

/**
 * Run `work`, abandoning the wait when the deadline passes or the drain is
 * cancelled. The abandoned work is not aborted, and a late rejection from it is
 * absorbed here, so it can never surface as an unhandled rejection.
 */
function raceWithStop<T>(
  work: () => Promise<T>,
  deadlineAt: number
): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const remainingMs = deadlineAt - Date.now();
    if (state.cancelled) {
      reject(new DrainStop('CANCELLED'));
      return;
    }
    if (remainingMs <= 0) {
      reject(new DrainStop('TIMEOUT'));
      return;
    }

    const stopWaiting = () => {
      clearTimeout(deadlineTimer);
      state.cancelWaiters.delete(onCancel);
    };
    const onCancel = () => {
      stopWaiting();
      reject(new DrainStop('CANCELLED'));
    };
    const deadlineTimer = setTimeout(
      () => {
        stopWaiting();
        reject(new DrainStop('TIMEOUT'));
      },
      Math.min(remainingMs, MAX_TIMER_DELAY_MS)
    );
    state.cancelWaiters.add(onCancel);

    work().then(
      (value) => {
        stopWaiting();
        resolve(value);
      },
      (error) => {
        stopWaiting();
        reject(error);
      }
    );
  });
}

/** Sleep for `ms`, ending early on cancel and never passing the deadline. */
function sleepWithStop(ms: number, deadlineAt: number): Promise<void> {
  let sleepTimer: ReturnType<typeof setTimeout> | undefined;
  return raceWithStop(
    () =>
      new Promise<void>((resolve) => {
        sleepTimer = setTimeout(resolve, Math.min(ms, MAX_TIMER_DELAY_MS));
      }),
    deadlineAt
  ).finally(() => clearTimeout(sleepTimer));
}

function finiteOr(value: number | undefined, fallback: number): number {
  return Number.isFinite(value) ? (value as number) : fallback;
}

/** Soonest retry deadline still in the future, if any item is cooling down. */
function soonestCooldownEnd(
  items: SyncItem[],
  now: number
): number | undefined {
  let soonest: number | undefined;
  for (const { nextRetryAt } of items) {
    if (nextRetryAt > now && (soonest === undefined || nextRetryAt < soonest)) {
      soonest = nextRetryAt;
    }
  }
  return soonest;
}

/**
 * Run a bounded drain loop. See {@link drainUntilEmpty} for the public contract.
 *
 * Each pass first lets any running cycle finish, then decides from the queue
 * size. All waits are interruptible and none of them uses up an iteration.
 *
 * @internal
 */
export async function runDrain(
  native: DrainNativeOps,
  options?: DrainOptions
): Promise<DrainResult> {
  if (state.active) {
    throw new SyncError(
      SyncErrorCode.DRAIN_ALREADY_ACTIVE,
      'A drainUntilEmpty() loop is already running. Await it before starting another. After cancelDrain() it still has to settle first.'
    );
  }

  const maxIterations = finiteOr(
    options?.maxIterations,
    DRAIN_DEFAULT_MAX_ITERATIONS
  );
  const timeoutMs = finiteOr(options?.timeoutMs, DRAIN_DEFAULT_TIMEOUT_MS);
  const delayBetweenCyclesMs = finiteOr(options?.delayBetweenCyclesMs, 0);
  const deadlineAt = Date.now() + timeoutMs;

  const guarded = <T>(work: () => Promise<T>) => raceWithStop(work, deadlineAt);
  const wait = (ms: number) => sleepWithStop(ms, deadlineAt);
  const idleWaitMs = Math.max(delayBetweenCyclesMs, DRAIN_IDLE_WAIT_MS);

  /** How long to wait after a cycle that took nothing off the queue. */
  const waitAfterNoProgress = async (): Promise<number> => {
    const pending = await guarded(native.getPendingItems);
    const now = Date.now();
    const cooldownEnd = soonestCooldownEnd(pending, now);
    if (cooldownEnd === undefined) {
      return idleWaitMs;
    }
    if (cooldownEnd >= deadlineAt) {
      throw new DrainStop('TIMEOUT');
    }
    return Math.max(delayBetweenCyclesMs, cooldownEnd - now);
  };

  let iterations = 0;
  let itemsDispatched = 0;
  let lastSize: number | undefined;
  let cycleJustRan = false;

  const result = (stoppedReason: DrainStoppedReason): DrainResult => ({
    iterations,
    itemsDispatched,
    queueRemaining: lastSize ?? 0,
    stoppedReason,
  });

  state.active = true;
  state.cancelled = false;

  try {
    for (;;) {
      // A running cycle holds items out of the count, and on iOS a flush during
      // one returns a stale result at once. Wait it out without flushing.
      if (await guarded(native.isSyncing)) {
        await wait(DRAIN_IDLE_WAIT_MS);
        continue;
      }

      const size = await guarded(native.getQueueSize);
      const removed = lastSize === undefined ? 0 : lastSize - size;
      if (removed > 0) {
        itemsDispatched += removed;
      }
      lastSize = size;

      if (size === 0) {
        // Re-read after the size: a cycle that started in between has claimed
        // items the count no longer shows.
        if (await guarded(native.isSyncing)) {
          await wait(DRAIN_IDLE_WAIT_MS);
          continue;
        }
        return result('EMPTY');
      }

      if (iterations >= maxIterations) {
        return result('MAX_ITERATIONS');
      }

      if (cycleJustRan) {
        cycleJustRan = false;
        const waitMs =
          removed > 0 ? delayBetweenCyclesMs : await waitAfterNoProgress();
        if (waitMs > 0) {
          await wait(waitMs);
          continue;
        }
      }

      // Neither dispatcher checks connectivity. Flushing offline would burn the
      // retry budget and mark valid items FAILED, so wait for the network.
      const connection = await guarded(native.getConnectionStatus);
      if (connection.status === ConnectionStatus.DISCONNECTED) {
        await wait(idleWaitMs);
        continue;
      }

      iterations += 1;
      cycleJustRan = true;
      await guarded(native.flush);
    }
  } catch (error) {
    if (error instanceof DrainStop) {
      return result(error.reason);
    }
    throw error;
  } finally {
    state.active = false;
    state.cancelled = false;
  }
}

/**
 * Request cancellation of the active drain. Wakes any wait in progress, so the
 * drain settles promptly. No-op when no drain is running, so callers can invoke
 * it unconditionally from cleanup paths.
 *
 * @internal
 */
export function cancelActiveDrain(): void {
  if (!state.active) {
    return;
  }
  state.cancelled = true;
  for (const wake of state.cancelWaiters) {
    wake();
  }
}
