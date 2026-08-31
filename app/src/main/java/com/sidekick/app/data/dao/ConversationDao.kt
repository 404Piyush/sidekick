package com.sidekick.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sidekick.app.data.ConversationEntity
import kotlinx.coroutines.flow.Flow

/**
 * Read/write API for the `conversations` table.
 *
 * Mutations are non-suspending `suspend fun`s (Room runs them on its own
 * background dispatcher); reads of one row return suspend values, reads of
 * many return [Flow] so the UI auto-refreshes on change.
 */
@Dao
interface ConversationDao {

    /** All conversations, newest-touched first. Reactive. */
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    /** All conversations for a teammate. Reactive. */
    @Query("SELECT * FROM conversations WHERE teammate = :slug ORDER BY updatedAt DESC")
    fun getByTeammate(slug: String): Flow<List<ConversationEntity>>

    /** One-shot lookup by primary key. `null` if absent. */
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): ConversationEntity?

    /** Insert and return the newly assigned row id. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(c: ConversationEntity): Long

    /** Hard-delete a conversation. CASCADE wipes its turns. */
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    /** Bump `updatedAt` to now — call after appending a turn. */
    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)
}
