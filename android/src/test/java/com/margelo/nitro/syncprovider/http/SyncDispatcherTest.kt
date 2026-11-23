package com.margelo.nitro.syncprovider.http

import android.database.sqlite.SQLiteDatabaseLockedException
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.margelo.nitro.syncprovider.AfterDispatchContext
import com.margelo.nitro.syncprovider.BeforeDispatchAction
import com.margelo.nitro.syncprovider.BeforeDispatchContext
import com.margelo.nitro.syncprovider.CallbackAnswer
import com.margelo.nitro.syncprovider.DispatchOutcome
import com.margelo.nitro.syncprovider.HeaderResolverContext
import com.margelo.nitro.syncprovider.HttpMethod
import com.margelo.nitro.syncprovider.NetworkQuality
import com.margelo.nitro.syncprovider.RetryAction
import com.margelo.nitro.syncprovider.RetryDeciderContext
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncEvent
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.SyncOptions
import com.margelo.nitro.syncprovider.SyncResult
import com.margelo.nitro.syncprovider.callbacks.HybridInstalls
import com.margelo.nitro.syncprovider.callbacks.JsCallbacks
import com.margelo.nitro.syncprovider.connectivity.LatencyProbe
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.database.SyncDeadLetterEntity
import com.margelo.nitro.syncprovider.database.SyncItemDao
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.error.SyncException
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.retry.RetryTimer
import com.margelo.nitro.syncprovider.retry.RetryWake
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.testutil.FakeFunction
import com.margelo.nitro.syncprovider.testutil.FakeJs
import com.margelo.nitro.syncprovider.testutil.MovingClock
import com.margelo.nitro.syncprovider.testutil.TestData
import com.margelo.nitro.syncprovider.util.JsonMap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.URI
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SyncDispatcherTest {

  private lateinit var db: SyncDatabase
  private lateinit var storage: SyncQueueStorage
  private lateinit var emitter: SyncEventEmitter
  private lateinit var server: MockWebServer
  private val clock = MovingClock()

  /** The `notBefore` of every finished cycle, in order. */
  private val finishedCycles: MutableList<Long?> = Collections.synchronizedList(mutableListOf())

  /** Where fake JS answers later, and where [collectEvents] collects. */
  private val jsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** Every line the callbacks logged, and the priority of each, in the same order. */
  private val callbackLog: MutableList<String> = Collections.synchronizedList(mutableListOf())
  private val callbackPriorities: MutableList<Int> = Collections.synchronizedList(mutableListOf())

  private val callbacks = JsCallbacks(
    scope = jsScope,
    notifyContext = Dispatchers.Default,
    log = { priority, message ->
      synchronized(callbackLog) {
        callbackLog += message
        callbackPriorities += priority
      }
    },
  )
  private val js = FakeJs(callbacks.gateway, jsScope)

  /** The current hybrid: what the tests install, they install through it. */
  private val hybrid = HybridInstalls(callbacks)

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      SyncDatabase::class.java,
    ).allowMainThreadQueries().build()
    storage = SyncQueueStorage.from(db)
    emitter = SyncEventEmitter()
    server = MockWebServer().apply { start() }
  }

  @After
  fun tearDown() {
    jsScope.cancel()
    server.shutdown()
    db.close()
  }

  @Suppress("LongParameterList")
  private fun newDispatcher(
    requestTimeoutMs: Long = 5_000L,
    maxAttempts: Int = 3,
    baseDelayMs: Long = 1_000L,
    maxDelayMs: Long = 60_000L,
    batchSize: Int = 25,
    isPaused: () -> Boolean = { false },
    credentials: () -> CredentialPolicy = { CredentialPolicy.TRUSTED },
    onCycleFinished: suspend (Long?, CycleEnding) -> Unit = { notBefore, _ ->
      finishedCycles += notBefore
    },
    queue: SyncQueueStorage = storage,
    minNetworkQuality: NetworkQuality? = null,
    quality: () -> NetworkQuality = { NetworkQuality.GOOD },
    healthCheckUrl: String? = null,
    healthCheckTtlMs: Double? = null,
    healthCheckBackoffMs: Double? = null,
    healthCheck: HealthCheck = this.healthCheck,
    defaultHeaders: Map<String, String>? = null,
    callbackTimeoutMs: Double? = null,
    tweak: (SyncOptions) -> SyncOptions = { it },
  ): SyncDispatcher {
    val options = TestData.syncOptions(
      retryPolicy = TestData.retryPolicy(
        maxAttempts = maxAttempts,
        baseDelayMs = baseDelayMs,
        maxDelayMs = maxDelayMs,
        jitter = false,
      ),
      requestTimeoutMs = requestTimeoutMs,
      batchSize = batchSize,
      minNetworkQuality = minNetworkQuality,
      healthCheckUrl = healthCheckUrl,
      healthCheckTtlMs = healthCheckTtlMs,
      healthCheckBackoffMs = healthCheckBackoffMs,
      defaultHeaders = defaultHeaders,
      callbackTimeoutMs = callbackTimeoutMs,
    ).let(tweak)
    return SyncDispatcher(
      storage = queue,
      emitter = emitter,
      getOptions = { options },
      clock = clock::now,
      isPaused = isPaused,
      credentialPolicy = credentials,
      networkQuality = quality,
      healthCheck = healthCheck,
      onHealthCheckSkipped = { healthSkips++ },
      onCycleFinished = onCycleFinished,
      callbacks = callbacks,
    )
  }

  /** On the test clock, so the reuse window and the backoff move with it. */
  private val healthCheck: HealthCheck by lazy { healthCheck() }
  private var healthSkips = 0

  private fun healthCheck(timeoutMs: Long = HealthCheck.TIMEOUT_MS): HealthCheck = HealthCheck(
    probe = HeadProbe(lazyOf(HeadProbe.newClient())),
    wallClock = clock::now,
    monotonicClock = clock::now,
    timeoutMs = timeoutMs,
  )

  /** Answers [code] after moving the clock by [attemptMs], as a real request would. */
  private fun respondAfter(attemptMs: Long, code: Int): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
      clock.advance(attemptMs)
      return MockResponse().setResponseCode(code)
    }
  }

  private fun TestScope.recordEvents(source: SyncEventEmitter = emitter): List<SyncEvent> {
    val events: MutableList<SyncEvent> = Collections.synchronizedList(mutableListOf())
    backgroundScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
      source.events.collect { events += it }
    }
    return events
  }

  private fun List<SyncEvent>.types(): List<SyncEventType> = map { it.type }

  private suspend fun enqueue(path: String = "/x"): String =
    storage.enqueue(TestData.input(url = server.url(path).toString()))

  private suspend fun row(id: String): SyncItemEntity = db.syncItemDao().findById(id)!!

  /** The dead letter [id] became; it is no longer queued. */
  private suspend fun deadLetter(id: String): SyncDeadLetterEntity {
    assertThat(db.syncItemDao().findById(id)).isNull()
    return checkNotNull(db.syncDeadLetterDao().findById(id))
  }

  /** A transient local failure, as SQLite reports a busy database. */
  private fun locked() = SQLiteDatabaseLockedException("database is locked")

  /* ---------------------------- plain dispatch ---------------------------- */

  @Test
  fun `flush dispatches a successful POST and removes the item`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
    val d = newDispatcher()
    val id = storage.enqueue(
      TestData.input(
        method = HttpMethod.POST,
        url = server.url("/echo").toString(),
      ),
    )

    val result = d.flush()

    assertThat(result.successCount.toInt()).isEqualTo(1)
    assertThat(result.succeededIds.toList()).containsExactly(id)
    assertThat(storage.count()).isEqualTo(0)
    val last = storage.getLastResult()
    assertThat(last).isNotNull()
    assertThat(last!!.successCount.toInt()).isEqualTo(1)
  }

  @Test
  fun `isSyncing flips false after flush completes`() = runTest {
    val d = newDispatcher()

    assertThat(d.isSyncing()).isFalse()
    d.flush()
    assertThat(d.isSyncing()).isFalse()
  }

  /* ---------------------------- request timeout --------------------------- */

  private suspend fun inFlightRows(): List<SyncItemEntity> =
    db.syncItemDao().findByStatusesLimit(listOf(SyncItemEntity.STATUS_IN_FLIGHT), 100)

  /**
   * OkHttp refuses a call timeout above `Int.MAX_VALUE` ms ("timeout too large."). Thrown
   * before the request, it left the row `IN_FLIGHT` and unsent, for every later recovery to
   * hand back to a cycle that failed it the same way.
   */
  @Test
  fun `a requestTimeoutMs beyond OkHttp's limit is capped, and the request goes out`() = runTest {
    for (timeoutMs in listOf(Double.POSITIVE_INFINITY, 1e300)) {
      server.enqueue(MockResponse().setResponseCode(200))
      val id = enqueue()

      val result = newDispatcher(tweak = { it.copy(requestTimeoutMs = timeoutMs) }).flush()

      assertWithMessage("rows left IN_FLIGHT with requestTimeoutMs = $timeoutMs")
        .that(inFlightRows().map { it.id }).isEmpty()
      assertWithMessage("requestTimeoutMs = $timeoutMs")
        .that(result.succeededIds.toList()).containsExactly(id)
      assertThat(storage.count()).isEqualTo(0)
    }
    assertThat(server.requestCount).isEqualTo(2)
  }

  /**
   * `requestTimeoutMs` bounds the whole call through OkHttp's call timeout, and OkHttp ends a
   * call that runs out of it with an `InterruptedIOException`, not a socket timeout: it was
   * reported as `NETWORK_ERROR`.
   */
  @Test
  fun `requestTimeoutMs running out is a TIMEOUT, and it is retried`() = runTest {
    // Reads the request and never answers: only the 1 s call timeout can end the call, long
    // before the client's 30 s read timeout.
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val id = enqueue()
    val events = recordEvents()

    val result = newDispatcher(requestTimeoutMs = 1_000L, maxAttempts = 3).flush()

    assertThat(row(id).lastErrorCode).isEqualTo(SyncErrorCode.TIMEOUT.name)
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).attempts).isEqualTo(1)
    assertThat(row(id).nextRetryAt).isGreaterThan(0L)
    assertThat(events.single { it.type == SyncEventType.ITEM_RETRYING }.errorCode)
      .isEqualTo(SyncErrorCode.TIMEOUT)
    assertThat(result.failedIds).isEmpty()
  }

  @Test
  fun `a connection dropped before the response is a NETWORK_ERROR, and it is retried`() = runTest {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    val id = enqueue()
    val events = recordEvents()

    val result = newDispatcher(requestTimeoutMs = 5_000L, maxAttempts = 3).flush()

    assertThat(row(id).lastErrorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR.name)
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).attempts).isEqualTo(1)
    assertThat(events.single { it.type == SyncEventType.ITEM_RETRYING }.errorCode)
      .isEqualTo(SyncErrorCode.NETWORK_ERROR)
    assertThat(result.failedIds).isEmpty()
  }

  /** The failures as OkHttp 4.12.0 raises them; a thread interrupt cannot be staged reliably. */
  @Test
  fun `only a socket timeout or a call that ran out of its timeout is a TIMEOUT`() {
    fun code(failure: IOException, ranOutOfTime: Boolean): SyncErrorCode =
      SyncDispatcher.transportFailureCode(failure, ranOutOfTime)
    // What RealCall makes of whatever stopped a call whose timeout ran out.
    val callTimeout = InterruptedIOException("timeout")
      .apply { initCause(SocketException("Socket closed")) }

    assertThat(code(callTimeout, ranOutOfTime = true)).isEqualTo(SyncErrorCode.TIMEOUT)
    assertThat(code(SocketTimeoutException("timeout"), ranOutOfTime = false))
      .isEqualTo(SyncErrorCode.TIMEOUT)
    // Okio's answer to a thread interrupt: the same class, before the deadline.
    assertThat(code(InterruptedIOException("interrupted"), ranOutOfTime = false))
      .isEqualTo(SyncErrorCode.NETWORK_ERROR)
    assertThat(code(IOException("unexpected end of stream"), ranOutOfTime = false))
      .isEqualTo(SyncErrorCode.NETWORK_ERROR)
    // A reset after the deadline, before the timeout fired, is still a reset.
    assertThat(code(SocketException("Connection reset"), ranOutOfTime = true))
      .isEqualTo(SyncErrorCode.NETWORK_ERROR)
  }

  /* -------------------------- cooldown and attempts ----------------------- */

  @Test
  fun `retriable failure cools down then succeeds on a later eligible cycle`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
    val d = newDispatcher(maxAttempts = 3, baseDelayMs = 1_000L)
    val id = enqueue()

    // Cycle 1: the 503 is retriable, so the item is rescheduled (cooling) and counts
    // as neither success nor failure.
    val first = d.flush()
    assertThat(first.successCount.toInt()).isEqualTo(0)
    assertThat(first.failureCount.toInt()).isEqualTo(0)
    val cooling = row(id)
    assertThat(cooling.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(cooling.attempts).isEqualTo(1)
    assertThat(cooling.nextRetryAt).isGreaterThan(clock.now())
    assertThat(server.requestCount).isEqualTo(1)

    // Cycle 2 before the cooldown elapses: excluded from the claim, so no request.
    clock.advance(1)
    val tooEarly = d.flush()
    assertThat(tooEarly.successCount.toInt()).isEqualTo(0)
    assertThat(tooEarly.failureCount.toInt()).isEqualTo(0)
    assertThat(server.requestCount).isEqualTo(1)

    // Past the cooldown the row is eligible and the 200 clears it.
    clock.advance(cooling.nextRetryAt - clock.now())
    val second = d.flush()
    assertThat(second.successCount.toInt()).isEqualTo(1)
    assertThat(second.succeededIds.toList()).containsExactly(id)
    assertThat(server.requestCount).isEqualTo(2)
    assertThat(storage.count()).isEqualTo(0)
  }

  @Test
  fun `the cooldown is measured from the end of the attempt`() = runTest {
    // The attempt outlasts its 1s backoff, as any timeout does. Measured from the
    // start of the attempt, the cooldown would already be over when it was written.
    server.dispatcher = respondAfter(attemptMs = 5_000L, code = 503)
    val id = enqueue()
    val startedAt = clock.now()

    newDispatcher(baseDelayMs = 1_000L).flush()

    assertThat(clock.now()).isEqualTo(startedAt + 5_000L)
    assertThat(row(id).nextRetryAt).isEqualTo(clock.now() + 1_000L)
  }

  @Test
  fun `a zero backoff still writes a positive deadline`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 10L, code = 503)
    val id = enqueue()

    newDispatcher(baseDelayMs = 0L, maxDelayMs = 0L).flush()

    // Positive means "attempted, retry pending"; 0 stays reserved for never attempted.
    assertThat(row(id).attempts).isEqualTo(1)
    assertThat(row(id).nextRetryAt).isEqualTo(clock.now())
    assertThat(row(id).nextRetryAt).isGreaterThan(0L)
  }

  /**
   * A delay past `Long.MAX_VALUE - now` overflowed `finishedAt + delay` into a deadline long
   * past: the row was due at once, and the next cycle retried it without waiting. The deadline
   * now stops at the largest JavaScript Date, a value `getPendingItems()` hands JS exactly.
   */
  @Test
  fun `a retry delay too large to represent keeps the row waiting`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 0L, code = 503)
    for ((index, base) in listOf(Double.POSITIVE_INFINITY, 1e300).withIndex()) {
      val id = enqueue()
      val d = newDispatcher(tweak = { it.copy(retryPolicy = it.retryPolicy.copy(baseDelayMs = base)) })

      d.flush()
      val cooling = row(id)
      d.flush()

      assertWithMessage("baseDelayMs = $base")
        .that(cooling.nextRetryAt).isEqualTo(TestData.MAX_JS_DATE_MS)
      assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(row(id).attempts).isEqualTo(1)
      assertWithMessage("requests after baseDelayMs = $base")
        .that(server.requestCount).isEqualTo(index + 1)
    }
  }

  /** The hold on a rejected restored credential adds the same delay, and cannot overflow either. */
  @Test
  fun `a credential hold too long to represent keeps the row waiting`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 10L, code = 401)
    val id = enqueue()
    val d = newDispatcher(
      credentials = { CredentialPolicy.PERSISTED },
      tweak = { it.copy(retryPolicy = it.retryPolicy.copy(baseDelayMs = Double.POSITIVE_INFINITY)) },
    )

    d.flush()
    val held = row(id)
    d.flush()

    assertThat(held.nextRetryAt).isEqualTo(TestData.MAX_JS_DATE_MS)
    assertThat(finishedCycles.first()).isEqualTo(held.nextRetryAt)
    assertThat(row(id).attempts).isEqualTo(0)
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `attempts accumulate across cycles until the budget is exhausted`() = runTest {
    repeat(2) { server.enqueue(MockResponse().setResponseCode(500)) }
    val d = newDispatcher(maxAttempts = 2, baseDelayMs = 1_000L)
    val id = enqueue()

    // Cycle 1: attempt 1/2 fails retriably, leaving the row cooling.
    d.flush()
    val afterFirst = row(id)
    assertThat(afterFirst.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(afterFirst.attempts).isEqualTo(1)

    // Cycle 2 after the cooldown: attempt 2/2 fails, exhausting the budget. attempts
    // must be 2 (preserved + incremented), proving no per-cycle reset.
    clock.advance(afterFirst.nextRetryAt - clock.now())
    val result = d.flush()
    assertThat(result.failureCount.toInt()).isEqualTo(1)
    assertThat(result.failedIds.toList()).containsExactly(id)
    val failed = deadLetter(id)
    assertThat(failed.attempts).isEqualTo(2)
    assertThat(result.errors[id] ?: "").contains(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `maxAttempts N means exactly N requests`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 5L, code = 503)
    val id = enqueue()
    val d = newDispatcher(maxAttempts = 3, baseDelayMs = 10L)

    repeat(3) {
      d.flush()
      clock.advance(1_000L)
    }

    assertThat(server.requestCount).isEqualTo(3)
    assertThat(deadLetter(id).attempts).isEqualTo(3)
    assertThat(deadLetter(id).lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
  }

  @Test
  fun `a budget lowered below the attempts already spent fails the row without a request`() = runTest {
    db.syncItemDao().insert(TestData.entity(id = "spent", attempts = 7, url = server.url("/x").toString()))
    val events = recordEvents()

    val result = newDispatcher(maxAttempts = 5).flush()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(deadLetter("spent").attempts).isEqualTo(7)
    assertThat(result.failedIds.toList()).containsExactly("spent")
    val failure = events.single { it.type == SyncEventType.ITEM_FAILED }
    assertThat(failure.errorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED)
  }

  @Test
  fun `flush does not retry on 401 UNAUTHORIZED`() = runTest {
    server.enqueue(MockResponse().setResponseCode(401))
    val d = newDispatcher(maxAttempts = 5)
    val id = enqueue("/secure")

    val result = d.flush()

    assertThat(result.failureCount.toInt()).isEqualTo(1)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(result.errors[id]).contains(SyncErrorCode.UNAUTHORIZED.name)
  }

  @Test
  fun `flush does not retry on a non-retryable 4xx`() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    val d = newDispatcher(maxAttempts = 5)
    val id = enqueue("/missing")

    val result = d.flush()

    assertThat(result.failureCount.toInt()).isEqualTo(1)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(result.errors[id]).contains(SyncErrorCode.SERVER_ERROR.name)
  }

  @Test
  fun `a non-retryable failure on the last attempt keeps its real code`() = runTest {
    server.enqueue(MockResponse().setResponseCode(401))
    val id = enqueue()

    newDispatcher(maxAttempts = 1).flush()

    assertThat(deadLetter(id).lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
  }

  /* --------------------------- cycle semantics ---------------------------- */

  @Test
  fun `an empty cycle emits a terminal event and records a 0-0 result`() = runTest {
    val events = recordEvents()

    val result = newDispatcher().flush()

    assertThat(result.successCount.toInt()).isEqualTo(0)
    assertThat(result.failureCount.toInt()).isEqualTo(0)
    assertThat(result.succeededIds).isEmpty()
    assertThat(result.failedIds).isEmpty()
    assertThat(events.types())
      .containsExactly(SyncEventType.SYNC_STARTED, SyncEventType.SYNC_SUCCEEDED)
      .inOrder()
    assertThat(storage.getHistory()).hasSize(1)
  }

  @Test
  fun `a flush during a cooldown closes its cycle while the queue is not empty`() = runTest {
    db.syncItemDao().insert(TestData.entity(id = "cooling", nextRetryAt = clock.now() + 60_000L))
    val events = recordEvents()

    newDispatcher().flush()

    assertThat(events.types())
      .containsExactly(SyncEventType.SYNC_STARTED, SyncEventType.SYNC_SUCCEEDED)
      .inOrder()
    assertThat(storage.count()).isEqualTo(1)
    assertThat(storage.getHistory()).hasSize(1)
  }

  @Test
  fun `an all-cooling cycle reports success with a 0-0 result and ITEM_RETRYING for the item`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    val events = recordEvents()

    val result = newDispatcher(baseDelayMs = 5_000L).flush()

    assertThat(events.types())
      .containsExactly(
        SyncEventType.SYNC_STARTED,
        SyncEventType.ITEM_RETRYING,
        SyncEventType.SYNC_SUCCEEDED,
      )
      .inOrder()
    val retrying = events.single { it.type == SyncEventType.ITEM_RETRYING }
    assertThat(retrying.itemId).isEqualTo(id)
    assertThat(retrying.attempt).isEqualTo(1.0)
    assertThat(retrying.statusCode).isEqualTo(503.0)
    assertThat(result.failedIds).isEmpty()
    assertThat(result.errors).isEmpty()
    assertThat(storage.getLastResult()!!.failureCount.toInt()).isEqualTo(0)
    assertThat(storage.getLastResult()!!.successCount.toInt()).isEqualTo(0)
  }

  @Test
  fun `a cycle that cannot start emits SYNC_FAILED and still re-arms the wake`() = runTest {
    val broken = mockk<SyncQueueStorage>(relaxed = true)
    coEvery { broken.claimPending(any(), any()) } throws
      SyncException(SyncErrorCode.NETWORK_ERROR, "storage is gone")
    val events = recordEvents()
    val d = newDispatcher(queue = broken)

    assertThrows(SyncException::class.java) { runBlocking { d.flush() } }

    assertThat(events.types())
      .containsExactly(SyncEventType.SYNC_STARTED, SyncEventType.SYNC_FAILED)
      .inOrder()
    assertThat(d.isSyncing()).isFalse()
    assertThat(finishedCycles).hasSize(1)
  }

  @Test
  fun `every cycle reports that it finished`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    enqueue()
    val d = newDispatcher()

    d.flush() // A cycle with one item.
    d.flush() // An empty cycle.

    assertThat(finishedCycles).containsExactly(null, null)
  }

  @Test
  fun `a wake that cannot be armed does not reject a completed flush`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    enqueue()
    val d = newDispatcher(
      onCycleFinished = { _, _ ->
        throw SyncException(SyncErrorCode.BACKGROUND_TASK_REGISTRATION_FAILED, "no WorkManager")
      },
    )

    val result = d.flush()

    assertThat(result.failureCount.toInt()).isEqualTo(0)
    assertThat(storage.getHistory()).hasSize(1)
  }

  /* -------------------------------- pause --------------------------------- */

  @Test
  fun `a paused flush dispatches nothing and starts no cycle`() = runTest {
    val id = enqueue()
    val events = recordEvents()

    val result = newDispatcher(isPaused = { true }).flush()

    assertThat(result).isEqualTo(SyncDispatcher.emptyResult())
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(events).isEmpty()
    assertThat(finishedCycles).isEmpty()
  }

  @Test
  fun `a flush that was waiting for the mutex obeys a pause that arrived meanwhile`() = runTest {
    val release = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        release.await(20, TimeUnit.SECONDS)
        return MockResponse().setResponseCode(200)
      }
    }
    var paused = false
    val d = newDispatcher(isPaused = { paused })
    enqueue()
    val running = async(Dispatchers.IO) { d.flush() }
    assertThat(server.takeRequest(20, TimeUnit.SECONDS)).isNotNull()
    val waiting = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { d.flush() }

    paused = true
    release.countDown()
    running.await()

    assertThat(waiting.await()).isEqualTo(SyncDispatcher.emptyResult())
  }

  /* ------------------------------ cancellation ---------------------------- */

  @Test
  fun `a cancelled cycle records started dispatches, releases the rest, and closes itself`() = runTest {
    val release = CompletableDeferred<Unit>()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        runBlocking { withTimeout(20_000L) { release.await() } }
        return MockResponse().setResponseCode(200)
      }
    }
    val started = enqueue("/started")
    val untouched = enqueue("/untouched")
    val events = recordEvents()
    // PERSISTED dispatches one item first, which pins down which row started.
    val d = newDispatcher(credentials = { CredentialPolicy.PERSISTED })

    val cycle = launch(Dispatchers.IO) { d.flush() }
    assertThat(server.takeRequest(20, TimeUnit.SECONDS)!!.path).isEqualTo("/started")
    val cancelling = launch(Dispatchers.IO) { cycle.cancelAndJoin() }
    release.complete(Unit)
    cancelling.join()

    // The request reached the server, so its outcome must not be lost.
    assertThat(db.syncItemDao().findById(started)).isNull()
    // This one never started: claimable again, with nothing spent.
    assertThat(row(untouched).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(untouched).attempts).isEqualTo(0)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(events.types().last()).isEqualTo(SyncEventType.SYNC_FAILED)
    assertThat(finishedCycles).hasSize(1)
    assertThat(d.isSyncing()).isFalse()
  }

  /**
   * A cycle stopped by cancellation, a worker that `disableBackgroundSync` stopped say, did not
   * fail: its `SYNC_FAILED` has no code, and the cancellation is no item's failure. The request
   * already on the wire still ends with its own answer.
   */
  @Test
  fun `a cycle cancelled mid-dispatch ends with SYNC_FAILED without a code and fails no item`() =
    runTest {
      val release = CompletableDeferred<Unit>()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          runBlocking { withTimeout(20_000L) { release.await() } }
          return MockResponse().setResponseCode(503)
        }
      }
      val sent = enqueue("/sent")
      val unsent = enqueue("/unsent")
      val events = recordEvents()
      // Restored headers send one item first: the other is claimed and still waiting.
      val d = newDispatcher(credentials = { CredentialPolicy.PERSISTED })

      val cycle = launch(Dispatchers.IO) { d.flush() }
      assertThat(server.takeRequest(20, TimeUnit.SECONDS)!!.path).isEqualTo("/sent")
      cycle.cancel()
      release.complete(Unit)
      cycle.join()

      // The request on the wire got its answer, a retriable 503, and waits for its retry.
      assertThat(row(sent).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(row(sent).attempts).isEqualTo(1)
      assertThat(row(sent).lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      // The one never sent is back in the queue, with nothing spent.
      assertThat(row(unsent).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(row(unsent).attempts).isEqualTo(0)
      assertThat(row(unsent).lastErrorCode).isNull()
      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(server.requestCount).isEqualTo(1)
      assertThat(events.single { it.type == SyncEventType.SYNC_FAILED }.errorCode).isNull()
      assertThat(events.types()).doesNotContain(SyncEventType.ITEM_FAILED)
      assertThat(events.mapNotNull { it.errorCode }).doesNotContain(SyncErrorCode.NETWORK_ERROR)
      assertThat(finishedCycles).hasSize(1)
    }

  /** Not every cancellation comes from the cycle's job; none is an item's failure. */
  @Test
  fun `a cancellation raised inside a dispatch is not that item's failure`() = runTest {
    val id = enqueue()
    val stopping = spyk(storage)
    coEvery { stopping.isQueued(any()) } throws CancellationException("stopped")
    val events = recordEvents()

    val thrown = runCatching { runBlocking { newDispatcher(queue = stopping).flush() } }

    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).attempts).isEqualTo(0)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(storage.getHistory().flatMap { it.failedIds.toList() }).isEmpty()
    assertThat(thrown.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    assertThat(events.single { it.type == SyncEventType.SYNC_FAILED }.errorCode).isNull()
    assertThat(events.types()).doesNotContain(SyncEventType.ITEM_FAILED)
  }

  /** An `Error` is no item's failure either: it ends the cycle, and strands no row. */
  @Test
  fun `an Error raised inside a dispatch ends the cycle and strands no row`() = runTest {
    val id = enqueue()
    val broken = spyk(storage)
    coEvery { broken.isQueued(any()) } throws NoClassDefFoundError("a class R8 removed")
    val events = recordEvents()

    val thrown = runCatching { runBlocking { newDispatcher(queue = broken).flush() } }

    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).attempts).isEqualTo(0)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(storage.getHistory().flatMap { it.failedIds.toList() }).isEmpty()
    assertThat(thrown.exceptionOrNull()).isInstanceOf(NoClassDefFoundError::class.java)
    assertThat(events.single { it.type == SyncEventType.SYNC_FAILED }.errorCode)
      .isEqualTo(SyncErrorCode.NETWORK_ERROR)
    assertThat(events.types()).doesNotContain(SyncEventType.ITEM_FAILED)
  }

  /* ------------------------- failures that are not I/O --------------------- */

  /**
   * Anything but an I/O failure during an attempt is permanent, as on iOS: `INVALID_PAYLOAD`,
   * dead-lettered. `NETWORK_ERROR` was reported for it without a retry, and a `NETWORK_ERROR`
   * is always retried. A configuration read that throws on the per-response read stands for
   * any such exception here.
   */
  @Test
  fun `a failure that is not an I_O error is a permanent INVALID_PAYLOAD`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    val events = recordEvents()
    val options = TestData.syncOptions(retryPolicy = TestData.retryPolicy(maxAttempts = 3))
    var reads = 0
    val d = SyncDispatcher(
      storage = storage,
      emitter = emitter,
      // The cycle's own read succeeds; the read for the response throws.
      getOptions = {
        check(reads++ == 0) { "configuration unreadable" }
        options
      },
      clock = clock::now,
    )

    val result = d.flush()

    assertThat(server.requestCount).isEqualTo(1)
    val dead = deadLetter(id)
    assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.INVALID_PAYLOAD.name)
    assertThat(dead.lastErrorMessage).isEqualTo("configuration unreadable")
    assertThat(dead.attempts).isEqualTo(1)
    assertThat(result.failedIds.toList()).containsExactly(id)
    assertThat(events.single { it.type == SyncEventType.ITEM_FAILED }.errorCode)
      .isEqualTo(SyncErrorCode.INVALID_PAYLOAD)
    assertThat(events.types()).doesNotContain(SyncEventType.ITEM_RETRYING)
  }

  /**
   * The shared client is built before any attempt. Failing to build it is no item's fault:
   * while it lasted, it would have dead-lettered every claimed item, batch after batch. The
   * cycle aborts instead, and its rows wait in the queue, unsent and unchanged.
   */
  @Test
  fun `a shared client that cannot be built aborts the cycle and dead-letters nothing`() =
    runTest {
      val id = enqueue()
      val events = recordEvents()
      val d = SyncDispatcher(
        storage = storage,
        emitter = emitter,
        getOptions = { TestData.syncOptions() },
        // Read while the shared client is built.
        verboseLogging = { throw IllegalStateException("no TLS provider") },
        clock = clock::now,
      )

      val thrown = runCatching { runBlocking { d.flush() } }

      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(row(id).attempts).isEqualTo(0)
      assertThat(server.requestCount).isEqualTo(0)
      assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
      assertThat(events.single { it.type == SyncEventType.SYNC_FAILED }.errorCode)
        .isEqualTo(SyncErrorCode.NETWORK_ERROR)
      assertThat(events.types()).doesNotContain(SyncEventType.ITEM_FAILED)
    }

  /** Each call is derived inside its attempt: a call that cannot be built fails that attempt. */
  @Test
  fun `a call that cannot be built fails the attempt as INVALID_PAYLOAD`() = runTest {
    val id = enqueue()
    val events = recordEvents()
    val client = mockk<OkHttpClient>()
    every { client.newBuilder() } throws IllegalStateException("no call can be made")
    val d = SyncDispatcher(
      storage = storage,
      emitter = emitter,
      getOptions = { TestData.syncOptions() },
      clock = clock::now,
      httpClient = lazyOf(client),
    )

    val result = d.flush()

    assertThat(server.requestCount).isEqualTo(0)
    val dead = deadLetter(id)
    assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.INVALID_PAYLOAD.name)
    assertThat(dead.lastErrorMessage).isEqualTo("no call can be made")
    assertThat(dead.attempts).isEqualTo(1)
    assertThat(result.failedIds.toList()).containsExactly(id)
    assertThat(events.single { it.type == SyncEventType.ITEM_FAILED }.attempt).isEqualTo(1.0)
  }

  /* -------------------- failures around the attempt, not in it ------------------ */

  /**
   * A local write that fails around the attempt is no outcome of the item: it ends the cycle, as
   * on iOS. The server's answer is known, though, and the settle applies it as the write would
   * have. After a `2xx` the row is delivered: removed, with its `ITEM_SUCCEEDED`, never a dead
   * letter and never sent again.
   */
  @Test
  fun `a failed write after a 2xx aborts the cycle and the settle removes the delivered row`() =
    runTest {
      server.enqueue(MockResponse().setResponseCode(200))
      val id = enqueue()
      val failing = spyk(storage)
      coEvery { failing.markSucceeded(any()) } throws locked()
      val events = recordEvents()

      val thrown = runCatching { runBlocking { newDispatcher(queue = failing).flush() } }

      assertDeliveredBySettle(id, thrown, events, attempt = 1)
    }

  /** The same on the only attempt `maxAttempts` 1 allows: a delivered item is no dead letter. */
  @Test
  fun `a failed write after a 2xx on the only attempt still delivers the row`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    val id = enqueue()
    val failing = spyk(storage)
    coEvery { failing.markSucceeded(any()) } throws locked()
    val events = recordEvents()

    val thrown = runCatching {
      runBlocking { newDispatcher(queue = failing, maxAttempts = 1).flush() }
    }

    assertDeliveredBySettle(id, thrown, events, attempt = 1)
  }

  /** And on the last attempt of the budget. */
  @Test
  fun `a failed write after a 2xx on the last attempt still delivers the row`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    insertLate()
    val failing = spyk(storage)
    coEvery { failing.markSucceeded(any()) } throws locked()
    val events = recordEvents()

    val thrown = runCatching {
      runBlocking { newDispatcher(queue = failing, maxAttempts = 3).flush() }
    }

    assertDeliveredBySettle("late", thrown, events, attempt = 3)
  }

  /**
   * [id] was answered with a `2xx` and its removal failed: the cycle failed, the settle removed
   * the row and reported it delivered before the cycle's `SYNC_FAILED`, and no later cycle sends
   * it again.
   */
  private suspend fun assertDeliveredBySettle(
    id: String,
    thrown: Result<*>,
    events: List<SyncEvent>,
    attempt: Int,
  ) {
    assertThat(thrown.exceptionOrNull()).isInstanceOf(SQLiteDatabaseLockedException::class.java)
    assertThat(db.syncItemDao().findById(id)).isNull()
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(events.types())
      .containsExactly(
        SyncEventType.SYNC_STARTED,
        SyncEventType.ITEM_SUCCEEDED,
        SyncEventType.SYNC_FAILED,
      )
      .inOrder()
    val delivered = events[1]
    assertThat(delivered.itemId).isEqualTo(id)
    assertThat(delivered.statusCode).isEqualTo(200.0)
    assertThat(delivered.attempt).isEqualTo(attempt.toDouble())
    assertThat(events.last().errorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR)
    assertThat(storage.getHistory()).isEmpty()
    // Nothing is left to send, even once any cooldown has passed.
    clock.advance(3_600_000L)
    newDispatcher().flush()
    assertThat(server.requestCount).isEqualTo(1)
  }

  /** An `Error` from the write after a `2xx`, a class R8 removed say, delivers the row too. */
  @Test
  fun `an Error from the write after a 2xx still delivers the row`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    val id = enqueue()
    val failing = spyk(storage)
    coEvery { failing.markSucceeded(any()) } throws NoClassDefFoundError("a class R8 removed")
    val events = recordEvents()

    val thrown = runCatching { runBlocking { newDispatcher(queue = failing).flush() } }

    assertThat(thrown.exceptionOrNull()).isInstanceOf(NoClassDefFoundError::class.java)
    assertThat(db.syncItemDao().findById(id)).isNull()
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(events.single { it.type == SyncEventType.ITEM_SUCCEEDED }.itemId).isEqualTo(id)
    clock.advance(3_600_000L)
    newDispatcher().flush()
    assertThat(server.requestCount).isEqualTo(1)
  }

  /** "late": attempted twice, due now, so the next attempt is the last of 3. */
  private suspend fun insertLate() {
    db.syncItemDao().insert(
      TestData.entity(
        id = "late",
        url = server.url("/x").toString(),
        attempts = 2,
        nextRetryAt = 1L,
      ),
    )
  }

  /** After a retryable answer the settle writes that answer's retry: its code, its cooldown. */
  @Test
  fun `a failed write after a retryable 503 aborts the cycle and the retry waits its cooldown`() =
    runTest {
      server.dispatcher = respondAfter(attemptMs = 0L, code = 503)
      val id = enqueue()
      val failing = spyk(storage)
      coEvery { failing.markRetry(any(), any(), any(), any(), any()) } throws locked()
      val events = recordEvents()

      val thrown = runCatching { runBlocking { newDispatcher(queue = failing).flush() } }

      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(row(id).attempts).isEqualTo(1)
      assertThat(row(id).lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      assertThat(row(id).nextRetryAt).isEqualTo(clock.now() + 1_000L)
      assertThat(thrown.exceptionOrNull()).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      assertThat(events.types())
        .containsExactly(
          SyncEventType.SYNC_STARTED,
          SyncEventType.ITEM_RETRYING,
          SyncEventType.SYNC_FAILED,
        )
        .inOrder()
      val retrying = events[1]
      assertThat(retrying.errorCode).isEqualTo(SyncErrorCode.SERVER_ERROR)
      assertThat(retrying.statusCode).isEqualTo(503.0)
      assertThat(retrying.attempt).isEqualTo(1.0)
      assertThat(events.last().errorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR)
      // With the storage healthy, the retry still waits for its cooldown.
      newDispatcher().flush()
      assertThat(server.requestCount).isEqualTo(1)
      clock.advance(1_000L)
      newDispatcher().flush()
      assertThat(server.requestCount).isEqualTo(2)
      assertThat(row(id).attempts).isEqualTo(2)
    }

  /** A permanent answer whose dead letter cannot be written: the settle writes it, real code. */
  @Test
  fun `a failed dead-letter write after a permanent 4xx leaves the dead letter to the settle`() =
    runTest {
      server.enqueue(MockResponse().setResponseCode(400))
      val id = enqueue()
      val failing = spyk(storage)
      coEvery {
        failing.moveToDeadLetters(any(), any(), any(), any(), any(), any())
      } throws locked()
      val events = recordEvents()

      val thrown = runCatching { runBlocking { newDispatcher(queue = failing).flush() } }

      assertThat(thrown.exceptionOrNull()).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      val dead = deadLetter(id)
      assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      assertThat(dead.lastErrorMessage).isEqualTo("HTTP 400 Client Error")
      assertThat(dead.attempts).isEqualTo(1)
      assertThat(events.types())
        .containsExactly(
          SyncEventType.SYNC_STARTED,
          SyncEventType.ITEM_FAILED,
          SyncEventType.ITEM_DEAD_LETTERED,
          SyncEventType.SYNC_FAILED,
        )
        .inOrder()
      val failed = events[1]
      assertThat(failed.errorCode).isEqualTo(SyncErrorCode.SERVER_ERROR)
      assertThat(failed.statusCode).isEqualTo(400.0)
      assertThat(failed.attempt).isEqualTo(1.0)
      assertThat(events[2].metadata)
        .containsExactly("lastErrorCode", SyncErrorCode.SERVER_ERROR.name)
      assertThat(events.last().errorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR)
      assertThat(server.requestCount).isEqualTo(1)
    }

  /** A retryable answer on the last attempt: the settle's dead letter says the budget ran out. */
  @Test
  fun `a failed dead-letter write after a 503 on the last attempt leaves it exhausted`() =
    runTest {
      server.enqueue(MockResponse().setResponseCode(503))
      insertLate()
      val failing = spyk(storage)
      coEvery {
        failing.moveToDeadLetters(any(), any(), any(), any(), any(), any())
      } throws locked()
      val events = recordEvents()

      runCatching { runBlocking { newDispatcher(queue = failing, maxAttempts = 3).flush() } }

      val dead = deadLetter("late")
      assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
      assertThat(dead.lastErrorMessage).isEqualTo("HTTP 503 Server Error")
      assertThat(dead.attempts).isEqualTo(3)
      val failed = events.single { it.type == SyncEventType.ITEM_FAILED }
      assertThat(failed.errorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED)
      assertThat(failed.statusCode).isEqualTo(503.0)
      assertThat(failed.attempt).isEqualTo(3.0)
    }

  /**
   * A restored credential was rejected and its hold could not be written: the settle writes the
   * hold the normal path would have, spending no attempt, and the next wake waits for it. The
   * rest of the batch was never sent and comes back as it was.
   */
  @Test
  fun `a failed hold write after a rejected restored credential leaves the hold to the settle`() =
    runTest {
      server.dispatcher = respondAfter(attemptMs = 0L, code = 401)
      val id = enqueue("/1")
      val rest = enqueue("/2")
      val failing = spyk(storage)
      coEvery { failing.markRetry(any(), any(), any(), any(), any()) } throws locked()
      val wake = recordingWake()
      val events = recordEvents()
      val d = newDispatcher(
        queue = failing,
        credentials = { CredentialPolicy.PERSISTED },
        onCycleFinished = { notBefore, ending ->
          finishedCycles += notBefore
          wake.arm(notBefore, ending)
        },
      )

      val thrown = runCatching { runBlocking { d.flush() } }

      assertThat(thrown.exceptionOrNull()).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      assertThat(server.requestCount).isEqualTo(1)
      val held = row(id)
      assertThat(held.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(held.attempts).isEqualTo(0)
      assertThat(held.lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
      assertThat(held.nextRetryAt).isEqualTo(clock.now() + SyncDispatcher.MIN_CREDENTIAL_HOLD_MS)
      assertThat(row(rest).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(row(rest).attempts).isEqualTo(0)
      assertThat(row(rest).nextRetryAt).isEqualTo(0L)
      assertThat(events.types())
        .containsExactly(
          SyncEventType.SYNC_STARTED,
          SyncEventType.ITEM_RETRYING,
          SyncEventType.SYNC_FAILED,
        )
        .inOrder()
      val retrying = events[1]
      assertThat(retrying.itemId).isEqualTo(id)
      assertThat(retrying.errorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED)
      assertThat(retrying.statusCode).isEqualTo(401.0)
      assertThat(retrying.attempt).isEqualTo(1.0)
      assertThat(finishedCycles).containsExactly(held.nextRetryAt)
      assertThat(timerDelays).containsExactly(SyncDispatcher.MIN_CREDENTIAL_HOLD_MS)
    }

  /**
   * A dispatcher whose every call reaches the server and then raises [error] before any answer
   * is classified, as a broken interceptor could.
   */
  private fun raisingAfterTheSend(maxAttempts: Int = 3, error: () -> Throwable): SyncDispatcher {
    val client = OkHttpClient.Builder()
      .retryOnConnectionFailure(false)
      .addInterceptor { chain ->
        chain.proceed(chain.request()).close()
        throw error()
      }
      .build()
    return SyncDispatcher(
      storage = storage,
      emitter = emitter,
      getOptions = {
        TestData.syncOptions(
          retryPolicy = TestData.retryPolicy(
            maxAttempts = maxAttempts,
            baseDelayMs = 1_000L,
            maxDelayMs = 60_000L,
            jitter = false,
          ),
        )
      },
      clock = clock::now,
      httpClient = lazyOf(client),
    )
  }

  /**
   * An `Error` raised during the send, after the request went out and before any answer was
   * classified: the settle counts the attempt, with the policy's cooldown and `NETWORK_ERROR`.
   */
  @Test
  fun `an Error during the send counts the attempt`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 0L, code = 200)
    val id = enqueue()
    val events = recordEvents()

    val thrown = runCatching {
      runBlocking { raisingAfterTheSend { NoClassDefFoundError("a class R8 removed") }.flush() }
    }

    assertThat(thrown.exceptionOrNull()).isInstanceOf(NoClassDefFoundError::class.java)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).attempts).isEqualTo(1)
    assertThat(row(id).lastErrorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR.name)
    assertThat(row(id).nextRetryAt).isEqualTo(clock.now() + 1_000L)
    assertThat(events.types())
      .containsExactly(
        SyncEventType.SYNC_STARTED,
        SyncEventType.ITEM_RETRYING,
        SyncEventType.SYNC_FAILED,
      )
      .inOrder()
    assertThat(events[1].errorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR)
    assertThat(events[1].statusCode).isNull()
    assertThat(events[1].attempt).isEqualTo(1.0)
    assertThat(events.last().errorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR)
  }

  /**
   * A send that raises before any answer every time: each aborted cycle counts the attempt and
   * pushes the deadline out, so no wake comes back at once, and the budget ends in a dead letter,
   * as for any retryable failure.
   */
  @Test
  fun `a send that keeps raising spends the budget instead of resending in a loop`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 0L, code = 200)
    val id = enqueue()
    val events = recordEvents()
    val d = raisingAfterTheSend { NoClassDefFoundError("a class R8 removed") }
    val deadlines = mutableListOf<Long>()

    repeat(2) { cycle ->
      runCatching { runBlocking { d.flush() } }
      assertWithMessage("attempts after cycle ${cycle + 1}")
        .that(row(id).attempts).isEqualTo(cycle + 1)
      val deadline = checkNotNull(storage.nextRetryDeadline())
      assertWithMessage("the wake after cycle ${cycle + 1} waits")
        .that(deadline).isGreaterThan(clock.now())
      deadlines += deadline
      // Not due yet: nothing is sent.
      d.flush()
      assertThat(server.requestCount).isEqualTo(cycle + 1)
      clock.advance(deadline - clock.now())
    }
    runCatching { runBlocking { d.flush() } }

    assertThat(deadlines[1] - deadlines[0]).isAtLeast(2_000L)
    assertThat(server.requestCount).isEqualTo(3)
    val dead = deadLetter(id)
    assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
    assertThat(dead.attempts).isEqualTo(3)
    assertThat(events.filter { it.itemId == id }.types().takeLast(2))
      .containsExactly(SyncEventType.ITEM_FAILED, SyncEventType.ITEM_DEAD_LETTERED)
      .inOrder()
    assertThat(storage.nextRetryDeadline()).isNull()
  }

  /** A claimed row whose request never went out goes back as it was. */
  @Test
  fun `a claimed row that was never sent comes back unchanged when the cycle aborts`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 0L, code = 200)
    val sent = enqueue("/sent")
    val unsent = enqueue("/unsent")
    val failing = spyk(storage)
    coEvery { failing.markSucceeded(sent) } throws locked()
    // Restored headers send one item first: the cycle aborts before the other one goes out.
    val d = newDispatcher(queue = failing, credentials = { CredentialPolicy.PERSISTED })

    runCatching { runBlocking { d.flush() } }

    assertThat(server.requestCount).isEqualTo(1)
    // Answered with a 2xx: the settle delivered it.
    assertThat(db.syncItemDao().findById(sent)).isNull()
    val waiting = row(unsent)
    assertThat(waiting.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(waiting.attempts).isEqualTo(0)
    assertThat(waiting.nextRetryAt).isEqualTo(0L)
    assertThat(waiting.lastErrorCode).isNull()
  }

  /** A cancellation stays no outcome: the row comes back unchanged, even after its request. */
  @Test
  fun `a cancellation after the request went out counts no attempt`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    val id = enqueue()
    val failing = spyk(storage)
    coEvery { failing.markSucceeded(any()) } throws CancellationException("stopped")

    val thrown = runCatching { runBlocking { newDispatcher(queue = failing).flush() } }

    assertThat(thrown.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).attempts).isEqualTo(0)
    assertThat(row(id).nextRetryAt).isEqualTo(0L)
  }

  /**
   * An exception after the outcome was written, here from the event that follows it, ends the
   * cycle as well, but the outcome stands. The settle only applies a write to a row still
   * `IN_FLIGHT`: this one keeps its code, its cooldown and its attempt, and is not reported again.
   */
  @Test
  fun `an exception after the outcome is written leaves that outcome as recorded`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    val failingEvents = retryingEventFails()
    val events = recordEvents(failingEvents)
    val d = SyncDispatcher(
      storage = storage,
      emitter = failingEvents,
      getOptions = {
        TestData.syncOptions(retryPolicy = TestData.retryPolicy(maxAttempts = 3, baseDelayMs = 1_000L))
      },
      clock = clock::now,
    )

    val thrown = runCatching { runBlocking { d.flush() } }

    assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    val cooling = row(id)
    assertThat(cooling.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(cooling.attempts).isEqualTo(1)
    assertThat(cooling.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
    assertThat(cooling.nextRetryAt).isEqualTo(clock.now() + 1_000L)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    // Its own event, once: the settle reports no write it did not apply.
    coVerify(exactly = 1) { failingEvents.emit(match { it.type == SyncEventType.ITEM_RETRYING }) }
    assertThat(events.single { it.type == SyncEventType.SYNC_FAILED }.errorCode)
      .isEqualTo(SyncErrorCode.NETWORK_ERROR)
  }

  /**
   * The same after a credential hold: a rejected restored credential, its hold written, then its
   * event fails. The hold stands, with no attempt spent, and the next wake still waits for it.
   */
  @Test
  fun `an exception after a credential hold is written leaves the hold as recorded`() = runTest {
    server.enqueue(MockResponse().setResponseCode(401))
    val id = enqueue()
    val failingEvents = retryingEventFails()
    val d = SyncDispatcher(
      storage = storage,
      emitter = failingEvents,
      getOptions = {
        TestData.syncOptions(retryPolicy = TestData.retryPolicy(maxAttempts = 3, baseDelayMs = 1_000L))
      },
      clock = clock::now,
      credentialPolicy = { CredentialPolicy.PERSISTED },
      onCycleFinished = { notBefore, _ -> finishedCycles += notBefore },
    )

    val thrown = runCatching { runBlocking { d.flush() } }

    assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    val held = row(id)
    assertThat(held.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(held.attempts).isEqualTo(0)
    assertThat(held.lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
    assertThat(held.nextRetryAt).isEqualTo(clock.now() + SyncDispatcher.MIN_CREDENTIAL_HOLD_MS)
    coVerify(exactly = 1) { failingEvents.emit(match { it.type == SyncEventType.ITEM_RETRYING }) }
    assertThat(finishedCycles).containsExactly(held.nextRetryAt)
  }

  /** An emitter whose `ITEM_RETRYING` always fails, as a bridge that went down would. */
  private fun retryingEventFails(): SyncEventEmitter {
    val failing = spyk(SyncEventEmitter())
    coEvery { failing.emit(match { it.type == SyncEventType.ITEM_RETRYING }) } throws
      IllegalStateException("bridge down")
    return failing
  }

  /**
   * A storage read that fails before the attempt left the row `IN_FLIGHT`, or, with Q4c, made
   * it a dead letter. The cycle aborts instead, the row waits in the queue, and the next cycle,
   * with the storage healthy again, sends it.
   */
  @Test
  fun `a failed storage read before the attempt strands no row, and the next cycle sends it`() =
    runTest {
      server.enqueue(MockResponse().setResponseCode(200))
      val id = enqueue()
      val failing = spyk(storage)
      coEvery { failing.isQueued(any()) } throws locked()
      val events = recordEvents()

      val thrown = runCatching { runBlocking { newDispatcher(queue = failing).flush() } }

      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(inFlightRows()).isEmpty()
      assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(server.requestCount).isEqualTo(0)
      assertThat(thrown.exceptionOrNull()).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      assertThat(events.single { it.type == SyncEventType.SYNC_FAILED }.errorCode)
        .isEqualTo(SyncErrorCode.NETWORK_ERROR)
      assertThat(newDispatcher().flush().succeededIds.toList()).containsExactly(id)
    }

  /** The one exception: when even the release fails, the row waits for recovery. */
  @Test
  fun `when the release fails too, the row waits in flight for recovery`() = runTest {
    val id = enqueue()
    val failing = spyk(storage)
    coEvery { failing.isQueued(any()) } throws locked()
    coEvery { failing.settleAbortedClaims(any()) } throws locked()

    val thrown = runCatching { runBlocking { newDispatcher(queue = failing).flush() } }

    assertThat(thrown.exceptionOrNull()).isInstanceOf(SQLiteDatabaseLockedException::class.java)
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    // Recovery, as an engine attach, a boot or a worker with no JS engine runs it.
    assertThat(newDispatcher().resetInFlight()).isEqualTo(1)
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
  }

  /** The other items of the batch either finish with their own outcome or wait, unsent. */
  @Test
  fun `a failure around one attempt ends the cycle without stranding the other items`() =
    runTest {
      server.dispatcher = respondAfter(attemptMs = 0L, code = 200)
      val failingId = enqueue("/failing")
      val others = List(3) { enqueue("/other$it") }
      val failing = spyk(storage)
      coEvery { failing.markSucceeded(failingId) } throws locked()
      val events = recordEvents()

      val thrown = runCatching { runBlocking { newDispatcher(queue = failing).flush() } }

      assertThat(thrown.exceptionOrNull()).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(inFlightRows()).isEmpty()
      // Answered with a 2xx: the settle delivered it.
      assertThat(db.syncItemDao().findById(failingId)).isNull()
      // Each one either delivered, with its own ITEM_SUCCEEDED, or never started and waiting as
      // it was, with no event.
      for (other in others) {
        val ownEvents = events.filter { it.itemId == other }.types()
        val waiting = db.syncItemDao().findById(other)
        if (waiting == null) {
          assertWithMessage("$other, delivered").that(ownEvents)
            .containsExactly(SyncEventType.ITEM_SUCCEEDED)
        } else {
          assertWithMessage("$other, never started").that(ownEvents).isEmpty()
          assertThat(waiting.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
          assertThat(waiting.attempts).isEqualTo(0)
          assertThat(waiting.nextRetryAt).isEqualTo(0L)
        }
      }
      assertThat(events.filter { it.itemId == failingId }.types())
        .containsExactly(SyncEventType.ITEM_SUCCEEDED)
      assertThat(events.last().type).isEqualTo(SyncEventType.SYNC_FAILED)
    }

  /**
   * The order of the events of a cycle that aborts: each item event of its own as it happens,
   * then the settle's, once its writes are in, and the cycle's `SYNC_FAILED` last.
   */
  @Test
  fun `after a settle the item events come first and SYNC_FAILED last`() = runTest {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse =
        MockResponse().setResponseCode(if (request.path == "/first") 200 else 503)
    }
    val first = enqueue("/first")
    val second = enqueue("/second")
    val failing = spyk(storage)
    coEvery { failing.markRetry(second, any(), any(), any(), any()) } throws locked()
    val events = recordEvents()
    // Restored headers: the first item goes alone, and records its outcome before the second
    // one starts.
    val d = newDispatcher(queue = failing, credentials = { CredentialPolicy.PERSISTED })

    runCatching { runBlocking { d.flush() } }

    assertThat(events.map { it.type to it.itemId })
      .containsExactly(
        SyncEventType.SYNC_STARTED to null,
        SyncEventType.ITEM_SUCCEEDED to first,
        SyncEventType.ITEM_RETRYING to second,
        SyncEventType.SYNC_FAILED to null,
      )
      .inOrder()
  }

  /**
   * An aborted cycle releases only the rows it claimed. A row `IN_FLIGHT` that it never claimed,
   * such as another process's claim, is left to its owner, or to recovery.
   */
  @Test
  fun `an aborted cycle releases only the rows it claimed`() = runTest {
    db.syncItemDao().insert(TestData.entity(id = "elsewhere", status = SyncItemEntity.STATUS_IN_FLIGHT))
    val release = CompletableDeferred<Unit>()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        runBlocking { withTimeout(20_000L) { release.await() } }
        return MockResponse().setResponseCode(200)
      }
    }
    enqueue("/sent")
    val unsent = enqueue("/unsent")
    val d = newDispatcher(credentials = { CredentialPolicy.PERSISTED })

    val cycle = launch(Dispatchers.IO) { d.flush() }
    assertThat(server.takeRequest(20, TimeUnit.SECONDS)!!.path).isEqualTo("/sent")
    cycle.cancel()
    release.complete(Unit)
    cycle.join()

    assertThat(row(unsent).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row("elsewhere").status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)
  }

  /** The real DAO, whose claim commits, says so, and then waits for [release] to return. */
  private class HeldClaimDao(
    private val real: SyncItemDao,
    private val committed: CompletableDeferred<Unit>,
    private val release: CountDownLatch,
  ) : SyncItemDao by real {
    override suspend fun claimPending(now: Long, limit: Int): List<SyncItemEntity> =
      real.claimPending(now, limit).also {
        committed.complete(Unit)
        release.await(20, TimeUnit.SECONDS)
      }
  }

  /**
   * A cancellation that lands while the claim returns, its transaction already committed. The
   * claim's result was discarded, so its rows stayed `IN_FLIGHT` and only recovery released them.
   * The claim now finishes, and the settle gives its rows back as they were.
   */
  @Test
  fun `a cancellation during the claim gives the claimed rows back`() = runTest {
    val id = enqueue()
    val committed = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val held = SyncQueueStorage(
      itemDao = HeldClaimDao(db.syncItemDao(), committed, release),
      resultDao = db.syncResultDao(),
      deadLetterDao = db.syncDeadLetterDao(),
    )
    val endings: MutableList<CycleEnding> = Collections.synchronizedList(mutableListOf())
    val events = recordEvents()
    val d = newDispatcher(queue = held, onCycleFinished = { _, ending -> endings += ending })

    val cycle = launch(Dispatchers.IO) { d.flush() }
    runBlocking { withTimeout(20_000L) { committed.await() } }
    cycle.cancel()
    release.countDown()
    cycle.join()

    val released = row(id)
    assertThat(released.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(released.attempts).isEqualTo(0)
    assertThat(released.nextRetryAt).isEqualTo(0L)
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(endings).containsExactly(CycleEnding.CANCELLED)
    assertThat(events.types())
      .containsExactly(SyncEventType.SYNC_STARTED, SyncEventType.SYNC_FAILED)
      .inOrder()
    assertThat(events.last().errorCode).isNull()
    // Claimable again: the next cycle sends it.
    server.enqueue(MockResponse().setResponseCode(200))
    assertThat(newDispatcher().flush().succeededIds.toList()).containsExactly(id)
  }

  @Test
  fun `resetInFlight waits for a running cycle instead of requeueing its rows`() = runTest {
    val release = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        release.await(20, TimeUnit.SECONDS)
        return MockResponse().setResponseCode(200)
      }
    }
    val d = newDispatcher()
    val id = enqueue()
    val running = async(Dispatchers.IO) { d.flush() }
    assertThat(server.takeRequest(20, TimeUnit.SECONDS)).isNotNull()

    val reset = async(Dispatchers.IO) { d.resetInFlight() }
    Thread.sleep(150L)

    // Still on the wire: resetting it now would let a second cycle send it again.
    assertThat(reset.isCompleted).isFalse()
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)

    release.countDown()
    assertThat(running.await().succeededIds.toList()).containsExactly(id)
    assertThat(reset.await()).isEqualTo(0)
  }

  @Test
  fun `resetInFlight recovers rows left behind by a previous process`() = runTest {
    db.syncItemDao().insert(TestData.entity(id = "orphan", status = SyncItemEntity.STATUS_IN_FLIGHT))

    assertThat(newDispatcher().resetInFlight()).isEqualTo(1)

    assertThat(row("orphan").status).isEqualTo(SyncItemEntity.STATUS_PENDING)
  }

  /* ------------------------ the wake after a failed cycle ------------------------ */

  /** The delays [recordingWake] gave the timer and the kick, in order. */
  private val timerDelays: MutableList<Long> = Collections.synchronizedList(mutableListOf())
  private val kickDelays: MutableList<Long> = Collections.synchronizedList(mutableListOf())

  /** The real wake on the test clock, reading the real storage, with both mechanisms recorded. */
  private fun recordingWake(): RetryWake {
    val timer = mockk<RetryTimer>(relaxed = true)
    every { timer.arm(any()) } answers { timerDelays += firstArg<Long>() }
    return RetryWake(
      storage = storage,
      timer = timer,
      scheduleKick = { kickDelays += it },
      isHalted = { false },
      isEngineLive = { true },
      clock = clock::now,
    )
  }

  /** The end of every cycle arms [wake], as the runtime wires it. */
  private fun armsAfterEachCycle(wake: RetryWake): suspend (Long?, CycleEnding) -> Unit =
    { notBefore, ending -> wake.arm(notBefore, ending) }

  /** Attempted once, with a cooldown that has already passed: due now. */
  private suspend fun insertDue(id: String = "due") {
    db.syncItemDao().insert(
      TestData.entity(
        id = id,
        url = server.url("/x").toString(),
        attempts = 1,
        nextRetryAt = clock.now() - 1_000L,
      ),
    )
  }

  /**
   * A failure between the claim and the send gives the row back as it was, its cooldown already
   * past. The wake that follows used to fire at once, so a failure that persisted ran cycles back
   * to back: each one waits for the next gate re-check step now.
   */
  @Test
  fun `a storage failure before the send backs the next wake off instead of looping`() = runTest {
    insertDue()
    val failing = spyk(storage)
    coEvery { failing.isQueued(any()) } throws locked()
    val d = newDispatcher(queue = failing, onCycleFinished = armsAfterEachCycle(recordingWake()))

    repeat(3) {
      assertThat(runCatching { runBlocking { d.flush() } }.exceptionOrNull())
        .isInstanceOf(SQLiteDatabaseLockedException::class.java)
    }

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("due").status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row("due").attempts).isEqualTo(1)
    assertThat(row("due").nextRetryAt).isEqualTo(clock.now() - 1_000L)
    assertThat(timerDelays).containsExactly(30_000L, 60_000L, 120_000L).inOrder()
    assertThat(kickDelays).containsExactly(30_000L, 60_000L, 120_000L).inOrder()
  }

  /** The same when the claim itself fails: nothing was claimed, and the due row stays due. */
  @Test
  fun `a failed claim backs the next wake off instead of looping`() = runTest {
    insertDue()
    val failing = spyk(storage)
    coEvery { failing.claimPending(any(), any()) } throws locked()
    val d = newDispatcher(queue = failing, onCycleFinished = armsAfterEachCycle(recordingWake()))

    repeat(3) {
      assertThat(runCatching { runBlocking { d.flush() } }.exceptionOrNull())
        .isInstanceOf(SQLiteDatabaseLockedException::class.java)
    }

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(timerDelays).containsExactly(30_000L, 60_000L, 120_000L).inOrder()
    assertThat(kickDelays).containsExactly(30_000L, 60_000L, 120_000L).inOrder()
  }

  /**
   * A cancellation of the cycle's own job says nothing about the storage: the wake after it is
   * armed as before. Here the job is cancelled while the claim returns.
   */
  @Test
  fun `a cancelled cycle does not start the failure backoff`() = runTest {
    insertDue()
    val wake = recordingWake()
    val committed = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val held = SyncQueueStorage(
      itemDao = HeldClaimDao(db.syncItemDao(), committed, release),
      resultDao = db.syncResultDao(),
      deadLetterDao = db.syncDeadLetterDao(),
    )
    val failing = spyk(storage)
    coEvery { failing.isQueued(any()) } throws locked()
    val toCancel = newDispatcher(queue = held, onCycleFinished = armsAfterEachCycle(wake))
    val toFail = newDispatcher(queue = failing, onCycleFinished = armsAfterEachCycle(wake))

    val cancelled = launch(Dispatchers.IO) { toCancel.flush() }
    runBlocking { withTimeout(20_000L) { committed.await() } }
    cancelled.cancel()
    release.countDown()
    cancelled.join()
    runCatching { runBlocking { toFail.flush() } }

    assertThat(cancelled.isCancelled).isTrue()
    assertThat(server.requestCount).isEqualTo(0)
    // At once after the cancellation; the first step only after the failure that follows it.
    assertThat(timerDelays).containsExactly(0L, 30_000L).inOrder()
    assertThat(kickDelays).containsExactly(0L, 30_000L).inOrder()
  }

  /**
   * A `CancellationException` raised while the cycle's own job is still active is no
   * cancellation of the cycle: the wake after it is held back as after any other failure, so a
   * source that kept raising one cannot run cycles back to back. Its rows still come back as
   * after a cancellation, and its `SYNC_FAILED` still carries no code.
   */
  @Test
  fun `a cancellation that is not the cycle's own backs the next wake off`() = runTest {
    insertDue()
    val stopping = spyk(storage)
    coEvery { stopping.isQueued(any()) } throws CancellationException("not this cycle's")
    val events = recordEvents()
    val d = newDispatcher(queue = stopping, onCycleFinished = armsAfterEachCycle(recordingWake()))

    repeat(3) {
      assertThat(runCatching { runBlocking { d.flush() } }.exceptionOrNull())
        .isInstanceOf(CancellationException::class.java)
    }

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("due").status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row("due").attempts).isEqualTo(1)
    assertThat(row("due").nextRetryAt).isEqualTo(clock.now() - 1_000L)
    assertThat(events.filter { it.type == SyncEventType.SYNC_FAILED }.map { it.errorCode })
      .containsExactly(null, null, null)
    assertThat(timerDelays).containsExactly(30_000L, 60_000L, 120_000L).inOrder()
    assertThat(kickDelays).containsExactly(30_000L, 60_000L, 120_000L).inOrder()
  }

  /**
   * A cycle that completes, here with an item that failed, starts the steps over, and the wake
   * after it is armed as before. The explicit flush right after the failures is not held back.
   */
  @Test
  fun `a completed cycle starts the failure backoff over`() = runTest {
    insertDue()
    val wake = recordingWake()
    val failing = spyk(storage)
    coEvery { failing.claimPending(any(), any()) } throws locked()
    val broken = newDispatcher(queue = failing, onCycleFinished = armsAfterEachCycle(wake))
    repeat(2) { runCatching { runBlocking { broken.flush() } } }

    server.enqueue(MockResponse().setResponseCode(503))
    newDispatcher(onCycleFinished = armsAfterEachCycle(wake)).flush()
    val cooldown = row("due").nextRetryAt - clock.now()
    runCatching { runBlocking { broken.flush() } }

    assertThat(server.requestCount).isEqualTo(1)
    assertThat(row("due").attempts).isEqualTo(2)
    assertThat(cooldown).isGreaterThan(0L)
    assertThat(cooldown).isLessThan(30_000L)
    assertThat(timerDelays).containsExactly(30_000L, 60_000L, cooldown, 30_000L).inOrder()
    assertThat(kickDelays).containsExactly(30_000L, 60_000L, cooldown, 30_000L).inOrder()
  }

  /* --------------------------- restored credentials ----------------------- */

  private fun persistedCredentials(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 1_000L,
    batchSize: Int = 25,
  ): SyncDispatcher = newDispatcher(
    maxAttempts = maxAttempts,
    baseDelayMs = baseDelayMs,
    batchSize = batchSize,
    credentials = { CredentialPolicy.PERSISTED },
  )

  @Test
  fun `a restored credential rejected with 401 holds the item without spending an attempt`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 200L, code = 401)
    val id = enqueue()

    val result = persistedCredentials().flush()

    val held = row(id)
    assertThat(held.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(held.attempts).isEqualTo(0)
    assertThat(held.lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
    assertThat(held.nextRetryAt).isEqualTo(clock.now() + SyncDispatcher.MIN_CREDENTIAL_HOLD_MS)
    assertThat(result.failureCount.toInt()).isEqualTo(0)
    assertThat(result.failedIds).isEmpty()
  }

  @Test
  fun `a restored credential rejected with 403 is held the same way`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 10L, code = 403)
    val id = enqueue()

    persistedCredentials().flush()

    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).attempts).isEqualTo(0)
  }

  @Test
  fun `the hold honors a backoff longer than its floor`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 10L, code = 401)
    val id = enqueue()
    val longBackoff = SyncDispatcher.MIN_CREDENTIAL_HOLD_MS * 2

    newDispatcher(
      baseDelayMs = longBackoff,
      maxDelayMs = longBackoff,
      credentials = { CredentialPolicy.PERSISTED },
    ).flush()

    assertThat(row(id).nextRetryAt).isEqualTo(clock.now() + longBackoff)
  }

  @Test
  fun `a rejected credential stops the cycle and leaves the rest of the batch untouched`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 10L, code = 401)
    val first = enqueue("/1")
    val second = enqueue("/2")
    val third = enqueue("/3")

    persistedCredentials().flush()

    assertThat(server.requestCount).isEqualTo(1)
    assertThat(row(first).lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
    for (id in listOf(second, third)) {
      assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(row(id).attempts).isEqualTo(0)
      assertThat(row(id).lastErrorCode).isNull()
      // Still eligible now, with nothing recorded.
      assertThat(row(id).nextRetryAt).isEqualTo(0L)
    }
  }

  /**
   * The rest of the batch goes back through the settle, so only rows this cycle claimed are
   * released. A row `IN_FLIGHT` that it never claimed, such as another process's claim, is left
   * to its owner, or to recovery, as an aborted cycle leaves it.
   */
  @Test
  fun `a rejected credential releases only the rows its cycle claimed`() = runTest {
    db.syncItemDao().insert(TestData.entity(id = "elsewhere", status = SyncItemEntity.STATUS_IN_FLIGHT))
    server.dispatcher = respondAfter(attemptMs = 10L, code = 401)
    val first = enqueue("/1")
    val second = enqueue("/2")

    persistedCredentials().flush()

    assertThat(server.requestCount).isEqualTo(1)
    assertThat(row(first).lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
    assertThat(row(second).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(second).attempts).isEqualTo(0)
    assertThat(row("elsewhere").status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)
  }

  @Test
  fun `the next wake waits for the hold even when released rows are due at once`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 10L, code = 401)
    db.syncItemDao().insert(
      TestData.entity(id = "a", url = server.url("/a").toString(), createdAt = 1L, attempts = 1, nextRetryAt = 1L),
    )
    db.syncItemDao().insert(
      TestData.entity(id = "b", url = server.url("/b").toString(), createdAt = 2L, attempts = 1, nextRetryAt = 1L),
    )

    persistedCredentials().flush()

    // "b" was released with a cooldown that already elapsed. Without this floor the
    // next wake would fire immediately, and each cycle would spend one more request
    // on a token that is known to be rejected.
    assertThat(finishedCycles).containsExactly(row("a").nextRetryAt)
    assertThat(row("a").nextRetryAt).isGreaterThan(row("b").nextRetryAt)
  }

  @Test
  fun `a healthy restored credential dispatches the whole batch`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 1L, code = 200)
    repeat(3) { enqueue("/$it") }

    val result = persistedCredentials().flush()

    assertThat(result.successCount.toInt()).isEqualTo(3)
    assertThat(storage.count()).isEqualTo(0)
  }

  @Test
  fun `a credential from this process keeps a 401 terminal`() = runTest {
    server.dispatcher = respondAfter(attemptMs = 10L, code = 401)
    val id = enqueue()

    newDispatcher(credentials = { CredentialPolicy.TRUSTED }).flush()

    assertThat(deadLetter(id).attempts).isEqualTo(1)
    assertThat(deadLetter(id).lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
  }

  @Test
  fun `headers that cannot be recovered dispatch nothing and start no cycle`() = runTest {
    val id = enqueue()
    val events = recordEvents()

    val result: SyncResult = newDispatcher(credentials = { CredentialPolicy.UNAVAILABLE }).flush()

    assertThat(result).isEqualTo(SyncDispatcher.emptyResult())
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).attempts).isEqualTo(0)
    assertThat(events).isEmpty()
    assertThat(finishedCycles).isEmpty()
  }

  /* -------------------------- network-quality gate -------------------------- */

  @Test
  fun `gate_unset_neverGates, so a manual flush on an offline link still dispatches`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    enqueue()
    var reads = 0
    val dispatcher = newDispatcher(quality = { reads++; NetworkQuality.OFFLINE })

    val outcome = dispatcher.flushWithOutcome()

    assertThat(outcome).isInstanceOf(FlushOutcome.Ran::class.java)
    assertThat(outcome.result.successCount).isEqualTo(1.0)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(reads).isEqualTo(0)
  }

  @Test
  fun `gate_offlineMinimum_neverGates`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    enqueue()
    var reads = 0
    val dispatcher = newDispatcher(
      minNetworkQuality = NetworkQuality.OFFLINE,
      quality = { reads++; NetworkQuality.OFFLINE },
    )

    val outcome = dispatcher.flushWithOutcome()

    assertThat(outcome.result.successCount).isEqualTo(1.0)
    assertThat(reads).isEqualTo(0)
  }

  @Test
  fun `gate_belowMinimum_skipsCycle`() = runTest {
    val id = enqueue()
    val events = recordEvents()
    val dispatcher = newDispatcher(
      minNetworkQuality = NetworkQuality.GOOD,
      quality = { NetworkQuality.MODERATE },
    )

    val outcome = dispatcher.flushWithOutcome()

    assertThat(outcome).isEqualTo(FlushOutcome.Skipped(FlushSkip.NETWORK_QUALITY_TOO_LOW))
    assertThat(outcome.isHeldByQualityGate).isTrue()
    // The no-cycle result, the shape a paused flush resolves with.
    assertThat(outcome.result).isEqualTo(SyncDispatcher.emptyResult())
    assertThat(events.types()).containsExactly(SyncEventType.SYNC_FAILED)
    assertThat(events.single().errorCode).isEqualTo(SyncErrorCode.NETWORK_QUALITY_TOO_LOW)
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(storage.getLastResult()).isNull()
    val untouched = row(id)
    assertThat(untouched.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(untouched.attempts).isEqualTo(0)
    assertThat(untouched.nextRetryAt).isEqualTo(0L)
    assertThat(untouched.lastErrorCode).isNull()
    assertThat(finishedCycles).isEmpty()
    assertThat(dispatcher.flush()).isEqualTo(SyncDispatcher.emptyResult())
  }

  @Test
  fun `the gate lets a link at or above the minimum through`() = runTest {
    repeat(2) { server.enqueue(MockResponse().setResponseCode(200)) }
    enqueue()
    var quality = NetworkQuality.GOOD
    val dispatcher = newDispatcher(minNetworkQuality = NetworkQuality.GOOD, quality = { quality })

    assertThat(dispatcher.flushWithOutcome()).isInstanceOf(FlushOutcome.Ran::class.java)

    enqueue()
    quality = NetworkQuality.EXCELLENT
    assertThat(dispatcher.flushWithOutcome()).isInstanceOf(FlushOutcome.Ran::class.java)
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `POOR, the most permissive minimum, blocks only OFFLINE`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    enqueue()
    var quality = NetworkQuality.OFFLINE
    val dispatcher = newDispatcher(minNetworkQuality = NetworkQuality.POOR, quality = { quality })

    assertThat(dispatcher.flushWithOutcome().isHeldByQualityGate).isTrue()

    quality = NetworkQuality.POOR
    assertThat(dispatcher.flushWithOutcome()).isInstanceOf(FlushOutcome.Ran::class.java)
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `the gate runs after the paused and the credential checks`() = runTest {
    enqueue()
    val events = recordEvents()
    var reads = 0
    val gated = { reads++; NetworkQuality.POOR }

    val paused = newDispatcher(
      isPaused = { true },
      minNetworkQuality = NetworkQuality.GOOD,
      quality = gated,
    ).flushWithOutcome()
    val noCredentials = newDispatcher(
      credentials = { CredentialPolicy.UNAVAILABLE },
      minNetworkQuality = NetworkQuality.GOOD,
      quality = gated,
    ).flushWithOutcome()

    assertThat(paused).isEqualTo(FlushOutcome.Skipped(FlushSkip.PAUSED))
    assertThat(noCredentials).isEqualTo(FlushOutcome.Skipped(FlushSkip.CREDENTIALS_UNAVAILABLE))
    assertThat(reads).isEqualTo(0)
    assertThat(events).isEmpty()
    assertThat(server.requestCount).isEqualTo(0)
  }

  /* ------------------------------- health check ------------------------------- */

  /** `/health` answers [health]; every other path, an item, answers 200. */
  private fun healthServer(health: () -> MockResponse) {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse =
        if (request.path == "/health") health() else MockResponse().setResponseCode(200)
    }
  }

  private var taken = 0

  /** The requests the server received since the last call. */
  private fun recorded(): List<RecordedRequest> {
    val received = server.requestCount
    return List(received - taken) { checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
      .also { taken = received }
  }

  private val healthUrl: String get() = server.url("/health").toString()

  @Test
  fun `health_unset_neverProbes`() = runTest {
    healthServer { MockResponse().setResponseCode(200) }
    enqueue()

    newDispatcher().flush()

    assertThat(recorded().map { it.method }).containsExactly("POST")
  }

  @Test
  fun `health_2xx_proceeds_andIsCachedForTtl`() = runTest {
    healthServer { MockResponse().setResponseCode(204) }
    val dispatcher = newDispatcher(healthCheckUrl = healthUrl, healthCheckTtlMs = 60_000.0)

    enqueue()
    assertThat(dispatcher.flushWithOutcome()).isInstanceOf(FlushOutcome.Ran::class.java)
    enqueue()
    clock.advance(59_999L)
    dispatcher.flush()
    assertThat(recorded().map { it.method }).containsExactly("HEAD", "POST", "POST").inOrder()

    enqueue()
    clock.advance(1L)
    dispatcher.flush()
    assertThat(recorded().map { it.method }).containsExactly("HEAD", "POST").inOrder()
  }

  @Test
  fun `a healthCheckTtlMs of 0 checks before every cycle`() = runTest {
    healthServer { MockResponse().setResponseCode(200) }
    val dispatcher = newDispatcher(healthCheckUrl = healthUrl, healthCheckTtlMs = 0.0)

    repeat(2) {
      enqueue()
      dispatcher.flush()
    }

    assertThat(recorded().map { it.method }).containsExactly("HEAD", "POST", "HEAD", "POST").inOrder()
  }

  @Test
  fun `health_failure_skipsCycle_andStartsBackoff, for 404, 500 and a timeout`() = runTest {
    val failures = listOf(
      "404" to { MockResponse().setResponseCode(404) },
      "500" to { MockResponse().setResponseCode(500) },
      "timeout" to { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) },
    )
    for ((name, failure) in failures) {
      healthServer(failure)
      val id = enqueue()
      val events = recordEvents()
      healthSkips = 0
      val dispatcher = newDispatcher(
        healthCheckUrl = healthUrl,
        healthCheckBackoffMs = 30_000.0,
        healthCheck = healthCheck(timeoutMs = 300L),
      )

      val failed = dispatcher.flushWithOutcome()
      clock.advance(29_999L)
      val backingOff = dispatcher.flushWithOutcome()

      assertWithMessage(name).that(failed).isEqualTo(FlushOutcome.Skipped(FlushSkip.HEALTH_CHECK_FAILED))
      assertWithMessage(name).that(backingOff)
        .isEqualTo(FlushOutcome.Skipped(FlushSkip.HEALTH_CHECK_BACKOFF))
      assertWithMessage(name).that(failed.result).isEqualTo(SyncDispatcher.emptyResult())
      // One HEAD: none is sent during the backoff.
      assertWithMessage(name).that(recorded().map { it.method }).containsExactly("HEAD")
      assertWithMessage(name).that(events.types())
        .containsExactly(SyncEventType.SYNC_FAILED, SyncEventType.SYNC_FAILED)
      assertWithMessage(name).that(events.map { it.errorCode }.distinct())
        .containsExactly(SyncErrorCode.HEALTH_CHECK_FAILED)
      assertWithMessage(name).that(healthSkips).isEqualTo(2)
      assertWithMessage(name).that(storage.getLastResult()).isNull()
      assertWithMessage(name).that(row(id).attempts).isEqualTo(0)
      assertWithMessage(name).that(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)

      // The backoff over, the next flush checks again.
      clock.advance(1L)
      dispatcher.flushWithOutcome()
      assertWithMessage(name).that(recorded().map { it.method }).containsExactly("HEAD")
      storage.clear()
    }
  }

  @Test
  fun `health_zeroBackoff_explicitFlushesStillProbe, while wakes wait a second`() = runTest {
    healthServer { MockResponse().setResponseCode(503) }
    val id = enqueue()
    val dispatcher = newDispatcher(healthCheckUrl = healthUrl, healthCheckBackoffMs = 0.0)

    repeat(3) { dispatcher.flush() }

    // Each flush() sends its own HEAD: the floor holds back only automatic wakes.
    assertThat(recorded().map { it.method }).containsExactly("HEAD", "HEAD", "HEAD")
    assertThat(healthSkips).isEqualTo(3)
    assertThat(healthCheck.wakeNotBefore()).isEqualTo(clock.now() + HealthCheck.MIN_WAKE_BACKOFF_MS)
    assertThat(row(id).attempts).isEqualTo(0)
  }

  @Test
  fun `health_success_and_configure_clearBackoff, the success half`() = runTest {
    var healthy = false
    healthServer { MockResponse().setResponseCode(if (healthy) 200 else 503) }
    enqueue()
    val dispatcher = newDispatcher(healthCheckUrl = healthUrl, healthCheckBackoffMs = 30_000.0)

    dispatcher.flush()
    assertThat(healthCheck.wakeNotBefore()).isEqualTo(clock.now() + 30_000L)

    healthy = true
    clock.advance(30_000L)
    val outcome = dispatcher.flushWithOutcome()

    assertThat(outcome).isInstanceOf(FlushOutcome.Ran::class.java)
    assertThat(healthCheck.wakeNotBefore()).isEqualTo(0L)
  }

  @Test
  fun `a redirect to a 2xx is healthy`() = runTest {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
        "/health" -> MockResponse().setResponseCode(302).setHeader("Location", "/ok")
        else -> MockResponse().setResponseCode(200)
      }
    }
    enqueue()

    val outcome = newDispatcher(healthCheckUrl = healthUrl).flushWithOutcome()

    assertThat(outcome).isInstanceOf(FlushOutcome.Ran::class.java)
  }

  @Test
  fun `health_qualitySample2xx_refreshesLastHealthy, so no extra HEAD is sent`() = runTest {
    healthServer { MockResponse().setResponseCode(200) }
    enqueue()
    // What the network-quality probe reports after a 2xx sample.
    healthCheck.recordHealthy(healthUrl)

    newDispatcher(healthCheckUrl = healthUrl).flush()

    assertThat(recorded().map { it.method }).containsExactly("POST")
  }

  @Test
  fun `health_noAuthHeaders_and_noCache`() = runTest {
    healthServer { MockResponse().setResponseCode(200) }
    enqueue()

    newDispatcher(
      healthCheckUrl = healthUrl,
      defaultHeaders = mapOf("Authorization" to "Bearer live"),
    ).flush()

    val (head, post) = recorded()
    assertThat(head.method).isEqualTo("HEAD")
    assertThat(head.getHeader("Authorization")).isNull()
    assertThat(head.getHeader("Cache-Control")).isEqualTo("no-cache")
    assertThat(head.getHeader("X-Test")).isNull()
    assertThat(post.getHeader("Authorization")).isEqualTo("Bearer live")
  }

  @Test
  fun `the quality gate runs before the health check`() = runTest {
    healthServer { MockResponse().setResponseCode(200) }
    enqueue()

    val outcome = newDispatcher(
      minNetworkQuality = NetworkQuality.GOOD,
      quality = { NetworkQuality.POOR },
      healthCheckUrl = healthUrl,
    ).flushWithOutcome()

    assertThat(outcome).isEqualTo(FlushOutcome.Skipped(FlushSkip.NETWORK_QUALITY_TOO_LOW))
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(healthSkips).isEqualTo(0)
  }

  /* ------------------------------- dead letters ------------------------------- */

  @Test
  fun `dlq_terminalFailure_movesRow_andEmitsFailedThenDeadLettered`() = runTest {
    server.enqueue(MockResponse().setResponseCode(400))
    val id = storage.enqueue(
      TestData.input(url = server.url("/x").toString(), metadata = mapOf("origin" to "test")),
    )
    val queued = row(id)
    val events = recordEvents()
    val startedAt = clock.now()

    val result = newDispatcher().flush()

    val dead = deadLetter(id)
    assertThat(dead.method).isEqualTo(queued.method)
    assertThat(dead.url).isEqualTo(queued.url)
    assertThat(dead.headers).isEqualTo(queued.headers)
    assertThat(dead.body).isEqualTo(queued.body)
    assertThat(dead.contentType).isEqualTo(queued.contentType)
    assertThat(dead.priority).isEqualTo(queued.priority)
    assertThat(dead.priorityWeight).isEqualTo(queued.priorityWeight)
    assertThat(dead.createdAt).isEqualTo(queued.createdAt)
    assertThat(dead.metadata).isEqualTo(queued.metadata)
    assertThat(dead.attempts).isEqualTo(1)
    assertThat(dead.lastAttemptAt).isEqualTo(startedAt)
    assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
    assertThat(dead.lastErrorMessage).startsWith("HTTP 400")
    assertThat(dead.deadLetteredAt).isAtLeast(dead.lastAttemptAt)
    assertThat(result.failedIds.toList()).containsExactly(id)

    val itemEvents = events.filter { it.itemId == id }
    assertThat(itemEvents.map { it.type })
      .containsExactly(SyncEventType.ITEM_FAILED, SyncEventType.ITEM_DEAD_LETTERED).inOrder()
    assertThat(itemEvents[0].errorCode).isEqualTo(SyncErrorCode.SERVER_ERROR)
    assertThat(itemEvents[1].errorCode).isEqualTo(SyncErrorCode.DEAD_LETTERED)
    assertThat(itemEvents[1].metadata).containsExactly("lastErrorCode", SyncErrorCode.SERVER_ERROR.name)
  }

  /** Holds every response until [release] opens, so a purge can land while requests are out. */
  private fun holdResponses(release: CountDownLatch, code: Int): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
      release.await(20, TimeUnit.SECONDS)
      return MockResponse().setResponseCode(code)
    }
  }

  @Test
  fun `dlq_clearQueue_whileInFlight_leavesNoDeadLetter`() = runTest {
    // A logout: the queue is purged while a request still carries the old token, and the
    // server then rejects that token under the live configuration.
    val release = CountDownLatch(1)
    server.dispatcher = holdResponses(release, code = 401)
    val id = storage.enqueue(
      TestData.input(
        url = server.url("/secure").toString(),
        headers = mapOf("Authorization" to "Bearer old-session"),
        body = "private-body",
      ),
    )
    val events = recordEvents()
    val d = newDispatcher(credentials = { CredentialPolicy.TRUSTED })

    val running = async(Dispatchers.IO) { d.flush() }
    assertThat(server.takeRequest(20, TimeUnit.SECONDS)).isNotNull()
    storage.clear()
    release.countDown()
    val result = running.await()

    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(storage.count()).isEqualTo(0)
    assertThat(events.types()).doesNotContain(SyncEventType.ITEM_DEAD_LETTERED)
    // The request did go out and fail: that outcome is still reported and counted.
    val failure = events.single { it.type == SyncEventType.ITEM_FAILED }
    assertThat(failure.itemId).isEqualTo(id)
    assertThat(failure.errorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED)
    assertThat(result.failedIds.toList()).containsExactly(id)
  }

  @Test
  fun `dlq_removeItem_whileInFlight_leavesNoDeadLetter`() = runTest {
    val release = CountDownLatch(1)
    server.dispatcher = holdResponses(release, code = 404)
    val id = enqueue("/missing")
    val events = recordEvents()
    val d = newDispatcher()

    val running = async(Dispatchers.IO) { d.flush() }
    assertThat(server.takeRequest(20, TimeUnit.SECONDS)).isNotNull()
    assertThat(storage.removeItem(id)).isTrue()
    release.countDown()
    val result = running.await()

    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(storage.count()).isEqualTo(0)
    assertThat(events.types()).doesNotContain(SyncEventType.ITEM_DEAD_LETTERED)
    val failure = events.single { it.type == SyncEventType.ITEM_FAILED }
    assertThat(failure.itemId).isEqualTo(id)
    assertThat(failure.errorCode).isEqualTo(SyncErrorCode.SERVER_ERROR)
    assertThat(result.failedIds.toList()).containsExactly(id)
  }

  @Test
  fun `dlq_removeItem_whileInFlight_sparesOnlyThatRow`() = runTest {
    val release = CountDownLatch(1)
    server.dispatcher = holdResponses(release, code = 422)
    val removed = enqueue("/removed")
    val kept = storage.enqueue(
      TestData.input(
        url = server.url("/kept").toString(),
        headers = mapOf("X-Trace" to "kept"),
        body = "kept-body",
      ),
    )
    val keptRow = row(kept)
    val events = recordEvents()
    val d = newDispatcher()

    val running = async(Dispatchers.IO) { d.flush() }
    // Both requests are out at once: the batch dispatches concurrently.
    repeat(2) { assertThat(server.takeRequest(20, TimeUnit.SECONDS)).isNotNull() }
    assertThat(storage.removeItem(removed)).isTrue()
    release.countDown()
    val result = running.await()

    assertThat(db.syncDeadLetterDao().findById(removed)).isNull()
    val dead = deadLetter(kept)
    assertThat(dead.headers).isEqualTo(keptRow.headers)
    assertThat(dead.body).isEqualTo("kept-body")
    assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
    assertThat(events.filter { it.type == SyncEventType.ITEM_DEAD_LETTERED }.map { it.itemId })
      .containsExactly(kept)
    assertThat(events.filter { it.type == SyncEventType.ITEM_FAILED }.map { it.itemId })
      .containsExactly(removed, kept)
    assertThat(result.failedIds.toList()).containsExactly(removed, kept)
  }

  @Test
  fun `dlq_spentBudget_rowPurgedAfterTheClaim_isNeitherSentNorReported`() = runTest {
    db.syncItemDao().insert(
      TestData.entity(id = "spent", attempts = 7, url = server.url("/x").toString()),
    )
    // Purge the queue right after the cycle claimed the row. The claim runs on the real
    // storage: mockk's callOriginal() cannot return from a suspension.
    val purgingAfterClaim = spyk(storage)
    coEvery { purgingAfterClaim.claimPending(any(), any()) } coAnswers {
      storage.claimPending(firstArg(), secondArg()).also { storage.clear() }
    }
    val events = recordEvents()

    val result = newDispatcher(maxAttempts = 5, queue = purgingAfterClaim).flush()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(events.filter { it.itemId == "spent" }).isEmpty()
    assertThat(result.failedIds.toList()).isEmpty()
    assertThat(result.succeededIds.toList()).isEmpty()
  }

  @Test
  fun `dlq_spentBudget_rowPurgedBeforeItsMove_reportsNothing`() = runTest {
    db.syncItemDao().insert(
      TestData.entity(id = "spent", attempts = 7, url = server.url("/x").toString()),
    )
    // Purge right after the dispatch found the row still queued: the move finds none.
    val purgingAfterCheck = spyk(storage)
    coEvery { purgingAfterCheck.isQueued(any()) } coAnswers {
      storage.isQueued(firstArg()).also { storage.clear() }
    }
    val events = recordEvents()

    val result = newDispatcher(maxAttempts = 5, queue = purgingAfterCheck).flush()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(events.filter { it.itemId == "spent" }).isEmpty()
    assertThat(result.failedIds.toList()).isEmpty()
  }

  @Test
  fun `an item purged before its dispatch started is never sent and reports nothing`() = runTest {
    val release = CountDownLatch(1)
    server.dispatcher = holdResponses(release, code = 200)
    val first = enqueue("/first")
    val second = enqueue("/second")
    val third = enqueue("/third")
    val events = recordEvents()
    // Restored headers: one item goes first, and the rest wait for its answer.
    val d = newDispatcher(credentials = { CredentialPolicy.PERSISTED })

    val running = async(Dispatchers.IO) { d.flush() }
    assertThat(server.takeRequest(20, TimeUnit.SECONDS)!!.path).isEqualTo("/first")
    storage.clear()
    release.countDown()
    val result = running.await()

    // The first request was out, so its outcome stands; the others never left.
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(result.succeededIds.toList()).containsExactly(first)
    assertThat(result.failedIds.toList()).isEmpty()
    assertThat(events.filter { it.itemId == second || it.itemId == third }).isEmpty()
  }

  @Test
  fun `with restored headers, the first item still queued goes alone first`() = runTest {
    server.enqueue(MockResponse().setResponseCode(401))
    val removed = enqueue("/removed")
    enqueue("/probe")
    val held = enqueue("/held")
    // The head of the batch is removed right after the claim, before its dispatch starts.
    val removingHead = spyk(storage)
    coEvery { removingHead.claimPending(any(), any()) } coAnswers {
      storage.claimPending(firstArg(), secondArg()).also { storage.removeItem(removed) }
    }
    val events = recordEvents()

    newDispatcher(credentials = { CredentialPolicy.PERSISTED }, queue = removingHead).flush()

    // The next item probes the restored credential alone, and its rejection holds the rest.
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.path).isEqualTo("/probe")
    assertThat(row(held).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(held).attempts).isEqualTo(0)
    assertThat(events.filter { it.itemId == removed || it.itemId == held }).isEmpty()
  }

  @Test
  fun `every terminal failure moves its row, with the code it failed with`() = runTest {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
        "/spent" -> MockResponse().setResponseCode(503)
        "/unauthorized" -> MockResponse().setResponseCode(401)
        else -> MockResponse().setResponseCode(422)
      }
    }
    val spent = enqueue("/spent")
    val unauthorized = enqueue("/unauthorized")
    val rejected = enqueue("/rejected")

    newDispatcher(maxAttempts = 1, credentials = { CredentialPolicy.TRUSTED }).flush()

    assertThat(deadLetter(spent).lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
    assertThat(deadLetter(unauthorized).lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
    assertThat(deadLetter(rejected).lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
    assertThat(storage.count()).isEqualTo(0)
  }

  @Test
  fun `dlq_lastErrorMessage_capturedBoundedAndRedacted`() = runTest {
    server.enqueue(
      MockResponse()
        .setStatus("HTTP/1.1 400 " + "x".repeat(2_000))
        .setHeader("X-Secret", "header-secret")
        .setBody("body-secret"),
    )
    val id = storage.enqueue(
      TestData.input(
        url = server.url("/x").toString(),
        headers = mapOf("Authorization" to "Bearer request-secret"),
        body = "request-body-secret",
      ),
    )

    newDispatcher().flush()

    val message = checkNotNull(deadLetter(id).lastErrorMessage)
    assertThat(message).startsWith("HTTP 400 x")
    assertThat(message.length).isEqualTo(SyncDeadLetterEntity.MAX_ERROR_MESSAGE_LENGTH)
    listOf("header-secret", "body-secret", "request-secret", "request-body-secret").forEach {
      assertThat(message).doesNotContain(it)
    }
  }

  @Test
  fun `a transport failure records its exception message`() = runTest {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    val id = enqueue()

    newDispatcher(maxAttempts = 1).flush()

    assertThat(deadLetter(id).lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
    assertThat(deadLetter(id).lastErrorMessage).isNotEmpty()
  }

  /* ----------------------------- the retry decider ----------------------------- */

  /** Every event from now on, collected as it is emitted, whatever runs the test. */
  private fun collectEvents(): List<SyncEvent> {
    val events: MutableList<SyncEvent> = Collections.synchronizedList(mutableListOf())
    jsScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
      emitter.events.collect { events += it }
    }
    return events
  }

  /** Answers every request with [code], without moving the clock. */
  private fun answerAll(code: Int) {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(code)
    }
  }

  /** A decider that records each context and answers what [reply] gives, at once; null: never. */
  private fun decider(reply: (RetryDeciderContext) -> CallbackAnswer?): FakeFunction<RetryDeciderContext> =
    js.function { requestId, context -> reply(context)?.let { answer(requestId, it) } }

  /** [decider], installed by the current hybrid. */
  private fun installDecider(reply: (RetryDeciderContext) -> CallbackAnswer?): FakeFunction<RetryDeciderContext> =
    decider(reply).also { assertThat(hybrid.setRetryDecider(it)).isTrue() }

  private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

  private fun List<SyncEvent>.of(id: String): List<SyncEvent> = filter { it.itemId == id }

  @Test
  fun `decider_unset_usesPolicy, as when no decider existed`() = runTest {
    server.dispatcher = respondAfter(250L, 503)
    val id = enqueue()
    val start = clock.now()
    val events = collectEvents()

    val result = newDispatcher().flush()

    val retried = row(id)
    assertThat(retried.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(retried.attempts).isEqualTo(1)
    assertThat(retried.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
    // The policy's delay for attempt 1, from the end of the attempt.
    assertThat(retried.nextRetryAt).isEqualTo(start + 250L + 1_000L)
    val retrying = events.of(id).single()
    assertThat(retrying.type).isEqualTo(SyncEventType.ITEM_RETRYING)
    assertThat(retrying.errorCode).isEqualTo(SyncErrorCode.SERVER_ERROR)
    assertThat(retrying.statusCode).isEqualTo(503.0)
    assertThat(result.failedIds.toList()).isEmpty()
    assertThat(callbackLog).isEmpty()
    assertThat(callbacks.gateway.pendingCount).isEqualTo(0)
  }

  @Test
  fun `decider_retry_overridesDelay, as given from the decision, with no jitter and no cap`() = runTest {
    server.dispatcher = respondAfter(250L, 503)
    val id = enqueue()
    val start = clock.now()
    installDecider {
      // The decision takes a while: the delay counts from it, not from the end of the attempt.
      clock.advance(DECIDE_MS)
      FakeJs.decision(RetryAction.RETRY, 12_345.9)
    }
    val events = collectEvents()

    newDispatcher(
      maxDelayMs = 5_000L,
      tweak = { it.copy(retryPolicy = it.retryPolicy.copy(jitter = true)) },
    ).flush()

    val retried = row(id)
    assertThat(retried.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(retried.attempts).isEqualTo(1)
    assertThat(retried.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
    assertThat(retried.nextRetryAt).isEqualTo(start + 250L + DECIDE_MS + 12_345L)
    // The retry keeps the attempt's real code.
    val retrying = events.of(id).single()
    assertThat(retrying.type).isEqualTo(SyncEventType.ITEM_RETRYING)
    assertThat(retrying.errorCode).isEqualTo(SyncErrorCode.SERVER_ERROR)
    assertThat(retrying.statusCode).isEqualTo(503.0)
    assertThat(retrying.attempt).isEqualTo(1.0)
  }

  @Test
  fun `decider_retry_withoutDelay_usesPolicyDelay, from the decision`() = runTest {
    server.dispatcher = respondAfter(250L, 503)
    val id = enqueue()
    val start = clock.now()
    installDecider {
      clock.advance(DECIDE_MS)
      FakeJs.decision(RetryAction.RETRY)
    }

    newDispatcher().flush()

    assertThat(row(id).nextRetryAt).isEqualTo(start + 250L + DECIDE_MS + 1_000L)
    assertThat(callbackLog).isEmpty()
  }

  @Test
  fun `decider_retry_invalidDelay_usesPolicyDelay, for NaN, infinity and a negative delay`() = runTest {
    answerAll(503)
    val delays = mapOf("/nan" to Double.NaN, "/infinite" to Double.POSITIVE_INFINITY, "/negative" to -1.0)
    val ids = delays.keys.associateWith { enqueue(it) }
    val start = clock.now()
    installDecider { context -> FakeJs.decision(RetryAction.RETRY, delays.getValue(URI(context.url).path)) }

    newDispatcher().flush()

    ids.values.forEach { id ->
      assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(row(id).nextRetryAt).isEqualTo(start + 1_000L)
    }
    // Not a failed answer: the decision stands, with the policy's delay, and the reason is logged.
    assertThat(callbackLog.count { it.contains("is not a usable delay; the policy's delay applies") })
      .isEqualTo(3)
  }

  @Test
  fun `decider_retry_hugeDelay_clampsDeadline, at the latest JS Date`() = runTest {
    answerAll(503)
    val delays = mapOf("/huge" to 1e300, "/above-max-delay" to 9.1e15, "/long" to 1e15)
    val ids = delays.keys.associateWith { enqueue(it) }
    val start = clock.now()
    installDecider { context -> FakeJs.decision(RetryAction.RETRY, delays.getValue(URI(context.url).path)) }

    newDispatcher().flush()

    assertThat(row(ids.getValue("/huge")).nextRetryAt).isEqualTo(TestData.MAX_JS_DATE_MS)
    assertThat(row(ids.getValue("/above-max-delay")).nextRetryAt).isEqualTo(TestData.MAX_JS_DATE_MS)
    // Below the ceilings, a delay is used as given.
    assertThat(row(ids.getValue("/long")).nextRetryAt).isEqualTo(start + 1_000_000_000_000_000L)
  }

  @Test
  fun `decider_drop_emitsFailedAndDeletesRow, with no dead letter`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    // A DROP ignores delayMs, even one no retry could use: it is not a failed answer.
    installDecider { FakeJs.decision(RetryAction.DROP, Double.NaN) }
    val events = collectEvents()

    val result = newDispatcher().flush()

    assertThat(db.syncItemDao().findById(id)).isNull()
    assertThat(db.syncDeadLetterDao().findById(id)).isNull()
    val failed = events.of(id).single()
    assertThat(failed.type).isEqualTo(SyncEventType.ITEM_FAILED)
    assertThat(failed.errorCode).isEqualTo(SyncErrorCode.RETRY_DECIDER_REJECTED)
    assertThat(failed.statusCode).isEqualTo(503.0)
    assertThat(failed.attempt).isEqualTo(1.0)
    assertThat(result.failureCount).isEqualTo(1.0)
    assertThat(result.failedIds.toList()).containsExactly(id)
    assertThat(result.errors).containsExactly(id, "RETRY_DECIDER_REJECTED: HTTP 503")
    assertThat(events.last().type).isEqualTo(SyncEventType.SYNC_FAILED)
    assertThat(callbackLog).isEmpty()
  }

  @Test
  fun `decider_deadLetter_movesRowWithDeciderCode, and the real failure as its message`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    // A DEAD_LETTER ignores delayMs, even one no retry could use: it is not a failed answer.
    installDecider { FakeJs.decision(RetryAction.DEAD_LETTER, -1.0) }
    val events = collectEvents()

    val result = newDispatcher().flush()

    val dead = deadLetter(id)
    assertThat(dead.attempts).isEqualTo(1)
    assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.RETRY_DECIDER_REJECTED.name)
    assertThat(dead.lastErrorMessage).isEqualTo("HTTP 503 Server Error")
    val itemEvents = events.of(id)
    assertThat(itemEvents.map { it.type })
      .containsExactly(SyncEventType.ITEM_FAILED, SyncEventType.ITEM_DEAD_LETTERED).inOrder()
    assertThat(itemEvents[0].errorCode).isEqualTo(SyncErrorCode.RETRY_DECIDER_REJECTED)
    assertThat(itemEvents[0].statusCode).isEqualTo(503.0)
    assertThat(itemEvents[0].attempt).isEqualTo(1.0)
    assertThat(itemEvents[1].errorCode).isEqualTo(SyncErrorCode.DEAD_LETTERED)
    assertThat(itemEvents[1].metadata)
      .containsExactly("lastErrorCode", SyncErrorCode.RETRY_DECIDER_REJECTED.name)
    assertThat(result.failedIds.toList()).containsExactly(id)
    assertThat(result.errors).containsExactly(id, "RETRY_DECIDER_REJECTED: HTTP 503")
    assertThat(callbackLog).isEmpty()
  }

  @Test
  fun `decider_failedAnswer_fallsBack at once to the policy, its delay from the fallback`() =
    runBlocking<Unit> {
      server.enqueue(MockResponse().setResponseCode(503))
      val id = enqueue()
      val start = clock.now()
      installDecider {
        clock.advance(DECIDE_MS)
        FakeJs.failed("the decider threw")
      }
      val started = System.nanoTime()

      newDispatcher(callbackTimeoutMs = 30_000.0).flush()

      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(row(id).attempts).isEqualTo(1)
      // The policy's delay, counted from the moment the fallback was decided.
      assertThat(row(id).nextRetryAt).isEqualTo(start + DECIDE_MS + 1_000L)
      assertThat(callbackLog.single())
        .endsWith("answered FAILED: the decider threw; the retry policy applies")
    }

  @Test
  fun `decider_invalidAnswer_fallsBack, a RESOLVED answer with no decision`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    val start = clock.now()
    installDecider { FakeJs.headers(mapOf("X-Wrong" to "kind")) }

    newDispatcher(callbackTimeoutMs = 30_000.0).flush()

    assertThat(row(id).nextRetryAt).isEqualTo(start + 1_000L)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(callbackLog.single())
      .endsWith("the answer carried no usable decision; the retry policy applies")
  }

  @Test
  fun `decider_neverAnswers_timesOut, and the policy applies`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    val start = clock.now()
    val decider = installDecider { null }
    val started = System.nanoTime()

    newDispatcher(callbackTimeoutMs = 200.0).flush()

    assertThat(elapsedMs(started)).isAtLeast(200L)
    assertThat(decider.calls).hasSize(1)
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).nextRetryAt).isEqualTo(start + 1_000L)
    assertThat(callbackLog.single()).endsWith("no answer within 200 ms; the retry policy applies")
    assertThat(callbacks.gateway.pendingCount).isEqualTo(0)
  }

  @Test
  fun `decider_lateAnswer_ignored, once the policy applied`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    val start = clock.now()
    val decider = js.function<RetryDeciderContext> { requestId, _ ->
      answerLater(requestId, 500L, FakeJs.decision(RetryAction.DEAD_LETTER))
    }
    assertThat(hybrid.setRetryDecider(decider)).isTrue()

    newDispatcher(callbackTimeoutMs = 150.0).flush()
    delay(800L)

    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).nextRetryAt).isEqualTo(start + 1_000L)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(callbacks.gateway.pendingCount).isEqualTo(0)
  }

  @Test
  fun `decider_unknownId_noop, and the request keeps its own answer`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    val start = clock.now()
    val decider = js.function<RetryDeciderContext> { requestId, _ ->
      answer("retryDecider:999999", FakeJs.decision(RetryAction.DEAD_LETTER))
      answer(requestId, FakeJs.decision(RetryAction.RETRY, 5_000.0))
    }
    assertThat(hybrid.setRetryDecider(decider)).isTrue()

    newDispatcher().flush()

    assertThat(row(id).nextRetryAt).isEqualTo(start + 5_000L)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
  }

  @Test
  fun `decider_notConsulted_nonRetryable`() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    val id = enqueue()
    val decider = installDecider { FakeJs.decision(RetryAction.RETRY) }

    newDispatcher().flush()

    assertThat(decider.calls).isEmpty()
    assertThat(deadLetter(id).lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
  }

  @Test
  fun `decider_notConsulted_budgetSpent, nor for a row whose budget was spent before`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    val last = enqueue("/last")
    db.syncItemDao().insert(TestData.entity(id = "spent", attempts = 1, url = server.url("/spent").toString()))
    val decider = installDecider { FakeJs.decision(RetryAction.RETRY) }

    newDispatcher(maxAttempts = 1).flush()

    assertThat(decider.calls).isEmpty()
    assertThat(deadLetter(last).lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
    assertThat(deadLetter("spent").lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `decider_notConsulted_credentialHold`() = runTest {
    server.enqueue(MockResponse().setResponseCode(401))
    val id = enqueue()
    val decider = installDecider { FakeJs.decision(RetryAction.DEAD_LETTER) }

    newDispatcher(credentials = { CredentialPolicy.PERSISTED }).flush()

    assertThat(decider.calls).isEmpty()
    assertThat(row(id).attempts).isEqualTo(0)
    assertThat(row(id).lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
  }

  @Test
  fun `decider_notConsulted_success`() = runTest {
    server.enqueue(MockResponse().setResponseCode(200))
    val id = enqueue()
    val decider = installDecider { FakeJs.decision(RetryAction.DROP) }

    val result = newDispatcher().flush()

    assertThat(decider.calls).isEmpty()
    assertThat(result.succeededIds.toList()).containsExactly(id)
  }

  @Test
  fun `decider_context_fields, for an HTTP answer`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    val url = server.url("/orders").toString()
    db.syncItemDao().insert(
      TestData.entity(id = "order", method = "PUT", url = url, attempts = 2)
        .copy(metadata = JsonMap.encode(mapOf("order" to "42"))),
    )
    val decider = installDecider { FakeJs.decision(RetryAction.RETRY) }

    newDispatcher(maxAttempts = 5).flush()

    assertThat(decider.calls).containsExactly(
      RetryDeciderContext(
        itemId = "order",
        // The attempt that just failed, as ITEM_RETRYING reports it.
        attempt = 3.0,
        errorCode = SyncErrorCode.SERVER_ERROR,
        statusCode = 503.0,
        url = url,
        method = HttpMethod.PUT,
        metadata = mapOf("order" to "42"),
      ),
    )
  }

  @Test
  fun `decider_context_fields, for a failure with no answer`() = runTest {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
    val id = enqueue()
    val decider = installDecider { FakeJs.decision(RetryAction.RETRY) }

    newDispatcher().flush()

    val context = decider.calls.single()
    assertThat(context.itemId).isEqualTo(id)
    assertThat(context.attempt).isEqualTo(1.0)
    assertThat(context.errorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR)
    assertThat(context.statusCode).isNull()
    assertThat(context.method).isEqualTo(HttpMethod.POST)
    assertThat(context.metadata).isNull()
  }

  @Test
  fun `decider_replace_and_clear`() = runTest {
    answerAll(503)
    val first = enqueue("/first")
    val replaced = decider { FakeJs.decision(RetryAction.DEAD_LETTER) }
    val current = decider { FakeJs.decision(RetryAction.DROP) }
    assertThat(hybrid.setRetryDecider(replaced)).isTrue()
    assertThat(hybrid.setRetryDecider(current)).isTrue()
    assertThat(hybrid.isRetryDeciderInstalled()).isTrue()
    val d = newDispatcher()

    d.flush()

    assertThat(replaced.calls).isEmpty()
    assertThat(current.calls).hasSize(1)
    assertThat(db.syncItemDao().findById(first)).isNull()

    hybrid.clearRetryDecider()
    assertThat(hybrid.isRetryDeciderInstalled()).isFalse()
    val second = enqueue("/second")
    d.flush()

    assertThat(current.calls).hasSize(1)
    assertThat(row(second).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(second).attempts).isEqualTo(1)
  }

  @Test
  fun `decider_purgedDuringWait_noResurrection, whatever it decides`() = runBlocking<Unit> {
    answerAll(503)
    val actions = mapOf(
      "/retry" to RetryAction.RETRY,
      "/dead-letter" to RetryAction.DEAD_LETTER,
      "/drop" to RetryAction.DROP,
    )
    val ids = actions.keys.associateWith { enqueue(it) }
    val decider = js.function<RetryDeciderContext> { requestId, context ->
      later {
        // A logout purges the row while the decider thinks.
        storage.removeItem(context.itemId)
        answer(requestId, FakeJs.decision(actions.getValue(URI(context.url).path)))
      }
    }
    assertThat(hybrid.setRetryDecider(decider)).isTrue()
    val events = collectEvents()

    val result = newDispatcher().flush()

    ids.values.forEach { id ->
      assertThat(db.syncItemDao().findById(id)).isNull()
      assertThat(db.syncDeadLetterDao().findById(id)).isNull()
    }
    // Each request went out, so its outcome is reported all the same; nothing is dead-lettered.
    assertThat(events.of(ids.getValue("/retry")).map { it.type }).containsExactly(SyncEventType.ITEM_RETRYING)
    listOf("/dead-letter", "/drop").forEach { path ->
      val failed = events.of(ids.getValue(path)).single()
      assertThat(failed.type).isEqualTo(SyncEventType.ITEM_FAILED)
      assertThat(failed.errorCode).isEqualTo(SyncErrorCode.RETRY_DECIDER_REJECTED)
    }
    assertThat(result.failedIds.toList())
      .containsExactly(ids.getValue("/dead-letter"), ids.getValue("/drop"))
  }

  @Test
  fun `lifecycle_newAttach_evictsOlderInstall, so the dispatch no longer asks the old runtime`() =
    runBlocking<Unit> {
      server.enqueue(MockResponse().setResponseCode(503))
      val id = enqueue()
      val start = clock.now()
      val old = installDecider { FakeJs.decision(RetryAction.DEAD_LETTER) }
      // A reload: the new runtime's hybrid is created and installs nothing.
      HybridInstalls(callbacks)
      val started = System.nanoTime()

      newDispatcher(callbackTimeoutMs = 30_000.0).flush()

      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(old.calls).isEmpty()
      assertThat(row(id).nextRetryAt).isEqualTo(start + 1_000L)
      assertThat(callbackLog).isEmpty()
    }

  @Test
  fun `lifecycle_eviction_endsOwnersWaits, and the policy applies at once`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    val start = clock.now()
    val asked = CompletableDeferred<Unit>()
    val decider = js.function<RetryDeciderContext> { _, _ -> asked.complete(Unit) }
    assertThat(hybrid.setRetryDecider(decider)).isTrue()
    val d = newDispatcher(callbackTimeoutMs = 60_000.0)
    val started = System.nanoTime()

    val running = async(Dispatchers.IO) { d.flush() }
    withTimeout(AT_ONCE_MS) { asked.await() }
    // A reload's new hybrid ends the old runtime's wait, which nothing would answer.
    HybridInstalls(callbacks)
    running.await()

    assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
    assertThat(row(id).nextRetryAt).isEqualTo(start + 1_000L)
    assertThat(callbackLog.single())
      .endsWith("the JS runtime that installed it is gone; the retry policy applies")
  }

  /** A callback that waits for `flush()` waits for the cycle that waits for it: never a deadlock. */
  @Test
  fun `android_callback_awaitingFlush_timesOut, then the fallback applies`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    val id = enqueue()
    val start = clock.now()
    lateinit var d: SyncDispatcher
    val nested = CompletableDeferred<SyncResult>()
    val decider = js.function<RetryDeciderContext> { requestId, _ ->
      later {
        nested.complete(d.flush())
        answer(requestId, FakeJs.decision(RetryAction.DEAD_LETTER))
      }
    }
    assertThat(hybrid.setRetryDecider(decider)).isTrue()
    d = newDispatcher(callbackTimeoutMs = 300.0)
    val started = System.nanoTime()

    withTimeout(20_000L) { d.flush() }
    val nestedResult = withTimeout(20_000L) { nested.await() }

    assertThat(elapsedMs(started)).isAtLeast(300L)
    assertThat(decider.calls).hasSize(1)
    // The late answer changed nothing, and the nested cycle found nothing due.
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(id).nextRetryAt).isEqualTo(start + 1_000L)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(nestedResult.successCount + nestedResult.failureCount).isEqualTo(0.0)
    assertThat(callbackLog.single()).endsWith("no answer within 300 ms; the retry policy applies")
  }

  /* --------------------------- the dispatch interceptors --------------------------- */

  /** A header resolver the current hybrid installs: it answers what [reply] gives, at once; null: never. */
  private fun installResolver(
    reply: (HeaderResolverContext) -> CallbackAnswer?,
  ): FakeFunction<HeaderResolverContext> =
    js.function<HeaderResolverContext> { requestId, context -> reply(context)?.let { answer(requestId, it) } }
      .also { assertThat(hybrid.setHeaderResolver(it)).isTrue() }

  /** A `beforeDispatch` the current hybrid installs: it answers what [reply] gives, at once; null: never. */
  private fun installBefore(
    reply: (BeforeDispatchContext) -> CallbackAnswer?,
  ): FakeFunction<BeforeDispatchContext> =
    js.function<BeforeDispatchContext> { requestId, context -> reply(context)?.let { answer(requestId, it) } }
      .also { assertThat(hybrid.setBeforeDispatch(it)).isTrue() }

  /** An `afterDispatch` the current hybrid installs: it runs [onCall], then answers RESOLVED at once. */
  private fun installAfter(onCall: (AfterDispatchContext) -> Unit = {}): FakeFunction<AfterDispatchContext> =
    js.function<AfterDispatchContext> { requestId, context ->
      onCall(context)
      answer(requestId, FakeJs.resolved())
    }.also { assertThat(hybrid.setAfterDispatch(it)).isTrue() }

  private fun proceed(): CallbackAnswer = FakeJs.before(BeforeDispatchAction.PROCEED)

  /** `afterDispatch` runs after the dispatch, on another thread: wait for it, never assume it. */
  private suspend fun awaitUntil(condition: () -> Boolean) {
    withTimeout(AT_ONCE_MS) {
      while (!condition()) delay(10L)
    }
  }

  /** A due row with an explicit claim order, attempted [attempts] times before. */
  private suspend fun seed(id: String, createdAt: Long = 1L, attempts: Int = 0): SyncItemEntity {
    db.syncItemDao().insert(
      TestData.entity(id = id, url = server.url("/$id").toString(), createdAt = createdAt, attempts = attempts),
    )
    return row(id)
  }

  /** Header [name] as it went out, whatever its case: its one entry, with its spelling. */
  private fun RecordedRequest.sentHeader(name: String): Pair<String, String> =
    headers.filter { it.first.equals(name, ignoreCase = true) }.single()

  /** The headers that went out in wire order, but for the ones OkHttp sets itself. */
  private fun RecordedRequest.layeredHeaders(): List<Pair<String, String>> =
    headers.filter { (name, _) -> OKHTTP_HEADERS.none { it.equals(name, ignoreCase = true) } }

  /** Every request the server got, by path. */
  private fun requestsByPath(): Map<String?, RecordedRequest> =
    List(server.requestCount) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }.associateBy { it.path }

  /**
   * [before] deferred by an interceptor: nothing else changed, its attempt kept, waiting until
   * [until] with INTERCEPTOR_FAILED.
   */
  private suspend fun assertDeferred(before: SyncItemEntity, until: Long) {
    assertThat(row(before.id)).isEqualTo(
      before.copy(lastErrorCode = SyncErrorCode.INTERCEPTOR_FAILED.name, nextRetryAt = until),
    )
  }

  /** A deferred item's one event: ITEM_RETRYING with INTERCEPTOR_FAILED, no status, the would-be attempt. */
  private fun List<SyncEvent>.assertDeferredEvent(id: String, attempt: Int) {
    val retrying = of(id).single()
    assertThat(retrying.type).isEqualTo(SyncEventType.ITEM_RETRYING)
    assertThat(retrying.errorCode).isEqualTo(SyncErrorCode.INTERCEPTOR_FAILED)
    assertThat(retrying.statusCode).isNull()
    assertThat(retrying.attempt).isEqualTo(attempt.toDouble())
  }

  @Test
  fun `interceptor_unset_sendsStaticHeaders, with no wait, no extra query and nothing asked`() = runTest {
    answerAll(200)
    val queue = spyk(storage)
    val id = storage.enqueue(TestData.input(url = server.url("/x").toString(), headers = mapOf("X-Item" to "i")))

    val result = newDispatcher(queue = queue, defaultHeaders = mapOf("Authorization" to "Bearer static")).flush()

    val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    assertThat(sent.sentHeader("Authorization")).isEqualTo("Authorization" to "Bearer static")
    assertThat(sent.sentHeader("X-Item")).isEqualTo("X-Item" to "i")
    assertThat(result.succeededIds.toList()).containsExactly(id)
    // The purge check before the request, and no other.
    coVerify(exactly = 1) { queue.isQueued(id) }
    assertThat(callbackLog).isEmpty()
    assertThat(callbacks.gateway.pendingCount).isEqualTo(0)
  }

  @Test
  fun `interceptor_order_resolver_before_dispatch_after, each once`() = runBlocking<Unit> {
    val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        timeline += "request"
        return MockResponse().setResponseCode(200)
      }
    }
    val id = enqueue()
    installResolver {
      timeline += "resolver"
      FakeJs.headers(mapOf("X-Resolved" to "r"))
    }
    installBefore {
      timeline += "before"
      proceed()
    }
    installAfter { timeline += "after" }
    jsScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
      emitter.events.collect { if (it.itemId == id) timeline += it.type.name }
    }

    newDispatcher().flush()
    awaitUntil { "after" in timeline }

    assertThat(timeline)
      .containsExactly("resolver", "before", "request", SyncEventType.ITEM_SUCCEEDED.name, "after")
      .inOrder()
  }

  @Test
  fun `interceptor_headerMergeOrder, defaults then the resolver then the item, whatever the case`() = runTest {
    answerAll(200)
    storage.enqueue(
      TestData.input(url = server.url("/x").toString(), headers = mapOf("X-LAYER" to "item", "X-Item" to "i")),
    )
    installResolver {
      FakeJs.headers(
        mapOf(
          "authorization" to "Bearer resolved",
          "x-layer" to "resolver",
          "X-Resolver" to "r",
          // Two of this layer's names that differ only by case: in name order, as on iOS, the
          // lower-case one wins. Only the resolver's layer is sorted.
          "x-dup" to "lower",
          "X-Dup" to "upper",
        ),
      )
    }

    newDispatcher(
      defaultHeaders = mapOf("Authorization" to "Bearer static", "X-Layer" to "default", "X-Default" to "d"),
    ).flush()

    // One entry per name, with the spelling and the value of the layer that wins.
    val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    assertThat(sent.sentHeader("Authorization")).isEqualTo("authorization" to "Bearer resolved")
    assertThat(sent.sentHeader("X-Layer")).isEqualTo("X-LAYER" to "item")
    assertThat(sent.sentHeader("X-Default")).isEqualTo("X-Default" to "d")
    assertThat(sent.sentHeader("X-Resolver")).isEqualTo("X-Resolver" to "r")
    assertThat(sent.sentHeader("X-Item")).isEqualTo("X-Item" to "i")
    assertThat(sent.sentHeader("X-Dup")).isEqualTo("x-dup" to "lower")
  }

  @Test
  fun `android_headerWireOrder, with nothing installed the static layers go in their map order`() = runTest {
    answerAll(200)
    storage.enqueue(
      TestData.input(url = server.url("/x").toString(), headers = mapOf("X-Item" to "i", "x-layer" to "item")),
    )

    newDispatcher(
      defaultHeaders = mapOf("X-Default" to "d", "X-Layer" to "default", "Authorization" to "Bearer static"),
    ).flush()

    // An item header replaces the default one of that name, whatever its case, and goes last.
    assertThat(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).layeredHeaders()).containsExactly(
      "X-Default" to "d",
      "Authorization" to "Bearer static",
      "X-Item" to "i",
      "x-layer" to "item",
    ).inOrder()
  }

  @Test
  fun `android_headerWireOrder, the resolver's layer in name order between the static ones`() = runTest {
    answerAll(200)
    storage.enqueue(
      TestData.input(
        url = server.url("/x").toString(),
        headers = mapOf("X-Item" to "i", "x-layer" to "item", "X-Shared" to "item"),
      ),
    )
    installResolver {
      FakeJs.headers(mapOf("X-Shared" to "resolver", "X-Resolver" to "r", "authorization" to "Bearer resolved"))
    }

    newDispatcher(
      defaultHeaders = mapOf("X-Default" to "d", "X-Layer" to "default", "Authorization" to "Bearer static"),
    ).flush()

    // What each layer keeps, in its own order: the defaults, the resolver's sorted, the item's.
    assertThat(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).layeredHeaders()).containsExactly(
      "X-Default" to "d",
      "X-Resolver" to "r",
      "authorization" to "Bearer resolved",
      "X-Item" to "i",
      "x-layer" to "item",
      "X-Shared" to "item",
    ).inOrder()
  }

  @Test
  fun `interceptor_resolverFails_failsClosed_keepsAttempt, and the cycle goes on`() = runBlocking<Unit> {
    answerAll(200)
    db.syncItemDao().insert(
      TestData.entity(id = FAILING, url = server.url("/failing").toString(), attempts = 1)
        .copy(lastAttemptAt = clock.now() - 10_000L, lastErrorCode = SyncErrorCode.NETWORK_ERROR.name),
    )
    val failing = row(FAILING)
    val sent = enqueue("/sent")
    val start = clock.now()
    val resolver = installResolver { context ->
      if (context.itemId == FAILING) {
        // The answer takes a while: the cooldown counts from it.
        clock.advance(DECIDE_MS)
        FakeJs.failed("token refresh failed")
      } else {
        FakeJs.headers(emptyMap())
      }
    }
    val before = installBefore { proceed() }
    val after = installAfter()
    val decider = installDecider { FakeJs.decision(RetryAction.DROP) }
    val events = collectEvents()

    val result = newDispatcher().flush()

    // Nothing went out for it, and the attempt it was about to make is not spent.
    assertThat(requestsByPath().keys).containsExactly("/sent")
    // The policy's 2 s for attempt 2 is below the floor: it waits 60 s from the answer.
    assertDeferred(failing, until = start + DECIDE_MS + 60_000L)
    events.assertDeferredEvent(FAILING, attempt = 2)
    assertThat(resolver.calls.map { it.attempt }).containsExactly(2.0, 1.0)
    assertThat(result.succeededIds.toList()).containsExactly(sent)
    assertThat(result.failedIds.toList()).isEmpty()
    assertThat(finishedCycles).containsExactly(null)
    awaitUntil { after.calls.isNotEmpty() }
    assertThat(before.calls.map { it.itemId }).containsExactly(sent)
    assertThat(after.calls.map { it.itemId }).containsExactly(sent)
    assertThat(decider.calls).isEmpty()
    assertThat(callbackLog.single()).matches(
      "headerResolver for item $FAILING \\(headerResolver:\\d+\\): answered FAILED: token refresh failed; " +
        "the item is not sent in this cycle",
    )
    assertThat(callbackPriorities.single()).isEqualTo(Log.WARN)
  }

  @Test
  fun `interceptor_resolverTimeout_failsClosed, after its timeout and not before`() = runBlocking<Unit> {
    answerAll(200)
    val id = enqueue()
    val before = row(id)
    val start = clock.now()
    installResolver { null }
    val events = collectEvents()
    val started = System.nanoTime()

    newDispatcher(callbackTimeoutMs = 300.0).flush()

    assertThat(elapsedMs(started)).isAtLeast(300L)
    assertThat(server.requestCount).isEqualTo(0)
    assertDeferred(before, until = start + 60_000L)
    events.assertDeferredEvent(id, attempt = 1)
    assertThat(callbackLog.single()).endsWith("no answer within 300 ms; the item is not sent in this cycle")
  }

  @Test
  fun `interceptor_resolverTimeout_failsClosed at once when its runtime is gone, logged as INFO`() =
    runBlocking<Unit> {
      answerAll(200)
      val id = enqueue()
      val before = row(id)
      val start = clock.now()
      val asked = CompletableDeferred<Unit>()
      installResolver {
        asked.complete(Unit)
        null
      }
      val started = System.nanoTime()

      val running = async(Dispatchers.IO) { newDispatcher(callbackTimeoutMs = 60_000.0).flush() }
      withTimeout(AT_ONCE_MS) { asked.await() }
      // A reload's new hybrid ends the old runtime's wait, which nothing would answer.
      HybridInstalls(callbacks)
      running.await()

      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(server.requestCount).isEqualTo(0)
      assertDeferred(before, until = start + 60_000L)
      assertThat(callbackLog.single())
        .endsWith("the JS runtime that installed it is gone; the item is not sent in this cycle")
      assertThat(callbackPriorities.single()).isEqualTo(Log.INFO)
    }

  @Test
  fun `interceptor_resolverInvalid_failsClosed, a RESOLVED answer with no headers`() = runTest {
    answerAll(200)
    val id = enqueue()
    val before = row(id)
    val start = clock.now()
    val hook = installBefore { proceed() }
    installResolver { FakeJs.resolved() }
    val events = collectEvents()

    newDispatcher().flush()

    assertThat(server.requestCount).isEqualTo(0)
    assertDeferred(before, until = start + 60_000L)
    events.assertDeferredEvent(id, attempt = 1)
    assertThat(hook.calls).isEmpty()
    assertThat(callbackLog.single())
      .endsWith("the answer carried no usable headers; the item is not sent in this cycle")
  }

  @Test
  fun `interceptor_resolverInvalid_failsClosed, a header value OkHttp refuses, never logged`() = runTest {
    answerAll(200)
    val id = enqueue()
    val before = row(id)
    val start = clock.now()
    installResolver { FakeJs.headers(mapOf("Authorization" to "Bearer secret\r\nX-Injected: 1")) }
    val events = collectEvents()

    val result = newDispatcher().flush()

    // Not a dead letter: the item is not at fault.
    assertThat(server.requestCount).isEqualTo(0)
    assertDeferred(before, until = start + 60_000L)
    events.assertDeferredEvent(id, attempt = 1)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(result.failedIds.toList()).isEmpty()
    assertThat(callbackLog.single()).doesNotContain("secret")
  }

  @Test
  fun `interceptor_beforeSkip_defers, for the policy's delay when it passes the floor`() = runTest {
    answerAll(200)
    val id = enqueue()
    val before = row(id)
    val start = clock.now()
    installBefore { FakeJs.before(BeforeDispatchAction.SKIP) }
    val events = collectEvents()

    val result = newDispatcher(baseDelayMs = 100_000L, maxDelayMs = 600_000L).flush()

    assertThat(server.requestCount).isEqualTo(0)
    assertDeferred(before, until = start + 100_000L)
    events.assertDeferredEvent(id, attempt = 1)
    assertThat(result.successCount + result.failureCount).isEqualTo(0.0)
    assertThat(callbackLog.single())
      .isEqualTo("beforeDispatch answered SKIP for item $id; it is not sent until ${start + 100_000L}")
    assertThat(callbackPriorities.single()).isEqualTo(Log.INFO)
  }

  @Test
  fun `interceptor_beforeFails_failsClosed`() = runTest {
    answerAll(200)
    val id = enqueue()
    val before = row(id)
    val start = clock.now()
    installResolver { FakeJs.headers(mapOf("X-Token" to "t")) }
    installBefore { FakeJs.failed("hook threw") }
    val events = collectEvents()

    newDispatcher().flush()

    assertThat(server.requestCount).isEqualTo(0)
    assertDeferred(before, until = start + 60_000L)
    events.assertDeferredEvent(id, attempt = 1)
    assertThat(callbackLog.single())
      .endsWith("answered FAILED: hook threw; the item is not sent in this cycle")
    assertThat(callbackPriorities.single()).isEqualTo(Log.WARN)
  }

  @Test
  fun `interceptor_beforeContext_hasMergedHeaders, with the stored content type apart`() = runTest {
    answerAll(200)
    val explicit = storage.enqueue(
      TestData.input(
        url = server.url("/explicit").toString(),
        headers = mapOf("content-type" to "application/vnd.layer+json", "X-Item" to "i"),
        contentType = "text/plain",
        metadata = mapOf("k" to "v"),
      ),
    )
    val implicit = storage.enqueue(
      TestData.input(url = server.url("/implicit").toString(), headers = null, contentType = null),
    )
    installResolver { FakeJs.headers(mapOf("authorization" to "Bearer resolved")) }
    val hook = installBefore { proceed() }

    newDispatcher(defaultHeaders = mapOf("Authorization" to "Bearer static", "X-Default" to "d")).flush()

    val contexts = hook.calls.associateBy { it.itemId }
    with(contexts.getValue(explicit)) {
      // A layer's Content-Type stays among the headers.
      assertThat(headers).containsExactly(
        "authorization", "Bearer resolved",
        "X-Default", "d",
        "content-type", "application/vnd.layer+json",
        "X-Item", "i",
      )
      assertThat(contentType).isEqualTo("text/plain")
      assertThat(attempt).isEqualTo(1.0)
      assertThat(url).isEqualTo(server.url("/explicit").toString())
      assertThat(method).isEqualTo(HttpMethod.POST)
      assertThat(metadata).containsExactly("k", "v")
    }
    with(contexts.getValue(implicit)) {
      // The Content-Type OkHttp takes from the body is not a header here.
      assertThat(headers).containsExactly("authorization", "Bearer resolved", "X-Default", "d")
      assertThat(contentType).isNull()
      assertThat(metadata).isNull()
    }
    // On the wire, the body's media type replaces a layer's Content-Type, as before.
    val sent = requestsByPath()
    assertThat(sent.getValue("/explicit").getHeader("Content-Type")).startsWith("text/plain")
    assertThat(sent.getValue("/implicit").getHeader("Content-Type")).startsWith("application/json")
  }

  @Test
  fun `interceptor_failure_isPerItem, the batch's waits all out at once`() = runBlocking<Unit> {
    answerAll(200)
    val ids = listOf("/a", "/b", "/c").associateWith { enqueue(it) }
    val asked: MutableList<Pair<String, HeaderResolverContext>> = Collections.synchronizedList(mutableListOf())
    val count = AtomicInteger(0)
    val resolver = js.function<HeaderResolverContext> { requestId, context ->
      asked += requestId to context
      // Answers only once the whole batch is waiting: one wait at a time would time out.
      if (count.incrementAndGet() == ids.size) {
        later {
          synchronized(asked) { asked.toList() }.forEach { (pending, asking) ->
            val reply = if (URI(asking.url).path == "/b") {
              FakeJs.failed("refresh failed")
            } else {
              FakeJs.headers(mapOf("X-Token" to "t"))
            }
            answer(pending, reply)
          }
        }
      }
    }
    assertThat(hybrid.setHeaderResolver(resolver)).isTrue()
    val b = row(ids.getValue("/b"))
    val events = collectEvents()
    val started = System.nanoTime()

    val result = newDispatcher(callbackTimeoutMs = 30_000.0).flush()

    assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
    val sent = requestsByPath()
    assertThat(sent.keys).containsExactly("/a", "/c")
    sent.values.forEach { assertThat(it.getHeader("X-Token")).isEqualTo("t") }
    assertThat(result.succeededIds.toList()).containsExactly(ids.getValue("/a"), ids.getValue("/c"))
    assertThat(result.failedIds.toList()).isEmpty()
    assertThat(row(b.id).status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row(b.id).lastErrorCode).isEqualTo(SyncErrorCode.INTERCEPTOR_FAILED.name)
    events.assertDeferredEvent(b.id, attempt = 1)
  }

  @Test
  fun `interceptor_purgedDuringResolver_notSent, answered or failed, and nothing reported`() =
    runBlocking<Unit> {
      answerAll(200)
      val answered = enqueue("/answered")
      val failed = enqueue("/failed")
      val resolver = js.function<HeaderResolverContext> { requestId, context ->
        later {
          // A logout purges the row while the resolver works.
          storage.removeItem(context.itemId)
          val reply = if (context.itemId == failed) FakeJs.failed("gone") else FakeJs.headers(emptyMap())
          answer(requestId, reply)
        }
      }
      assertThat(hybrid.setHeaderResolver(resolver)).isTrue()
      val hook = installBefore { proceed() }
      val events = collectEvents()

      val result = newDispatcher().flush()

      assertThat(server.requestCount).isEqualTo(0)
      assertThat(resolver.calls).hasSize(2)
      assertThat(hook.calls).isEmpty()
      listOf(answered, failed).forEach { id ->
        assertThat(db.syncItemDao().findById(id)).isNull()
        assertThat(events.of(id)).isEmpty()
      }
      assertThat(result.successCount + result.failureCount).isEqualTo(0.0)
    }

  @Test
  fun `interceptor_purgedDuringBefore_notSent, on PROCEED or SKIP, and nothing reported`() =
    runBlocking<Unit> {
      answerAll(200)
      val proceeding = enqueue("/proceed")
      val skipping = enqueue("/skip")
      val hook = js.function<BeforeDispatchContext> { requestId, context ->
        later {
          storage.removeItem(context.itemId)
          val action = if (context.itemId == skipping) BeforeDispatchAction.SKIP else BeforeDispatchAction.PROCEED
          answer(requestId, FakeJs.before(action))
        }
      }
      assertThat(hybrid.setBeforeDispatch(hook)).isTrue()
      val events = collectEvents()

      val result = newDispatcher().flush()

      assertThat(server.requestCount).isEqualTo(0)
      assertThat(hook.calls).hasSize(2)
      listOf(proceeding, skipping).forEach { id ->
        assertThat(db.syncItemDao().findById(id)).isNull()
        assertThat(events.of(id)).isEmpty()
      }
      assertThat(result.successCount + result.failureCount).isEqualTo(0.0)
      assertThat(callbackLog.single())
        .isEqualTo("beforeDispatch answered SKIP for item $skipping; it was removed meanwhile")
    }

  @Test
  fun `android_interceptor_releasedDuringWait_leftAsItIs, no longer in flight and nothing reported`() =
    runBlocking<Unit> {
      answerAll(200)
      val id = enqueue()
      val before = row(id)
      val resolver = js.function<HeaderResolverContext> { requestId, _ ->
        later {
          // The claim is given back meanwhile, as another process's recovery would give it back.
          db.syncItemDao().resetInFlight()
          answer(requestId, FakeJs.failed("no token"))
        }
      }
      assertThat(hybrid.setHeaderResolver(resolver)).isTrue()
      val events = collectEvents()

      val result = newDispatcher().flush()

      // Not deferred: the row is as the release left it, and nothing reports it.
      assertThat(server.requestCount).isEqualTo(0)
      assertThat(resolver.calls).hasSize(1)
      assertThat(row(id)).isEqualTo(before)
      assertThat(events.of(id)).isEmpty()
      assertThat(result.successCount + result.failureCount).isEqualTo(0.0)
    }

  @Test
  fun `interceptor_after_calledOncePerSentRequest, with how each request ended`() = runBlocking<Unit> {
    val purged = enqueue("/purged")
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
        "/ok" -> MockResponse().setResponseCode(200).setHeadersDelay(200L, TimeUnit.MILLISECONDS)
        "/purged" -> {
          // Purged while its request is out: it still went out.
          runBlocking { storage.removeItem(purged) }
          MockResponse().setResponseCode(200)
        }
        "/retry", "/drop", "/exhausted" -> MockResponse().setResponseCode(503)
        else -> MockResponse().setResponseCode(400)
      }
    }
    val ok = enqueue("/ok")
    val retry = enqueue("/retry")
    val drop = enqueue("/drop")
    val permanent = enqueue("/permanent")
    seed("exhausted", attempts = 2)
    installDecider { context ->
      FakeJs.decision(if (URI(context.url).path == "/drop") RetryAction.DROP else RetryAction.RETRY)
    }
    val after = installAfter()

    newDispatcher(maxAttempts = 3).flush()
    awaitUntil { after.calls.size == 6 }
    delay(200L)

    val calls = after.calls.associateBy { it.itemId }
    assertThat(calls).hasSize(6)
    assertThat(after.calls).hasSize(6)
    fun assertCall(id: String, outcome: DispatchOutcome, statusCode: Int, errorCode: SyncErrorCode?, attempt: Int) {
      val call = calls.getValue(id)
      assertWithMessage(id).that(call.outcome).isEqualTo(outcome)
      assertWithMessage(id).that(call.statusCode).isEqualTo(statusCode.toDouble())
      assertWithMessage(id).that(call.errorCode).isEqualTo(errorCode)
      assertWithMessage(id).that(call.attempt).isEqualTo(attempt.toDouble())
      assertWithMessage(id).that(call.method).isEqualTo(HttpMethod.POST)
      assertWithMessage(id).that(call.metadata).isNull()
      assertWithMessage(id).that(call.durationMs).isAtLeast(0.0)
    }
    assertCall(ok, DispatchOutcome.SUCCEEDED, 200, null, attempt = 1)
    assertCall(purged, DispatchOutcome.SUCCEEDED, 200, null, attempt = 1)
    assertCall(retry, DispatchOutcome.RETRYING, 503, SyncErrorCode.SERVER_ERROR, attempt = 1)
    assertCall(drop, DispatchOutcome.FAILED, 503, SyncErrorCode.RETRY_DECIDER_REJECTED, attempt = 1)
    assertCall(permanent, DispatchOutcome.FAILED, 400, SyncErrorCode.SERVER_ERROR, attempt = 1)
    assertCall("exhausted", DispatchOutcome.FAILED, 503, SyncErrorCode.MAX_ATTEMPTS_EXCEEDED, attempt = 3)
    // The request's own time: at least the server's delay before its answer.
    assertThat(calls.getValue(ok).durationMs).isAtLeast(200.0)
    assertThat(calls.getValue(ok).url).isEqualTo(server.url("/ok").toString())
  }

  @Test
  fun `interceptor_after_calledOncePerSentRequest, for a credential hold before the cycle stops`() =
    runBlocking<Unit> {
      answerAll(401)
      seed("head", createdAt = 1L)
      val rest = seed("rest", createdAt = 2L)
      // Never answers: its entry stays out, so it shows the call was made within the cycle.
      val after = js.function<AfterDispatchContext> { _, _ -> }
      assertThat(hybrid.setAfterDispatch(after)).isTrue()

      newDispatcher(credentials = { CredentialPolicy.PERSISTED }, callbackTimeoutMs = 60_000.0).flush()

      assertThat(callbacks.gateway.pendingCount).isEqualTo(1)
      awaitUntil { after.calls.isNotEmpty() }
      val call = after.calls.single()
      assertThat(call.itemId).isEqualTo("head")
      assertThat(call.outcome).isEqualTo(DispatchOutcome.RETRYING)
      assertThat(call.errorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED)
      assertThat(call.statusCode).isEqualTo(401.0)
      assertThat(call.attempt).isEqualTo(1.0)
      // The rest of the batch went back unsent, and no call is made for it.
      assertThat(server.requestCount).isEqualTo(1)
      assertThat(row("rest")).isEqualTo(rest)
    }

  @Test
  fun `interceptor_after_notCalledWhenNotSent, and an item that cannot be sent asks nothing`() =
    runBlocking<Unit> {
      answerAll(200)
      val sent = enqueue("/sent")
      val purged = enqueue("/purged")
      val deferred = enqueue("/deferred")
      val skipped = enqueue("/skipped")
      seed("spent", attempts = 3)
      db.syncItemDao().insert(TestData.entity(id = "unbuildable", url = "ftp://example.com/unbuildable"))
      val resolver = js.function<HeaderResolverContext> { requestId, context ->
        when (context.itemId) {
          purged -> later {
            storage.removeItem(purged)
            answer(requestId, FakeJs.headers(emptyMap()))
          }
          deferred -> answer(requestId, FakeJs.failed("no token"))
          else -> answer(requestId, FakeJs.headers(emptyMap()))
        }
      }
      assertThat(hybrid.setHeaderResolver(resolver)).isTrue()
      val hook = installBefore { context ->
        FakeJs.before(if (context.itemId == skipped) BeforeDispatchAction.SKIP else BeforeDispatchAction.PROCEED)
      }
      val after = installAfter()

      newDispatcher(maxAttempts = 3).flush()
      // The one request that went out gets its call: once it has, any other would have too.
      awaitUntil { after.calls.isNotEmpty() }
      delay(200L)

      assertThat(after.calls.map { it.itemId }).containsExactly(sent)
      assertThat(requestsByPath().keys).containsExactly("/sent")
      // A spent budget and a request OkHttp refuses fail as before, and reach no interceptor.
      assertThat(deadLetter("spent").lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
      assertThat(deadLetter("unbuildable").lastErrorCode).isEqualTo(SyncErrorCode.INVALID_PAYLOAD.name)
      assertThat(resolver.calls.map { it.itemId }).containsExactly(sent, purged, deferred, skipped)
      assertThat(hook.calls.map { it.itemId }).containsExactly(sent, skipped)
    }

  @Test
  fun `interceptor_after_neverAwaited, its call or its answer`() = runBlocking<Unit> {
    answerAll(200)
    val id = enqueue()
    val release = CountDownLatch(1)
    // Holds its thread until the test lets it go, and never answers.
    val after = js.function<AfterDispatchContext> { _, _ -> release.await(20, TimeUnit.SECONDS) }
    assertThat(hybrid.setAfterDispatch(after)).isTrue()
    val started = System.nanoTime()

    val result = newDispatcher(callbackTimeoutMs = 60_000.0).flush()

    assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
    assertThat(result.succeededIds.toList()).containsExactly(id)
    awaitUntil { after.calls.isNotEmpty() }
    release.countDown()
    // Asked and unanswered: only its own timeout ends its entry.
    assertThat(callbacks.gateway.pendingCount).isEqualTo(1)
    assertThat(after.calls.single().itemId).isEqualTo(id)
  }

  @Test
  fun `interceptor_after_followsItsItemEvent, for each item`() = runBlocking<Unit> {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse =
        MockResponse().setResponseCode(if (request.path == "/retry") 503 else 200)
    }
    val ok = enqueue("/ok")
    val retry = enqueue("/retry")
    val timeline: MutableList<String> = Collections.synchronizedList(mutableListOf())
    jsScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
      emitter.events.collect { event -> event.itemId?.let { timeline += "${event.type.name}:$it" } }
    }
    installAfter { timeline += "after:${it.itemId}" }

    newDispatcher().flush()
    awaitUntil { timeline.count { it.startsWith("after:") } == 2 }

    val seen = synchronized(timeline) { timeline.toList() }
    val succeeded = seen.indexOf("${SyncEventType.ITEM_SUCCEEDED.name}:$ok")
    val retrying = seen.indexOf("${SyncEventType.ITEM_RETRYING.name}:$retry")
    assertThat(succeeded).isAtLeast(0)
    assertThat(retrying).isAtLeast(0)
    assertThat(seen.indexOf("after:$ok")).isGreaterThan(succeeded)
    assertThat(seen.indexOf("after:$retry")).isGreaterThan(retrying)
  }

  @Test
  fun `interceptor_clear_restoresStaticHeaders, and asks nothing more`() = runTest {
    answerAll(200)
    enqueue("/first")
    val resolver = installResolver { FakeJs.headers(mapOf("Authorization" to "Bearer resolved")) }
    val hook = installBefore { proceed() }
    val d = newDispatcher(defaultHeaders = mapOf("Authorization" to "Bearer static"))

    d.flush()
    hybrid.clearHeaderResolver()
    hybrid.clearBeforeDispatch()
    enqueue("/second")
    d.flush()

    val sent = requestsByPath()
    assertThat(sent.getValue("/first").getHeader("Authorization")).isEqualTo("Bearer resolved")
    assertThat(sent.getValue("/second").getHeader("Authorization")).isEqualTo("Bearer static")
    assertThat(resolver.calls).hasSize(1)
    assertThat(hook.calls).hasSize(1)
  }

  @Test
  fun `interceptor_probes_neverCallCallbacks, the health check's or the latency probe's`() = runBlocking<Unit> {
    val healthStatus = AtomicInteger(503)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse =
        MockResponse().setResponseCode(if (request.method == "HEAD") healthStatus.get() else 200)
    }
    val id = enqueue()
    val resolver = installResolver { FakeJs.headers(mapOf("X-Resolved" to "r")) }
    val hook = installBefore { proceed() }
    val after = installAfter()
    val decider = installDecider { FakeJs.decision(RetryAction.DROP) }
    val healthUrl = server.url("/health").toString()

    // A failed check skips the cycle: nothing is asked.
    newDispatcher(healthCheckUrl = healthUrl).flush()
    LatencyProbe(HeadProbe(lazyOf(HeadProbe.newClient()))).measure(server.url("/latency").toString())
    assertThat(resolver.calls + hook.calls + after.calls + decider.calls).isEmpty()
    // A healthy check: only the item's own request is asked about.
    healthStatus.set(200)
    newDispatcher(healthCheckUrl = healthUrl, healthCheck = healthCheck()).flush()
    awaitUntil { after.calls.isNotEmpty() }

    val requests = List(server.requestCount) { checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
    assertThat(requests.map { it.method }).containsExactly("HEAD", "HEAD", "HEAD", "HEAD", "HEAD", "POST").inOrder()
    requests.filter { it.method == "HEAD" }.forEach { assertThat(it.getHeader("X-Resolved")).isNull() }
    assertThat(requests.last().getHeader("X-Resolved")).isEqualTo("r")
    assertThat(resolver.calls.map { it.itemId }).containsExactly(id)
    assertThat(hook.calls.map { it.itemId }).containsExactly(id)
    assertThat(after.calls.map { it.itemId }).containsExactly(id)
    assertThat(decider.calls).isEmpty()
  }

  @Test
  fun `lifecycle_clearOrReplace_keepsPendingWait, a clear during the wait keeps its answer`() =
    runBlocking<Unit> {
      answerAll(200)
      enqueue("/first")
      val resolver = js.function<HeaderResolverContext> { requestId, _ ->
        // JS clears the resolver while this wait is out; the answer still comes.
        hybrid.clearHeaderResolver()
        answerLater(requestId, 300L, FakeJs.headers(mapOf("X-Token" to "from the wait")))
      }
      assertThat(hybrid.setHeaderResolver(resolver)).isTrue()
      val d = newDispatcher(callbackTimeoutMs = 30_000.0)
      val started = System.nanoTime()

      d.flush()

      assertThat(elapsedMs(started)).isAtLeast(300L)
      enqueue("/second")
      d.flush()
      val sent = requestsByPath()
      assertThat(sent.getValue("/first").getHeader("X-Token")).isEqualTo("from the wait")
      assertThat(sent.getValue("/second").getHeader("X-Token")).isNull()
      assertThat(resolver.calls).hasSize(1)
    }

  @Test
  fun `lifecycle_clearOrReplace_keepsPendingWait, a replacing set leaves the wait to its timeout`() =
    runBlocking<Unit> {
      answerAll(200)
      val id = enqueue()
      val before = row(id)
      val start = clock.now()
      val replacement = js.function<HeaderResolverContext> { requestId, _ ->
        answer(requestId, FakeJs.headers(mapOf("X-Token" to "new")))
      }
      val replaced = js.function<HeaderResolverContext> { _, _ ->
        // Replaced while this wait is out, and never answered.
        hybrid.setHeaderResolver(replacement)
      }
      assertThat(hybrid.setHeaderResolver(replaced)).isTrue()
      val d = newDispatcher(callbackTimeoutMs = 300.0)
      val started = System.nanoTime()

      d.flush()

      // Bounded by its timeout, not cut short by the replacement.
      assertThat(elapsedMs(started)).isAtLeast(300L)
      assertDeferred(before, until = start + 60_000L)
      assertThat(callbackLog.single()).endsWith("no answer within 300 ms; the item is not sent in this cycle")
      // Once the item is due, the new resolver is the one asked.
      clock.advance(60_000L)
      d.flush()
      assertThat(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).getHeader("X-Token")).isEqualTo("new")
      assertThat(replaced.calls).hasSize(1)
      assertThat(replacement.calls).hasSize(1)
    }

  @Test
  fun `lifecycle_teardown_retiresInstalls_untilNewAttach, an orphaned resolver holds the item unsent`() =
    runBlocking<Unit> {
      answerAll(200)
      val held = enqueue("/held")
      val before = row(held)
      val start = clock.now()
      val resolver = installResolver { FakeJs.headers(mapOf("Authorization" to "Bearer resolved")) }
      val hook = installBefore { proceed() }
      val after = installAfter()
      val decider = installDecider { FakeJs.decision(RetryAction.DROP) }
      // The hybrid is disposed: what it installed stays, orphans that no runtime answers.
      hybrid.retire()
      val events = collectEvents()
      val d = newDispatcher(
        callbackTimeoutMs = 60_000.0,
        defaultHeaders = mapOf("Authorization" to "Bearer static"),
      )
      val started = System.nanoTime()

      d.flush()

      // Deferred at once, not after the timeout, and never sent with the static headers alone.
      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(server.requestCount).isEqualTo(0)
      assertDeferred(before, until = start + 60_000L)
      events.assertDeferredEvent(held, attempt = 1)
      assertThat(callbackLog.single()).matches(
        "headerResolver for item $held \\(headerResolver:\\d+\\): the JS runtime that installed it is gone; " +
          "the item is not sent in this cycle",
      )
      assertThat(callbackPriorities.single()).isEqualTo(Log.INFO)

      // A newer hybrid attaches and installs nothing yet: the next item goes out with the static headers.
      HybridInstalls(callbacks)
      val next = enqueue("/next")
      val result = d.flush()

      val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
      assertThat(sent.path).isEqualTo("/next")
      assertThat(sent.sentHeader("Authorization")).isEqualTo("Authorization" to "Bearer static")
      assertThat(result.succeededIds.toList()).containsExactly(next)
      // The held item waits out its cooldown, and no JS function was ever called.
      assertThat(server.requestCount).isEqualTo(1)
      assertDeferred(before, until = start + 60_000L)
      assertThat(callbackLog).hasSize(1)
      listOf(resolver.calls, hook.calls, after.calls, decider.calls).forEach { assertThat(it).isEmpty() }
    }

  @Test
  fun `lifecycle_teardown_retiresInstalls_untilNewAttach, an orphaned beforeDispatch holds the item unsent too`() =
    runTest {
      answerAll(200)
      val id = enqueue()
      val before = row(id)
      val start = clock.now()
      val hook = installBefore { proceed() }
      hybrid.retire()
      val events = collectEvents()

      newDispatcher(callbackTimeoutMs = 60_000.0).flush()

      assertThat(server.requestCount).isEqualTo(0)
      assertDeferred(before, until = start + 60_000L)
      events.assertDeferredEvent(id, attempt = 1)
      assertThat(hook.calls).isEmpty()
      assertThat(callbackLog.single()).matches(
        "beforeDispatch for item $id \\(beforeDispatch:\\d+\\): the JS runtime that installed it is gone; " +
          "the item is not sent in this cycle",
      )
      assertThat(callbackPriorities.single()).isEqualTo(Log.INFO)
    }

  @Test
  fun `lifecycle_teardown_retiresInstalls_untilNewAttach, an orphaned decider leaves the retry to the policy`() =
    runBlocking<Unit> {
      server.enqueue(MockResponse().setResponseCode(503))
      val id = enqueue()
      val start = clock.now()
      val decider = installDecider { FakeJs.decision(RetryAction.DEAD_LETTER) }
      val after = installAfter()
      hybrid.retire()
      val events = collectEvents()
      val started = System.nanoTime()

      newDispatcher(callbackTimeoutMs = 60_000.0).flush()

      // The policy's 1 s for attempt 1, at once, and no dead letter.
      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(row(id).nextRetryAt).isEqualTo(start + 1_000L)
      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(events.of(id).map { it.type }).containsExactly(SyncEventType.ITEM_RETRYING)
      // afterDispatch is only logged, after the decider's line.
      awaitUntil { callbackLog.size == 2 && callbacks.gateway.pendingCount == 0 }
      assertThat(callbackLog[0]).matches(
        "retryDecider for item $id \\(retryDecider:\\d+\\): the JS runtime that installed it is gone; " +
          "the retry policy applies",
      )
      assertThat(callbackLog[1]).matches(
        "afterDispatch for item $id \\(afterDispatch:\\d+\\): the JS runtime that installed it is gone; " +
          "nothing changes",
      )
      assertThat(callbackPriorities).containsExactly(Log.INFO, Log.INFO)
      assertThat(decider.calls).isEmpty()
      assertThat(after.calls).isEmpty()
    }

  @Test
  fun `android_interceptor_persistedHeadDeferred_nextItemProbes, alone`() = runTest {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse =
        MockResponse().setResponseCode(if (request.path == "/second") 401 else 200)
    }
    val first = seed("first", createdAt = 1L)
    seed("second", createdAt = 2L)
    val third = seed("third", createdAt = 3L)
    val resolver = installResolver { context ->
      if (context.itemId == "first") FakeJs.failed("no token yet") else FakeJs.headers(emptyMap())
    }
    val events = collectEvents()

    val result = newDispatcher(credentials = { CredentialPolicy.PERSISTED }).flush()

    // The deferred head tested no credential, so the next item went alone, and its rejection
    // gave the rest of the batch back unsent.
    assertThat(requestsByPath().keys).containsExactly("/second")
    assertThat(row("first").lastErrorCode).isEqualTo(SyncErrorCode.INTERCEPTOR_FAILED.name)
    assertThat(row("first").attempts).isEqualTo(first.attempts)
    assertThat(row("second").lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
    assertThat(row("third")).isEqualTo(third)
    assertThat(resolver.calls.map { it.itemId }).containsExactly("first", "second").inOrder()
    events.assertDeferredEvent("first", attempt = 1)
    assertThat(events.of("third")).isEmpty()
    assertThat(result.successCount + result.failureCount).isEqualTo(0.0)
  }

  private companion object {
    /** How long a fake decision takes, on the test clock. */
    const val DECIDE_MS = 300L

    /** An item attempted once before, whose header resolver fails. */
    const val FAILING = "01FAILING"

    /** Well inside every long callback timeout here: what "at once" allows on a loaded machine. */
    const val AT_ONCE_MS = 10_000L

    /** The headers OkHttp sets on its own, from the body or the connection. */
    val OKHTTP_HEADERS = listOf("Content-Type", "Content-Length", "Host", "Connection", "Accept-Encoding", "User-Agent")
  }
}
