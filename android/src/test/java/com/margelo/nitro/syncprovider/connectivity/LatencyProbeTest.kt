package com.margelo.nitro.syncprovider.connectivity

import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.http.HeadProbe
import com.margelo.nitro.syncprovider.http.HeadResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

internal class LatencyProbeTest {

  private lateinit var server: MockWebServer
  private val probe = LatencyProbe(HeadProbe(lazyOf(HeadProbe.newClient())))

  @Before
  fun setUp() {
    server = MockWebServer().apply { start() }
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `three HEADs back to back share one connection`() = runBlocking<Unit> {
    repeat(LatencyProbe.SAMPLES) { server.enqueue(MockResponse().setResponseCode(204)) }

    val results = probe.measure(server.url("/health").toString())

    assertThat(results).hasSize(3)
    assertThat(results.all { it is HeadResult.Response }).isTrue()
    val requests = List(3) { checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
    assertThat(requests.map { it.method }.distinct()).containsExactly("HEAD")
    assertThat(requests.map { it.getHeader("Cache-Control") }.distinct()).containsExactly("no-cache")
    assertThat(requests.map { it.sequenceNumber }).containsExactly(0, 1, 2).inOrder()
  }

  @Test
  fun `any status is a sample, and a request without an answer counts as infinite`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    // Read, then hung up on: the pooled connection dies, so the next request opens another.
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    server.enqueue(MockResponse().setResponseCode(404))

    val latencies = LatencyProbe.latencies(probe.measure(server.url("/health").toString()))

    assertThat(latencies).hasSize(3)
    assertThat(latencies[0]).isNotNull()
    assertThat(latencies[1]).isNull()
    assertThat(latencies[2]).isNotNull()
  }
}
