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
  fun `getPending excludes IN_FLIGHT and FAILED rows`() = runTest {
    val id = storage.enqueue(TestData.input())
    val claimed = storage.claimPending(1)
    assertThat(claimed).hasSize(1)
    assertThat(claimed.first().id).isEqualTo(id)

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
  fun `markRetry resets the row to PENDING with new attempts and error code`() = runTest {
    val id = storage.enqueue(TestData.input())
    storage.claimPending(1)

    storage.markRetry(id, attempts = 2, lastAttemptAt = 1_234L, lastErrorCode = SyncErrorCode.NETWORK_ERROR)

    val row = db.syncItemDao().findById(id)!!
    assertThat(row.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row.attempts).isEqualTo(2)
    assertThat(row.lastAttemptAt).isEqualTo(1_234L)
    assertThat(row.lastErrorCode).isEqualTo(SyncErrorCode.NETWORK_ERROR.name)
  }

  @Test
  fun `markFailed marks the row as FAILED and records the error code`() = runTest {
    val id = storage.enqueue(TestData.input())

    storage.markFailed(id, attempts = 5, lastAttemptAt = 9_999L, lastErrorCode = SyncErrorCode.TIMEOUT)

    val row = db.syncItemDao().findById(id)!!
    assertThat(row.status).isEqualTo(SyncItemEntity.STATUS_FAILED)
    assertThat(row.attempts).isEqualTo(5)
    assertThat(row.lastAttemptAt).isEqualTo(9_999L)
    assertThat(row.lastErrorCode).isEqualTo(SyncErrorCode.TIMEOUT.name)
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
