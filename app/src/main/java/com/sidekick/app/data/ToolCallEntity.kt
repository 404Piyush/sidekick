package com.sidekick.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted record of one [com.sidekick.app.tools.Tool] invocation
 * triggered by the agent loop. Rendered inline in the transcript so the
 * user sees "Coder used read_file(notes/todo.md) → 'TODO list...'" between
 * assistant messages.
 *
 * M3 attaches one row per emitted [com.sidekick.app.agent.AgentEvent.ToolCall].
 * M4 may extend with fields for image attachments, error status, etc.
 *
 * @property turnId The assistant [TurnEntity] that preceded this tool call.
 *                  Foreign-keyed with `onDelete = CASCADE` so deleting the
 *                  parent turn also clears its tool calls.
 * @property toolName The model's chosen tool (e.g. `"read_file"`). Stored
 *                    verbatim so the transcript renderer doesn't need to
 *                    look it up.
 * @property argsJson Serialised arguments — the [kotlinx.serialization.json.JsonObject]
 *                    the model emitted, encoded as a JSON string. We
 *                    store as text rather than a child table because the
 *                    schema is tool-specific and the UI only renders a
 *                    one-line summary.
 * @property resultJson Serialised [com.sidekick.app.tools.ToolResult] —
 *                      `{"ok": "..."}` or `{"err": "..."}`. `null` while
 *                      the tool is still running.
 * @property createdAt Unix millis at invocation time.
 */
@Entity(
    tableName = "tool_calls",
    foreignKeys = [
        ForeignKey(
            entity = TurnEntity::class,
            parentColumns = ["id"],
            childColumns = ["turnId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["turnId"])],
)
data class ToolCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val turnId: Long,
    val toolName: String,
    val argsJson: String,
    val resultJson: String?,
    val createdAt: Long,
)
