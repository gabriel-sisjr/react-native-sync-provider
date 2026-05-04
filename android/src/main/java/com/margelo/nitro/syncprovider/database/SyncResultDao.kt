package com.margelo.nitro.syncprovider.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface SyncResultDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(result: SyncResultEntity)

  @Query("SELECT * FROM sync_results ORDER BY finishedAt DESC LIMIT 1")
  suspend fun findLast(): SyncResultEntity?

  @Query("SELECT * FROM sync_results ORDER BY finishedAt DESC")
  suspend fun findAll(): List<SyncResultEntity>

  @Query("SELECT * FROM sync_results ORDER BY finishedAt DESC LIMIT :limit")
  suspend fun findLatest(limit: Int): List<SyncResultEntity>

  @Query("DELETE FROM sync_results")
  suspend fun clear()
}
