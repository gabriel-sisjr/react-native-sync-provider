package com.margelo.nitro.syncprovider.retry

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.testutil.MovingClock
import com.margelo.nitro.syncprovider.testutil.TestData
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class RetryWakeTest {

  private lateinit var db: SyncDatabase
  private lateinit var storage: SyncQueueStorage
  private val timer = mockk<RetryTimer>(relaxed = true)
  private val kicks = mutableListOf<Long>()
  private val clock = MovingClock()
  private var halted = false
  private var live = true

  private val wake: RetryWake
    get() = RetryWake(
      storage = storage,
      timer = timer,
      scheduleKick = { kicks += it },
      isHalted = { halted },
      isEngineLive = { live },
      clock = clock::now,
    )

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

  private suspend fun insert(id: String, nextRetryAt: Long, status: String = SyncItemEntity.STATUS_PENDING) {
    db.syncItemDao().insert(TestData.entity(id = id, status = status, nextRetryAt = nextRetryAt))
  }

  @Test
  fun `arms for the soonest cooling row with the time remaining`() = runTest {
    insert("late", clock.now() + 9_000L)
    insert("soon", clock.now() + 1_500L)

    wake.arm()

    verify { timer.arm(1_500L) }
    assertThat(kicks).containsExactly(1_500L)
  }

  @Test
  fun `a cooldown that already elapsed wakes at once instead of being stranded`() = runTest {
    insert("due", clock.now() - 500L)
    insert("later", clock.now() + 60_000L)

    wake.arm()

    verify { timer.arm(0L) }
    assertThat(kicks).containsExactly(0L)
  }

  @Test
  fun `never-attempted rows do not wake the engine`() = runTest {
    insert("left-over-from-a-full-batch", 0L)

    wake.arm()

    verify { timer.cancel() }
    verify(exactly = 0) { timer.arm(any()) }
    assertThat(kicks).isEmpty()
  }

  @Test
  fun `rows that are not pending do not wake the engine`() = runTest {
    insert("in-flight", clock.now() - 1L, SyncItemEntity.STATUS_IN_FLIGHT)
    insert("failed", clock.now() - 1L, SyncItemEntity.STATUS_FAILED)

    wake.arm()

    verify(exactly = 0) { timer.arm(any()) }
    assertThat(kicks).isEmpty()
  }

  @Test
  fun `a wake with nothing left to do replaces the previous one with nothing`() = runTest {
    wake.arm()

    verify { timer.cancel() }
  }

  @Test
  fun `nothing is armed while halted`() = runTest {
    insert("due", clock.now() - 1L)
    halted = true

    wake.arm()

    verify { timer.cancel() }
    verify(exactly = 0) { timer.arm(any()) }
    assertThat(kicks).isEmpty()
  }

  @Test
  fun `a process with no JS engine relies on the kick alone`() = runTest {
    insert("cooling", clock.now() + 2_000L)
    live = false

    wake.arm()

    verify(exactly = 0) { timer.arm(any()) }
    assertThat(kicks).containsExactly(2_000L)
  }

  @Test
  fun `notBefore holds a wake back past a row that is already due`() = runTest {
    insert("due", clock.now() - 1L)

    wake.arm(notBefore = clock.now() + 60_000L)

    verify { timer.arm(60_000L) }
    assertThat(kicks).containsExactly(60_000L)
  }

  @Test
  fun `notBefore does not bring a later deadline forward`() = runTest {
    insert("cooling", clock.now() + 90_000L)

    wake.arm(notBefore = clock.now() + 60_000L)

    verify { timer.arm(90_000L) }
  }

  @Test
  fun `resume dispatches never-attempted rows at once`() = runTest {
    insert("new", 0L)

    wake.armForResume()

    verify { timer.arm(0L) }
    // The kick keeps using the retry deadline, and this row has none.
    assertThat(kicks).isEmpty()
  }

  @Test
  fun `resume with only cooling rows waits for them`() = runTest {
    insert("cooling", clock.now() + 3_000L)

    wake.armForResume()

    verify { timer.arm(3_000L) }
    assertThat(kicks).containsExactly(3_000L)
  }

  @Test
  fun `resume with an empty queue arms nothing`() = runTest {
    wake.armForResume()

    verify(exactly = 0) { timer.arm(any()) }
    assertThat(kicks).isEmpty()
  }

  @Test
  fun `resume arms nothing while still halted`() = runTest {
    insert("new", 0L)
    halted = true

    wake.armForResume()

    verify(exactly = 0) { timer.arm(any()) }
    assertThat(kicks).isEmpty()
  }
}
