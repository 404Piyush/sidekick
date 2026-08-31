package com.sidekick.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sidekick.app.data.ProviderConfigEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/write API for the `provider_configs` table.
 *
 * The active row is the one with `isActive = true`; [setActive] is the
 * canonical flip (sets all rows to inactive, then activates the target).
 */
@Dao
interface ProviderConfigDao {

    /** Currently active provider config, or `null` if none. One-shot. */
    @Query("SELECT * FROM provider_configs WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ProviderConfigEntity?

    /** All configs, newest first. Reactive. */
    @Query("SELECT * FROM provider_configs ORDER BY id DESC")
    fun getAll(): Flow<List<ProviderConfigEntity>>

    /**
     * Make [id] the only active row. Sets every other row's `isActive` to 0
     * inside a single SQL statement so the invariant holds atomically.
     */
    @Query("UPDATE provider_configs SET isActive = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setActive(id: Long)

    /** Insert and return the newly assigned row id. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(config: ProviderConfigEntity): Long

    /** Update by primary key. */
    @Update
    suspend fun update(config: ProviderConfigEntity)
}
