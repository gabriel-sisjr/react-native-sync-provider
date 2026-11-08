package com.margelo.nitro.syncprovider.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.margelo.nitro.syncprovider.SyncErrorCode
import com.margelo.nitro.syncprovider.database.SyncDatabase
import com.margelo.nitro.syncprovider.database.SyncItemEntity
import com.margelo.nitro.syncprovider.testutil.TestData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A replay holds one write transaction from its existence check to its delete, so a purge, a
 * discard or a second replay of the same id waits for it: none can slip in between and bring
 * a purged dead letter back to the queue, or replay it twice.
 *
 * On a WAL file database, as on most devices, with the replay parked inside its transaction
 * right before its first write.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class DeadLetterReplayRaceTest {

  private lateinit var context: Context
  private lateinit var db: SyncDatabase
  private lateinit var storage: SyncQueueStorage

  /** While set, the next statement that writes a queue row parks before it runs. */
  private val parkNextQueueInsert = AtomicBoolean(false)
  private val parked = CountDownLatch(1)
  private val release = CountDownLatch(1)
  private val statements: MutableList<String> = Collections.synchronizedList(mutableListOf())

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.databaseBuilder(context, SyncDatabase::class.java, DB_NAME)
      .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
      .setQueryCallback(
        object : RoomDatabase.QueryCallback {
          override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
            statements += sqlQuery
            val queueInsert = sqlQuery.startsWith("INSERT") && sqlQuery.contains("sync_items")
            if (queueInsert && parkNextQueueInsert.compareAndSet(true, false)) {
              parked.countDown()
              release.await(20, TimeUnit.SECONDS)
            }
          }
        },
        // On the thread that runs the statement, so parking keeps its transaction open.
        { it.run() },
      )
      .build()
    storage = SyncQueueStorage.from(db)
  }

  @After
  fun tearDown() {
    release.countDown()
    db.close()
    context.deleteDatabase(DB_NAME)
  }

  /** A dead letter made the way a terminal failure makes one. */
  private suspend fun deadLetter(): String {
    val id = storage.enqueue(TestData.input())
    val row = checkNotNull(db.syncItemDao().findById(id))
    check(
      storage.moveToDeadLetters(
        entity = row,
        attempts = 3,
        lastAttemptAt = 1L,
        lastErrorCode = SyncErrorCode.SERVER_ERROR,
        lastErrorMessage = "HTTP 500",
        deadLetteredAt = 2L,
      ),
    )
    return id
  }

  /**
   * Replays [id] and parks it after its existence and capacity checks, inside its
   * transaction. [racer] starts meanwhile, and the replay is released once the racer has had
   * time to finish had nothing held it back.
   */
  private suspend fun <T> CoroutineScope.raceParkedReplay(
    id: String,
    racer: suspend () -> T,
  ): Pair<Boolean, T> {
    parkNextQueueInsert.set(true)
    val replay = async(Dispatchers.IO) { storage.replayDeadLetter(id) }
    assertWithMessage("the replay never reached its insert; statements: $statements")
      .that(parked.await(20, TimeUnit.SECONDS))
      .isTrue()
    val racing = async(Dispatchers.IO) { racer() }
    Thread.sleep(RACE_WINDOW_MS)
    val ranInside = racing.isCompleted
    release.countDown()
    val outcome = replay.await() to racing.await()
    assertWithMessage("ran while the replay held its transaction").that(ranInside).isFalse()
    return outcome
  }

  @Test
  fun `a clearQueue racing a replay waits for it, and takes the replayed row too`() = runBlocking<Unit> {
    val id = deadLetter()

    val (replayed, _) = raceParkedReplay(id) { storage.clear() }

    assertThat(replayed).isTrue()
    assertThat(storage.count()).isEqualTo(0)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
  }

  @Test
  fun `a discard racing a replay waits for it, then finds nothing to discard`() = runBlocking<Unit> {
    val id = deadLetter()

    val (replayed, discarded) = raceParkedReplay(id) { storage.discardDeadLetter(id) }

    assertThat(replayed).isTrue()
    assertThat(discarded).isFalse()
    assertThat(db.syncItemDao().findById(id)?.status).isEqualTo(SyncItemEntity.STATUS_PENDING)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
  }

  @Test
  fun `a second replay racing the first waits for it, then finds nothing to replay`() = runBlocking<Unit> {
    val id = deadLetter()

    val (first, second) = raceParkedReplay(id) { storage.replayDeadLetter(id) }

    assertThat(first).isTrue()
    assertThat(second).isFalse()
    assertThat(storage.count()).isEqualTo(1)
    assertThat(db.syncDeadLetterDao().count()).isEqualTo(0)
  }

  private companion object {
    const val DB_NAME = "dead-letter-replay-race.db"

    /** Far longer than any of the racers takes when nothing holds it back. */
    const val RACE_WINDOW_MS = 300L
  }
}
