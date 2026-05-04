package com.margelo.nitro.syncprovider.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.SyncProviderRuntime
import com.margelo.nitro.syncprovider.storage.SyncQueueStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class RecoveryWorkerTest {

  private lateinit var context: Context
  private lateinit var runtime: SyncProviderRuntime
  private lateinit var storage: SyncQueueStorage
  private lateinit var bgManager: BackgroundSyncManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    storage = mockk(relaxed = true)
    bgManager = mockk(relaxed = true)
    runtime = mockk(relaxed = true) {
      every { storage } returns this@RecoveryWorkerTest.storage
      every { backgroundSyncManager } returns this@RecoveryWorkerTest.bgManager
    }

    mockkObject(SyncProviderRuntime.Companion)
    every { SyncProviderRuntime.getOrNull(any()) } returns runtime
    every { SyncProviderRuntime.bootstrap(any()) } returns runtime
  }

  @After
  fun tearDown() {
    unmockkObject(SyncProviderRuntime.Companion)
  }

  @Test
  fun `doWork resets in-flight items and reschedules periodic`() = runTest {
    coEvery { storage.resetInFlight() } returns 3
    val worker = TestListenableWorkerBuilder<RecoveryWorker>(context).build()

    val result = worker.doWork()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    coVerify(exactly = 1) { storage.resetInFlight() }
    coVerify(exactly = 1) { bgManager.maybeReschedulePeriodic() }
  }

  @Test
  fun `doWork succeeds even when nothing was in-flight`() = runTest {
    coEvery { storage.resetInFlight() } returns 0
    val worker = TestListenableWorkerBuilder<RecoveryWorker>(context).build()

    val result = worker.doWork()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
  }

  @Test
  fun `doWork returns retry when storage throws`() = runTest {
    coEvery { storage.resetInFlight() } throws RuntimeException("db gone")
    val worker = TestListenableWorkerBuilder<RecoveryWorker>(context).build()

    val result = worker.doWork()

    assertThat(result).isEqualTo(ListenableWorker.Result.retry())
  }
}
