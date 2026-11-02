package com.margelo.nitro.syncprovider.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.testutil.TestData
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SyncItemDaoTest {

  private lateinit var db: SyncDatabase
  private lateinit var dao: SyncItemDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      SyncDatabase::class.java,
    ).allowMainThreadQueries().build()
    dao = db.syncItemDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `insert and findById returns the same row`() = runTest {
    val entity = TestData.entity(id = "01HABC", attempts = 0)

    dao.insert(entity)

    val found = dao.findById("01HABC")
    assertThat(found).isNotNull()
    assertThat(found!!.id).isEqualTo("01HABC")
    assertThat(found.url).isEqualTo(entity.url)
    assertThat(found.attempts).isEqualTo(0)
  }

  @Test
  fun `insertBatch persists every entity`() = runTest {
    val items = listOf(
      TestData.entity("01A"),
      TestData.entity("01B"),
      TestData.entity("01C"),
    )

    dao.insertBatch(items)

    assertThat(dao.count()).isEqualTo(3)
  }

  @Test
  fun `count and countByStatus reflect inserted state`() = runTest {
    dao.insert(TestData.entity("01A", status = SyncItemEntity.STATUS_PENDING))
    dao.insert(TestData.entity("01B", status = SyncItemEntity.STATUS_IN_FLIGHT))
    dao.insert(TestData.entity("01C", status = SyncItemEntity.STATUS_FAILED))
    dao.insert(TestData.entity("01D", status = SyncItemEntity.STATUS_PENDING))

    assertThat(dao.count()).isEqualTo(4)
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_PENDING)).isEqualTo(2)
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_IN_FLIGHT)).isEqualTo(1)
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_FAILED)).isEqualTo(1)
  }

  @Test
  fun `deleteById removes the row and returns the affected count`() = runTest {
    dao.insert(TestData.entity("01A"))
    dao.insert(TestData.entity("01B"))

    val removed = dao.deleteById("01A")

    assertThat(removed).isEqualTo(1)
    assertThat(dao.count()).isEqualTo(1)
    assertThat(dao.findById("01A")).isNull()
  }

  @Test
  fun `clear empties the table`() = runTest {
    dao.insert(TestData.entity("01A"))
    dao.insert(TestData.entity("01B"))

    dao.clear()

    assertThat(dao.count()).isEqualTo(0)
  }

  @Test
  fun `findByStatuses orders by priorityWeight then createdAt`() = runTest {
    dao.insert(TestData.entity("low", priority = "LOW", createdAt = 1L))
    dao.insert(TestData.entity("high", priority = "HIGH", createdAt = 2L))
    dao.insert(TestData.entity("normal-old", priority = "NORMAL", createdAt = 3L))
    dao.insert(TestData.entity("normal-new", priority = "NORMAL", createdAt = 4L))

    val ordered = dao.findByStatuses(listOf(SyncItemEntity.STATUS_PENDING))

    assertThat(ordered.map { it.id })
      .containsExactly("high", "normal-old", "normal-new", "low")
      .inOrder()
  }

  @Test
  fun `findByStatusesLimit caps the result count`() = runTest {
    dao.insert(TestData.entity("01A", priority = "HIGH", createdAt = 1L))
    dao.insert(TestData.entity("01B", priority = "NORMAL", createdAt = 2L))
    dao.insert(TestData.entity("01C", priority = "NORMAL", createdAt = 3L))

    val limited = dao.findByStatusesLimit(listOf(SyncItemEntity.STATUS_PENDING), 2)

    assertThat(limited).hasSize(2)
    assertThat(limited.first().id).isEqualTo("01A")
  }

  @Test
  fun `updateStatus mutates only the targeted columns`() = runTest {
    dao.insert(TestData.entity("01A", attempts = 0))

    val rows = dao.updateStatus(
      id = "01A",
      status = SyncItemEntity.STATUS_FAILED,
      attempts = 3,
      lastAttemptAt = 99_999L,
      lastErrorCode = "TIMEOUT",
      nextRetryAt = 12_345L,
    )

    assertThat(rows).isEqualTo(1)
    val updated = dao.findById("01A")!!
    assertThat(updated.status).isEqualTo(SyncItemEntity.STATUS_FAILED)
    assertThat(updated.attempts).isEqualTo(3)
    assertThat(updated.lastAttemptAt).isEqualTo(99_999L)
    assertThat(updated.lastErrorCode).isEqualTo("TIMEOUT")
    assertThat(updated.nextRetryAt).isEqualTo(12_345L)
  }

  @Test
  fun `claimPending flips only eligible rows and skips cooling ones`() = runTest {
    val now = 5_000L
    dao.insert(TestData.entity("high", priority = "HIGH", createdAt = 1L, nextRetryAt = 0L))
    dao.insert(TestData.entity("cooling", priority = "HIGH", createdAt = 2L, nextRetryAt = now + 1L))
    dao.insert(TestData.entity("normal", priority = "NORMAL", createdAt = 3L, nextRetryAt = now))

    val claimed = dao.claimPending(now = now, limit = 10)

    // Eligible rows only, ordered by priority then createdAt; cooling row skipped.
    assertThat(claimed.map { it.id }).containsExactly("high", "normal").inOrder()
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_IN_FLIGHT)).isEqualTo(2)
    assertThat(dao.findById("cooling")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
  }

  @Test
  fun `claimPending stays under the bind limit of old SQLite versions, whatever the batch`() = runTest {
    val recording = StatementRecordingDao(dao, claimableRows = 2_100)

    val claimed = recording.claimPending(now = 5_000L, limit = 2_100)

    assertThat(claimed).hasSize(2_100)
    assertThat(recording.statementSizes.sum()).isEqualTo(2_100)
    assertThat(recording.statementSizes.max()).isAtMost(OLD_SQLITE_MAX_BIND_PARAMETERS)
  }

  @Test
  fun `nextRetryDeadline reports the soonest deadline, even one already in the past`() = runTest {
    assertThat(dao.nextRetryDeadline()).isNull()
    dao.insert(TestData.entity("later", nextRetryAt = 9_000L))
    assertThat(dao.nextRetryDeadline()).isEqualTo(9_000L)

    dao.insert(TestData.entity("overdue", nextRetryAt = 1L))
    assertThat(dao.nextRetryDeadline()).isEqualTo(1L)
  }

  @Test
  fun `nextRetryDeadline ignores never-attempted rows and rows that are not pending`() = runTest {
    dao.insert(TestData.entity("never-attempted", nextRetryAt = 0L))
    dao.insert(TestData.entity("in-flight", status = SyncItemEntity.STATUS_IN_FLIGHT, nextRetryAt = 5L))
    dao.insert(TestData.entity("failed", status = SyncItemEntity.STATUS_FAILED, nextRetryAt = 5L))

    assertThat(dao.nextRetryDeadline()).isNull()
  }

  @Test
  fun `nextPendingDeadline sees never-attempted rows too`() = runTest {
    assertThat(dao.nextPendingDeadline()).isNull()
    dao.insert(TestData.entity("cooling", nextRetryAt = 9_000L))
    assertThat(dao.nextPendingDeadline()).isEqualTo(9_000L)

    dao.insert(TestData.entity("never-attempted", nextRetryAt = 0L))
    assertThat(dao.nextPendingDeadline()).isEqualTo(0L)
  }

  @Test
  fun `resetInFlight flips IN_FLIGHT rows back to PENDING`() = runTest {
    dao.insert(TestData.entity("01A", status = SyncItemEntity.STATUS_IN_FLIGHT))
    dao.insert(TestData.entity("01B", status = SyncItemEntity.STATUS_IN_FLIGHT))
    dao.insert(TestData.entity("01C", status = SyncItemEntity.STATUS_PENDING))
    dao.insert(TestData.entity("01D", status = SyncItemEntity.STATUS_FAILED))

    val reset = dao.resetInFlight()

    assertThat(reset).isEqualTo(2)
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_IN_FLIGHT)).isEqualTo(0)
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_PENDING)).isEqualTo(3)
    assertThat(dao.countByStatus(SyncItemEntity.STATUS_FAILED)).isEqualTo(1)
  }

  @Test
  fun `resetInFlight keeps the cooldown of the rows it requeues`() = runTest {
    dao.insert(TestData.entity("01A", status = SyncItemEntity.STATUS_IN_FLIGHT, nextRetryAt = 42_000L))

    dao.resetInFlight()

    assertThat(dao.findById("01A")!!.nextRetryAt).isEqualTo(42_000L)
  }

  @Test
  fun `priorityWeight maps HIGH NORMAL LOW`() {
    assertThat(SyncItemEntity.priorityWeight("HIGH")).isEqualTo(0)
    assertThat(SyncItemEntity.priorityWeight("normal")).isEqualTo(1)
    assertThat(SyncItemEntity.priorityWeight("LOW")).isEqualTo(2)
    assertThat(SyncItemEntity.priorityWeight("anything-else")).isEqualTo(1)
  }

  /**
   * Records how many ids each `markInFlight` statement binds. Robolectric's SQLite
   * allows 32,766 parameters, so a real database here could not reveal a breach of the
   * 999 that older Android releases allow.
   *
   * Do not replace this with a real claim of that size. A claim of 12,000 rows under
   * Robolectric's SQLite leaves the JVM unable to attach MockK's agent, which fails
   * every later MockK test in the run, in a different class.
   */
  private class StatementRecordingDao(
    real: SyncItemDao,
    private val claimableRows: Int,
  ) : SyncItemDao by real {
    val statementSizes = mutableListOf<Int>()

    override suspend fun selectClaimable(now: Long, limit: Int): List<SyncItemEntity> =
      List(claimableRows) { TestData.entity("row-$it") }

    override suspend fun markInFlight(ids: List<String>) {
      statementSizes += ids.size
    }

    override suspend fun claimPending(now: Long, limit: Int): List<SyncItemEntity> =
      super<SyncItemDao>.claimPending(now, limit)
  }

  private companion object {
    const val OLD_SQLITE_MAX_BIND_PARAMETERS = 999
  }
}
