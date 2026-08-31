package com.sidekick.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.sidekick.app.data.TurnEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/write API for the `turns` table.
 *
 * `getByConversation` is a [Flow] so the transcript screen re-renders on every
 * insert. `countByConversation` is a one-shot suspend — used by the
 * ViewModel to compute the next `position` without subscribing.
 */
@Dao
interface TurnDao {

    /** Turns for a conversation, in display order (oldest first). Reactive. */
    @Query("SELECT * FROM turns WHERE conversationId = :conversationId ORDER BY position ASC")
    fun getByConversation(conversationId: Long): Flow<List<TurnEntity>>

    /** Insert and return the newly assigned row id. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(turn: TurnEntity): Long

    /** Update by primary key — used while the assistant reply is streaming. */
    @Update
    suspend fun update(turn: TurnEntity)

    /** Number of turns in a conversation. One-shot. */
    @Query("SELECT COUNT(*) FROM turns WHERE conversationId = :conversationId")
    suspend fun countByConversation(conversationId: Long): Int
}
