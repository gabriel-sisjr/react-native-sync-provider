package com.margelo.nitro.syncprovider.retry

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class RetryTimerTest {

  private var offline = false
  private var flushes = 0

  private fun TestScope.timer(flush: suspend () -> Unit = { flushes++ }): RetryTimer =
    RetryTimer(scope = backgroundScope, isOffline = { offline }, flush = flush)

  private fun TestScope.advance(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
  }

  @Test
  fun `fires once when the delay elapses`() = runTest {
    val timer = timer()

    timer.arm(1_000L)
    advance(999L)
    assertThat(flushes).isEqualTo(0)

    advance(1L)
    assertThat(flushes).isEqualTo(1)

    advance(10_000L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `isWaiting tracks the countdown`() = runTest {
    val timer = timer()
    assertThat(timer.isWaiting).isFalse()

    timer.arm(1_000L)
    assertThat(timer.isWaiting).isTrue()

    advance(1_000L)
    assertThat(timer.isWaiting).isFalse()

    timer.arm(1_000L)
    timer.cancel()
    assertThat(timer.isWaiting).isFalse()
  }

  @Test
  fun `a zero delay fires at once`() = runTest {
    val timer = timer()

    timer.arm(0L)
    runCurrent()

    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `a new arm replaces the previous one`() = runTest {
    val timer = timer()

    timer.arm(1_000L)
    advance(500L)
    timer.arm(1_000L)
    advance(999L)
    assertThat(flushes).isEqualTo(0)

    advance(1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `cancel drops a waiting timer`() = runTest {
    val timer = timer()

    timer.arm(1_000L)
    timer.cancel()
    advance(5_000L)

    assertThat(flushes).isEqualTo(0)
  }

  @Test
  fun `a new arm does not abort a cycle that already started`() = runTest {
    val finishCycle = CompletableDeferred<Unit>()
    var completed = 0
    val timer = timer {
      finishCycle.await()
      completed++
    }

    timer.arm(0L)
    runCurrent()
    timer.arm(60_000L)
    finishCycle.complete(Unit)
    runCurrent()

    assertThat(completed).isEqualTo(1)
  }

  @Test
  fun `firing offline does not flush`() = runTest {
    offline = true
    val timer = timer()

    timer.arm(1_000L)
    advance(60_000L)

    assertThat(flushes).isEqualTo(0)
  }

  @Test
  fun `a wake that fired offline is delivered once the network returns`() = runTest {
    offline = true
    val timer = timer()
    timer.arm(1_000L)
    advance(1_000L)

    offline = false
    timer.onNetworkAvailable()
    runCurrent()
    assertThat(flushes).isEqualTo(1)

    // Delivered once: later connectivity changes owe nothing.
    timer.onNetworkAvailable()
    runCurrent()
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `the network returning owes nothing if no wake fired offline`() = runTest {
    val timer = timer()

    timer.onNetworkAvailable()
    runCurrent()

    assertThat(flushes).isEqualTo(0)
  }

  @Test
  fun `a network that returns between the check and the flag is not missed`() = runTest {
    var checks = 0
    val timer = RetryTimer(
      scope = backgroundScope,
      // Offline for the first check only: its callback passed before the flag was set.
      isOffline = { checks++ == 0 },
      flush = { flushes++ },
    )

    timer.arm(0L)
    runCurrent()

    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `cancel forgets a wake owed to the network`() = runTest {
    offline = true
    val timer = timer()
    timer.arm(0L)
    runCurrent()

    timer.cancel()
    offline = false
    timer.onNetworkAvailable()
    runCurrent()

    assertThat(flushes).isEqualTo(0)
  }

  @Test
  fun `a new arm supersedes a wake owed to the network`() = runTest {
    offline = true
    val timer = timer()
    timer.arm(0L)
    runCurrent()

    offline = false
    timer.arm(1_000L)
    timer.onNetworkAvailable()
    runCurrent()
    assertThat(flushes).isEqualTo(0)

    advance(1_000L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `a failing flush is contained and the timer keeps working`() = runTest {
    var calls = 0
    val timer = timer {
      calls++
      if (calls == 1) error("no JS caller exists to receive this")
    }

    timer.arm(0L)
    runCurrent()
    timer.arm(0L)
    runCurrent()

    assertThat(calls).isEqualTo(2)
  }

  /* ------------------------------ held by a gate ------------------------------ */

  /** Null while the gate is open; read by the timer before each dispatch, as the runtime does. */
  private var hold: WakeHold? = null
  private var holdChecks = 0

  private fun TestScope.gatedTimer(flush: suspend () -> Unit = { flushes++ }): RetryTimer =
    RetryTimer(
      scope = backgroundScope,
      isOffline = { offline },
      flush = flush,
      holdReason = {
        holdChecks++
        hold
      },
    )

  @Test
  fun `gate_automaticWake_rerunsWhenQualityReachesMinimum, holding it silently until then`() = runTest {
    hold = WakeHold.Quality
    val timer = gatedTimer()

    timer.arm(1_000L)
    advance(1_000L)
    // Held without a flush, so the dispatcher emits nothing.
    assertThat(flushes).isEqualTo(0)
    assertThat(timer.isWaiting).isTrue()

    hold = null
    timer.onDispatchPossible()
    runCurrent()
    assertThat(flushes).isEqualTo(1)

    // Delivered once: its next check does not run it again.
    advance(GateRecheck.MAX_DELAY_MS)
    assertThat(flushes).isEqualTo(1)
    assertThat(timer.isWaiting).isFalse()
  }

  @Test
  fun `a held wake is checked again after 30 s, doubling up to 15 min`() = runTest {
    hold = WakeHold.Quality
    val timer = gatedTimer()
    timer.arm(0L)
    runCurrent()
    val checks = mutableListOf<Long>()

    repeat(7) {
      val before = holdChecks
      val startedAt = currentTime
      while (holdChecks == before) advance(1_000L)
      checks += currentTime - startedAt
    }

    assertThat(checks).containsExactly(
      30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L,
    ).inOrder()
    assertThat(flushes).isEqualTo(0)
  }

  @Test
  fun `a held wake runs on its own check once the gate opens`() = runTest {
    hold = WakeHold.Quality
    val timer = gatedTimer()
    timer.arm(0L)
    runCurrent()

    hold = null
    advance(GateRecheck.FIRST_DELAY_MS)

    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `a held wake waits for the minimum, not just for the network`() = runTest {
    hold = WakeHold.Quality
    val timer = gatedTimer()
    timer.arm(0L)
    runCurrent()

    timer.onNetworkAvailable()
    runCurrent()

    assertThat(flushes).isEqualTo(0)
  }

  @Test
  fun `a wake that fired offline is held, not flushed, when the gate is closed once online`() = runTest {
    offline = true
    hold = WakeHold.Quality
    val timer = gatedTimer()
    timer.arm(0L)
    runCurrent()

    offline = false
    timer.onNetworkAvailable()
    runCurrent()
    assertThat(flushes).isEqualTo(0)
    assertThat(timer.isWaiting).isTrue()

    hold = null
    timer.onDispatchPossible()
    runCurrent()
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `a new link starts the backoff over and checks a held wake within 30 s`() = runTest {
    hold = WakeHold.Quality
    val timer = gatedTimer()
    timer.arm(0L)
    runCurrent()
    // Two checks in: the next one would come 120 s later.
    advance(30_000L + 60_000L)
    val before = holdChecks

    timer.resetGateBackoff()
    advance(GateRecheck.FIRST_DELAY_MS - 1L)
    assertThat(holdChecks).isEqualTo(before)

    advance(1L)
    assertThat(holdChecks).isGreaterThan(before)
  }

  @Test
  fun `a new arm starts the backoff over and supersedes a held wake`() = runTest {
    hold = WakeHold.Quality
    val timer = gatedTimer()
    timer.arm(0L)
    runCurrent()
    advance(30_000L + 60_000L)

    timer.arm(1_000L)
    timer.onDispatchPossible()
    runCurrent()
    assertThat(flushes).isEqualTo(0)

    advance(1_000L)
    val before = holdChecks
    advance(GateRecheck.FIRST_DELAY_MS - 1L)
    assertThat(holdChecks).isEqualTo(before)
    advance(1L)
    assertThat(holdChecks).isGreaterThan(before)
  }

  @Test
  fun `a gate that opens between the check and the hold releases the wake at once`() = runTest {
    var checksLeft = 1
    val timer = RetryTimer(
      scope = backgroundScope,
      isOffline = { false },
      flush = { flushes++ },
      holdReason = { if (checksLeft-- > 0) WakeHold.Quality else null },
    )

    timer.arm(0L)
    runCurrent()

    assertThat(flushes).isEqualTo(1)
    assertThat(timer.isWaiting).isFalse()
  }

  @Test
  fun `a wake the dispatcher turned back is held like one the timer held`() = runTest {
    val timer = gatedTimer()
    hold = WakeHold.Quality

    timer.holdForQuality()
    advance(GateRecheck.FIRST_DELAY_MS - 1L)
    assertThat(flushes).isEqualTo(0)

    hold = null
    advance(1L)
    assertThat(flushes).isEqualTo(1)
  }

  @Test
  fun `a health-check backoff holds a wake silently until it ends, outside the quality backoff`() =
    runTest {
      hold = WakeHold.Health(remainingMs = 45_000L)
      val timer = gatedTimer()
      timer.arm(0L)
      runCurrent()

      advance(44_999L)
      assertThat(flushes).isEqualTo(0)
      // The backoff ends while the quality gate is closed: the quality backoff starts at 30 s.
      hold = WakeHold.Quality
      advance(1L)
      val before = holdChecks
      advance(GateRecheck.FIRST_DELAY_MS - 1L)
      assertThat(holdChecks).isEqualTo(before)
      advance(1L)
      assertThat(holdChecks).isGreaterThan(before)
      assertThat(flushes).isEqualTo(0)
    }

  @Test
  fun `cancel forgets a held wake`() = runTest {
    hold = WakeHold.Quality
    val timer = gatedTimer()
    timer.arm(0L)
    runCurrent()

    timer.cancel()
    hold = null
    timer.onDispatchPossible()
    advance(GateRecheck.MAX_DELAY_MS)

    assertThat(flushes).isEqualTo(0)
  }

  @Test
  fun `a wake owed to the network is also delivered once dispatch is possible`() = runTest {
    offline = true
    val timer = gatedTimer()
    timer.arm(0L)
    runCurrent()

    offline = false
    timer.onDispatchPossible()
    runCurrent()

    assertThat(flushes).isEqualTo(1)
  }
}
