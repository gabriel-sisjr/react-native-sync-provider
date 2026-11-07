package com.margelo.nitro.syncprovider.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.HttpMethod
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.SyncPriority
import com.margelo.nitro.syncprovider.SyncResult
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.error.SyncException
import com.margelo.nitro.syncprovider.testutil.TestData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SyncQueueStorageTest {

  private lateinit var db: SyncDatabase
  private lateinit var storage: SyncQueueStorage

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      SyncDatabase::class.java,
    ).allowMainThreadQueries().build()
    storage = SyncQueueStorage.from(db)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `enqueue persists a row and returns its id`() = runTest {
    val id = storage.enqueue(TestData.input())

    assertThat(id).isNotEmpty()
    assertThat(storage.count()).isEqualTo(1)
    val pending = storage.getPending()
    assertThat(pending).hasSize(1)
    assertThat(pending.first().id).isEqualTo(id)
    assertThat(pending.first().method).isEqualTo(HttpMethod.POST)
  }

  @Test
  fun `enqueue rejects an invalid url with INVALID_URL`() = runTest {
    val exception = assertThrows(SyncException::class.java) {
      kotlinx.coroutines.runBlocking {
        storage.enqueue(TestData.input(url = "not-a-url"))
      }
    }
    assertThat(exception.code).isEqualTo(SyncErrorCode.INVALID_URL)
    assertThat(storage.count()).isEqualTo(0)
  }

  @Test
  fun `enqueue rejects when the queue is full`() = runTest {
    storage.maxQueueSize = 2
    storage.enqueue(TestData.input(url = "https://api.example.com/a"))
    storage.enqueue(TestData.input(url = "https://api.example.com/b"))

    val exception = assertThrows(SyncException::class.java) {
      kotlinx.coroutines.runBlocking {
        storage.enqueue(TestData.input(url = "https://api.example.com/c"))
      }
    }
    assertThat(exception.code).isEqualTo(SyncErrorCode.QUEUE_FULL)
  }

  @Test
  fun `enqueueBatch persists every input`() = runTest {
    val ids = storage.enqueueBatch(
      listOf(
        TestData.input(url = "https://api.example.com/a"),
        TestData.input(url = "https://api.example.com/b"),
        TestData.input(url = "https://api.example.com/c"),
      ),
    )

    assertThat(ids).hasSize(3)
    assertThat(storage.count()).isEqualTo(3)
  }

  @Test
  fun `enqueueBatch rejects when total would exceed the cap`() = runTest {
    storage.maxQueueSize = 2

    val exception = assertThrows(SyncException::class.java) {
      kotlinx.coroutines.runBlocking {
        storage.enqueueBatch(
          listOf(
            TestData.input(url = "https://api.example.com/a"),
            TestData.input(url = "https://api.example.com/b"),
            TestData.input(url = "https://api.example.com/c"),
          ),
        )
      }
    }
    assertThat(exception.code).isEqualTo(SyncErrorCode.QUEUE_FULL)
    assertThat(storage.count()).isEqualTo(0)
  }

  @Test
  fun `enqueueBatch with empty list is a no-op`() = runTest {
    val ids = storage.enqueueBatch(emptyList())

    assertThat(ids).isEmpty()
    assertThat(storage.count()).isEqualTo(0)
  }

  @Test
  fun `removeItem deletes when present and returns true`() = runTest {
    val id = storage.enqueue(TestData.input())

    val removed = storage.removeItem(id)

    assertThat(removed).isTrue()
    assertThat(storage.count()).isEqualTo(0)
  }

  @Test
  fun `removeItem returns false when missing`() = runTest {
    val removed = storage.removeItem("does-not-exist")

    assertThat(removed).isFalse()
  }

  @Test
  fun `clear empties the queue`() = runTest {
    storage.enqueue(TestData.input(url = "https://api.example.com/a"))
    storage.enqueue(TestData.input(url = "https://api.example.com/b"))

    storage.clear()

    assertThat(storage.count()).isEqualTo(0)
  }

  @Test
  fun `getPending excludes IN_FLIGHT rows and dead letters`() = runTest {
    val id = storage.enqueue(TestData.input())
    val claimed = storage.claimPending(1)
    assertThat(claimed).hasSize(1)
    assertThat(claimed.first().id).isEqualTo(id)
    storage.enqueue(TestData.input())
    deadLetter(storage.claimPending(1).single())

    val pending = storage.getPending()

    assertThat(pending).isEmpty()
    assertThat(storage.pendingCount()).isEqualTo(0)
  }

  @Test
  fun `claimPending atomically marks rows as IN_FLIGHT`() = runTest {
    storage.enqueueBatch(
      listOf(
        TestData.input(url = "https://api.example.com/a", priority = SyncPriority.HIGH),
        TestData.input(url = "https://api.example.com/b", priority = SyncPriority.NORMAL),
        TestData.input(url = "https://api.example.com/c", priority = SyncPriority.LOW),
      ),
    )

    val claimed = storage.claimPending(2)

    assertThat(claimed).hasSize(2)
    assertThat(claimed.first().priority).isEqualTo("HIGH")
    val dao = db.syncItemDao()
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_IN_FLIGHT)).isEqualTo(2)
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_PENDING)).isEqualTo(1)
  }

  @Test
  fun `markSucceeded deletes the row`() = runTest {
    val id = storage.enqueue(TestData.input())

    storage.markSucceeded(id)

    assertThat(storage.count()).isEqualTo(0)
  }

  @Test
  fun `markRetry resets the row to PENDING with attempts error code and cooldown`() = runTest {
    val id = storage.enqueue(TestData.input())
    storage.claimPending(1)

    storage.markRetry(
      id,
      attempts = 2,
      lastAttemptAt = 1_234L,
      lastErrorCode = SyncErrorCode.NETWORK_ERROR,
      nextRetryAt = 9_876L,
    )

    val row = db.syncItemDao().findById(id)!!
    assertThat(row.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row.attempts).isEqualTo(2)
    assertThat(row.lastAttemptAt).isEqualTo(1_234L)
    assertThat(row.lastErrorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR.name)
    assertThat(row.nextRetryAt).isEqualTo(9_876L)
  }

  @Test
  fun `claimPending excludes rows whose cooldown has not elapsed`() = runTest {
    val dao = db.syncItemDao()
    val now = 5_000L
    dao.insert(TestData.entity(id = "cooling", nextRetryAt = now + 1_000L))
    dao.insert(TestData.entity(id = "eligible", nextRetryAt = now))

    val claimed = storage.claimPending(limit = 10, now = now)

    assertThat(claimed.map { it.id }).containsExactly("eligible")
    assertThat(dao.findById("cooling")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(dao.findById("eligible")!!.status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)
  }

  @Test
  fun `claimPending picks a cooling row up once now reaches its nextRetryAt`() = runTest {
    val dao = db.syncItemDao()
    dao.insert(TestData.entity(id = "cooling", nextRetryAt = 5_000L))

    // Before the deadline: excluded.
    assertThat(storage.claimPending(limit = 10, now = 4_999L)).isEmpty()
    // At the deadline (<=): eligible.
    val claimed = storage.claimPending(limit = 10, now = 5_000L)

    assertThat(claimed.map { it.id }).containsExactly("cooling")
  }

  @Test
  fun `nextRetryDeadline returns the soonest retry deadline or null when none`() = runTest {
    val dao = db.syncItemDao()
    assertThat(storage.nextRetryDeadline()).isNull()

    dao.insert(TestData.entity(id = "late", nextRetryAt = 6_000L))
    dao.insert(TestData.entity(id = "soon", nextRetryAt = 1_500L))
    dao.insert(TestData.entity(id = "never-attempted", nextRetryAt = 0L))

    assertThat(storage.nextRetryDeadline()).isEqualTo(1_500L)
  }

  @Test
  fun `nextPendingDeadline returns 0 once a never-attempted row exists`() = runTest {
    val dao = db.syncItemDao()
    assertThat(storage.nextPendingDeadline()).isNull()

    dao.insert(TestData.entity(id = "cooling", nextRetryAt = 1_500L))
    assertThat(storage.nextPendingDeadline()).isEqualTo(1_500L)

    dao.insert(TestData.entity(id = "never-attempted", nextRetryAt = 0L))
    assertThat(storage.nextPendingDeadline()).isEqualTo(0L)
  }

  @Test
  fun `getPending surfaces attempts and nextRetryAt, cooling rows included`() = runTest {
    db.syncItemDao().insert(TestData.entity(id = "cooling", attempts = 2, nextRetryAt = 9_000L))

    val item = storage.getPending().single()

    assertThat(item.attempts).isEqualTo(2.0)
    assertThat(item.nextRetryAt).isEqualTo(9_000.0)
  }

  @Test
  fun `pendingCount counts cooling rows but not FAILED or IN_FLIGHT ones`() = runTest {
    val dao = db.syncItemDao()
    dao.insert(TestData.entity(id = "new"))
    dao.insert(TestData.entity(id = "cooling", nextRetryAt = 9_000L))
    dao.insert(TestData.entity(id = "in-flight", status = SyncItemEntity.STATUS_IN_FLIGHT))
    dao.insert(TestData.entity(id = "failed", status = SyncItemEntity.STATUS_FAILED))

    assertThat(storage.pendingCount()).isEqualTo(2)
  }

  @Test
  fun `the capacity check still counts every row`() = runTest {
    val dao = db.syncItemDao()
    storage.maxQueueSize = 2
    dao.insert(TestData.entity(id = "in-flight", status = SyncItemEntity.STATUS_IN_FLIGHT))
    dao.insert(TestData.entity(id = "failed", status = SyncItemEntity.STATUS_FAILED))

    val exception = assertThrows(SyncException::class.java) {
      kotlinx.coroutines.runBlocking { storage.enqueue(TestData.input()) }
    }

    assertThat(exception.code).isEqualTo(SyncErrorCode.QUEUE_FULL)
  }

  @Test
  fun `claimPending never double-claims under concurrent cycles`() = runTest {
    val dao = db.syncItemDao()
    val now = 5_000L
    repeat(20) { i -> dao.insert(TestData.entity(id = "row-$i", createdAt = i.toLong())) }

    // Two concurrent claims racing over the same eligible pool.
    val (a, b) = coroutineScope {
      val first = async(Dispatchers.IO) { storage.claimPending(limit = 20, now = now) }
      val second = async(Dispatchers.IO) { storage.claimPending(limit = 20, now = now) }
      first.await() to second.await()
    }

    val claimedIds = a.map { it.id } + b.map { it.id }
    // @Transaction atomicity: no row appears in both claims.
    assertThat(claimedIds.toSet()).hasSize(claimedIds.size)
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_IN_FLIGHT)).isEqualTo(20)
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_PENDING)).isEqualTo(0)
  }

  /* ------------------------------- dead letters ------------------------------- */

  private suspend fun deadLetter(entity: SyncItemEntity, message: String? = "HTTP 410 Gone") =
    storage.moveToDeadLetters(
      entity = entity,
      attempts = 5,
      lastAttemptAt = 9_999L,
      lastErrorCode = SyncErrorCode.SERVER_ERROR,
      lastErrorMessage = message,
      deadLetteredAt = 10_000L,
    )

  @Test
  fun `a terminal failure moves the row, never leaving it in both tables`() = runTest {
    val id = storage.enqueue(TestData.input(metadata = mapOf("k" to "v")))
    val queued = db.syncItemDao().findById(id)!!

    deadLetter(queued)

    assertThat(db.syncItemDao().findById(id)).isNull()
    val dead = db.syncDeadLetterDao().findById(id)!!
    assertThat(dead.url).isEqualTo(queued.url)
    assertThat(dead.headers).isEqualTo(queued.headers)
    assertThat(dead.body).isEqualTo(queued.body)
    assertThat(dead.metadata).isEqualTo(queued.metadata)
    assertThat(dead.createdAt).isEqualTo(queued.createdAt)
    assertThat(dead.attempts).isEqualTo(5)
    assertThat(dead.lastAttemptAt).isEqualTo(9_999L)
    assertThat(dead.lastErrorCode).isEqualTo(SyncErrorCode.SERVER_ERROR.name)
    assertThat(dead.lastErrorMessage).isEqualTo("HTTP 410 Gone")
    assertThat(dead.deadLetteredAt).isEqualTo(10_000L)
  }

  @Test
  fun `the recorded message is cut to 1024 characters`() = runTest {
    val id = storage.enqueue(TestData.input())

    deadLetter(db.syncItemDao().findById(id)!!, message = "x".repeat(5_000))

    assertThat(db.syncDeadLetterDao().findById(id)!!.lastErrorMessage).hasLength(1_024)
  }

  @Test
  fun `dlq_deadLetters_doNotCountTowardCapacity`() = runTest {
    storage.maxQueueSize = 1
    val first = storage.enqueue(TestData.input())
    deadLetter(db.syncItemDao().findById(first)!!)

    storage.enqueue(TestData.input())

    assertThat(storage.count()).isEqualTo(1)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(1)
  }

  @Test
  fun `dlq_clearQueue_clearsDeadLetters, in the same purge as the queue`() = runTest {
    val dead = storage.enqueue(TestData.input())
    deadLetter(db.syncItemDao().findById(dead)!!)
    storage.enqueue(TestData.input())

    storage.clear()

    assertThat(storage.count()).isEqualTo(0)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
  }

  @Test
  fun `dlq_removeItem_fallsThroughToDeadLetters`() = runTest {
    val dead = storage.enqueue(TestData.input())
    deadLetter(db.syncItemDao().findById(dead)!!)
    val queued = storage.enqueue(TestData.input())

    assertThat(storage.removeItem(dead)).isTrue()
    assertThat(storage.removeItem(queued)).isTrue()
    assertThat(storage.removeItem("unknown")).isFalse()
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
    assertThat(storage.count()).isEqualTo(0)
  }

  @Test
  fun `resetInFlight flips IN_FLIGHT back to PENDING`() = runTest {
    storage.enqueue(TestData.input(url = "https://api.example.com/a"))
    storage.enqueue(TestData.input(url = "https://api.example.com/b"))
    storage.claimPending(2)

    val reset = storage.resetInFlight()

    assertThat(reset).isEqualTo(2)
    assertThat(storage.pendingCount()).isEqualTo(2)
  }

  @Test
  fun `resetInFlight does not extend or clear a cooldown`() = runTest {
    db.syncItemDao().insert(
      TestData.entity(id = "in-flight", status = SyncItemEntity.STATUS_IN_FLIGHT, nextRetryAt = 42_000L),
    )

    storage.resetInFlight()

    val row = db.syncItemDao().findById("in-flight")!!
    assertThat(row.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row.nextRetryAt).isEqualTo(42_000L)
  }

  private fun sampleResult(
    successCount: Int = 1,
    failureCount: Int = 0,
    succeeded: Array<String> = arrayOf("a"),
    failed: Array<String> = emptyArray(),
    errors: Map<String, String> = emptyMap(),
  ): SyncResult = SyncResult(
    startedAt = 1.0,
    finishedAt = 2.0,
    successCount = successCount.toDouble(),
    failureCount = failureCount.toDouble(),
    succeededIds = succeeded,
    failedIds = failed,
    errors = errors,
  )

  @Test
  fun `recordResult and getLastResult roundtrip`() = runTest {
    val recorded = sampleResult(
      successCount = 2,
      failureCount = 1,
      succeeded = arrayOf("alpha", "beta"),
      failed = arrayOf("gamma"),
      errors = mapOf("gamma" to "TIMEOUT: boom"),
    )

    storage.recordResult(recorded)

    val last = storage.getLastResult()
    assertThat(last).isNotNull()
    assertThat(last!!.successCount.toInt()).isEqualTo(2)
    assertThat(last.failureCount.toInt()).isEqualTo(1)
    assertThat(last.succeededIds.toList()).containsExactly("alpha", "beta").inOrder()
    assertThat(last.failedIds.toList()).containsExactly("gamma")
    assertThat(last.errors).containsEntry("gamma", "TIMEOUT: boom")
  }

  @Test
  fun `getLastResult is null when empty`() = runTest {
    assertThat(storage.getLastResult()).isNull()
  }

  @Test
  fun `getHistory respects the limit`() = runTest {
    repeat(4) { idx ->
      storage.recordResult(sampleResult(successCount = idx + 1))
    }

    val limited = storage.getHistory(2)
    val all = storage.getHistory()

    assertThat(limited).hasSize(2)
    assertThat(all).hasSize(4)
  }

  @Test
  fun `clearHistory empties the result table`() = runTest {
    storage.recordResult(sampleResult())
    storage.recordResult(sampleResult())

    storage.clearHistory()

    assertThat(storage.getHistory()).isEmpty()
  }
}
