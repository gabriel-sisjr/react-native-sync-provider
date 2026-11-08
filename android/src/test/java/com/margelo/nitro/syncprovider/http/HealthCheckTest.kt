package com.margelo.nitro.syncprovider.http

import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.testutil.MovingClock
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

internal class HealthCheckTest {

  private lateinit var server: MockWebServer
  private val wall = MovingClock()
  private val monotonic = MovingClock(start = 5_000L)
  private val check = HealthCheck(
    probe = HeadProbe(lazyOf(HeadProbe.newClient())),
    wallClock = wall::now,
    monotonicClock = monotonic::now,
  )

  @Before
  fun setUp() {
    server = MockWebServer().apply { start() }
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun url(path: String = "/health"): String = server.url(path).toString()

  @Test
  fun `a 2xx answer elsewhere counts only for the URL that gave it`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(200))
    check.recordHealthy(url("/other"))

    val verdict = check.check(url(), ttlMs = 60_000L, backoffMs = 30_000L)

    assertThat(verdict).isEqualTo(HealthCheck.Verdict.Healthy)
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `the reuse window runs on the monotonic clock`() = runBlocking<Unit> {
    check.recordHealthy(url())

    wall.advance(3_600_000L)
    assertThat(check.check(url(), ttlMs = 60_000L, backoffMs = 0L)).isEqualTo(HealthCheck.Verdict.Healthy)
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun `a clock that went backwards reads as stale`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(200))
    check.recordHealthy(url())
    monotonic.advance(-1L)

    check.check(url(), ttlMs = 60_000L, backoffMs = 0L)

    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `a failure backs off until the wall-clock instant it reports, and 0 means none`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(500))
    server.enqueue(MockResponse().setResponseCode(500))
    server.enqueue(MockResponse().setResponseCode(500))

    val failed = check.check(url(), ttlMs = 60_000L, backoffMs = 30_000L)
    assertThat(failed).isEqualTo(HealthCheck.Verdict.Failed(until = wall.now() + 30_000L))
    assertThat(check.wakeNotBefore()).isEqualTo(wall.now() + 30_000L)
    assertThat(check.check(url(), ttlMs = 60_000L, backoffMs = 30_000L))
      .isEqualTo(HealthCheck.Verdict.BackingOff(until = wall.now() + 30_000L))

    check.clearBackoff()
    assertThat(check.wakeNotBefore()).isEqualTo(0L)
    // No backoff: the next check sends its HEAD at once.
    assertThat(check.check(url(), ttlMs = 60_000L, backoffMs = 0L))
      .isEqualTo(HealthCheck.Verdict.Failed(until = wall.now()))
    assertThat(check.check(url(), ttlMs = 60_000L, backoffMs = 0L))
      .isEqualTo(HealthCheck.Verdict.Failed(until = wall.now()))
    assertThat(server.requestCount).isEqualTo(3)
  }

  @Test
  fun `health_zeroBackoff_holdsAutomaticWakesForOneSecond`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    server.enqueue(MockResponse().setResponseCode(200))

    check.check(url(), ttlMs = 60_000L, backoffMs = 0L)
    val failedAt = wall.now()

    assertThat(check.wakeNotBefore()).isEqualTo(failedAt + HealthCheck.MIN_WAKE_BACKOFF_MS)
    wall.advance(HealthCheck.MIN_WAKE_BACKOFF_MS - 1L)
    assertThat(check.wakeNotBefore()).isEqualTo(failedAt + HealthCheck.MIN_WAKE_BACKOFF_MS)
    wall.advance(1L)
    assertThat(check.wakeNotBefore()).isEqualTo(0L)

    // A success lifts the hold at once, as a configureSync does.
    check.check(url(), ttlMs = 60_000L, backoffMs = 0L)
    assertThat(check.wakeNotBefore()).isEqualTo(0L)
  }

  @Test
  fun `a backoff too long for the clock never ends, rather than ending in the past`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))

    val failed = check.check(url(), ttlMs = 60_000L, backoffMs = Long.MAX_VALUE)

    assertThat(failed).isEqualTo(HealthCheck.Verdict.Failed(until = Long.MAX_VALUE))
    assertThat(check.wakeNotBefore()).isEqualTo(Long.MAX_VALUE)
  }
}
