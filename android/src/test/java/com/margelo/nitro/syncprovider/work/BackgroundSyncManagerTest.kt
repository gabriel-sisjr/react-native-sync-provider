package com.margelo.nitro.syncprovider.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.SyncProviderRuntime
import com.margelo.nitro.syncprovider.http.SyncDispatcher
import com.margelo.nitro.syncprovider.testutil.RuntimeTestSupport
import com.margelo.nitro.syncprovider.testutil.TestData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
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

  /* --------------------------- (f) the opt-in retry kick ----------------------------- */

  @Test
  fun `the kick needs background sync to have been enabled`() {
    manager.scheduleOneTimeFlush(initialDelayMs = 30_000L)

    assertThat(manager.isEnabled()).isFalse()
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEqualTo(0)
  }

  @Test
  fun `the kick is delayed by setInitialDelay`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))

    manager.scheduleOneTimeFlush(initialDelayMs = 30_000L)

    assertThat(activeSpec(BackgroundSyncManager.UNIQUE_ONE_TIME).initialDelay).isEqualTo(30_000L)
  }

  @Test
  fun `a zero delay runs as soon as WorkManager allows`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))

    manager.scheduleOneTimeFlush(initialDelayMs = 0L)

    assertThat(activeSpec(BackgroundSyncManager.UNIQUE_ONE_TIME).initialDelay).isEqualTo(0L)
  }

  @Test
  fun `a later kick REPLACEs the armed one`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    manager.scheduleOneTimeFlush(initialDelayMs = 30_000L)
    val first = activeSpec(BackgroundSyncManager.UNIQUE_ONE_TIME)

    manager.scheduleOneTimeFlush(initialDelayMs = 5_000L)

    val second = activeSpec(BackgroundSyncManager.UNIQUE_ONE_TIME)
    assertThat(second.id).isNotEqualTo(first.id)
    assertThat(second.initialDelay).isEqualTo(5_000L)
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEqualTo(1)
  }

  @Test
  fun `the kick honors the constraints the consumer configured`() {
    manager.enable(
      TestData.backgroundSyncOptions(
        foregroundOnly = false,
        requiresUnmeteredNetwork = true,
        requiresCharging = true,
      ),
    )

    manager.scheduleOneTimeFlush(initialDelayMs = 1_000L)

    val constraints = activeSpec(BackgroundSyncManager.UNIQUE_ONE_TIME).constraints
    assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
    assertThat(constraints.requiresCharging()).isTrue()
  }

  @Test
  fun `the kick needs only a connection by default`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))

    manager.scheduleOneTimeFlush(initialDelayMs = 1_000L)

    val constraints = activeSpec(BackgroundSyncManager.UNIQUE_ONE_TIME).constraints
    assertThat(constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    assertThat(constraints.requiresCharging()).isFalse()
  }

  @Test
  fun `the kick is suppressed when foregroundOnly is persisted`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    manager.scheduleOneTimeFlush(initialDelayMs = 30_000L)

    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEqualTo(0)
  }

  @Test
  fun `a kick that cannot be scheduled is logged, not thrown`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    mockkStatic(WorkManager::class)
    try {
      every { WorkManager.getInstance(any<Context>()) } throws IllegalStateException("not initialized")

      manager.scheduleOneTimeFlush(initialDelayMs = 1_000L)
    } finally {
      unmockkStatic(WorkManager::class)
    }
  }

  @Test
  fun `cancelRetryKick drops the kick and leaves periodic work alone`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    manager.scheduleOneTimeFlush(initialDelayMs = 30_000L)

    manager.cancelRetryKick()

    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEqualTo(0)
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(1)
  }

  /* --------------------------------- (g) disable() ----------------------------------- */

  @Test
  fun `disable cancels the periodic work and a pending kick`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    manager.scheduleOneTimeFlush(initialDelayMs = 30_000L)

    manager.disable()

    assertThat(manager.isEnabled()).isFalse()
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(0)
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEqualTo(0)
  }

  @Test
  fun `no kick can be armed after disable`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))

    manager.disable()
    manager.scheduleOneTimeFlush(initialDelayMs = 30_000L)

    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEqualTo(0)
  }

  /** A cycle ending between the two steps must see it, or it re-arms a kick. */
  @Test
  fun `disable publishes the flag before anything is cancelled`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    val enabledSeenByCancel = mutableListOf<Boolean>()
    val workManager = mockk<WorkManager>(relaxed = true)
    every { workManager.cancelUniqueWork(any()) } answers {
      enabledSeenByCancel += manager.isEnabled()
      mockk(relaxed = true)
    }
    mockkStatic(WorkManager::class)
    try {
      every { WorkManager.getInstance(any<Context>()) } returns workManager

      manager.disable()
    } finally {
      unmockkStatic(WorkManager::class)
    }

    assertThat(enabledSeenByCancel).containsExactly(false, false)
  }

  /* --- (d) data-integrity recovery still runs under the flag; boot re-arm stays gated - */

  @Test
  fun `scheduleRecovery is not gated by foregroundOnly`() {
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    manager.scheduleRecovery()

    assertThat(workInfos(BackgroundSyncManager.UNIQUE_RECOVERY)).hasSize(1)
  }

  @Test
  fun `recovery resets in-flight items under foregroundOnly without re-arming periodic`() = runTest {
    // Persist the foreground-only intent through a real manager (no periodic scheduled).
    manager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))
    assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(0)

    val dispatcher = mockk<SyncDispatcher>(relaxed = true)
    coEvery { dispatcher.resetInFlight() } returns 2
    val runtime = mockk<SyncProviderRuntime>(relaxed = true)
    every { runtime.dispatcher } returns dispatcher
    every { runtime.backgroundSyncManager } returns manager

    mockkObject(SyncProviderRuntime.Companion)
    try {
      every { SyncProviderRuntime.getOrNull(any()) } returns runtime
      every { SyncProviderRuntime.bootstrap(any()) } returns runtime

      val worker = TestListenableWorkerBuilder<RecoveryWorker>(context).build()
      val result = worker.doWork()

      // Recovery (data integrity) ran regardless of the flag...
      assertThat(result).isEqualTo(ListenableWorker.Result.success())
      coVerify(exactly = 1) { dispatcher.resetInFlight() }
      // ...but the boot re-arm of OS-scheduled work stayed gated.
      assertThat(activeWorkCount(BackgroundSyncManager.UNIQUE_PERIODIC)).isEqualTo(0)
    } finally {
      unmockkObject(SyncProviderRuntime.Companion)
    }
  }
}
