package com.margelo.nitro.syncprovider.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.testutil.RuntimeTestSupport
import com.margelo.nitro.syncprovider.testutil.TestData
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural tests for [BackgroundSyncManager].
 *
 * Uses the WorkManager test harness ([WorkManagerTestInitHelper] + [SynchronousExecutor])
 * so enqueue / cancel land in a real (in-memory) WorkManager while constraints stay
 * unmet — the [SyncWorker] / [RecoveryWorker] bodies never actually execute, we only
 * assert on the scheduled [WorkInfo] state and on the stored [WorkSpec].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class BackgroundSyncManagerTest {

  private lateinit var context: Context
  private lateinit var manager: BackgroundSyncManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    val config = Configuration.Builder()
      .setExecutor(SynchronousExecutor())
      .build()
    WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    RuntimeTestSupport.reset()
    manager = BackgroundSyncManager(context)
  }

  @After
  fun tearDown() {
    RuntimeTestSupport.awaitRecovery(context)
    RuntimeTestSupport.reset()
  }

  private fun workInfos(uniqueName: String): List<WorkInfo> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get()

  /** Count of work that is still pending/running (excludes CANCELLED / finished states). */
  private fun activeWorkCount(uniqueName: String): Int =
    workInfos(uniqueName).count { !it.state.isFinished }

  private fun activeSpec(uniqueName: String): WorkSpec {
    val info = workInfos(uniqueName).single { !it.state.isFinished }
    return WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
      .getWorkSpec(info.id.toString())!!
  }

  /* ---------------- (a) flag persisted + round-tripped via lastOptions() ------------- */

  @Test
  fun `foregroundOnly is persisted and round-trips through lastOptions`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    val restored = BackgroundSyncManager(context).lastOptions()

    assertThat(restored).isNotNull()
    assertThat(restored!!.foregroundOnly).isTrue()
    assertThat(BackgroundSyncManager(context).isForegroundOnly()).isTrue()
  }

  @Test
  fun `foregroundOnly defaults to false in lastOptions when enabled without the flag`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = null))

    assertThat(manager.lastOptions()!!.foregroundOnly).isFalse()
    assertThat(manager.isForegroundOnly()).isFalse()
  }

  /* ------- (b) enable(foregroundOnly=true) skips periodic enqueue + cancels both ------ */

  @Test
  fun `enable with foregroundOnly true does not enqueue periodic work`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(0)
  }

  @Test
  fun `enable with foregroundOnly true cancels previously scheduled periodic and one-time work`() {
    // Arrange: arm both works through a normal (non foreground-only) enable.
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    manager.scheduleOneTimeFlush()
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(1)
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEqualTo(1)

    // Act: flip the consumer to foreground-only.
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    // Assert: nothing left scheduled, but the enabled intent is preserved (KEY_ENABLED).
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(0)
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEqualTo(0)
    assertThat(manager.isEnabled()).isTrue()
  }

  @Test
  fun `enable with foregroundOnly false enqueues periodic work`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))

    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(1)
  }

  /** A cycle ending between the two steps must see the flag, or it re-arms a kick. */
  @Test
  fun `foregroundOnly is published before anything is cancelled`() {
    val flagSeenByCancel = mutableListOf<Boolean>()
    val workManager = mockk<WorkManager>(relaxed = true)
    every { workManager.cancelUniqueWork(any()) } answers {
      flagSeenByCancel += manager.isForegroundOnly()
      mockk(relaxed = true)
    }
    mockkStatic(WorkManager::class)
    try {
      every { WorkManager.getInstance(any<Context>()) } returns workManager

      manager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))
    } finally {
      unmockkStatic(WorkManager::class)
    }

    assertThat(flagSeenByCancel).containsExactly(true, true)
  }

  /* --------- (c) maybeReschedulePeriodic() is inert when the flag is set ------------- */

  @Test
  fun `maybeReschedulePeriodic is inert when foregroundOnly is persisted`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(0)

    manager.maybeReschedulePeriodic()

    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(0)
  }

  @Test
  fun `maybeReschedulePeriodic re-arms periodic work when foregroundOnly is false`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    // Simulate the OS dropping the scheduled job (e.g. across a reboot).
    WorkManager.getInstance(context).cancelUniqueWork(BackgroundSyncManager.UNIQUE_PERIODIC)
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(0)

    manager.maybeReschedulePeriodic()

    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(1)
  }

  /* ----------------- (e) scheduleOneTimeFlush() is gated by the flag ----------------- */

  @Test
  fun `scheduleOneTimeFlush is gated when foregroundOnly is persisted`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    manager.scheduleOneTimeFlush()

    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEqualTo(0)
  }

  @Test
  fun `scheduleOneTimeFlush enqueues when foregroundOnly is not set`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))

    manager.scheduleOneTimeFlush()

    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEqualTo(1)
  }

  /* --- (d) data-integrity recovery still runs under the flag; boot re-arm stays gated - */

  @Test
  fun `scheduleRecovery is not gated by foregroundOnly`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    manager.scheduleRecovery()

    assertThat(workInfos(BackgroundSyncManager.UNIQUE_RECOVERY)).hasSize(1)
  }
}
