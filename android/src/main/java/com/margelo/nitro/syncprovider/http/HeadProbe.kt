package com.margelo.nitro.syncprovider.http

import com.margelo.nitro.syncprovider.util.UrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** The outcome of one [HeadProbe.head] call. */
internal sealed interface HeadResult {
  /**
   * The server answered.
   *
   * @property statusCode the final status, after redirects. Every status is an answer,
   *   4xx and 5xx included: judging it is the caller's business.
   * @property latencyMs milliseconds from handing the request to the client until the
   *   final response's headers arrived, redirect hops included, on a monotonic clock.
   *   A new connection adds DNS, connect and TLS time; a pooled one does not.
   */
  data class Response(val statusCode: Int, val latencyMs: Long) : HeadResult

  /**
   * No answer arrived. [message] describes the failure for logs; for
   * [FailureKind.INVALID_URL] it never repeats the URL, whose query can carry a token.
   */
  data class Failure(val kind: FailureKind, val message: String) : HeadResult

  enum class FailureKind {
    /** The probe timeout ran out before an answer. */
    TIMEOUT,

    /** DNS, connect, TLS or protocol failure before an answer. */
    NETWORK,

    /** Not an absolute http(s) URL. Nothing was sent. */
    INVALID_URL,
  }
}

/**
 * Sends one `HEAD` request and reports how the server answered. Shared by the
 * network-quality probe and the health check.
 *
 * It sends with its own minimal client, never the dispatcher's:
 * - no interceptors, cache, cookies or authenticator, so a probe never carries the
 *   queue's default headers or credentials. The one header it adds,
 *   `Cache-Control: no-cache`, keeps an intermediary from answering from its cache;
 * - redirects are followed, so [HeadResult.Response.statusCode] is the final status;
 * - after a connection failure, the next address of the host: a host that resolves to
 *   IPv4 and IPv6 is reached through whichever works, as other platform clients do. The
 *   per-call timeout still bounds the whole exchange. A `408` is also repeated once, as
 *   OkHttp does whenever these retries are on;
 * - a single timeout, set per call, spanning DNS, connect, TLS, the request, the
 *   response headers and every redirect, so nothing inherits the dispatcher's timeouts;
 * - a short-lived connection pool. Back-to-back samples share a connection, and a later
 *   probe never picks up one the server may have closed meanwhile, which would add a
 *   silent retry to the time measured.
 *
 * @param client what to send with. Production shares [sharedClient], built once per
 *   process on first use; tests pass their own.
 */
internal class HeadProbe(client: Lazy<OkHttpClient> = sharedClient) {
  private val client: OkHttpClient by client

  /**
   * Sends `HEAD url` and suspends until the answer, a failure, or [timeoutMs].
   *
   * Cancelling the caller cancels the request. The response is closed before this
   * returns.
   *
   * @param timeoutMs the budget for the whole exchange. Must be positive: the client
   *   reads 0 as "no timeout".
   */
  suspend fun head(url: String, timeoutMs: Long): HeadResult {
    require(timeoutMs > 0) { "timeoutMs must be positive, was $timeoutMs" }
    if (!UrlValidator.isValid(url)) {
      return HeadResult.Failure(HeadResult.FailureKind.INVALID_URL, "Not an absolute http(s) URL")
    }
    val request = Request.Builder()
      .url(url.toHttpUrl())
      .head()
      .header("Cache-Control", "no-cache")
      .build()
    // IO for the first call, which builds the client (TLS and trust-store set-up). The
    // request itself never blocks a thread.
    return withContext(Dispatchers.IO) {
      // The call timeout ends the exchange. This net only covers what it cannot
      // interrupt, such as a DNS lookup that hangs.
      withTimeoutOrNull(safetyNetMs(timeoutMs)) { send(request, timeoutMs) } ?: timedOut(timeoutMs)
    }
  }

  private suspend fun send(request: Request, timeoutMs: Long): HeadResult =
    suspendCancellableCoroutine { continuation ->
      val call = client.newCall(request)
      call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
      continuation.invokeOnCancellation { call.cancel() }
      val startedAt = System.nanoTime()
      call.enqueue(
        object : Callback {
          override fun onResponse(call: Call, response: okhttp3.Response) {
            val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            val statusCode = response.use { it.code }
            continuation.resume(HeadResult.Response(statusCode, latencyMs))
          }

          // After a cancellation the continuation ignores this resume.
          override fun onFailure(call: Call, e: IOException) {
            continuation.resume(failureOf(e, timeoutMs))
          }
        },
      )
    }

  companion object {
    private const val SAFETY_NET_MARGIN_MS = 500L
    private const val POOL_MAX_IDLE_CONNECTIONS = 2

    /**
     * Below the shortest idle timeout common servers apply (5 s), so a pooled connection
     * is gone before the server can close it.
     */
    private const val POOL_KEEP_ALIVE_MS = 3_000L

    /** The client every [HeadProbe] shares unless given another. */
    val sharedClient: Lazy<OkHttpClient> = lazy { newClient() }

    /** A new client with the probe settings. */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
      .followRedirects(true)
      .followSslRedirects(true)
      .retryOnConnectionFailure(true)
      .cache(null)
      .cookieJar(CookieJar.NO_COOKIES)
      .connectionPool(
        ConnectionPool(POOL_MAX_IDLE_CONNECTIONS, POOL_KEEP_ALIVE_MS, TimeUnit.MILLISECONDS),
      )
      // 0 disables each of these: the per-call timeout is the only clock.
      .connectTimeout(0, TimeUnit.MILLISECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .writeTimeout(0, TimeUnit.MILLISECONDS)
      .build()

    private fun safetyNetMs(timeoutMs: Long): Long = when {
      timeoutMs > Long.MAX_VALUE - SAFETY_NET_MARGIN_MS -> Long.MAX_VALUE
      else -> timeoutMs + SAFETY_NET_MARGIN_MS
    }

    private fun timedOut(timeoutMs: Long): HeadResult.Failure =
      HeadResult.Failure(HeadResult.FailureKind.TIMEOUT, "No answer within $timeoutMs ms")

    /** The call timeout surfaces as an [InterruptedIOException], as a socket timeout would. */
    private fun failureOf(e: IOException, timeoutMs: Long): HeadResult.Failure = when (e) {
      is InterruptedIOException -> timedOut(timeoutMs)
      else -> HeadResult.Failure(
        HeadResult.FailureKind.NETWORK,
        e.message ?: e.javaClass.simpleName,
      )
    }
  }
}
