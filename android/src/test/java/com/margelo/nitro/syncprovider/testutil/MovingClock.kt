package com.margelo.nitro.syncprovider.testutil

import java.util.concurrent.atomic.AtomicLong

/**
 * A clock tests move by hand. Cooldown tests must not freeze time: a frozen clock
 * makes every attempt take zero milliseconds, which hides any bug that depends on an
 * attempt outlasting its backoff.
 */
internal class MovingClock(start: Long = 1_000_000L) {
  private val current = AtomicLong(start)

  fun now(): Long = current.get()

  fun advance(ms: Long): Long = current.addAndGet(ms)
}
