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

    val failed = check.check(url(), ttlMs = 60_000L, backoffMs = 30_000L)
    assertThat(failed).isEqualTo(HealthCheck.Verdict.Failed(until = wall.now() + 30_000L))
    assertThat(check.backoffUntil()).isEqualTo(wall.now() + 30_000L)
    assertThat(check.check(url(), ttlMs = 60_000L, backoffMs = 30_000L))
      .isEqualTo(HealthCheck.Verdict.BackingOff(until = wall.now() + 30_000L))

    check.clearBackoff()
    assertThat(check.backoffUntil()).isEqualTo(0L)
    check.check(url(), ttlMs = 60_000L, backoffMs = 0L)
    assertThat(check.backoffUntil()).isEqualTo(0L)
    assertThat(server.requestCount).isEqualTo(2)
  }
}
