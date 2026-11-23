package com.margelo.nitro.syncprovider.http

import android.database.sqlite.SQLiteDatabaseLockedException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.BeforeDispatchAction
import com.margelo.nitro.syncprovider.BeforeDispatchContext
import com.margelo.nitro.syncprovider.CallbackAnswer
import com.margelo.nitro.syncprovider.HeaderResolverContext
import com.margelo.nitro.syncprovider.RetryAction
import com.margelo.nitro.syncprovider.RetryDeciderContext
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncEvent
import com.margelo.nitro.syncprovider.SyncEventType
import com.margelo.nitro.syncprovider.callbacks.HybridInstalls
import com.margelo.nitro.syncprovider.callbacks.JsCallbacks
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.database.SyncDeadLetterEntity
import com.margelo.nitro.syncprovider.database.SyncItemDao
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.retry.RetryPolicyEvaluator
import com.margelo.nitro.syncprovider.retry.RetryTimer
import com.margelo.nitro.syncprovider.retry.RetryWake
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.testutil.FakeFunction
import com.margelo.nitro.syncprovider.testutil.FakeJs
import com.margelo.nitro.syncprovider.testutil.MovingClock
import com.margelo.nitro.syncprovider.testutil.TestData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The abort/settle matrix, Android column: one test per cell, named after it. A row is what
 * happened to the claimed item's attempt; a column is what failed:
 * - C1: the outcome write fails once, the cycle aborts, and the settle's own write succeeds. R5,
 *   R6 and R7 have no outcome write: the cycle aborts or is cancelled, and the settle or the
 *   release succeeds.
 * - C2: the outcome write fails, and the settle's (or the release's) own write fails too.
 *
 * Each test checks the row against its state before the claim, the events in order with their
 * codes, how the cycle ended, and the next automatic wake: the delays the real [RetryWake] gives
 * the in-process timer and the WorkManager kick, on the test clock. When the cycle leaves nothing
 * to wake for, a due row it never saw shows the hold it left, armed the way `configureSync` or an
 * engine start arms it.
 *
 * Every item starts attempted once, 10 s ago, with a cooldown that has passed: due now. Each
 * request takes [ATTEMPT_MS], and a failing outcome write takes [WRITE_MS] before it throws, so
 * the settle runs later than the attempt ended.
 *
 * With a retry decider installed:
 * - R8: a retryable answer the decider decided, then the write of that decision fails. The settle
 *   applies the decision as recorded, without asking JS again; when nothing was decided, an
 *   [Error] raised while the decider was being asked say, it applies the policy's retry.
 * - R11: the cycle is cancelled while the decider is asked, or before. The wait ends at once and
 *   the dispatch writes the policy's retry itself; the decider is not asked once the cycle has
 *   stopped.
 *
 * With a header resolver or `beforeDispatch` installed:
 * - R9: an interceptor gives nothing to use, then the write that defers the item fails. Nothing
 *   was sent and nothing recorded, so the settle gives the row back as it was.
 * - R10: the cycle is cancelled while an interceptor is asked, or as its answer lands. The wait
 *   ends at once, nothing is sent, and no deferral is written: the row comes back as it was,
 *   with no event of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SettleMatrixTest {

  private lateinit var db: SyncDatabase
  private lateinit var storage: SyncQueueStorage
  private lateinit var emitter: SyncEventEmitter
  private lateinit var server: MockWebServer
  private lateinit var wake: RetryWake
  private val clock = MovingClock()

  /** When each test's first cycle starts, and so when its first attempt starts. */
  private val t0 = clock.now()

  /** The delays [wake] gave the timer and the kick, in order. */
  private val timerDelays: MutableList<Long> = Collections.synchronizedList(mutableListOf())
  private val kickDelays: MutableList<Long> = Collections.synchronizedList(mutableListOf())

  /** How each cycle ended, in order. */
  private val endings: MutableList<CycleEnding> = Collections.synchronizedList(mutableListOf())

  /** The JS callbacks the cycles consult, and fake JS that answers for them. */
  private val jsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val callbacks = JsCallbacks(scope = jsScope, notifyContext = Dispatchers.Default, log = { _, _ -> })
  private val js = FakeJs(callbacks.gateway, jsScope)
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
    val timer = mockk<RetryTimer>(relaxed = true)
    every { timer.arm(any()) } answers { timerDelays += firstArg<Long>() }
    wake = RetryWake(
      storage = storage,
      timer = timer,
      scheduleKick = { kickDelays += it },
      isHalted = { false },
      isEngineLive = { true },
      clock = clock::now,
    )
  }

  @After
  fun tearDown() {
    jsScope.cancel()
    server.shutdown()
    db.close()
  }

  /* -------------------------------- C1 -------------------------------- */

  @Test
  fun `R1 C1 a 2xx whose removal fails is delivered by the settle`() = runTest {
    answer(200)
    seed(ITEM)
    val failing = spyk(storage)
    coEvery { failing.markSucceeded(any()) } answers { failWrite() }
    val events = recordEvents()

    val thrown = flushOnce(dispatcher(queue = failing))

    assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
    assertThat(db.syncItemDao().findById(ITEM)).isNull()
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(events.seen())
      .containsExactly(
        Seen(SyncEventType.SYNC_STARTED),
        Seen(SyncEventType.ITEM_SUCCEEDED, ITEM, statusCode = 200, attempt = 2),
        Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
      )
      .inOrder()
    assertThat(endings).containsExactly(CycleEnding.FAILED)
    // Nothing is left to wake for, and the failure hold has started.
    assertNoWake()
    assertThat(nextArmForADueRow()).isEqualTo(FIRST_HOLD_MS)
    // Never sent again, even once any cooldown has passed.
    db.syncItemDao().deleteById(WITNESS)
    clock.advance(3_600_000L)
    dispatcher(armsWake = false).flush()
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `R2 C1 a retryable answer whose write fails is retried by the settle with its code`() =
    runTest {
      answer(503)
      seed(ITEM)
      val failing = spyk(storage)
      coEvery { failing.markRetry(any(), any(), any(), any(), any()) } answers { failWrite() }
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(queue = failing))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      val retry = row(ITEM)
      assertThat(retry.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(retry.attempts).isEqualTo(2)
      assertThat(retry.lastAttemptAt).isEqualTo(t0)
      assertThat(retry.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      // The policy's backoff for attempt 2, from the attempt's end: not from the settle.
      assertThat(retry.nextRetryAt).isEqualTo(t0 + ATTEMPT_MS + 2 * BASE_DELAY_MS)
      assertThat(retry.nextRetryAt).isAtMost(RetryPolicyEvaluator.MAX_DEADLINE_MS)
      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_RETRYING, ITEM, SyncErrorCode.SERVER_ERROR, 503, 2),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      // The later of the cooldown and the failure hold.
      assertWake(maxOf(retry.nextRetryAt - clock.now(), FIRST_HOLD_MS))
      assertThat(timerDelays.single()).isEqualTo(FIRST_HOLD_MS)
    }

  @Test
  fun `R2b C1 a retryable answer on the last attempt whose dead letter fails is exhausted`() =
    runTest {
      answer(503)
      seed(ITEM, attempts = 2)
      val failing = spyk(storage)
      coEvery {
        failing.moveToDeadLetters(any(), any(), any(), any(), any(), any())
      } answers { failWrite() }
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(queue = failing, maxAttempts = 3))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      val dead = deadLetter(ITEM)
      assertThat(dead.attempts).isEqualTo(3)
      assertThat(dead.lastAttemptAt).isEqualTo(t0)
      assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
      assertThat(dead.lastErrorMessage).isEqualTo("HTTP 503 Server Error")
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_FAILED, ITEM, SyncErrorCode.MAX_ATTEMPTS_EXCEEDED, 503, 3),
          Seen(SyncEventType.ITEM_DEAD_LETTERED, ITEM, SyncErrorCode.DEAD_LETTERED),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(events[2].metadata)
        .containsExactly(SyncDispatcher.METADATA_LAST_ERROR_CODE, "MAX_ATTEMPTS_EXCEEDED")
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      assertNoWake()
      assertThat(nextArmForADueRow()).isEqualTo(FIRST_HOLD_MS)
    }

  @Test
  fun `R2t C1 a call timeout whose write fails is retried by the settle as a TIMEOUT`() =
    runTest {
      timeOut()
      seed(ITEM)
      val failing = spyk(storage)
      coEvery { failing.markRetry(any(), any(), any(), any(), any()) } answers { failWrite() }
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(queue = failing, requestTimeoutMs = CALL_TIMEOUT_MS))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      val retry = row(ITEM)
      assertThat(retry.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(retry.attempts).isEqualTo(2)
      assertThat(retry.lastAttemptAt).isEqualTo(t0)
      assertThat(retry.lastErrorCode).isEqualTo(SyncErrorCode.TIMEOUT.name)
      assertThat(retry.nextRetryAt).isEqualTo(t0 + ATTEMPT_MS + 2 * BASE_DELAY_MS)
      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_RETRYING, ITEM, SyncErrorCode.TIMEOUT, attempt = 2),
          // The cycle's own code: what ended it was a local write, not the attempt.
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      assertWake(FIRST_HOLD_MS)
    }

  @Test
  fun `R2t C1 at the budget a call timeout whose dead letter fails is exhausted`() = runTest {
    timeOut()
    seed(ITEM, attempts = 2)
    val failing = spyk(storage)
    coEvery {
      failing.moveToDeadLetters(any(), any(), any(), any(), any(), any())
    } answers { failWrite() }
    val events = recordEvents()

    val thrown = flushOnce(
      dispatcher(queue = failing, maxAttempts = 3, requestTimeoutMs = CALL_TIMEOUT_MS),
    )

    assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
    val dead = deadLetter(ITEM)
    assertThat(dead.attempts).isEqualTo(3)
    assertThat(dead.lastAttemptAt).isEqualTo(t0)
    assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
    // The attempt's own failure, OkHttp's call timeout, not the write that ended the cycle.
    assertThat(dead.lastErrorMessage).isEqualTo("timeout")
    assertThat(events.seen())
      .containsExactly(
        Seen(SyncEventType.SYNC_STARTED),
        Seen(SyncEventType.ITEM_FAILED, ITEM, SyncErrorCode.MAX_ATTEMPTS_EXCEEDED, attempt = 3),
        Seen(SyncEventType.ITEM_DEAD_LETTERED, ITEM, SyncErrorCode.DEAD_LETTERED),
        Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
      )
      .inOrder()
    assertThat(events[2].metadata)
      .containsExactly(SyncDispatcher.METADATA_LAST_ERROR_CODE, "MAX_ATTEMPTS_EXCEEDED")
    assertThat(endings).containsExactly(CycleEnding.FAILED)
    assertNoWake()
    assertThat(nextArmForADueRow()).isEqualTo(FIRST_HOLD_MS)
  }

  @Test
  fun `R3 C1 a permanent answer whose dead letter fails is dead-lettered by the settle`() =
    runTest {
      answer(404)
      seed(ITEM)
      val failing = spyk(storage)
      coEvery {
        failing.moveToDeadLetters(any(), any(), any(), any(), any(), any())
      } answers { failWrite() }
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(queue = failing))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      val dead = deadLetter(ITEM)
      assertThat(dead.attempts).isEqualTo(2)
      assertThat(dead.lastAttemptAt).isEqualTo(t0)
      assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      assertThat(dead.lastErrorMessage).isEqualTo("HTTP 404 Client Error")
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_FAILED, ITEM, SyncErrorCode.SERVER_ERROR, 404, 2),
          Seen(SyncEventType.ITEM_DEAD_LETTERED, ITEM, SyncErrorCode.DEAD_LETTERED),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(events[2].metadata)
        .containsExactly(SyncDispatcher.METADATA_LAST_ERROR_CODE, "SERVER_ERROR")
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      assertNoWake()
      assertThat(nextArmForADueRow()).isEqualTo(FIRST_HOLD_MS)
    }

  @Test
  fun `R4 C1 a rejected restored credential whose hold write fails is held by the settle`() =
    runTest {
      answer(401)
      seed(ITEM, createdAt = 1L)
      val rest = seed(REST, createdAt = 2L)
      val failing = spyk(storage)
      coEvery { failing.markRetry(any(), any(), any(), any(), any()) } answers { failWrite() }
      val events = recordEvents()

      val thrown =
        flushOnce(dispatcher(queue = failing, credentials = CredentialPolicy.PERSISTED))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      // The cycle stopped after its first request.
      assertThat(server.requestCount).isEqualTo(1)
      val held = row(ITEM)
      assertThat(held.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      // No attempt spent.
      assertThat(held.attempts).isEqualTo(1)
      assertThat(held.lastAttemptAt).isEqualTo(t0)
      assertThat(held.lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
      // The hold the normal path writes: at least 60 s from the attempt's end.
      assertThat(held.nextRetryAt)
        .isEqualTo(t0 + ATTEMPT_MS + SyncDispatcher.MIN_CREDENTIAL_HOLD_MS)
      assertThat(row(REST)).isEqualTo(rest)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_RETRYING, ITEM, SyncErrorCode.UNAUTHORIZED, 401, 2),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      // The rest is due, but the wake waits for the hold, which outlasts the failure hold.
      assertWake(held.nextRetryAt - clock.now())
      assertThat(timerDelays.single()).isGreaterThan(FIRST_HOLD_MS)
    }

  @Test
  fun `R5 C1 a send that raised before any answer counts the attempt with NETWORK_ERROR`() =
    runTest {
      answer(200)
      seed(ITEM)
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(client = raisingAfterTheSend()))

      assertThat(thrown).isInstanceOf(NoClassDefFoundError::class.java)
      assertThat(server.requestCount).isEqualTo(1)
      val retry = row(ITEM)
      assertThat(retry.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(retry.attempts).isEqualTo(2)
      assertThat(retry.lastAttemptAt).isEqualTo(t0)
      assertThat(retry.lastErrorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR.name)
      // The backoff for attempt 2, from the settle's clock: for a lone item, the attempt's end.
      assertThat(retry.nextRetryAt).isEqualTo(t0 + ATTEMPT_MS + 2 * BASE_DELAY_MS)
      assertThat(retry.nextRetryAt).isAtMost(RetryPolicyEvaluator.MAX_DEADLINE_MS)
      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_RETRYING, ITEM, SyncErrorCode.NETWORK_ERROR, attempt = 2),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      assertWake(FIRST_HOLD_MS)
    }

  @Test
  fun `R5 C1 at the budget a send that raised before any answer is exhausted`() = runTest {
    answer(200)
    seed(ITEM, attempts = 2)
    val events = recordEvents()

    val thrown = flushOnce(dispatcher(maxAttempts = 3, client = raisingAfterTheSend()))

    assertThat(thrown).isInstanceOf(NoClassDefFoundError::class.java)
    val dead = deadLetter(ITEM)
    assertThat(dead.attempts).isEqualTo(3)
    assertThat(dead.lastAttemptAt).isEqualTo(t0)
    assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
    assertThat(dead.lastErrorMessage).isEqualTo(RAISED)
    assertThat(events.seen())
      .containsExactly(
        Seen(SyncEventType.SYNC_STARTED),
        Seen(SyncEventType.ITEM_FAILED, ITEM, SyncErrorCode.MAX_ATTEMPTS_EXCEEDED, attempt = 3),
        Seen(SyncEventType.ITEM_DEAD_LETTERED, ITEM, SyncErrorCode.DEAD_LETTERED),
        Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
      )
      .inOrder()
    assertThat(endings).containsExactly(CycleEnding.FAILED)
    assertNoWake()
    assertThat(nextArmForADueRow()).isEqualTo(FIRST_HOLD_MS)
  }

  @Test
  fun `R6 C1 a claimed row never sent comes back as it was and its wake waits the hold`() =
    runTest {
      val before = seed(ITEM)
      val failing = spyk(storage)
      coEvery { failing.isQueued(any()) } throws locked()
      val events = recordEvents()
      val d = dispatcher(queue = failing)

      repeat(2) {
        assertThat(flushOnce(d)).isInstanceOf(SQLiteDatabaseLockedException::class.java)
        assertThat(row(ITEM)).isEqualTo(before)
      }

      assertThat(server.requestCount).isEqualTo(0)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.FAILED, CycleEnding.FAILED)
      // The row is due, yet each wake waits for the next step of the failure hold, never 0.
      assertThat(timerDelays).containsExactly(FIRST_HOLD_MS, SECOND_HOLD_MS).inOrder()
      assertThat(kickDelays).containsExactly(FIRST_HOLD_MS, SECOND_HOLD_MS).inOrder()
    }

  /**
   * The Android side of the documented difference: a request that already started finishes and
   * records its own answer, and the attempt counts. A claimed row not yet sent comes back as it
   * was. No failure hold starts, so the due row's wake fires at once.
   */
  @Test
  fun `R7 C1 a cancelled cycle keeps a started request's answer and gives an unsent row back`() =
    runTest {
      val release = CompletableDeferred<Unit>()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          runBlocking { withTimeout(20_000L) { release.await() } }
          clock.advance(ATTEMPT_MS)
          return MockResponse().setResponseCode(503)
        }
      }
      seed(ITEM, createdAt = 1L)
      val unsent = seed(REST, createdAt = 2L)
      val events = recordEvents()
      // Restored headers send one item first: the other is claimed and waits for its answer.
      val d = dispatcher(credentials = CredentialPolicy.PERSISTED)

      val cycle = launch(Dispatchers.IO) { d.flush() }
      assertThat(server.takeRequest(20, TimeUnit.SECONDS)?.path).isEqualTo("/$ITEM")
      cycle.cancel()
      release.complete(Unit)
      cycle.join()

      assertThat(cycle.isCancelled).isTrue()
      assertThat(server.requestCount).isEqualTo(1)
      val started = row(ITEM)
      assertThat(started.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(started.attempts).isEqualTo(2)
      assertThat(started.lastAttemptAt).isEqualTo(t0)
      assertThat(started.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      assertThat(started.nextRetryAt).isEqualTo(t0 + ATTEMPT_MS + 2 * BASE_DELAY_MS)
      assertThat(row(REST)).isEqualTo(unsent)
      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_RETRYING, ITEM, SyncErrorCode.SERVER_ERROR, 503, 2),
          Seen(SyncEventType.SYNC_FAILED),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.CANCELLED)
      assertWake(0L)
    }

  /** A cancellation neither starts the failure hold nor lifts or advances a running one. */
  @Test
  fun `R7 C1 a cancelled cycle leaves a running failure hold as it was`() = runTest {
    val before = seed(ITEM)
    val failing = spyk(storage)
    coEvery { failing.isQueued(any()) } throws locked()
    val toFail = dispatcher(queue = failing)
    val committed = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val toCancel = dispatcher(queue = heldClaimStorage(committed, release))

    flushOnce(toFail)
    cancelDuringTheClaim(toCancel, committed, release)
    flushOnce(toFail)

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row(ITEM)).isEqualTo(before)
    assertThat(endings)
      .containsExactly(CycleEnding.FAILED, CycleEnding.CANCELLED, CycleEnding.FAILED)
      .inOrder()
    // The hold the first failure started, then the step after it: the cancellation kept both.
    assertThat(timerDelays).containsExactly(FIRST_HOLD_MS, FIRST_HOLD_MS, SECOND_HOLD_MS).inOrder()
    assertThat(kickDelays).containsExactly(FIRST_HOLD_MS, FIRST_HOLD_MS, SECOND_HOLD_MS).inOrder()
  }

  /* -------------------------------- C2 -------------------------------- */

  @Test
  fun `R1 C2 a 2xx whose removal and settle both fail waits in flight for recovery`() = runTest {
    answer(200)
    val before = seed(ITEM)
    val failing = spyk(storage)
    coEvery { failing.markSucceeded(any()) } answers { failWrite() }
    settleFails(failing)
    val events = recordEvents()

    val thrown = flushOnce(dispatcher(queue = failing))

    assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
    assertWaitsInFlight(before, events, SyncErrorCode.NETWORK_ERROR, CycleEnding.FAILED)
  }

  @Test
  fun `R2 C2 a retryable answer whose write and settle both fail waits in flight`() = runTest {
    answer(503)
    val before = seed(ITEM)
    val failing = spyk(storage)
    coEvery { failing.markRetry(any(), any(), any(), any(), any()) } answers { failWrite() }
    settleFails(failing)
    val events = recordEvents()

    val thrown = flushOnce(dispatcher(queue = failing))

    assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
    assertWaitsInFlight(before, events, SyncErrorCode.NETWORK_ERROR, CycleEnding.FAILED)
  }

  @Test
  fun `R2b C2 a last-attempt retryable answer whose writes all fail waits in flight`() = runTest {
    answer(503)
    val before = seed(ITEM, attempts = 2)
    val failing = spyk(storage)
    coEvery {
      failing.moveToDeadLetters(any(), any(), any(), any(), any(), any())
    } answers { failWrite() }
    settleFails(failing)
    val events = recordEvents()

    val thrown = flushOnce(dispatcher(queue = failing, maxAttempts = 3))

    assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
    assertWaitsInFlight(before, events, SyncErrorCode.NETWORK_ERROR, CycleEnding.FAILED)
  }

  @Test
  fun `R2t C2 a call timeout whose write and settle both fail waits in flight`() = runTest {
    timeOut()
    val before = seed(ITEM)
    val failing = spyk(storage)
    coEvery { failing.markRetry(any(), any(), any(), any(), any()) } answers { failWrite() }
    settleFails(failing)
    val events = recordEvents()

    val thrown = flushOnce(dispatcher(queue = failing, requestTimeoutMs = CALL_TIMEOUT_MS))

    assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
    assertWaitsInFlight(before, events, SyncErrorCode.NETWORK_ERROR, CycleEnding.FAILED)
  }

  @Test
  fun `R3 C2 a permanent answer whose dead letter and settle both fail waits in flight`() =
    runTest {
      answer(404)
      val before = seed(ITEM)
      val failing = spyk(storage)
      coEvery {
        failing.moveToDeadLetters(any(), any(), any(), any(), any(), any())
      } answers { failWrite() }
      settleFails(failing)
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(queue = failing))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      assertWaitsInFlight(before, events, SyncErrorCode.NETWORK_ERROR, CycleEnding.FAILED)
    }

  /**
   * Both rows wait in flight. The credential hold is not written, but the cycle still reports it,
   * so the next automatic arm waits for it rather than for the failure hold alone.
   */
  @Test
  fun `R4 C2 a rejected credential whose hold write and settle both fail waits in flight`() =
    runTest {
      answer(401)
      val before = seed(ITEM, createdAt = 1L)
      val rest = seed(REST, createdAt = 2L)
      val failing = spyk(storage)
      coEvery { failing.markRetry(any(), any(), any(), any(), any()) } answers { failWrite() }
      settleFails(failing)
      val events = recordEvents()

      val thrown =
        flushOnce(dispatcher(queue = failing, credentials = CredentialPolicy.PERSISTED))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      assertThat(server.requestCount).isEqualTo(1)
      assertThat(row(ITEM)).isEqualTo(before.copy(status = SyncItemEntity.STATUS_IN_FLIGHT))
      assertThat(row(REST)).isEqualTo(rest.copy(status = SyncItemEntity.STATUS_IN_FLIGHT))
      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      assertNoWake()
      val holdEnd = t0 + ATTEMPT_MS + SyncDispatcher.MIN_CREDENTIAL_HOLD_MS
      assertThat(nextArmForADueRow()).isEqualTo(holdEnd - clock.now())
      // Recovery gives both back as they were before the claim.
      assertThat(dispatcher(armsWake = false).resetInFlight()).isEqualTo(2)
      assertThat(row(ITEM)).isEqualTo(before)
      assertThat(row(REST)).isEqualTo(rest)
    }

  @Test
  fun `R5 C2 a send that raised and whose settle fails waits in flight uncounted`() = runTest {
    answer(200)
    val before = seed(ITEM)
    val failing = spyk(storage)
    settleFails(failing)
    val events = recordEvents()

    val thrown = flushOnce(dispatcher(queue = failing, client = raisingAfterTheSend()))

    assertThat(thrown).isInstanceOf(NoClassDefFoundError::class.java)
    assertThat(server.requestCount).isEqualTo(1)
    assertWaitsInFlight(before, events, SyncErrorCode.NETWORK_ERROR, CycleEnding.FAILED)
  }

  @Test
  fun `R6 C2 a claimed row never sent whose release fails waits in flight`() = runTest {
    val before = seed(ITEM)
    val failing = spyk(storage)
    coEvery { failing.isQueued(any()) } throws locked()
    settleFails(failing)
    val events = recordEvents()

    val thrown = flushOnce(dispatcher(queue = failing))

    assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
    assertThat(server.requestCount).isEqualTo(0)
    assertWaitsInFlight(before, events, SyncErrorCode.NETWORK_ERROR, CycleEnding.FAILED)
  }

  @Test
  fun `R7 C2 a cancelled cycle whose release fails waits in flight`() = runTest {
    val before = seed(ITEM)
    val committed = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val held = spyk(heldClaimStorage(committed, release))
    settleFails(held)
    val events = recordEvents()

    cancelDuringTheClaim(dispatcher(queue = held), committed, release)

    assertThat(server.requestCount).isEqualTo(0)
    assertWaitsInFlight(before, events, null, CycleEnding.CANCELLED)
  }

  /* ------------------------------- the retry decider ------------------------------- */

  @Test
  fun `decider_abortSettle_appliesRecordedOutcome R8 C1 a decided dead letter whose move fails is dead-lettered by the settle`() =
    runTest {
      answer(503)
      seed(ITEM)
      val decider = installDecider(RetryAction.DEAD_LETTER)
      val failing = spyk(storage)
      coEvery {
        failing.moveToDeadLetters(any(), any(), any(), any(), any(), any())
      } answers { failWrite() }
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(queue = failing))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      val dead = deadLetter(ITEM)
      assertThat(dead.attempts).isEqualTo(2)
      assertThat(dead.lastAttemptAt).isEqualTo(t0)
      assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.RETRY_DECIDER_REJECTED.name)
      // The attempt's own failure, not the decision.
      assertThat(dead.lastErrorMessage).isEqualTo("HTTP 503 Server Error")
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_FAILED, ITEM, SyncErrorCode.RETRY_DECIDER_REJECTED, 503, 2),
          Seen(SyncEventType.ITEM_DEAD_LETTERED, ITEM, SyncErrorCode.DEAD_LETTERED),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(events[2].metadata)
        .containsExactly(SyncDispatcher.METADATA_LAST_ERROR_CODE, "RETRY_DECIDER_REJECTED")
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      assertNoWake()
      assertThat(nextArmForADueRow()).isEqualTo(FIRST_HOLD_MS)
      // The settle applied what was decided: JS was asked once, by the dispatch.
      assertThat(decider.calls).hasSize(1)
    }

  @Test
  fun `decider_abortSettle_appliesRecordedOutcome R8 C1 a decided drop whose delete fails is dropped by the settle`() =
    runTest {
      answer(503)
      seed(ITEM)
      val decider = installDecider(RetryAction.DROP)
      val failing = spyk(storage)
      coEvery { failing.markDropped(any()) } answers { failWrite() }
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(queue = failing))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      assertThat(db.syncItemDao().findById(ITEM)).isNull()
      assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
      // Once: the drop writes before it reports, so its failed delete had reported nothing.
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_FAILED, ITEM, SyncErrorCode.RETRY_DECIDER_REJECTED, 503, 2),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      assertNoWake()
      assertThat(nextArmForADueRow()).isEqualTo(FIRST_HOLD_MS)
      assertThat(decider.calls).hasSize(1)
    }

  @Test
  fun `decider_abortSettle_appliesRecordedOutcome R8 C1 a decided retry whose write fails waits the decided delay`() =
    runTest {
      answer(503)
      seed(ITEM)
      val decider = installDecider(RetryAction.RETRY, delayMs = 12_345.0, decideMs = DECIDE_MS)
      val failing = spyk(storage)
      coEvery { failing.markRetry(any(), any(), any(), any(), any()) } answers { failWrite() }
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(queue = failing))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      val retry = row(ITEM)
      assertThat(retry.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(retry.attempts).isEqualTo(2)
      assertThat(retry.lastAttemptAt).isEqualTo(t0)
      assertThat(retry.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      // The decided delay, from the decision: not the policy's, and not from the settle.
      assertThat(retry.nextRetryAt).isEqualTo(t0 + ATTEMPT_MS + DECIDE_MS + 12_345L)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_RETRYING, ITEM, SyncErrorCode.SERVER_ERROR, 503, 2),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      assertWake(maxOf(retry.nextRetryAt - clock.now(), FIRST_HOLD_MS))
      assertThat(decider.calls).hasSize(1)
    }

  @Test
  fun `decider_abortSettle_appliesRecordedOutcome R8 C1 with nothing decided, an Error while asking, the settle applies the policy's retry`() =
    runTest {
      answer(503)
      seed(ITEM)
      val decider = js.function<RetryDeciderContext> { _, _ -> throw NoClassDefFoundError(RAISED) }
      assertThat(hybrid.setRetryDecider(decider)).isTrue()
      val events = recordEvents()

      val thrown = flushOnce(dispatcher())

      assertThat(thrown).isInstanceOf(NoClassDefFoundError::class.java)
      val retry = row(ITEM)
      assertThat(retry.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(retry.attempts).isEqualTo(2)
      assertThat(retry.lastAttemptAt).isEqualTo(t0)
      assertThat(retry.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      // Remembered before the decider was asked: the policy's delay, from the attempt's end.
      assertThat(retry.nextRetryAt).isEqualTo(t0 + ATTEMPT_MS + 2 * BASE_DELAY_MS)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_RETRYING, ITEM, SyncErrorCode.SERVER_ERROR, 503, 2),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      assertWake(FIRST_HOLD_MS)
      assertThat(decider.calls).hasSize(1)
    }

  @Test
  fun `decider_abortSettle_appliesRecordedOutcome R8 C2 a decided dead letter whose move and settle both fail waits in flight`() =
    runTest {
      answer(503)
      val before = seed(ITEM)
      installDecider(RetryAction.DEAD_LETTER)
      val failing = spyk(storage)
      coEvery {
        failing.moveToDeadLetters(any(), any(), any(), any(), any(), any())
      } answers { failWrite() }
      settleFails(failing)
      val events = recordEvents()

      flushOnce(dispatcher(queue = failing))

      assertWaitsInFlight(before, events, SyncErrorCode.NETWORK_ERROR, CycleEnding.FAILED)
    }

  @Test
  fun `decider_stopMidWait_appliesPolicy R11 TRUSTED, a cancelled cycle ends the wait at once and writes the policy's retry`() =
    runTest {
      answer(503)
      seed(ITEM)
      val decider = neverAnswering()
      val events = recordEvents()
      val d = dispatcher(callbackTimeoutMs = LONG_CALLBACK_TIMEOUT_MS)

      val cycle = launch(Dispatchers.IO) { d.flush() }
      runBlocking { withTimeout(20_000L) { decider.asked.await() } }
      val started = System.nanoTime()
      cycle.cancel()
      cycle.join()

      // Cut short: the 60 s wait did not run out.
      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(cycle.isCancelled).isTrue()
      val retried = row(ITEM)
      assertThat(retried.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
      assertThat(retried.attempts).isEqualTo(2)
      assertThat(retried.lastAttemptAt).isEqualTo(t0)
      assertThat(retried.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      assertThat(retried.nextRetryAt).isEqualTo(t0 + ATTEMPT_MS + 2 * BASE_DELAY_MS)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_RETRYING, ITEM, SyncErrorCode.SERVER_ERROR, 503, 2),
          Seen(SyncEventType.SYNC_FAILED),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.CANCELLED)
      assertWake(retried.nextRetryAt - clock.now())
    }

  @Test
  fun `decider_stopMidWait_appliesPolicy R11 PERSISTED, a cancelled cycle ends the leading item's wait at once`() =
    runTest {
      answer(503)
      seed(ITEM, createdAt = 1L)
      val unsent = seed(REST, createdAt = 2L)
      val decider = neverAnswering()
      val events = recordEvents()
      // Restored headers: the leading item is dispatched in the cycle's own coroutine.
      val d = dispatcher(credentials = CredentialPolicy.PERSISTED, callbackTimeoutMs = LONG_CALLBACK_TIMEOUT_MS)

      val cycle = launch(Dispatchers.IO) { d.flush() }
      runBlocking { withTimeout(20_000L) { decider.asked.await() } }
      val started = System.nanoTime()
      cycle.cancel()
      cycle.join()

      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      val retried = row(ITEM)
      assertThat(retried.attempts).isEqualTo(2)
      assertThat(retried.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      assertThat(retried.nextRetryAt).isEqualTo(t0 + ATTEMPT_MS + 2 * BASE_DELAY_MS)
      assertThat(row(REST)).isEqualTo(unsent)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_RETRYING, ITEM, SyncErrorCode.SERVER_ERROR, 503, 2),
          Seen(SyncEventType.SYNC_FAILED),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.CANCELLED)
      // The unsent row is due.
      assertWake(0L)
      assertThat(decider.calls).hasSize(1)
    }

  @Test
  fun `decider_stopMidWait_appliesPolicy R11 a cycle cancelled during the request never asks the decider`() =
    runTest {
      val release = CompletableDeferred<Unit>()
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          runBlocking { withTimeout(20_000L) { release.await() } }
          clock.advance(ATTEMPT_MS)
          return MockResponse().setResponseCode(503)
        }
      }
      seed(ITEM)
      val decider = installDecider(RetryAction.DEAD_LETTER)
      val events = recordEvents()
      val d = dispatcher()

      val cycle = launch(Dispatchers.IO) { d.flush() }
      assertThat(server.takeRequest(20, TimeUnit.SECONDS)?.path).isEqualTo("/$ITEM")
      cycle.cancel()
      release.complete(Unit)
      cycle.join()

      assertThat(decider.calls).isEmpty()
      val retried = row(ITEM)
      assertThat(retried.attempts).isEqualTo(2)
      assertThat(retried.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
      assertThat(retried.nextRetryAt).isEqualTo(t0 + ATTEMPT_MS + 2 * BASE_DELAY_MS)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.ITEM_RETRYING, ITEM, SyncErrorCode.SERVER_ERROR, 503, 2),
          Seen(SyncEventType.SYNC_FAILED),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.CANCELLED)
    }

  /* --------------------------- the dispatch interceptors --------------------------- */

  @Test
  fun `interceptor_deferWriteFails_rowComesBackAsItWas R9 C1 a deferral whose write fails is given back and its wake waits the hold`() =
    runTest {
      answer(200)
      val before = seed(ITEM)
      val failing = spyk(storage)
      coEvery { failing.markDeferred(any(), any(), any()) } answers { failWrite() }
      installResolver(FakeJs.failed("no token"))
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(queue = failing))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      assertThat(server.requestCount).isEqualTo(0)
      // The cooldown and the code are lost for this cycle only: nothing else changed.
      assertThat(row(ITEM)).isEqualTo(before)
      assertThat(events.seen())
        .containsExactly(
          Seen(SyncEventType.SYNC_STARTED),
          Seen(SyncEventType.SYNC_FAILED, errorCode = SyncErrorCode.NETWORK_ERROR),
        )
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.FAILED)
      // The row is due, yet the wake waits for the failure hold, so the failure cannot loop.
      assertWake(FIRST_HOLD_MS)
    }

  @Test
  fun `interceptor_deferWriteFails_rowComesBackAsItWas R9 C2 a deferral whose write and release both fail waits in flight`() =
    runTest {
      answer(200)
      val before = seed(ITEM)
      val failing = spyk(storage)
      coEvery { failing.markDeferred(any(), any(), any()) } answers { failWrite() }
      settleFails(failing)
      installResolver(FakeJs.failed("no token"))
      val events = recordEvents()

      val thrown = flushOnce(dispatcher(queue = failing))

      assertThat(thrown).isInstanceOf(SQLiteDatabaseLockedException::class.java)
      assertThat(server.requestCount).isEqualTo(0)
      assertWaitsInFlight(before, events, SyncErrorCode.NETWORK_ERROR, CycleEnding.FAILED)
    }

  @Test
  fun `interceptor_stopMidWait_sendsNothing R10 C1 TRUSTED, a cycle cancelled during the resolver's wait gives the row back as it was`() =
    runTest {
      answer(200)
      val before = seed(ITEM)
      val resolver = neverAnsweringResolver()
      val hook = js.function<BeforeDispatchContext> { requestId, _ ->
        answer(requestId, FakeJs.before(BeforeDispatchAction.PROCEED))
      }
      assertThat(hybrid.setBeforeDispatch(hook)).isTrue()
      val events = recordEvents()
      val d = dispatcher(callbackTimeoutMs = LONG_CALLBACK_TIMEOUT_MS)

      val cycle = launch(Dispatchers.IO) { d.flush() }
      runBlocking { withTimeout(20_000L) { resolver.asked.await() } }
      val started = System.nanoTime()
      cycle.cancel()
      cycle.join()

      // Cut short: the 60 s wait did not run out. Not a failure of the resolver: no deferral.
      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(cycle.isCancelled).isTrue()
      assertThat(server.requestCount).isEqualTo(0)
      assertThat(row(ITEM)).isEqualTo(before)
      assertThat(hook.calls).isEmpty()
      assertThat(events.seen())
        .containsExactly(Seen(SyncEventType.SYNC_STARTED), Seen(SyncEventType.SYNC_FAILED))
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.CANCELLED)
      assertWake(0L)
    }

  @Test
  fun `interceptor_stopMidWait_sendsNothing R10 C1 a PROCEED that lands as the cycle stops sends nothing`() =
    runTest {
      answer(200)
      val before = seed(ITEM)
      val running = CompletableDeferred<Job>()
      val hook = js.function<BeforeDispatchContext> { requestId, _ ->
        // The answer lands first, then the cycle stops, before the request can go out.
        answer(requestId, FakeJs.before(BeforeDispatchAction.PROCEED))
        runBlocking { withTimeout(20_000L) { running.await() } }.cancel()
      }
      assertThat(hybrid.setBeforeDispatch(hook)).isTrue()
      val events = recordEvents()
      val d = dispatcher(callbackTimeoutMs = LONG_CALLBACK_TIMEOUT_MS)

      val cycle = launch(Dispatchers.IO) { d.flush() }
      running.complete(cycle)
      cycle.join()

      assertThat(cycle.isCancelled).isTrue()
      assertThat(hook.calls).hasSize(1)
      assertThat(server.requestCount).isEqualTo(0)
      assertThat(row(ITEM)).isEqualTo(before)
      assertThat(events.seen())
        .containsExactly(Seen(SyncEventType.SYNC_STARTED), Seen(SyncEventType.SYNC_FAILED))
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.CANCELLED)
      assertWake(0L)
    }

  @Test
  fun `interceptor_stopMidWait_sendsNothing R10 C1 PERSISTED, the leading item's wait ends at once and the batch goes back`() =
    runTest {
      answer(200)
      val first = seed(ITEM, createdAt = 1L)
      val rest = seed(REST, createdAt = 2L)
      val resolver = neverAnsweringResolver()
      val events = recordEvents()
      // Restored headers: the leading item is dispatched in the cycle's own coroutine.
      val d = dispatcher(credentials = CredentialPolicy.PERSISTED, callbackTimeoutMs = LONG_CALLBACK_TIMEOUT_MS)

      val cycle = launch(Dispatchers.IO) { d.flush() }
      runBlocking { withTimeout(20_000L) { resolver.asked.await() } }
      val started = System.nanoTime()
      cycle.cancel()
      cycle.join()

      assertThat(elapsedMs(started)).isLessThan(AT_ONCE_MS)
      assertThat(server.requestCount).isEqualTo(0)
      assertThat(row(ITEM)).isEqualTo(first)
      assertThat(row(REST)).isEqualTo(rest)
      assertThat(resolver.calls).hasSize(1)
      assertThat(events.seen())
        .containsExactly(Seen(SyncEventType.SYNC_STARTED), Seen(SyncEventType.SYNC_FAILED))
        .inOrder()
      assertThat(endings).containsExactly(CycleEnding.CANCELLED)
      assertWake(0L)
    }

  @Test
  fun `interceptor_stopMidWait_sendsNothing R10 C2 a cycle cancelled during the wait whose release fails waits in flight`() =
    runTest {
      answer(200)
      val before = seed(ITEM)
      val failing = spyk(storage)
      settleFails(failing)
      val resolver = neverAnsweringResolver()
      val events = recordEvents()
      val d = dispatcher(queue = failing, callbackTimeoutMs = LONG_CALLBACK_TIMEOUT_MS)

      val cycle = launch(Dispatchers.IO) { d.flush() }
      runBlocking { withTimeout(20_000L) { resolver.asked.await() } }
      cycle.cancel()
      cycle.join()

      assertThat(server.requestCount).isEqualTo(0)
      assertWaitsInFlight(before, events, null, CycleEnding.CANCELLED)
    }

  /* ---------------------------- the deadline ceiling ---------------------------- */

  @Test
  fun `R2 C1 ceiling a retry the settle writes never names a deadline past a JS Date`() =
    runTest {
      answer(503)
      seed(ITEM)
      val failing = spyk(storage)
      coEvery { failing.markRetry(any(), any(), any(), any(), any()) } answers { failWrite() }

      flushOnce(dispatcher(queue = failing, baseDelayMs = Long.MAX_VALUE))

      assertThat(row(ITEM).attempts).isEqualTo(2)
      assertThat(row(ITEM).nextRetryAt).isEqualTo(RetryPolicyEvaluator.MAX_DEADLINE_MS)
      assertWake(RetryPolicyEvaluator.MAX_DEADLINE_MS - clock.now())
    }

  @Test
  fun `R4 C1 ceiling a hold the settle writes never names a deadline past a JS Date`() =
    runTest {
      answer(401)
      seed(ITEM)
      val failing = spyk(storage)
      coEvery { failing.markRetry(any(), any(), any(), any(), any()) } answers { failWrite() }

      flushOnce(
        dispatcher(
          queue = failing,
          credentials = CredentialPolicy.PERSISTED,
          baseDelayMs = Long.MAX_VALUE,
        ),
      )

      assertThat(row(ITEM).attempts).isEqualTo(1)
      assertThat(row(ITEM).nextRetryAt).isEqualTo(RetryPolicyEvaluator.MAX_DEADLINE_MS)
      assertWake(RetryPolicyEvaluator.MAX_DEADLINE_MS - clock.now())
    }

  @Test
  fun `R5 C1 ceiling a retry the settle computes never names a deadline past a JS Date`() =
    runTest {
      answer(200)
      seed(ITEM)

      flushOnce(dispatcher(baseDelayMs = Long.MAX_VALUE, client = raisingAfterTheSend()))

      assertThat(row(ITEM).attempts).isEqualTo(2)
      assertThat(row(ITEM).nextRetryAt).isEqualTo(RetryPolicyEvaluator.MAX_DEADLINE_MS)
      assertWake(RetryPolicyEvaluator.MAX_DEADLINE_MS - clock.now())
    }

  /* ------------------------------- helpers ------------------------------- */

  /**
   * Every C2 cell: the row is `IN_FLIGHT` with nothing else changed, there is no dead letter and
   * no item event, `SYNC_FAILED` carries [code], and the row arms no wake. The hold the cycle left
   * still governs the next automatic arm. Recovery then gives the row back as it was before the
   * claim, claimable again, so its request can go out once more.
   */
  private suspend fun assertWaitsInFlight(
    before: SyncItemEntity,
    events: List<SyncEvent>,
    code: SyncErrorCode?,
    ending: CycleEnding,
  ) {
    assertThat(row(before.id)).isEqualTo(before.copy(status = SyncItemEntity.STATUS_IN_FLIGHT))
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(events.seen())
      .containsExactly(
        Seen(SyncEventType.SYNC_STARTED),
        Seen(SyncEventType.SYNC_FAILED, errorCode = code),
      )
      .inOrder()
    assertThat(endings).containsExactly(ending)
    assertNoWake()
    val hold = if (ending == CycleEnding.FAILED) FIRST_HOLD_MS else 0L
    assertThat(nextArmForADueRow()).isEqualTo(hold)
    assertThat(dispatcher(armsWake = false).resetInFlight()).isEqualTo(1)
    assertThat(row(before.id)).isEqualTo(before)
  }

  /** The events that matter here, without their timestamps. */
  private data class Seen(
    val type: SyncEventType,
    val itemId: String? = null,
    val errorCode: SyncErrorCode? = null,
    val statusCode: Int? = null,
    val attempt: Int? = null,
  )

  private fun List<SyncEvent>.seen(): List<Seen> = map {
    Seen(it.type, it.itemId, it.errorCode, it.statusCode?.toInt(), it.attempt?.toInt())
  }

  private fun TestScope.recordEvents(): List<SyncEvent> {
    val events: MutableList<SyncEvent> = Collections.synchronizedList(mutableListOf())
    backgroundScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
      emitter.events.collect { events += it }
    }
    return events
  }

  /**
   * A retry decider the current hybrid installs: it answers [action] with [delayMs] at once,
   * once the decision has taken [decideMs] on the test clock.
   */
  private fun installDecider(
    action: RetryAction,
    delayMs: Double? = null,
    decideMs: Long = 0L,
  ): FakeFunction<RetryDeciderContext> = js.function<RetryDeciderContext> { requestId, _ ->
    clock.advance(decideMs)
    answer(requestId, FakeJs.decision(action, delayMs))
  }.also { assertThat(hybrid.setRetryDecider(it)).isTrue() }

  /** A JS function the current hybrid installed, which never answers. */
  private class NeverAnswering<C>(
    val asked: CompletableDeferred<Unit>,
    private val function: FakeFunction<C>,
  ) {
    val calls: List<C> get() = function.calls
  }

  /** A retry decider the current hybrid installs, which never answers. */
  private fun neverAnswering(): NeverAnswering<RetryDeciderContext> {
    val asked = CompletableDeferred<Unit>()
    val function = js.function<RetryDeciderContext> { _, _ -> asked.complete(Unit) }
    assertThat(hybrid.setRetryDecider(function)).isTrue()
    return NeverAnswering(asked, function)
  }

  /** A header resolver the current hybrid installs, which never answers. */
  private fun neverAnsweringResolver(): NeverAnswering<HeaderResolverContext> {
    val asked = CompletableDeferred<Unit>()
    val function = js.function<HeaderResolverContext> { _, _ -> asked.complete(Unit) }
    assertThat(hybrid.setHeaderResolver(function)).isTrue()
    return NeverAnswering(asked, function)
  }

  /** A header resolver the current hybrid installs: it answers [reply] at once. */
  private fun installResolver(reply: CallbackAnswer): FakeFunction<HeaderResolverContext> =
    js.function<HeaderResolverContext> { requestId, _ -> answer(requestId, reply) }
      .also { assertThat(hybrid.setHeaderResolver(it)).isTrue() }

  private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

  @Suppress("LongParameterList")
  private fun dispatcher(
    queue: SyncQueueStorage = storage,
    credentials: CredentialPolicy = CredentialPolicy.TRUSTED,
    maxAttempts: Int = 3,
    baseDelayMs: Long = BASE_DELAY_MS,
    requestTimeoutMs: Long = 5_000L,
    client: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
    armsWake: Boolean = true,
    callbackTimeoutMs: Double? = null,
  ): SyncDispatcher {
    val options = TestData.syncOptions(
      retryPolicy = TestData.retryPolicy(
        maxAttempts = maxAttempts,
        baseDelayMs = baseDelayMs,
        maxDelayMs = if (baseDelayMs > MAX_DELAY_MS) baseDelayMs else MAX_DELAY_MS,
        jitter = false,
      ),
      requestTimeoutMs = requestTimeoutMs,
      callbackTimeoutMs = callbackTimeoutMs,
    )
    return SyncDispatcher(
      storage = queue,
      emitter = emitter,
      getOptions = { options },
      clock = clock::now,
      callbacks = callbacks,
      credentialPolicy = { credentials },
      onCycleFinished = { notBefore, ending ->
        if (armsWake) {
          endings += ending
          wake.arm(notBefore, ending)
        }
      },
      httpClient = lazyOf(client),
    )
  }

  /** Answers every request with [code], [ATTEMPT_MS] after it arrives. */
  private fun answer(code: Int) {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        clock.advance(ATTEMPT_MS)
        return MockResponse().setResponseCode(code)
      }
    }
  }

  /**
   * Reads every request, [ATTEMPT_MS] after it arrives, and never answers: only the call timeout,
   * a `requestTimeoutMs` of [CALL_TIMEOUT_MS], ends the call, a `TIMEOUT` the attempt classifies.
   */
  private fun timeOut() {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        clock.advance(ATTEMPT_MS)
        return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
      }
    }
  }

  /** Every call reaches the server, then raises an [Error] before its answer is classified. */
  private fun raisingAfterTheSend(): OkHttpClient = OkHttpClient.Builder()
    .retryOnConnectionFailure(false)
    .addInterceptor { chain ->
      chain.proceed(chain.request()).close()
      throw NoClassDefFoundError(RAISED)
    }
    .build()

  /** Attempted [attempts] times, last 10 s before [t0], with a cooldown already past. */
  private suspend fun seed(id: String, createdAt: Long = 1L, attempts: Int = 1): SyncItemEntity {
    db.syncItemDao().insert(
      TestData.entity(
        id = id,
        url = server.url("/$id").toString(),
        createdAt = createdAt,
        attempts = attempts,
        nextRetryAt = t0 - 1_000L,
      ).copy(lastAttemptAt = t0 - 10_000L, lastErrorCode = SyncErrorCode.NETWORK_ERROR.name),
    )
    return row(id)
  }

  private suspend fun row(id: String): SyncItemEntity =
    checkNotNull(db.syncItemDao().findById(id)) { "row $id is not queued" }

  /** The dead letter [id] became; it is no longer queued. */
  private suspend fun deadLetter(id: String): SyncDeadLetterEntity {
    assertThat(db.syncItemDao().findById(id)).isNull()
    return checkNotNull(db.syncDeadLetterDao().findById(id))
  }

  /** A transient local failure, as SQLite reports a busy database. */
  private fun locked() = SQLiteDatabaseLockedException("database is locked")

  /** An outcome write that takes [WRITE_MS] and then fails. */
  private fun failWrite(): Nothing {
    clock.advance(WRITE_MS)
    throw locked()
  }

  /** C2: the settle's (or the release's) own write fails too. */
  private fun settleFails(spy: SyncQueueStorage) {
    coEvery { spy.settleAbortedClaims(any()) } throws locked()
  }

  private fun flushOnce(d: SyncDispatcher): Throwable? =
    runCatching { runBlocking { d.flush() } }.exceptionOrNull()

  private fun assertWake(delayMs: Long) {
    assertThat(timerDelays).containsExactly(delayMs)
    assertThat(kickDelays).containsExactly(delayMs)
  }

  private fun assertNoWake() {
    assertThat(timerDelays).isEmpty()
    assertThat(kickDelays).isEmpty()
  }

  /**
   * The delay of the next automatic arm, as `configureSync` or an engine start arms it, for a due
   * row the cycle never saw: the hold the cycle left, or 0 when it left none.
   */
  private suspend fun nextArmForADueRow(): Long {
    seed(WITNESS, createdAt = 99L)
    val timerSeen = timerDelays.size
    val kickSeen = kickDelays.size
    wake.arm()
    val delayMs = timerDelays.drop(timerSeen).single()
    assertThat(kickDelays.drop(kickSeen)).containsExactly(delayMs)
    return delayMs
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

  private fun heldClaimStorage(
    committed: CompletableDeferred<Unit>,
    release: CountDownLatch,
  ): SyncQueueStorage = SyncQueueStorage(
    itemDao = HeldClaimDao(db.syncItemDao(), committed, release),
    resultDao = db.syncResultDao(),
    deadLetterDao = db.syncDeadLetterDao(),
  )

  /**
   * Cancels [d]'s cycle while its claim returns, its transaction committed, as WorkManager
   * stopping a worker cancels the worker's job.
   */
  private suspend fun TestScope.cancelDuringTheClaim(
    d: SyncDispatcher,
    committed: CompletableDeferred<Unit>,
    release: CountDownLatch,
  ) {
    val cycle = launch(Dispatchers.IO) { d.flush() }
    runBlocking { withTimeout(20_000L) { committed.await() } }
    cycle.cancel()
    release.countDown()
    cycle.join()
    assertThat(cycle.isCancelled).isTrue()
  }

  private companion object {
    const val ITEM = "item"
    const val REST = "rest"
    const val WITNESS = "witness"
    const val RAISED = "a class R8 removed"

    /** How long each request takes on the test clock. */
    const val ATTEMPT_MS = 200L

    /** How long a failing outcome write takes before it throws. */
    const val WRITE_MS = 500L

    /** `requestTimeoutMs` at its 1 s floor, in real time: the call timeout rows wait for it. */
    const val CALL_TIMEOUT_MS = 1_000L

    /** Exponential and without jitter: 1 s before attempt 1, 2 s before attempt 2. */
    const val BASE_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 60_000L

    /** The failure hold's first two steps. */
    const val FIRST_HOLD_MS = 30_000L
    const val SECOND_HOLD_MS = 60_000L

    /** How long a fake decision takes, on the test clock. */
    const val DECIDE_MS = 300L

    /** A `callbackTimeoutMs` no test waits out. */
    const val LONG_CALLBACK_TIMEOUT_MS = 60_000.0

    /** Well inside [LONG_CALLBACK_TIMEOUT_MS]: what "at once" allows on a loaded machine. */
    const val AT_ONCE_MS = 10_000L
  }
}
