package com.margelo.nitro.syncprovider.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.margelo.nitro.syncprovider.testutil.RuntimeTestSupport
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room migration coverage, v1 -> v2 -> v3.
 *
 * Exercises [SyncDatabase.MIGRATION_1_2] and [SyncDatabase.MIGRATION_2_3] with the real
 * exported schemas (`android/schemas/.../{1,2,3}.json`, surfaced as test assets by the
 * `android.sourceSets.test.assets.srcDirs` line in `android/build.gradle`):
 *
 *  - [runMigrationsAndValidate] confirms each migrated schema matches its entities
 *    byte-for-byte (columns, indices, the new table).
 *  - A pre-existing v1 row survives with `nextRetryAt` defaulted to 0.
 *  - v2 -> v3, and v1 -> v2 -> v3, move every `FAILED` row to the dead letters and
 *    leave `PENDING` and `IN_FLIGHT` rows as they are.
 *  - [SyncDatabase.get], the builder that ships, opens a v1 file at v3.
 *  - That same builder wipes a store from a newer version: a downgrade is
 *    destructive, by design.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
internal class SyncDatabaseMigrationTest {

  @get:Rule
  val helper: MigrationTestHelper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    SyncDatabase::class.java,
  )

  @Before
  fun setUp() = RuntimeTestSupport.reset()

  @After
  fun tearDown() = RuntimeTestSupport.reset()

  @Test
  fun `MIGRATION_1_2 validates against the v2 schema and preserves existing rows`() {
    helper.createDatabase(DB_VALIDATE, 1).apply {
      execSQL(insertV1Row(id = V1_ID, attempts = 2, status = "PENDING"))
      execSQL(insertV1Row(id = V1_IN_FLIGHT_ID, attempts = 1, status = "IN_FLIGHT"))
      close()
    }

    // runMigrationsAndValidate throws if the post-migration schema diverges
    // from the exported 2.json (column affinity, NOT NULL, default, indices).
    val migrated = helper.runMigrationsAndValidate(
      DB_VALIDATE,
      2,
      true,
      SyncDatabase.MIGRATION_1_2,
    )

    migrated.query("SELECT * FROM sync_items WHERE id = '$V1_ID'").use { cursor ->
      assertThat(cursor.moveToFirst()).isTrue()
      fun text(column: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(column))
      fun number(column: String): Long = cursor.getLong(cursor.getColumnIndexOrThrow(column))

      // The new NOT NULL column defaulted to 0, and every legacy column is intact.
      assertThat(number("nextRetryAt")).isEqualTo(0L)
      assertThat(text("method")).isEqualTo("POST")
      assertThat(text("url")).isEqualTo("https://api.example.com/v1/legacy")
      assertThat(text("headers")).isEqualTo("""{"X-Legacy":"1"}""")
      assertThat(text("body")).isEqualTo("""{"a":1}""")
      assertThat(text("contentType")).isEqualTo("application/json")
      assertThat(text("priority")).isEqualTo("HIGH")
      assertThat(number("priorityWeight")).isEqualTo(0L)
      assertThat(number("createdAt")).isEqualTo(1_700_000_000_000L)
      assertThat(number("attempts")).isEqualTo(2L)
      assertThat(number("lastAttemptAt")).isEqualTo(1_700_000_005_000L)
      assertThat(text("lastErrorCode")).isEqualTo("TIMEOUT")
      assertThat(text("status")).isEqualTo("PENDING")
      assertThat(text("metadata")).isEqualTo("""{"origin":"v1"}""")
    }
    migrated.query("SELECT status FROM sync_items WHERE id = '$V1_IN_FLIGHT_ID'").use { cursor ->
      assertThat(cursor.moveToFirst()).isTrue()
      assertThat(cursor.getString(0)).isEqualTo("IN_FLIGHT")
    }
    helper.closeWhenFinished(migrated)
  }

  @Test
  fun `MIGRATION_1_2 swaps the status index for the composite status_nextRetryAt index`() {
    helper.createDatabase(DB_INDEX, 1).apply {
      // Sanity: the v1 single-column status index exists before migrating.
      assertThat(indexNames()).contains("index_sync_items_status")
      close()
    }

    val migrated = helper.runMigrationsAndValidate(
      DB_INDEX,
      2,
      true,
      SyncDatabase.MIGRATION_1_2,
    )

    val indices = migrated.indexNames()
    assertThat(indices).contains("index_sync_items_status_nextRetryAt")
    assertThat(indices).contains("index_sync_items_priorityWeight_createdAt")
    // The redundant single-column index is dropped (left-prefix of the composite).
    assertThat(indices).doesNotContain("index_sync_items_status")
    helper.closeWhenFinished(migrated)
  }

  @Test
  fun `migration_v2_movesFailedRows_keepsPending`() {
    helper.createDatabase(DB_V2, 2).apply {
      execSQL(insertV2Row(id = "pending", status = "PENDING", nextRetryAt = 1_700_000_090_000L))
      execSQL(insertV2Row(id = "in-flight", status = "IN_FLIGHT"))
      execSQL(insertV2Row(id = "failed", status = "FAILED", lastAttemptAt = "1700000005000"))
      execSQL(insertV2Row(id = "never-tried", status = "FAILED", lastAttemptAt = "NULL", lastErrorCode = "NULL"))
      execSQL(insertV2Row(id = "zero", status = "FAILED", lastAttemptAt = "0", lastErrorCode = "''"))
      close()
    }

    val migrated = helper.runMigrationsAndValidate(DB_V2, 3, true, SyncDatabase.MIGRATION_2_3)

    assertQueueKeptLiveRows(migrated)
    assertFailedRowsMoved(migrated)
    helper.closeWhenFinished(migrated)
  }

  @Test
  fun `migration_v1_movesFailedRows_keepsPending, through v2`() {
    helper.createDatabase(DB_V1, 1).apply {
      execSQL(insertV1Row(id = "pending", attempts = 2, status = "PENDING"))
      execSQL(insertV1Row(id = "in-flight", attempts = 1, status = "IN_FLIGHT"))
      execSQL(insertV1Row(id = "failed", attempts = 3, status = "FAILED"))
      close()
    }

    val migrated = helper.runMigrationsAndValidate(
      DB_V1,
      3,
      true,
      SyncDatabase.MIGRATION_1_2,
      SyncDatabase.MIGRATION_2_3,
    )

    migrated.query("SELECT id, status, attempts FROM sync_items ORDER BY id").use { cursor ->
      val rows = buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getInt(2))) }
      assertThat(rows).containsExactly(Triple("in-flight", "IN_FLIGHT", 1), Triple("pending", "PENDING", 2)).inOrder()
    }
    migrated.query("SELECT * FROM sync_dead_letters").use { cursor ->
      assertThat(cursor.count).isEqualTo(1)
      cursor.moveToFirst()
      fun text(column: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(column))
      fun number(column: String): Long = cursor.getLong(cursor.getColumnIndexOrThrow(column))
      assertThat(text("id")).isEqualTo("failed")
      assertThat(text("url")).isEqualTo("https://api.example.com/v1/legacy")
      assertThat(text("headers")).isEqualTo("""{"X-Legacy":"1"}""")
      assertThat(text("metadata")).isEqualTo("""{"origin":"v1"}""")
      assertThat(number("attempts")).isEqualTo(3L)
      assertThat(number("lastAttemptAt")).isEqualTo(1_700_000_005_000L)
      assertThat(number("deadLetteredAt")).isEqualTo(1_700_000_005_000L)
      assertThat(text("lastErrorCode")).isEqualTo("TIMEOUT")
      assertThat(text("lastErrorMessage")).isNull()
    }
    helper.closeWhenFinished(migrated)
  }

  @Test
  fun `the shipping builder opens a v1 database at version 3, its FAILED rows moved`() = runTest {
    helper.createDatabase(DB_SHIPPING, 1).apply {
      execSQL(insertV1Row(id = V1_ID, attempts = 1, status = "FAILED"))
      execSQL(insertV1Row(id = V1_IN_FLIGHT_ID, attempts = 1, status = "PENDING"))
      close()
    }

    val database = SyncDatabase.get(ApplicationProvider.getApplicationContext<Context>())

    // Opening runs both migrations and Room's identity-hash validation through the
    // real builder, so a missed version bump or addMigrations fails here.
    assertThat(database.openHelper.writableDatabase.version).isEqualTo(3)
    assertThat(database.syncItemDao().findById(V1_ID)).isNull()
    val moved = checkNotNull(database.syncDeadLetterDao().findById(V1_ID))
    assertThat(moved.attempts).isEqualTo(1)
    assertThat(moved.lastErrorCode).isEqualTo("TIMEOUT")
    val kept = checkNotNull(database.syncItemDao().findById(V1_IN_FLIGHT_ID))
    assertThat(kept.nextRetryAt).isEqualTo(0L)
    assertThat(kept.status).isEqualTo("PENDING")
  }

  @Test
  fun `the shipping builder wipes a store from a newer version instead of failing to open`() = runTest {
    helper.createDatabase(DB_SHIPPING, 3).apply {
      execSQL(insertV2Row(id = V1_ID, status = "PENDING"))
      execSQL("PRAGMA user_version = 4")
      close()
    }

    val database = SyncDatabase.get(ApplicationProvider.getApplicationContext<Context>())

    assertThat(database.openHelper.writableDatabase.version).isEqualTo(3)
    assertThat(database.syncItemDao().count()).isEqualTo(0)
    assertThat(database.syncDeadLetterDao().count()).isEqualTo(0)
  }

  /** `PENDING` and `IN_FLIGHT` rows of the v2 fixture, as they were. */
  private fun assertQueueKeptLiveRows(db: SupportSQLiteDatabase) {
    db.query("SELECT id, status, nextRetryAt, attempts FROM sync_items ORDER BY id").use { cursor ->
      val rows = buildList {
        while (cursor.moveToNext()) {
          add(listOf(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3)))
        }
      }
      assertThat(rows).containsExactly(
        listOf("in-flight", "IN_FLIGHT", 0L, 2),
        listOf("pending", "PENDING", 1_700_000_090_000L, 2),
      ).inOrder()
    }
  }

  /** The three `FAILED` rows of the v2 fixture, moved with the legacy fallbacks. */
  private fun assertFailedRowsMoved(db: SupportSQLiteDatabase) {
    db.query("SELECT * FROM sync_dead_letters ORDER BY id").use { cursor ->
      val rows = buildMap {
        while (cursor.moveToNext()) {
          fun text(column: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(column))
          fun number(column: String): Long = cursor.getLong(cursor.getColumnIndexOrThrow(column))
          assertThat(text("lastErrorMessage")).isNull()
          assertThat(text("method")).isEqualTo("PUT")
          assertThat(text("body")).isEqualTo("""{"b":2}""")
          assertThat(text("priority")).isEqualTo("LOW")
          assertThat(number("priorityWeight")).isEqualTo(2L)
          assertThat(number("attempts")).isEqualTo(2L)
          put(
            checkNotNull(text("id")),
            Triple(number("deadLetteredAt"), number("lastAttemptAt"), text("lastErrorCode")),
          )
        }
      }
      assertThat(rows).containsExactly(
        // lastAttemptAt > 0: it dates the move too.
        "failed", Triple(1_700_000_005_000L, 1_700_000_005_000L, "SERVER_ERROR"),
        // None, or 0: createdAt stands in for both, and a missing code falls back.
        "never-tried", Triple(1_700_000_000_000L, 1_700_000_000_000L, "MAX_ATTEMPTS_EXCEEDED"),
        "zero", Triple(1_700_000_000_000L, 1_700_000_000_000L, "MAX_ATTEMPTS_EXCEEDED"),
      )
    }
  }

  private fun SupportSQLiteDatabase.indexNames(): Set<String> =
    query("PRAGMA index_list('sync_items')").use { cursor ->
      buildSet {
        val nameColumn = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) add(cursor.getString(nameColumn))
      }
    }

  private fun insertV2Row(
    id: String,
    status: String,
    nextRetryAt: Long = 0L,
    lastAttemptAt: String = "1700000005000",
    lastErrorCode: String = "'SERVER_ERROR'",
  ): String =
    """
    INSERT INTO sync_items
      (id, method, url, headers, body, contentType, priority, priorityWeight,
       createdAt, attempts, lastAttemptAt, lastErrorCode, status, metadata, nextRetryAt)
    VALUES
      ('$id', 'PUT', 'https://api.example.com/v2/legacy', '{"X-Legacy":"2"}',
       '{"b":2}', 'application/json', 'LOW', 2,
       1700000000000, 2, $lastAttemptAt, $lastErrorCode, '$status', '{"origin":"v2"}', $nextRetryAt)
    """.trimIndent()

  private fun insertV1Row(id: String, attempts: Int, status: String): String =
    """
    INSERT INTO sync_items
      (id, method, url, headers, body, contentType, priority, priorityWeight,
       createdAt, attempts, lastAttemptAt, lastErrorCode, status, metadata)
    VALUES
      ('$id', 'POST', 'https://api.example.com/v1/legacy', '{"X-Legacy":"1"}',
       '{"a":1}', 'application/json', 'HIGH', 0,
       1700000000000, $attempts, 1700000005000, 'TIMEOUT', '$status', '{"origin":"v1"}')
    """.trimIndent()

  private companion object {
    const val V1_ID = "01HV1ROW"
    const val V1_IN_FLIGHT_ID = "01HV1INFLIGHT"
    const val DB_VALIDATE = "migration-validate.db"
    const val DB_INDEX = "migration-index.db"
    const val DB_V1 = "migration-v1.db"
    const val DB_V2 = "migration-v2.db"

    /** The file name [SyncDatabase.get] opens. */
    const val DB_SHIPPING = "syncprovider.db"
  }
}
