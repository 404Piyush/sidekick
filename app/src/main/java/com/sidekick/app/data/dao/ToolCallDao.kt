package com.sidekick.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sidekick.app.data.ToolCallEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/write API for the `tool_calls` table.
 *
 * The DAO is intentionally small — M3 only needs insert + lookup-by-turn.
 * M4 will add `update` (to fill in [ToolCallEntity.resultJson] when the
 * tool completes) and `deleteByTurn` if we add a "retry tool call" UI.
 */
@Dao
interface ToolCallDao {

    /**
     * All tool calls associated with a turn, in display order (oldest
     * first). Reactive so the transcript screen can render tool rows as
     * the agent loop emits them.
     */
    @Query("SELECT * FROM tool_calls WHERE turnId = :turnId ORDER BY createdAt ASC, id ASC")
    fun getByTurn(turnId: Long): Flow<List<ToolCallEntity>>

    /** One-shot lookup by turn — used by tests and back-fills. */
    @Query("SELECT * FROM tool_calls WHERE turnId = :turnId ORDER BY createdAt ASC, id ASC")
    suspend fun listByTurn(turnId: Long): List<ToolCallEntity>

    /** Insert and return the newly assigned row id. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(call: ToolCallEntity): Long

    /** Update by primary key — used to back-fill `resultJson` when the tool finishes. */
    @Query("UPDATE tool_calls SET resultJson = :resultJson WHERE id = :id")
    suspend fun setResult(id: Long, resultJson: String)
}
