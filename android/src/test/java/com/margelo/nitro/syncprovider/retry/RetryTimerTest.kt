package com.margelo.nitro.syncprovider.retry

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
}
