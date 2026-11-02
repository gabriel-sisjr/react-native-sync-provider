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
 * Room v1 -> v2 migration coverage.
 *
 * Exercises [SyncDatabase.MIGRATION_1_2] with the real exported schemas
 * (`android/schemas/.../{1,2}.json`, surfaced as test assets by the
 * `android.sourceSets.test.assets.srcDirs` line in `android/build.gradle`):
 *
 *  - [runMigrationsAndValidate] confirms the migrated schema matches the v2
 *    entity byte-for-byte (column + composite index + dropped index).
 *  - A pre-existing v1 row survives with `nextRetryAt` defaulted to 0.
 *  - [SyncDatabase.get], the builder that ships, opens a v1 file at v2.
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
  fun `the shipping builder opens a v1 database at version 2`() = runTest {
    helper.createDatabase(DB_SHIPPING, 1).apply {
      execSQL(insertV1Row(id = V1_ID, attempts = 1, status = "FAILED"))
      close()
    }

    val database = SyncDatabase.get(ApplicationProvider.getApplicationContext<Context>())

    // Opening runs MIGRATION_1_2 and Room's identity-hash validation through the
    // real builder, so a missed version bump or addMigrations fails here.
    assertThat(database.openHelper.writableDatabase.version).isEqualTo(2)
    val row = database.syncItemDao().findById(V1_ID)
    assertThat(row).isNotNull()
    assertThat(row!!.nextRetryAt).isEqualTo(0L)
    assertThat(row.attempts).isEqualTo(1)
    assertThat(row.status).isEqualTo("FAILED")
  }

  @Test
  fun `the shipping builder wipes a store from a newer version instead of failing to open`() = runTest {
    helper.createDatabase(DB_SHIPPING, 2).apply {
      execSQL(insertV1Row(id = V1_ID, attempts = 1, status = "PENDING"))
      execSQL("PRAGMA user_version = 3")
      close()
    }

    val database = SyncDatabase.get(ApplicationProvider.getApplicationContext<Context>())

    assertThat(database.openHelper.writableDatabase.version).isEqualTo(2)
    assertThat(database.syncItemDao().count()).isEqualTo(0)
  }

  private fun SupportSQLiteDatabase.indexNames(): Set<String> =
    query("PRAGMA index_list('sync_items')").use { cursor ->
      buildSet {
        val nameColumn = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) add(cursor.getString(nameColumn))
      }
    }

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

    /** The file name [SyncDatabase.get] opens. */
    const val DB_SHIPPING = "syncprovider.db"
  }
}
