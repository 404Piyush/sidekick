package com.sidekick.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persisted chat thread. Each thread belongs to a teammate (by slug string —
 * not the teammate's primary key — so renaming a teammate doesn't orphan
 * conversations).
 *
 * @property teammate Slug of the teammate the thread belongs to
 *                    (`"coder"`, `"builder"`, `"researcher"`).
 * @property title User-facing title, defaults to the first user message.
 * @property createdAt Unix millis at creation.
 * @property updatedAt Unix millis of the most recent message / title change.
 *                    The transcript screen sorts by this descending.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val teammate: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)
