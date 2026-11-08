/**
 * Why a {@link drainUntilEmpty} loop stopped.
 *
 * - `EMPTY` — `getQueueSize()` is `0` and `isSyncing()` is `false` (the success
 *   case).
 * - `MAX_ITERATIONS` — the loop started `DrainOptions.maxIterations` flush
 *   cycles and items remain.
 * - `TIMEOUT` — `DrainOptions.timeoutMs` elapsed, or the soonest retry cooldown
 *   ends at or after that deadline, so waiting for it would be pointless.
 * - `CANCELLED` — {@link cancelDrain} was called while the loop was running.
 *
 * @public
 */
export type DrainStoppedReason =
  | 'EMPTY'
  | 'MAX_ITERATIONS'
  | 'TIMEOUT'
  | 'CANCELLED';

/**
 * Bounds for a {@link drainUntilEmpty} loop.
 *
 * Every option has a safe default, so `drainUntilEmpty()` with no arguments
 * always ends, even if items keep being enqueued concurrently. A non-finite
 * value (`NaN`, `Infinity`) falls back to that option's default.
 *
 * @public
 */
export interface DrainOptions {
  /**
   * Maximum number of flush cycles to start before stopping with
   * `stoppedReason: 'MAX_ITERATIONS'`.
   *
   * Only real `flush()` calls count. Time spent waiting for a running cycle, a
   * retry cooldown or connectivity does not use up iterations.
   *
   * @defaultValue 32
   */
  maxIterations?: number;
  /**
   * Hard deadline for the whole loop, in milliseconds. When it passes, the loop
   * stops with `stoppedReason: 'TIMEOUT'`, even if a `flush()` is still in
   * flight.
   *
   * A native cycle that is already running is not aborted: it keeps going after
   * the drain returns, and its items may still be dispatched.
   *
   * @defaultValue 60000
   */
  timeoutMs?: number;
  /**
   * Minimum pause, in milliseconds, between flush cycles. When omitted or `0`,
   * cycles that make progress run back-to-back.
   *
   * This option is not needed to wait for retry cooldowns: the loop already
   * sleeps until the soonest `nextRetryAt`. A cycle that makes no progress with
   * nothing cooling always waits at least 250 ms, so the loop cannot spin.
   *
   * @defaultValue undefined
   */
  delayBetweenCyclesMs?: number;
}

/**
 * Outcome of a {@link drainUntilEmpty} loop.
 *
 * @public
 */
export interface DrainResult {
  /** Number of flush cycles started, including one interrupted by a stop. */
  iterations: number;
  /**
   * Best-effort count of items that left the pending queue during the drain,
   * summed from decreases in queue size.
   *
   * It is not a success count. It includes items that failed permanently, which
   * are now in the dead-letter queue, and items removed by `removeItem` /
   * `clearQueue`, and it under-counts when items are enqueued during the drain.
   * Use `getSyncHistory` for per-cycle results.
   */
  itemsDispatched: number;
  /**
   * Last pending-item count the loop observed, including items still cooling
   * down. After a stop during a flush this predates that flush. It is `0` if the
   * loop stopped before its first read, which takes a `timeoutMs` shorter than
   * one bridge call.
   */
  queueRemaining: number;
  /** Why the loop stopped. */
  stoppedReason: DrainStoppedReason;
}
