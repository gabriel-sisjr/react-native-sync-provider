package com.margelo.nitro.syncprovider.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.SyncProviderRuntime
import com.margelo.nitro.syncprovider.SyncResult
import com.margelo.nitro.syncprovider.http.SyncDispatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
internal class SyncWorkerTest {

  private lateinit var context: Context
  private lateinit var runtime: SyncProviderRuntime
  private lateinit var dispatcher: SyncDispatcher

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    runtime = mockk(relaxed = true)
    dispatcher = mockk(relaxed = true)
    every { runtime.dispatcher } returns dispatcher

    mockkObject(SyncProviderRuntime.Companion)
    every { SyncProviderRuntime.getOrNull(any()) } returns runtime
    every { SyncProviderRuntime.bootstrap(any()) } returns runtime
  }

  @After
  fun tearDown() {
    unmockkObject(SyncProviderRuntime.Companion)
  }

  private fun successResult() = SyncResult(
    startedAt = 1.0,
    finishedAt = 2.0,
    successCount = 1.0,
    failureCount = 0.0,
    succeededIds = arrayOf("a"),
    failedIds = emptyArray(),
    errors = emptyMap(),
  )

  private fun failureResult() = SyncResult(
    startedAt = 1.0,
    finishedAt = 2.0,
    successCount = 0.0,
    failureCount = 1.0,
    succeededIds = emptyArray(),
    failedIds = arrayOf("a"),
    errors = mapOf("a" to "TIMEOUT: x"),
  )

  @Test
  fun `doWork returns success when flush has no failures`() = runTest {
    coEvery { dispatcher.flush() } returns successResult()
    val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

    val result = worker.doWork()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
  }

  @Test
  fun `doWork returns success even when flush reports terminal failures`() = runTest {
    // Terminal item failures must not drive WorkManager's exponential backoff: it
    // would race the retry wake. Worker-level retry is reserved for thrown workers.
    coEvery { dispatcher.flush() } returns failureResult()
    val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

    val result = worker.doWork()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
  }

  @Test
  fun `doWork returns retry when flush throws`() = runTest {
    coEvery { dispatcher.flush() } throws RuntimeException("boom")
    val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

    val result = worker.doWork()

    assertThat(result).isEqualTo(ListenableWorker.Result.retry())
  }

  @Test
  fun `doWork does not turn a stopped worker into a retry`() {
    coEvery { dispatcher.flush() } throws CancellationException("stopped")
    val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

    assertThrows(CancellationException::class.java) { runBlocking { worker.doWork() } }
  }

  @Test
  fun `doWork is inert when background dispatch is blocked`() = runTest {
    every { runtime.isBackgroundDispatchBlocked() } returns true
    val input = Data.Builder().putBoolean(SyncWorker.KEY_PERIODIC, true).build()
    val worker = TestListenableWorkerBuilder<SyncWorker>(context)
      .setInputData(input)
      .build()

    val result = worker.doWork()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    coVerify(exactly = 0) { dispatcher.flush() }
  }

  @Test
  fun `doWork honors the periodic input flag without crashing`() = runTest {
    coEvery { dispatcher.flush() } returns successResult()
    val input = Data.Builder().putBoolean(SyncWorker.KEY_PERIODIC, true).build()
    val worker = TestListenableWorkerBuilder<SyncWorker>(context)
      .setInputData(input)
      .build()

    val result = worker.doWork()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
  }
}
