package com.margelo.nitro.syncprovider.http

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.HttpMethod
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.events.SyncEventEmitter
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.testutil.TestData
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SyncDispatcherTest {

  private lateinit var db: SyncDatabase
  private lateinit var storage: SyncQueueStorage
  private lateinit var emitter: SyncEventEmitter
  private lateinit var server: MockWebServer
  private lateinit var dispatcher: SyncDispatcher

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

  private fun newDispatcher(
    requestTimeoutMs: Long = 5_000L,
    maxAttempts: Int = 3,
  ): SyncDispatcher {
    val options = TestData.syncOptions(
      retryPolicy = TestData.retryPolicy(
        maxAttempts = maxAttempts,
        baseDelayMs = 1L,
        maxDelayMs = 10L,
      ),
      requestTimeoutMs = requestTimeoutMs,
      batchSize = 25,
    )
    return SyncDispatcher(
      storage = storage,
      emitter = emitter,
      getOptions = { options },
    ).also { dispatcher = it }
  }

  @Test
  fun `flush returns an empty result when the queue is empty`() = runTest {
    val d = newDispatcher()

    val result = d.flush()

    assertThat(result.successCount.toInt()).isEqualTo(0)
    assertThat(result.failureCount.toInt()).isEqualTo(0)
    assertThat(result.succeededIds).isEmpty()
    assertThat(result.failedIds).isEmpty()
  }

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
  fun `flush retries 5xx until success within maxAttempts`() = runTest {
    server.enqueue(MockResponse().setResponseCode(503))
    server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
    val d = newDispatcher(maxAttempts = 3)
    val id = storage.enqueue(TestData.input(url = server.url("/x").toString()))

    val result = d.flush()

    assertThat(result.successCount.toInt()).isEqualTo(1)
    assertThat(result.succeededIds.toList()).containsExactly(id)
    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `flush marks an item failed after exceeding maxAttempts`() = runTest {
    repeat(5) { server.enqueue(MockResponse().setResponseCode(500)) }
    val d = newDispatcher(maxAttempts = 2)
    val id = storage.enqueue(TestData.input(url = server.url("/x").toString()))

    val result = d.flush()

    assertThat(result.failureCount.toInt()).isEqualTo(1)
    assertThat(result.failedIds.toList()).containsExactly(id)
    val row = db.syncItemDao().findById(id)
    assertThat(row).isNotNull()
    assertThat(row!!.status).isEqualTo(SyncItemEntity.STATUS_FAILED)
    val message = result.errors[id] ?: ""
    assertThat(message).contains(SyncErrorCode.MAX_ATTEMPTS_EXCEEDED.name)
  }

  @Test
  fun `flush does not retry on 401 UNAUTHORIZED`() = runTest {
    server.enqueue(MockResponse().setResponseCode(401))
    val d = newDispatcher(maxAttempts = 5)
    val id = storage.enqueue(TestData.input(url = server.url("/secure").toString()))

    val result = d.flush()

    assertThat(result.failureCount.toInt()).isEqualTo(1)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(result.errors[id]).contains(SyncErrorCode.UNAUTHORIZED.name)
  }

  @Test
  fun `flush does not retry on a non-retryable 4xx`() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    val d = newDispatcher(maxAttempts = 5)
    val id = storage.enqueue(TestData.input(url = server.url("/missing").toString()))

    val result = d.flush()

    assertThat(result.failureCount.toInt()).isEqualTo(1)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(result.errors[id]).contains(SyncErrorCode.SERVER_ERROR.name)
  }

  @Test
  fun `isSyncing flips false after flush completes`() = runTest {
    val d = newDispatcher()

    assertThat(d.isSyncing()).isFalse()
    d.flush()
    assertThat(d.isSyncing()).isFalse()
  }
}
