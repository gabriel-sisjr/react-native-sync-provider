package com.margelo.nitro.syncprovider.http

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.HttpMethod
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncEvent
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.SyncResult
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.error.SyncException
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.testutil.MovingClock
import com.margelo.nitro.syncprovider.testutil.TestData
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    onCycleFinished: suspend (Long?) -> Unit = { finishedCycles += it },
    queue: SyncQueueStorage = storage,
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
    )
    return SyncDispatcher(
      storage = queue,
      emitter = emitter,
      getOptions = { options },
      clock = clock::now,
      isPaused = isPaused,
      credentialPolicy = credentials,
      onCycleFinished = onCycleFinished,
    )
  }

  /** Answers [code] after moving the clock by [attemptMs], as a real request would. */
  private fun respondAfter(attemptMs: Long, code: Int): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
      clock.advance(attemptMs)
      return MockResponse().setResponseCode(code)
    }
  }

  private fun TestScope.recordEvents(): List<SyncEvent> {
    val events: MutableList<SyncEvent> = Collections.synchronizedList(mutableListOf())
    backgroundScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
      emitter.events.collect { events += it }
    }
    return events
  }

  private fun List<SyncEvent>.types(): List<SyncEventType> = map { it.type }

  private suspend fun enqueue(path: String = "/x"): String =
    storage.enqueue(TestData.input(url = server.url(path).toString()))

  private suspend fun row(id: String): SyncItemEntity = db.syncItemDao().findById(id)!!

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
    val failed = row(id)
    assertThat(failed.status).isEqualTo(SyncItemEntity.STATUS_FAILED)
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
    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_FAILED)
    assertThat(row(id).attempts).isEqualTo(3)
    assertThat(row(id).lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
  }

  @Test
  fun `a budget lowered below the attempts already spent fails the row without a request`() = runTest {
    db.syncItemDao().insert(TestData.entity(id = "spent", attempts = 7, url = server.url("/x").toString()))
    val events = recordEvents()

    val result = newDispatcher(maxAttempts = 5).flush()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("spent").status).isEqualTo(SyncItemEntity.STATUS_FAILED)
    assertThat(row("spent").attempts).isEqualTo(7)
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

    assertThat(row(id).lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
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
      onCycleFinished = {
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
      // Still reads as "never attempted".
      assertThat(row(id).nextRetryAt).isEqualTo(0L)
    }
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

    assertThat(row(id).status).isEqualTo(SyncItemEntity.STATUS_FAILED)
    assertThat(row(id).attempts).isEqualTo(1)
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
}
