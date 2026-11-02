package com.margelo.nitro.syncprovider.retry

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.http.SyncDispatcher
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.testutil.TestData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The offline rule. The dispatcher does not check connectivity, so a timer that
 * flushed offline would spend every attempt and mark valid items `FAILED` while the
 * device simply has no network. Runs the real timer, wake and dispatcher together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class OfflineRetryTest {

  private lateinit var db: SyncDatabase
  private lateinit var storage: SyncQueueStorage
  private lateinit var server: MockWebServer
  private lateinit var dispatcher: SyncDispatcher
  private lateinit var timer: RetryTimer
  private lateinit var wake: RetryWake
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @Volatile private var offline = true

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      SyncDatabase::class.java,
    ).allowMainThreadQueries().build()
    storage = SyncQueueStorage.from(db)
    server = MockWebServer().apply { start() }
    val options = TestData.syncOptions(
      retryPolicy = TestData.retryPolicy(maxAttempts = 2, baseDelayMs = 1L, maxDelayMs = 1L),
    )
    dispatcher = SyncDispatcher(
      storage = storage,
      emitter = SyncEventEmitter(),
      getOptions = { options },
      onCycleFinished = { wake.arm(it) },
    )
    timer = RetryTimer(scope, isOffline = { offline }, flush = { dispatcher.flush() })
    wake = RetryWake(
      storage = storage,
      timer = timer,
      scheduleKick = {},
      isHalted = { false },
      isEngineLive = { true },
    )
  }

  @After
  fun tearDown() {
    scope.cancel()
    server.shutdown()
    db.close()
  }

  private suspend fun insertDueRow(): SyncItemEntity {
    val row = TestData.entity(
      id = "due",
      url = server.url("/x").toString(),
      attempts = 1,
      nextRetryAt = 1L,
    )
    db.syncItemDao().insert(row)
    return row
  }

  private suspend fun awaitRowGone(id: String) {
    val gone = withTimeoutOrNull(20_000L) {
      while (db.syncItemDao().findById(id) != null) delay(10L)
      true
    }
    assertThat(gone).isTrue()
  }

  @Test
  fun `no attempt is consumed while the device is offline`() = runBlocking {
    val before = insertDueRow()

    // With a budget of 2 and one attempt spent, a single flush here would turn this
    // perfectly valid item into a terminal failure.
    repeat(5) {
      wake.arm()
      delay(30L)
    }

    val after = db.syncItemDao().findById("due")!!
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(after.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(after.attempts).isEqualTo(before.attempts)
    assertThat(after.nextRetryAt).isEqualTo(before.nextRetryAt)
    assertThat(after.lastErrorCode).isNull()
  }

  @Test
  fun `the wake is delivered when connectivity returns`() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(200))
    insertDueRow()
    wake.arm()
    delay(50L)
    assertThat(server.requestCount).isEqualTo(0)

    offline = false
    timer.onNetworkAvailable()

    awaitRowGone("due")
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `an online timer retries until the row is resolved, with no JS involved`() = runBlocking {
    offline = false
    server.enqueue(MockResponse().setResponseCode(503))
    server.enqueue(MockResponse().setResponseCode(200))
    db.syncItemDao().insert(
      TestData.entity(id = "new", url = server.url("/x").toString(), attempts = 0, nextRetryAt = 0L),
    )

    dispatcher.flush() // Only this first cycle is explicit.

    awaitRowGone("new")
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `a manual flush offline keeps its existing behavior and spends the attempt`() = runBlocking {
    // Connection refused, as a device with no network would see.
    db.syncItemDao().insert(
      TestData.entity(id = "manual", url = "http://127.0.0.1:1/x", attempts = 0, nextRetryAt = 0L),
    )

    dispatcher.flush()

    val row = db.syncItemDao().findById("manual")!!
    assertThat(row.attempts).isEqualTo(1)
    assertThat(row.lastErrorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR.name)
  }
}
