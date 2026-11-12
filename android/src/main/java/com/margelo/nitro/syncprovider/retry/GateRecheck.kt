package com.margelo.nitro.syncprovider.retry

/** Why an automatic wake may not dispatch yet. Holding one emits nothing. */
internal sealed interface WakeHold {
  /** The network-quality gate is closed: check again after the next [GateRecheck] step. */
  data object Quality : WakeHold

  /** A health-check backoff is running: check again when it ends, [remainingMs] from now. */
  data class Health(val remainingMs: Long) : WakeHold
}

/**
 * The delays between checks of a wake the network-quality gate holds back: 30 s, doubled
 * for each consecutive check the gate turns back too, at most 15 min.
 */
internal object GateRecheck {
  const val FIRST_DELAY_MS: Long = 30_000L
  const val MAX_DELAY_MS: Long = 15L * 60L * 1_000L

  /** The delay before the next check, after [streak] consecutive checks the gate turned back. */
  fun delayMs(streak: Int): Long {
    val doublings = streak.coerceIn(0, MAX_DOUBLINGS)
    return (FIRST_DELAY_MS shl doublings).coerceAtMost(MAX_DELAY_MS)
  }

  /** Past this many doublings the delay sits at [MAX_DELAY_MS] anyway. */
  private const val MAX_DOUBLINGS = 10
}
