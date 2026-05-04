package com.margelo.nitro.syncprovider.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SyncResultDaoTest {

  private lateinit var db: SyncDatabase
  private lateinit var dao: SyncResultDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      SyncDatabase::class.java,
    ).allowMainThreadQueries().build()
    dao = db.syncResultDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun result(
    finishedAt: Long,
    successCount: Int = 1,
    failureCount: Int = 0,
  ): SyncResultEntity = SyncResultEntity(
    id = UUID.randomUUID().toString(),
    startedAt = finishedAt - 100L,
    finishedAt = finishedAt,
    successCount = successCount,
    failureCount = failureCount,
    succeededIds = """["a","b"]""",
    failedIds = "[]",
    errors = "{}",
  )

  @Test
  fun `insert and findLast returns the most recently finished entry`() = runTest {
    dao.insert(result(finishedAt = 100L))
    dao.insert(result(finishedAt = 200L))
    dao.insert(result(finishedAt = 150L))

    val last = dao.findLast()

    assertThat(last).isNotNull()
    assertThat(last!!.finishedAt).isEqualTo(200L)
  }

  @Test
  fun `findLast returns null on empty table`() = runTest {
    assertThat(dao.findLast()).isNull()
  }

  @Test
  fun `findAll returns rows ordered by finishedAt DESC`() = runTest {
    dao.insert(result(finishedAt = 1_000L))
    dao.insert(result(finishedAt = 3_000L))
    dao.insert(result(finishedAt = 2_000L))

    val all = dao.findAll()

    assertThat(all.map { it.finishedAt }).containsExactly(3_000L, 2_000L, 1_000L).inOrder()
  }

  @Test
  fun `findLatest caps to limit`() = runTest {
    dao.insert(result(finishedAt = 1L))
    dao.insert(result(finishedAt = 2L))
    dao.insert(result(finishedAt = 3L))
    dao.insert(result(finishedAt = 4L))

    val latest = dao.findLatest(2)

    assertThat(latest).hasSize(2)
    assertThat(latest.map { it.finishedAt }).containsExactly(4L, 3L).inOrder()
  }

  @Test
  fun `clear empties the table`() = runTest {
    dao.insert(result(finishedAt = 100L))
    dao.insert(result(finishedAt = 200L))

    dao.clear()

    assertThat(dao.findAll()).isEmpty()
  }
}
