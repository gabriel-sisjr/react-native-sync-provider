package com.margelo.nitro.syncprovider.retry

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import com.margelo.nitro.syncprovider.testutil.MovingClock
import com.margelo.nitro.syncprovider.testutil.TestData
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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

  /** The delay of every timer arm, in order. */
  private val armed = mutableListOf<Long>()
  private val kicks = mutableListOf<Long>()

  /** Every kick armed after the quality gate turned one back: its delay and streak. */
  private val gatedKicks = mutableListOf<Pair<Long, Int>>()
  private var gateOpen = false
  private var healthBackoffUntil = 0L
  private val clock = MovingClock()
  private var halted = false
  private var live = true

  /** One per test, like the runtime's: the credential hold must carry between calls. */
  private val wake: RetryWake by lazy {
    RetryWake(
      storage = storage,
      timer = timer,
      scheduleKick = { kicks += it },
      isHalted = { halted },
      isEngineLive = { live },
      clock = clock::now,
      isQualityGateOpen = { gateOpen },
      scheduleGatedKick = { delayMs, streak -> gatedKicks += delayMs to streak },
      healthBackoffUntil = { healthBackoffUntil },
    )
  }

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      SyncDatabase::class.java,
    ).allowMainThreadQueries().build()
    storage = SyncQueueStorage.from(db)
    every { timer.arm(capture(armed)) } just Runs
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

  /* ------------------------------ the credential hold ----------------------------- */

  @Test
  fun `a credential hold outlives the cycle that set it`() = runTest {
    insert("due", clock.now() - 1L)

    wake.arm(notBefore = clock.now() + HOLD_MS)
    wake.arm() // A later cycle that was not rejected again.

    assertThat(armed).containsExactly(HOLD_MS, HOLD_MS).inOrder()
    assertThat(kicks).containsExactly(HOLD_MS, HOLD_MS).inOrder()
  }

  @Test
  fun `resume waits for the credential hold, even for a row with no cooldown`() = runTest {
    insert("due", clock.now() - 1L)
    wake.arm(notBefore = clock.now() + HOLD_MS)
    insert("fresh", 0L)

    wake.armForResume()

    assertThat(armed).containsExactly(HOLD_MS, HOLD_MS).inOrder()
    assertThat(kicks).containsExactly(HOLD_MS, HOLD_MS).inOrder()
  }

  @Test
  fun `a hold reported while halted still holds back the wake that follows`() = runTest {
    insert("due", clock.now() - 1L)
    halted = true
    wake.arm(notBefore = clock.now() + HOLD_MS)
    assertThat(armed).isEmpty()

    halted = false
    wake.armForResume()

    assertThat(armed).containsExactly(HOLD_MS)
    assertThat(kicks).containsExactly(HOLD_MS)
  }

  @Test
  fun `with no JS engine the hold holds back the kick alone`() = runTest {
    insert("due", clock.now() - 1L)
    live = false

    wake.arm(notBefore = clock.now() + HOLD_MS)
    wake.arm()

    assertThat(armed).isEmpty()
    assertThat(kicks).containsExactly(HOLD_MS, HOLD_MS).inOrder()
  }

  @Test
  fun `a hold never wakes the queue by itself`() = runTest {
    wake.arm(notBefore = clock.now() + HOLD_MS)

    verify { timer.cancel() }
    assertThat(armed).isEmpty()
    assertThat(kicks).isEmpty()
  }

  @Test
  fun `a later rejection extends the hold, and an earlier one cannot shorten it`() = runTest {
    insert("due", clock.now() - 1L)

    wake.arm(notBefore = clock.now() + HOLD_MS)
    wake.arm(notBefore = clock.now() + HOLD_MS / 2)
    wake.arm(notBefore = clock.now() + HOLD_MS * 2)

    assertThat(armed).containsExactly(HOLD_MS, HOLD_MS, HOLD_MS * 2).inOrder()
    assertThat(kicks).containsExactly(HOLD_MS, HOLD_MS, HOLD_MS * 2).inOrder()
  }

  @Test
  fun `clearing the hold lets the next wake fire earlier`() = runTest {
    insert("due", clock.now() - 1L)
    wake.arm(notBefore = clock.now() + HOLD_MS)

    wake.clearCredentialHold()
    wake.arm()

    assertThat(armed).containsExactly(HOLD_MS, 0L).inOrder()
    assertThat(kicks).containsExactly(HOLD_MS, 0L).inOrder()
  }

  @Test
  fun `once the hold has passed, wakes are back to normal`() = runTest {
    insert("due", clock.now() - 1L)
    wake.arm(notBefore = clock.now() + HOLD_MS)

    clock.advance(HOLD_MS + 1L)
    wake.arm()
    wake.armForResume()

    assertThat(armed).containsExactly(HOLD_MS, 0L, 0L).inOrder()
    assertThat(kicks).containsExactly(HOLD_MS, 0L, 0L).inOrder()
  }

  /* ------------------------- after the quality gate ------------------------- */

  @Test
  fun `a kick the gate turned back comes back after the next backoff step, carrying the streak`() =
    runTest {
      insert("due", clock.now() - 1L)

      wake.armAfterGate(gateStreak = 0)
      wake.armAfterGate(gateStreak = 1)
      wake.armAfterGate(gateStreak = 9)

      assertThat(gatedKicks).containsExactly(
        30_000L to 1,
        60_000L to 2,
        GateRecheck.MAX_DELAY_MS to 10,
      ).inOrder()
      assertThat(kicks).isEmpty()
    }

  @Test
  fun `the in-process timer is left to hold its own wakes`() = runTest {
    insert("due", clock.now() - 1L)

    wake.armAfterGate(gateStreak = 0)

    assertThat(armed).isEmpty()
    verify(exactly = 0) { timer.cancel() }
  }

  @Test
  fun `a later retry deadline wins over the backoff step`() = runTest {
    insert("later", clock.now() + 90_000L)

    wake.armAfterGate(gateStreak = 0)

    assertThat(gatedKicks).containsExactly(90_000L to 1)
  }

  @Test
  fun `a kick the gate turned back still waits for the credential hold`() = runTest {
    insert("due", clock.now() - 1L)
    wake.arm(notBefore = clock.now() + HOLD_MS)
    kicks.clear()

    wake.armAfterGate(gateStreak = 0)

    assertThat(gatedKicks).containsExactly(HOLD_MS to 1)
  }

  @Test
  fun `no row with a cooldown, no kick after the gate`() = runTest {
    insert("fresh", nextRetryAt = 0L)

    wake.armAfterGate(gateStreak = 0)

    assertThat(gatedKicks).isEmpty()
  }

  @Test
  fun `a gate that opened meanwhile makes it a plain arm, which starts the backoff over`() = runTest {
    insert("due", clock.now() - 1L)
    gateOpen = true

    wake.armAfterGate(gateStreak = 3)

    assertThat(gatedKicks).isEmpty()
    assertThat(armed).containsExactly(0L)
    assertThat(kicks).containsExactly(0L)
  }

  @Test
  fun `nothing is re-armed after the gate while halted`() = runTest {
    halted = true
    insert("due", clock.now() - 1L)

    wake.armAfterGate(gateStreak = 0)

    assertThat(gatedKicks).isEmpty()
    assertThat(kicks).isEmpty()
    verify { timer.cancel() }
  }

  @Test
  fun `no wake on any path fires before a health-check backoff ends`() = runTest {
    insert("due", clock.now() - 1L)
    insert("fresh", nextRetryAt = 0L)
    healthBackoffUntil = clock.now() + 45_000L

    wake.arm()
    wake.armForResume()
    wake.armAfterGate(gateStreak = 0)

    assertThat(armed).containsExactly(45_000L, 45_000L).inOrder()
    assertThat(kicks).containsExactly(45_000L, 45_000L).inOrder()
    assertThat(gatedKicks).containsExactly(45_000L to 1)
  }

  @Test
  fun `the later of the credential hold and the health backoff wins`() = runTest {
    insert("due", clock.now() - 1L)
    healthBackoffUntil = clock.now() + 30_000L

    wake.arm(notBefore = clock.now() + HOLD_MS)

    assertThat(armed).containsExactly(HOLD_MS)
  }

  private companion object {
    const val HOLD_MS = 60_000L
  }
}
