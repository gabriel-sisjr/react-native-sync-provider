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
import com.margelo.nitro.syncprovider.config.ConfigSource
import com.margelo.nitro.syncprovider.config.SecretStore
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.http.SyncDispatcher
import com.margelo.nitro.syncprovider.testutil.InMemorySecretStore
import com.margelo.nitro.syncprovider.testutil.RuntimeTestSupport
import com.margelo.nitro.syncprovider.testutil.TestData
import com.margelo.nitro.syncprovider.work.BackgroundSyncManager
import com.margelo.nitro.syncprovider.work.RecoveryWorker
import com.margelo.nitro.syncprovider.work.SyncWorker
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * The runtime wired for real: SQLite, SharedPreferences and a test WorkManager. Only
 * the Keystore is faked, which neither Robolectric nor a JVM can provide.
 *
 * [restartProcess] drops the runtime and keeps what is on disk, which is what a worker
 * started with no JS sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SyncProviderRuntimeTest {

  private lateinit var context: Context
  private lateinit var server: MockWebServer
  private lateinit var runtime: SyncProviderRuntime
  private val secrets = InMemorySecretStore()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      context,
      Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )
    RuntimeTestSupport.reset()
    server = MockWebServer().apply { start() }
    runtime = SyncProviderRuntime.bootstrap(context, secrets)
  }

  @After
  fun tearDown() {
    RuntimeTestSupport.awaitRecovery(context)
    RuntimeTestSupport.reset()
    server.shutdown()
  }

  private fun restartProcess() {
    runtime.shutdown()
    runtime = SyncProviderRuntime.bootstrap(context, secrets)
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

  private fun rejectingWithoutToken(): Dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse =
      MockResponse().setResponseCode(if (request.getHeader("Authorization") == TOKEN) 200 else 401)
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

  /* ----------------------------- cold-start config ---------------------------- */

  @Test
  fun `a runtime with nothing configured reports DEFAULTS`() {
    assertThat(runtime.configSource).isEqualTo(ConfigSource.DEFAULTS)
    assertThat(runtime.currentOptions()).isEqualTo(SyncProviderRuntime.defaultOptions())
  }

  @Test
  fun `configureSync in this process reports LIVE, and a restart reports PERSISTED`() = runBlocking {
    runtime.configure(TestData.syncOptions())
    assertThat(runtime.configSource).isEqualTo(ConfigSource.LIVE)

    restartProcess()

    assertThat(runtime.configSource).isEqualTo(ConfigSource.PERSISTED)
  }

  @Test
  fun `a configured maxAttempts survives a fresh runtime`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(retryPolicy = TestData.retryPolicy(maxAttempts = 10)))
    insert("budget", attempts = 6)
    server.enqueue(MockResponse().setResponseCode(200))

    restartProcess()
    runWorker()

    // With the default budget of 5, this row would fail without a single request.
    assertThat(runtime.currentOptions().retryPolicy.maxAttempts).isEqualTo(10.0)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(row("budget")).isNull()
  }

  @Test
  fun `a cold worker honors the configured batchSize`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(batchSize = 2))
    repeat(3) { insert("row-$it", createdAt = it.toLong()) }
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200)
    }

    restartProcess()
    runWorker()

    assertThat(server.requestCount).isEqualTo(2)
  }

  @Test
  fun `a cold worker honors the configured requestTimeoutMs`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(requestTimeoutMs = 1_000L))
    insert("slow")
    val release = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        // Past the default 30s timeout, so only the configured one can end this early.
        release.await(40, TimeUnit.SECONDS)
        return MockResponse().setResponseCode(200)
      }
    }

    restartProcess()
    val startedAt = System.currentTimeMillis()
    runWorker()
    release.countDown()

    assertThat(System.currentTimeMillis() - startedAt).isLessThan(20_000L)
    assertThat(row("slow")!!.attempts).isEqualTo(1)
  }

  @Test
  fun `a cold periodic worker dispatches under a persisted MANUAL, which still gates the kick`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(503))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    runtime.configure(TestData.syncOptions(strategy = SyncStrategy.MANUAL))
    insert("x")

    restartProcess()
    runPeriodicWorker()

    assertThat(runtime.currentOptions().strategy).isEqualTo(SyncStrategy.MANUAL)
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(row("x")!!.nextRetryAt).isGreaterThan(0L)
    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `a fresh runtime applies the configured maxQueueSize`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(maxQueueSize = 321))

    restartProcess()

    assertThat(runtime.storage.maxQueueSize).isEqualTo(321)
  }

  /* --------------------------- cold-worker recovery --------------------------- */

  @Test
  fun `a cold worker dispatches a row a dead process left IN_FLIGHT`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(200))
    insert("orphan", status = SyncItemEntity.STATUS_IN_FLIGHT)

    restartProcess()
    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(1)
    assertThat(row("orphan")).isNull()
  }

  @Test
  fun `a cold worker resets a row still cooling, without dispatching it`() = runBlocking<Unit> {
    val deadline = System.currentTimeMillis() + FAR_MS
    insert("cooling", attempts = 1, nextRetryAt = deadline, status = SyncItemEntity.STATUS_IN_FLIGHT)
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))

    restartProcess()
    runWorker()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("cooling")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row("cooling")!!.nextRetryAt).isEqualTo(deadline)
    // A row left IN_FLIGHT has no wake. Once reset, the cycle that follows arms one.
    assertThat(oneTimeWork()).hasSize(1)
  }

  @Test
  fun `a paused cold worker leaves an orphaned row alone`() = runBlocking<Unit> {
    insert("orphan", status = SyncItemEntity.STATUS_IN_FLIGHT)
    runtime.pause()

    restartProcess()
    runWorker()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("orphan")!!.status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)
  }

  @Test
  fun `a cold worker under foregroundOnly leaves an orphaned row alone`() = runBlocking<Unit> {
    insert("orphan", status = SyncItemEntity.STATUS_IN_FLIGHT)
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = true))

    restartProcess()
    runWorker()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("orphan")!!.status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)
  }

  @Test
  fun `a cold worker resets an orphaned row even when headers cannot be recovered`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    insert("orphan", status = SyncItemEntity.STATUS_IN_FLIGHT)
    secrets.failReads = true

    restartProcess()
    runWorker()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("orphan")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
  }

  @Test
  fun `a worker beside an attached engine leaves recovery to the engine`() = runBlocking<Unit> {
    attachEngineAndRecover()
    insert("claimed", status = SyncItemEntity.STATUS_IN_FLIGHT)

    runWorker()

    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("claimed")!!.status).isEqualTo(SyncItemEntity.STATUS_IN_FLIGHT)
  }

  /* --------------------------------- pause lifetime ----------------------------- */

  @Test
  fun `a cold runtime stays paused and its worker is inert`() = runBlocking<Unit> {
    insert("x")
    runtime.pause()

    restartProcess()
    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(runtime.dispatcher.flush()).isEqualTo(SyncDispatcher.emptyResult())
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("x")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)

    restartProcess() // A runtime built for a worker leaves the flag for the next one.
    assertThat(runtime.isPaused).isTrue()
  }

  @Test
  fun `a cold recovery leaves the pause on and arms nothing`() = runBlocking<Unit> {
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insertFarCooling()
    runtime.pause()

    restartProcess()
    TestListenableWorkerBuilder<RecoveryWorker>(context).build().doWork()

    assertThat(runtime.isPaused).isTrue()
    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `a new JS engine clears the persisted pause, so a later cold worker dispatches`() = runBlocking<Unit> {
    server.enqueue(MockResponse().setResponseCode(200))
    insert("x")
    runtime.pause()
    restartProcess()
    assertThat(runtime.isPaused).isTrue()

    attachEngineAndRecover()
    assertThat(runtime.isPaused).isFalse()

    restartProcess()
    assertThat(runtime.isPaused).isFalse()
    runWorker()
    assertThat(row("x")).isNull()
  }

  @Test
  fun `a new engine is unpaused before its recovery arms the first wake`() = runBlocking<Unit> {
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insertFarCooling()
    runtime.pause()
    mockkConstructor(BackgroundSyncManager::class)
    // Recovery runs at the instant it is scheduled, the worst case for whatever follows.
    every { anyConstructed<BackgroundSyncManager>().scheduleRecovery() } answers {
      runBlocking { TestListenableWorkerBuilder<RecoveryWorker>(context).build().doWork() }
    }
    try {
      restartProcess()

      runtime.attachEngine()

      assertThat(runtime.isRetryTimerArmed).isTrue()
      assertThat(oneTimeWork()).hasSize(1)
    } finally {
      unmockkConstructor(BackgroundSyncManager::class)
    }
  }

  @Test
  fun `an engine arriving after an inert worker wakes the queue in that process`() = runBlocking<Unit> {
    goOnline()
    server.enqueue(MockResponse().setResponseCode(200))
    insert("due", attempts = 1, nextRetryAt = 1L)
    runtime.pause()
    restartProcess()
    runWorker()
    assertThat(server.requestCount).isEqualTo(0)

    runtime.attachEngine()

    awaitGone("due")
    assertThat(server.requestCount).isEqualTo(1)
  }

  @Test
  fun `a reload clears the pause even though the old engine never detached`() = runBlocking<Unit> {
    attachEngineAndRecover()
    runtime.pause()

    attachEngineAndRecover()

    assertThat(runtime.isPaused).isFalse()
  }

  @Test
  fun `a pause set after the engine attached holds until resumeSync`() = runBlocking<Unit> {
    attachEngineAndRecover()
    runtime.pause()
    restartProcess()
    assertThat(runtime.isPaused).isTrue()

    runtime.resume()
    restartProcess()

    assertThat(runtime.isPaused).isFalse()
  }

  /* ----------------------------- restored credentials ------------------------- */

  @Test
  fun `a cold worker authenticates with the persisted default headers`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    insert("auth")
    server.dispatcher = rejectingWithoutToken()

    restartProcess()
    runWorker()

    assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")).isEqualTo(TOKEN)
    assertThat(row("auth")).isNull()
  }

  @Test
  fun `a rejected persisted token leaves the item pending and the worker successful`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to "Bearer expired")))
    insert("auth")
    server.dispatcher = rejectingWithoutToken()

    restartProcess()
    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    val held = row("auth")!!
    assertThat(held.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(held.attempts).isEqualTo(0)
    assertThat(held.lastErrorCode).isEqualTo(SyncErrorCode.UNAUTHORIZED.name)
    assertThat(held.nextRetryAt).isGreaterThan(System.currentTimeMillis())
  }

  @Test
  fun `a 401 stays terminal once JS has configured in this process`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to "Bearer expired")))
    restartProcess()
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to "Bearer expired")))
    insert("auth")
    server.dispatcher = rejectingWithoutToken()

    runtime.dispatcher.flush()

    assertThat(row("auth")!!.status).isEqualTo(SyncItemEntity.STATUS_FAILED)
  }

  @Test
  fun `a 401 stays terminal when no headers were ever persisted`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions())
    insert("auth")
    server.dispatcher = rejectingWithoutToken()

    restartProcess()
    runWorker()

    assertThat(row("auth")!!.status).isEqualTo(SyncItemEntity.STATUS_FAILED)
  }

  @Test
  fun `a logout cannot overtake a configureSync that is still being written`() = runBlocking<Unit> {
    val writing = CountDownLatch(1)
    val finishWrite = CountDownLatch(1)
    val slowSecrets = object : SecretStore by secrets {
      override fun write(value: String): Boolean {
        writing.countDown()
        finishWrite.await(20, TimeUnit.SECONDS)
        return secrets.write(value)
      }
    }
    runtime.shutdown()
    runtime = SyncProviderRuntime.bootstrap(context, slowSecrets)
    val login = async(Dispatchers.IO) {
      runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    }
    assertThat(writing.await(20, TimeUnit.SECONDS)).isTrue()

    val logout = async(Dispatchers.IO) { runtime.configure(TestData.syncOptions(defaultHeaders = null)) }
    delay(100L)

    // Had the logout taken effect in memory, its write would race this one for the disk.
    assertThat(runtime.currentOptions().defaultHeaders).containsKey("Authorization")
    finishWrite.countDown()
    login.await()
    logout.await()

    assertThat(runtime.currentOptions().defaultHeaders).isNull()
    assertThat(secrets.stored).isNull()
  }

  @Test
  fun `configuring without headers deletes them, which is the logout path`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    assertThat(secrets.stored).isNotNull()

    runtime.configure(TestData.syncOptions(defaultHeaders = null))
    assertThat(secrets.stored).isNull()

    restartProcess()
    assertThat(runtime.currentOptions().defaultHeaders).isNull()
  }

  @Test
  fun `headers that cannot be recovered dispatch nothing and the worker succeeds`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    insert("auth")
    secrets.failReads = true

    restartProcess()
    val result = runWorker()

    assertThat(result).isEqualTo(ListenableWorker.Result.success())
    assertThat(server.requestCount).isEqualTo(0)
    assertThat(row("auth")!!.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(row("auth")!!.attempts).isEqualTo(0)
  }

  @Test
  fun `no wake is armed while headers cannot be recovered`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    runtime.backgroundSyncManager.enable(TestData.backgroundSyncOptions(foregroundOnly = false))
    insert("due", attempts = 1, nextRetryAt = 1L)
    secrets.failReads = true

    restartProcess()
    runtime.armRetryWake()

    assertThat(oneTimeWork()).isEmpty()
  }

  @Test
  fun `a worker recovers once the headers can be read again`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    insert("auth")
    server.dispatcher = rejectingWithoutToken()
    secrets.failReads = true
    restartProcess()
    runWorker()
    assertThat(server.requestCount).isEqualTo(0)

    secrets.failReads = false
    runWorker()

    assertThat(row("auth")).isNull()
  }

  @Test
  fun `configureSync lifts the block when headers could not be recovered`() = runBlocking<Unit> {
    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    insert("auth")
    server.dispatcher = rejectingWithoutToken()
    secrets.failReads = true
    restartProcess()

    runtime.configure(TestData.syncOptions(defaultHeaders = mapOf("Authorization" to TOKEN)))
    runtime.dispatcher.flush()

    assertThat(row("auth")).isNull()
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
    const val TOKEN = "Bearer live"
    const val AWAIT_TIMEOUT_MS = 20_000L
    const val FAR_MS = 3_600_000L
  }
}
