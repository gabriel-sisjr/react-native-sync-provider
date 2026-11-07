package com.margelo.nitro.syncprovider.http

import android.os.SystemClock

/**
 * The check a cycle runs before claiming its first item, with the state it keeps between
 * cycles:
 * - when `healthCheckUrl` last answered `2xx`. A `2xx` latency sample of the network-quality
 *   probe counts too ([recordHealthy]), so the endpoint is not hit twice. Measured on a
 *   monotonic clock, and only for the URL that answered;
 * - the backoff after a failure, until a wall-clock instant, the clock retry deadlines use:
 *   no request is sent until it ends, and no wake is armed before it.
 *
 * One `HEAD` per check, through the probe the quality measurement shares. Healthy means a
 * final `2xx` status, after redirects; any other status, a transport error or a timeout is a
 * failure.
 */
internal class HealthCheck(
  private val probe: HeadProbe,
  private val wallClock: () -> Long = { System.currentTimeMillis() },
  private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
  private val timeoutMs: Long = TIMEOUT_MS,
) {
  private val lock = Any()

  /** Guarded by [lock]. */
  private var healthyUrl: String? = null
  private var healthyAt = 0L
  private var backoffUntil = 0L

  /** What [check] decided. */
  sealed interface Verdict {
    /** The endpoint answered `2xx`, now or within the reuse window: the cycle may run. */
    data object Healthy : Verdict

    /** The check failed now. Cycles are skipped until [until], a wall-clock instant. */
    data class Failed(val until: Long) : Verdict

    /** A backoff from an earlier failure runs until [until]; nothing was sent. */
    data class BackingOff(val until: Long) : Verdict
  }

  /**
   * @param ttlMs how long a `2xx` answer is reused; `0` checks every time.
   * @param backoffMs how long cycles are skipped after a failure; `0` means no backoff.
   */
  suspend fun check(url: String, ttlMs: Long, backoffMs: Long): Verdict {
    synchronized(lock) {
      if (backoffUntil > wallClock()) return Verdict.BackingOff(backoffUntil)
      if (isFreshLocked(url, ttlMs)) return Verdict.Healthy
    }
    val result = probe.head(url, timeoutMs)
    val healthy = result is HeadResult.Response && result.statusCode in HEALTHY_STATUSES
    synchronized(lock) {
      if (healthy) {
        healthyUrl = url
        healthyAt = monotonicClock()
        backoffUntil = 0L
        return Verdict.Healthy
      }
      backoffUntil = wallClock() + backoffMs
      return Verdict.Failed(backoffUntil)
    }
  }

  /** A `2xx` answer from [url] outside [check], from the network-quality probe. */
  fun recordHealthy(url: String) {
    synchronized(lock) {
      healthyUrl = url
      healthyAt = monotonicClock()
    }
  }

  /** The end of the running backoff, a wall-clock instant, or `0` when none runs. */
  fun backoffUntil(): Long = synchronized(lock) {
    if (backoffUntil > wallClock()) backoffUntil else 0L
  }

  /** `configureSync` ran: the next cycle checks again at once. */
  fun clearBackoff() {
    synchronized(lock) { backoffUntil = 0L }
  }

  private fun isFreshLocked(url: String, ttlMs: Long): Boolean {
    if (healthyUrl != url) return false
    val age = monotonicClock() - healthyAt
    return age in 0 until ttlMs
  }

  companion object {
    /** `healthCheckTtlMs` when unset. */
    const val DEFAULT_TTL_MS: Long = 60_000L

    /** `healthCheckBackoffMs` when unset. */
    const val DEFAULT_BACKOFF_MS: Long = 30_000L

    /** The probe's short timeout, as for a latency sample. */
    const val TIMEOUT_MS: Long = 2_000L

    private val HEALTHY_STATUSES = 200..299
  }
}
