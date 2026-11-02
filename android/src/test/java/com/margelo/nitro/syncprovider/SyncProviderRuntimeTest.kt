package com.margelo.nitro.syncprovider

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.testutil.RuntimeTestSupport
import com.margelo.nitro.syncprovider.testutil.TestData
import com.margelo.nitro.syncprovider.work.BackgroundSyncManager
import com.margelo.nitro.syncprovider.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The runtime wired for real: SQLite, SharedPreferences and a test WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SyncProviderRuntimeTest {

  private lateinit var context: Context
  private lateinit var server: MockWebServer
  private lateinit var runtime: SyncProviderRuntime

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    RuntimeTestSupport.reset()
    server = MockWebServer().apply { start() }
    runtime = SyncProviderRuntime.bootstrap(context)
  }

  @After
  fun tearDown() {
    RuntimeTestSupport.awaitRecovery(context)
    RuntimeTestSupport.reset()
    server.shutdown()
  }

  private suspend fun runWorker(): ListenableWorker.Result =
    TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

  private suspend fun runPeriodicWorker(): ListenableWorker.Result =
    TestListenableWorkerBuilder<SyncWorker>(context)
      .setInputData(Data.Builder().putBoolean(SyncWorker.KEY_PERIODIC, true).build())
      .build()
      .doWork()

  private fun oneTimeWork(): List<WorkInfo> = active(BackgroundSyncManager.UNIQUE_ONE_TIME)

  private fun active(name: String): List<WorkInfo> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
      .filter { !it.state.isFinished }

  private fun manager(): ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)

  private fun goOnline() {
    val capabilities = ShadowNetworkCapabilities.newInstance()
    shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    shadowOf(manager()).setNetworkCapabilities(manager().activeNetwork, capabilities)
  }

  private fun goOffline() {
    shadowOf(manager()).setNetworkCapabilities(manager().activeNetwork, ShadowNetworkCapabilities.newInstance())
  }

  private suspend fun insert(
    id: String,
    attempts: Int = 0,
    nextRetryAt: Long = 0L,
    status: String = SyncItemEntity.STATUS_PENDING,
    createdAt: Long = 1L,
  ) {
    runtime.database.syncItemDao().insert(
      TestData.entity(
        id = id,
        url = server.url("/$id").toString(),
        attempts = attempts,
        nextRetryAt = nextRetryAt,
        status = status,
        createdAt = createdAt,
      ),
    )
  }

  private suspend fun row(id: String): SyncItemEntity? = runtime.database.syncItemDao().findById(id)

  private suspend fun awaitGone(id: String) {
    val gone = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
      while (row(id) != null) delay(10L)
      true
    }
    assertThat(gone).isTrue()
  }

  /** Attach an engine and wait out the recovery that arms the first wake. */
  private fun attachEngineAndRecover() {
    runtime.attachEngine()
    RuntimeTestSupport.awaitRecovery(context)
  }

  /** Inserts a row whose wake is far enough away that nothing can fire during a test. */
  private suspend fun insertFarCooling(id: String = "cooling") {
    insert(id, attempts = 1, nextRetryAt = System.currentTimeMillis() + FAR_MS)
  }

  /* -------------------------- the opt-in WorkManager kick --------------------- */

  @Test
  fun `an app that never enabled background sync gets no background work`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    insert("x")

    runtime.dispatcher.flush()

    assertThat(row("x")!!.attempts).isEqualTo(1)
    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `an enabled app gets a kick after a retriable failure`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("x")

    runtime.dispatcher.flush()

    assertThat(oneTimeWork()).hasSize(1)
  }

  @Test
  fun `the kick is delayed by the time left until the deadline`() = runBlocking<Unit> {
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insertFarCooling()

    runtime.armRetryWake()

    val spec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao()
      .getWorkSpec(oneTimeWork().single().id.toString())!!
    assertThat(spec.initialDelay).isIn(Range.closed(FAR_MS - 60_000L, FAR_MS))
  }

  @Test
  fun `a cooldown that elapsed during the cycle still gets a kick`() = runBlocking<Unit> {
    // The default policy's first backoff is shorter than this request.
    server.enqueue(MockResponse().setResponseCode(503).setHeadersDelay(300, TimeUnit.MILLISECONDS))
    server.enqueue(MockResponse().setResponseCode(200).setHeadersDelay(600, TimeUnit.MILLISECONDS))
    runtime.configure(
      TestData.syncOptions(retryPolicy = TestData.retryPolicy(baseDelayMs = 100L, maxDelayMs = 100L)),
    )
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("failing", createdAt = 1L)
    insert("slow-sibling", createdAt = 2L)

    runtime.dispatcher.flush()

    assertThat(oneTimeWork()).hasSize(1)
  }

  @Test
  fun `no kick is armed under foregroundOnly`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))
    insert("x")

    runtime.dispatcher.flush()

    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `no kick is armed under the MANUAL strategy, and configuring it cancels one`() = runBlocking<Unit> {
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("x", attempts = 1, nextRetryAt = System.currentTimeMillis() + 60_000L)
    runtime.armRetryWake()
    assertThat(oneTimeWork()).hasSize(1)

    runtime.configure(TestData.syncOptions(strategy = SyncStrategy.MANUAL))
    runtime.armRetryWake()

    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `no kick is armed while paused, and pausing cancels one`() = runBlocking<Unit> {
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("x", attempts = 1, nextRetryAt = System.currentTimeMillis() + 60_000L)
    runtime.armRetryWake()
    assertThat(oneTimeWork()).hasSize(1)

    runtime.pause()
    runtime.armRetryWake()

    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `disabling background sync stops the kick for good`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("x")
    runtime.dispatcher.flush()
    assertThat(oneTimeWork()).hasSize(1)

    runtime.backgroundSyncManager.disable()
    assertThat(oneTimeWork()).isEmpty()

    runtime.dispatcher.flush() // The row is cooling, so this cycle is empty.
    assertThat(oneTimeWork()).isEmpty()
  }

  /* ----------------------------- the worker's gates --------------------------- */

  @Test
  fun `a worker dispatches nothing while the engine is paused`() = runBlocking<Unit> {
    insert("x")
    runtime.pause()

    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("x")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
  }

  @Test
  fun `a worker dispatches nothing under foregroundOnly`() = runBlocking<Unit> {
    insert("x")
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(0)
  }

  @Test
  fun `a periodic worker dispatches under MANUAL, while the timer and the kick stay unarmed`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    runtime.configure(TestData.syncOptions(strategy = SyncStrategy.MANUAL))
    attachEngineAndRecover()
    insert("x")

    val result = runPeriodicWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(1)
    // The row is cooling, so any wake that was allowed would have armed for it.
    assertThat(row("x")!!.nextRetryAt).isGreaterThan(0L)
    assertThat(runtime.isRetryTimerArmed).isFalse()
    assertThat(oneTimeWork()).isEmpty()
  }

  /* ---------------------------- the in-process timer -------------------------- */

  @Test
  fun `engine start wakes for a retry that came due while the app was closed`() = runBlocking<Unit> {
    goOnline()
    server.enqueue(MockResponse().setResponseCode(200))
    insert("due", attempts = 1, nextRetryAt = 1L)

    runtime.attachEngine()

    awaitGone("due")
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `configureSync arms the timer`() = runBlocking<Unit> {
    goOnline()
    server.enqueue(MockResponse().setResponseCode(200))
    // Recovery must be over: its own wake would otherwise pick this row up.
    attachEngineAndRecover()
    insert("cooling", attempts = 1, nextRetryAt = System.currentTimeMillis() + 100L)

    runtime.configure(TestData.syncOptions())

    awaitGone("cooling")
  }

  @Test
  fun `the timer runs under foregroundOnly, because it is in-process`() = runBlocking<Unit> {
    goOnline()
    server.enqueue(MockResponse().setResponseCode(200))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))
    runtime.attachEngine()
    insert("cooling", attempts = 1, nextRetryAt = System.currentTimeMillis() + 100L)

    runtime.armRetryWake()

    awaitGone("cooling")
  }

  @Test
  fun `the timer stays off under the MANUAL strategy`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insertFarCooling()

    runtime.configure(TestData.syncOptions(strategy = SyncStrategy.MANUAL))

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `switching to the MANUAL strategy cancels an armed timer`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insertFarCooling()
    runtime.configure(TestData.syncOptions())
    assertThat(runtime.isRetryTimerArmed).isTrue()

    runtime.configure(TestData.syncOptions(strategy = SyncStrategy.MANUAL))

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `pausing cancels the timer`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insertFarCooling()
    runtime.armRetryWake()
    assertThat(runtime.isRetryTimerArmed).isTrue()

    runtime.pause()

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `the timer is not armed while paused`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insertFarCooling()
    runtime.pause()

    runtime.armRetryWake()

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `resuming dispatches never-attempted rows at once`() = runBlocking<Unit> {
    goOnline()
    server.enqueue(MockResponse().setResponseCode(200))
    runtime.attachEngine()
    runtime.pause()
    insert("new")

    runtime.resume()

    awaitGone("new")
  }

  @Test
  fun `detaching the last engine cancels the timer`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insertFarCooling()
    runtime.armRetryWake()
    assertThat(runtime.isRetryTimerArmed).isTrue()

    runtime.detachEngine()

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `a reload that attaches before detaching keeps the timer`() = runBlocking<Unit> {
    attachEngineAndRecover()
    runtime.attachEngine()
    insertFarCooling()
    runtime.armRetryWake()

    runtime.detachEngine()

    assertThat(runtime.isRetryTimerArmed).isTrue()
  }

  @Test
  fun `a process with no JS engine arms no timer`() = runBlocking<Unit> {
    insertFarCooling()

    runtime.armRetryWake()

    assertThat(runtime.isRetryTimerArmed).isFalse()
  }

  @Test
  fun `a timer that fired offline flushes as soon as the network returns`() = runBlocking<Unit> {
    goOffline()
    server.enqueue(MockResponse().setResponseCode(200))
    insert("due", attempts = 1, nextRetryAt = 1L)
    runtime.attachEngine()
    delay(250L)
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("due")!!.attempts).isEqualTo(1)

    goOnline()
    val network = manager().activeNetwork!!
    shadowOf(manager()).networkCallbacks.forEach { it.onAvailable(network) }

    awaitGone("due")
    assertThat(server.requestCount).isEqualTo(1)
  }

  /* ----------------------------------- queue ---------------------------------- */

  @Test
  fun `queueSize counts what is waiting to be dispatched, cooling items included`() = runBlocking<Unit> {
    insert("new")
    insert("cooling", attempts = 1, nextRetryAt = System.currentTimeMillis() + 60_000L)
    insert("in-flight", status = SyncItemEntity.STATUS_IN_FLIGHT)
    insert("failed", status = SyncItemEntity.STATUS_FAILED)

    assertThat(runtime.queueSize()).isEqualTo(2)
  }

  private companion object {
    const val AWAIT_TIMEOUT_MS = 20_000L
    const val FAR_MS = 3_600_000L
  }
}
