package com.sidekick.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One message in a [ConversationEntity]. The composite index on
 * `(conversationId, position)` makes ordered reads and position-based pagination
 * cheap.
 *
 * `onDelete = CASCADE` on the foreign key means deleting a conversation
 * automatically clears its turns. Tested in [com.sidekick.app.data.ConversationDaoTest].
 *
 * @property role `"user"`, `"assistant"`, or `"system"`.
 * @property position 0-based ordinal within the conversation. The DAO queries
 *                    order by this ascending.
 */
@Entity(
    tableName = "turns",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId", "position"])],
)
data class TurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: Long,
    val role: String,
    val content: String,
    val position: Int,
    val createdAt: Long,
)
