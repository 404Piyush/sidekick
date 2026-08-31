package com.sidekick.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sidekick.app.data.TeammateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/write API for the `teammates` table. Seeded on first DB open by
 * [com.sidekick.app.data.Seed.seedIfEmpty].
 */
@Dao
interface TeammateDao {

    /** All teammates (small fixed set). Reactive — UI re-renders on change. */
    @Query("SELECT * FROM teammates ORDER BY id ASC")
    fun getAll(): Flow<List<TeammateEntity>>

    /** One-shot lookup by slug. `null` if absent. */
    @Query("SELECT * FROM teammates WHERE id = :id")
    suspend fun getById(id: String): TeammateEntity?

    /** Insert; if a row with the same id already exists, replace it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(teammate: TeammateEntity)
}
