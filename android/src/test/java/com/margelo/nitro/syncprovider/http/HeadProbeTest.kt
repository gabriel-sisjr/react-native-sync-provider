package com.margelo.nitro.syncprovider.http

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real-time tests (`runBlocking`, not `runTest`): virtual time would fire the probe's
 * timeout as soon as the test coroutine waits for the server.
 */
internal class HeadProbeTest {

  private lateinit var server: MockWebServer

  /** A client of its own, so no test can pick up a connection another one pooled. */
  private val probe = HeadProbe(lazyOf(HeadProbe.newClient()))

  @Before
  fun setUp() {
    server = MockWebServer().apply { start() }
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun url(path: String = "/health"): String = server.url(path).toString()

  private suspend fun HeadProbe.answer(url: String, timeoutMs: Long = 2_000L): HeadResult.Response {
    val result = head(url, timeoutMs)
    assertThat(result).isInstanceOf(HeadResult.Response::class.java)
    return result as HeadResult.Response
  }

  private suspend fun HeadProbe.failure(url: String, timeoutMs: Long = 2_000L): HeadResult.Failure {
    val result = head(url, timeoutMs)
    assertThat(result).isInstanceOf(HeadResult.Failure::class.java)
    return result as HeadResult.Failure
  }

  /* ------------------------------- answers ------------------------------- */

  @Test
  fun `a 2xx answer reports its status and the time it took`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(150, TimeUnit.MILLISECONDS))

    val answer = probe.answer(url())

    assertThat(answer.statusCode).isEqualTo(204)
    assertThat(answer.latencyMs).isAtLeast(150L)
    assertThat(answer.latencyMs).isLessThan(2_000L)
    val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
    assertThat(request.method).isEqualTo("HEAD")
    assertThat(request.path).isEqualTo("/health")
  }

  @Test
  fun `4xx and 5xx statuses are answers too, and none is retried`() = runBlocking<Unit> {
    val codes = listOf(400, 401, 404, 405, 408, 429, 500, 503)
    codes.forEach { server.enqueue(MockResponse().setResponseCode(it)) }

    val answers = codes.map { probe.answer(url()) }

    assertThat(answers.map { it.statusCode }).containsExactlyElementsIn(codes).inOrder()
    assertThat(answers.map { it.latencyMs }.all { it in 0L until 2_000L }).isTrue()
    assertThat(server.requestCount).isEqualTo(codes.size)
  }

  @Test
  fun `the probe sends only no-cache beyond what every request carries`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(200))

    probe.answer(url())

    val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
    val allowed = setOf("host", "connection", "accept-encoding", "user-agent", "cache-control")
    val sent = request.headers.names().map { it.lowercase() }
    assertThat(allowed).containsAtLeastElementsIn(sent)
    assertThat(request.getHeader("Cache-Control")).isEqualTo("no-cache")
    assertThat(request.getHeader("Authorization")).isNull()
    assertThat(request.getHeader("Cookie")).isNull()
    assertThat(request.bodySize).isEqualTo(0L)
  }

  @Test
  fun `redirects are followed to the final status, as HEAD`() = runBlocking<Unit> {
    val redirects = listOf(301, 302, 303, 307, 308)
    redirects.forEach { code ->
      server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "/final"))
      server.enqueue(MockResponse().setResponseCode(200))
    }

    val answers = redirects.map { probe.answer(url("/start")) }

    assertThat(answers.map { it.statusCode }.distinct()).containsExactly(200)
    val requests = List(redirects.size * 2) {
      checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
    }
    assertThat(requests.map { it.method }.distinct()).containsExactly("HEAD")
    assertThat(requests.map { it.path }).containsExactlyElementsIn(
      redirects.flatMap { listOf("/start", "/final") },
    ).inOrder()
  }

  @Test
  fun `back-to-back probes share one connection`() = runBlocking<Unit> {
    repeat(3) { server.enqueue(MockResponse().setResponseCode(200)) }

    repeat(3) { probe.answer(url()) }

    val sequence = List(3) { checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)).sequenceNumber }
    assertThat(sequence).containsExactly(0, 1, 2).inOrder()
  }

  @Test
  fun `a default probe sends with the shared client`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(200))

    val answer = HeadProbe().answer(url())

    assertThat(answer.statusCode).isEqualTo(200)
  }

  /* ------------------------------- failures ------------------------------ */

  @Test
  fun `a server that never answers is a timeout, within the bound`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val startedAt = System.nanoTime()

    val failure = probe.failure(url(), timeoutMs = 300L)

    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    assertThat(failure.kind).isEqualTo(HeadResult.FailureKind.TIMEOUT)
    assertThat(elapsedMs).isAtLeast(250L)
    assertThat(elapsedMs).isLessThan(300L + 1_500L)
  }

  @Test
  fun `a refused connection is a network failure`() = runBlocking<Unit> {
    val stopped = MockWebServer().apply { start() }
    val target = stopped.url("/health").toString()
    stopped.shutdown()

    val failure = probe.failure(target)

    assertThat(failure.kind).isEqualTo(HeadResult.FailureKind.NETWORK)
    assertThat(failure.message).isNotEmpty()
  }

  @Test
  fun `an invalid URL fails before anything is sent and is never repeated`() = runBlocking<Unit> {
    val invalid = listOf(
      "",
      "   ",
      "not a url",
      "/health",
      "example.com/health",
      "ftp://example.com/health?token=secret",
      "ws://example.com/socket?token=secret",
    )

    val failures = invalid.map { probe.failure(it) }

    assertThat(failures.map { it.kind }.distinct())
      .containsExactly(HeadResult.FailureKind.INVALID_URL)
    assertThat(failures.none { it.message.contains("secret") }).isTrue()
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun `a timeout that is not positive is rejected`() {
    listOf(0L, -1L).forEach { timeoutMs ->
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { probe.head(url(), timeoutMs) }
      }
    }
    assertThat(server.requestCount).isEqualTo(0)
  }

  /* ------------------------------ cancellation ---------------------------- */

  @Test
  fun `cancelling the caller cancels the request`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val canceled = CountDownLatch(1)
    val client = HeadProbe.newClient().newBuilder()
      .eventListener(
        object : EventListener() {
          override fun canceled(call: Call) = canceled.countDown()
        },
      )
      .build()
    val listening = HeadProbe(lazyOf(client))
    val probing = launch(Dispatchers.Default) { listening.head(url(), timeoutMs = 30_000L) }
    // On the wire before the cancellation, so there is a request to cancel.
    assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()

    withTimeout(2_000L) { probing.cancelAndJoin() }

    assertThat(probing.isCancelled).isTrue()
    assertThat(canceled.await(2, TimeUnit.SECONDS)).isTrue()
    withTimeout(2_000L) {
      while (client.dispatcher.runningCallsCount() > 0) delay(10L)
    }
  }

  /* ---------------------------- client settings --------------------------- */

  @Test
  fun `the probe client adds nothing to a request and retries nothing`() {
    val client = HeadProbe.newClient()

    assertThat(client.interceptors).isEmpty()
    assertThat(client.networkInterceptors).isEmpty()
    assertThat(client.cache).isNull()
    assertThat(client.cookieJar).isSameInstanceAs(CookieJar.NO_COOKIES)
    assertThat(client.authenticator).isSameInstanceAs(Authenticator.NONE)
    assertThat(client.followRedirects).isTrue()
    assertThat(client.followSslRedirects).isTrue()
    assertThat(client.retryOnConnectionFailure).isFalse()
    // The per-call timeout is the only clock.
    assertThat(client.callTimeoutMillis).isEqualTo(0)
    assertThat(client.connectTimeoutMillis).isEqualTo(0)
    assertThat(client.readTimeoutMillis).isEqualTo(0)
    assertThat(client.writeTimeoutMillis).isEqualTo(0)
  }
}
