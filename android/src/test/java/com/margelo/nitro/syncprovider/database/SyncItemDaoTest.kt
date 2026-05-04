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
    )

    assertThat(rows).isEqualTo(1)
    val updated = dao.findById("01A")!!
    assertThat(updated.status).isEqualTo(SyncItemEntity.STATUS_FAILED)
    assertThat(updated.attempts).isEqualTo(3)
    assertThat(updated.lastAttemptAt).isEqualTo(99_999L)
    assertThat(updated.lastErrorCode).isEqualTo("TIMEOUT")
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
  fun `priorityWeight maps HIGH NORMAL LOW`() {
    assertThat(SyncItemEntity.priorityWeight("HIGH")).isEqualTo(0)
    assertThat(SyncItemEntity.priorityWeight("normal")).isEqualTo(1)
    assertThat(SyncItemEntity.priorityWeight("LOW")).isEqualTo(2)
    assertThat(SyncItemEntity.priorityWeight("anything-else")).isEqualTo(1)
  }
}
