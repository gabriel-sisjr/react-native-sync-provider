package com.margelo.nitro.syncprovider.work

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.SyncProviderRuntime
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.testutil.InMemorySecretStore
import com.margelo.nitro.syncprovider.testutil.RuntimeTestSupport
import com.margelo.nitro.syncprovider.testutil.TestData
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The boot path end to end: broadcast, real [RecoveryWorker], real runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class BootCompletedReceiverTest {

  private lateinit var context: Context
  private lateinit var runtime: SyncProviderRuntime

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    RuntimeTestSupport.reset()
    runtime = SyncProviderRuntime.bootstrap(context, InMemorySecretStore())
  }

  @After
  fun tearDown() {
    RuntimeTestSupport.awaitRecovery(context)
    RuntimeTestSupport.reset()
  }

  private fun infos(name: String): List<WorkInfo> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

  private fun active(name: String): List<WorkInfo> = infos(name).filter { !it.state.isFinished }

  private fun receive(action: String?) {
    BootCompletedReceiver().onReceive(context, Intent(action))
  }

  @Test
  fun `boot runs recovery but stays inert under foregroundOnly`() = runTest {
    val dao = runtime.database.syncItemDao()
    dao.insert(TestData.entity(id = "stuck", status = SyncItemEntity.STATUS_IN_FLIGHT, nextRetryAt = 42_000L))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    receive(Intent.ACTION_BOOT_COMPLETED)
    RuntimeTestSupport.awaitRecovery(context)

    assertThat(infos(BackgroundSyncManager.UNIQUE_RECOVERY).single().state)
      .isEqualTo(WorkInfo.State.SUCCEEDED)
    val row = dao.findById("stuck")!!
    assertThat(row.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row.nextRetryAt).isEqualTo(42_000L)
    assertThat(active(BackgroundSyncManager.UNIQUE_PERIODIC)).isEmpty()
    assertThat(active(BackgroundSyncManager.UNIQUE_ONE_TIME)).isEmpty()
  }

  @Test
  fun `boot re-arms periodic work and a kick for the rows it left cooling`() = runTest {
    runtime.database.syncItemDao().insert(
      TestData.entity(id = "cooling", nextRetryAt = System.currentTimeMillis() + 60_000L),
    )
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    WorkManager.getInstance(context).cancelUniqueWork(BackgroundSyncManager.UNIQUE_PERIODIC)

    receive(Intent.ACTION_BOOT_COMPLETED)
    RuntimeTestSupport.awaitRecovery(context)

    assertThat(active(BackgroundSyncManager.UNIQUE_PERIODIC)).hasSize(1)
    assertThat(active(BackgroundSyncManager.UNIQUE_ONE_TIME)).hasSize(1)
  }

  @Test
  fun `a package update runs recovery too`() {
    receive(Intent.ACTION_MY_PACKAGE_REPLACED)

    assertThat(infos(BackgroundSyncManager.UNIQUE_RECOVERY)).hasSize(1)
  }

  /**
   * Building a runtime opens the database and decrypts the stored headers, which must
   * not happen on the broadcast's main thread. WorkManager is mocked because the real
   * worker starts at once and builds the runtime, which is exactly where it belongs.
   */
  @Test
  fun `the broadcast enqueues recovery and builds no runtime`() {
    RuntimeTestSupport.reset() // A boot starts with no runtime: drop the one from setUp.
    val workManager = mockk<WorkManager>(relaxed = true)
    mockkStatic(WorkManager::class)
    try {
      every { WorkManager.getInstance(any<Context>()) } returns workManager

      receive(Intent.ACTION_BOOT_COMPLETED)

      assertThat(SyncProviderRuntime.getOrNull(null)).isNull()
      verify(exactly = 1) {
        workManager.enqueueUniqueWork(
          BackgroundSyncManager.UNIQUE_RECOVERY,
          any<ExistingWorkPolicy>(),
          any<OneTimeWorkRequest>(),
        )
      }
    } finally {
      unmockkStatic(WorkManager::class)
    }
  }

  @Test
  fun `any other broadcast is ignored`() {
    receive(Intent.ACTION_AIRPLANE_MODE_CHANGED)
    receive(null)

    assertThat(infos(BackgroundSyncManager.UNIQUE_RECOVERY)).isEmpty()
  }
}
