package com.sidekick.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.data.dao.ConversationDao
import com.sidekick.app.data.dao.ToolCallDao
import com.sidekick.app.data.dao.TurnDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * In-memory Room tests for [ToolCallDao]: insert, reactive Flow lookup,
 * `setResult` back-fill, and CASCADE delete via the parent turn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolCallDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var turnDao: TurnDao
    private lateinit var toolCallDao: ToolCallDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversationDao = db.conversationDao()
        turnDao = db.turnDao()
        toolCallDao = db.toolCallDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndListByTurn() = runBlocking {
        val cid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "t", createdAt = 0L, updatedAt = 0L),
        )
        val turnId = turnDao.insert(
            TurnEntity(conversationId = cid, role = "assistant", content = "thinking...", position = 0, createdAt = 1L),
        )
        val id = toolCallDao.insert(
            ToolCallEntity(
                turnId = turnId,
                toolName = "read_file",
                argsJson = """{"path":"notes/todo.md"}""",
                resultJson = null,
                createdAt = 2L,
            ),
        )
        assertTrue("id should be auto-assigned", id > 0)

        val rows = toolCallDao.listByTurn(turnId)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(turnId, row.turnId)
        assertEquals("read_file", row.toolName)
        assertEquals("""{"path":"notes/todo.md"}""", row.argsJson)
        assertNull(row.resultJson)
    }

    @Test
    fun getByTurnIsReactive() = runBlocking {
        val cid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "t", createdAt = 0L, updatedAt = 0L),
        )
        val turnId = turnDao.insert(
            TurnEntity(conversationId = cid, role = "assistant", content = "", position = 0, createdAt = 0L),
        )

        // Initial empty.
        assertEquals(0, toolCallDao.getByTurn(turnId).first().size)

        // Insert one; Flow should re-emit.
        toolCallDao.insert(
            ToolCallEntity(
                turnId = turnId,
                toolName = "list_dir",
                argsJson = "{}",
                resultJson = null,
                createdAt = 1L,
            ),
        )
        val first = toolCallDao.getByTurn(turnId).first()
        assertEquals(1, first.size)
        assertEquals("list_dir", first.single().toolName)
    }

    @Test
    fun setResultBackfillsResultJson() = runBlocking {
        val cid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "t", createdAt = 0L, updatedAt = 0L),
        )
        val turnId = turnDao.insert(
            TurnEntity(conversationId = cid, role = "assistant", content = "", position = 0, createdAt = 0L),
        )
        val id = toolCallDao.insert(
            ToolCallEntity(
                turnId = turnId,
                toolName = "read_file",
                argsJson = """{"path":"a.txt"}""",
                resultJson = null,
                createdAt = 1L,
            ),
        )

        toolCallDao.setResult(id, """{"ok":"contents"}""")

        val updated = toolCallDao.listByTurn(turnId).single()
        assertNotNull(updated.resultJson)
        assertEquals("""{"ok":"contents"}""", updated.resultJson)
    }

    @Test
    fun deletingTurnCascadesToolCalls() = runBlocking {
        val cid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "t", createdAt = 0L, updatedAt = 0L),
        )
        val turnId = turnDao.insert(
            TurnEntity(conversationId = cid, role = "assistant", content = "x", position = 0, createdAt = 0L),
        )
        toolCallDao.insert(
            ToolCallEntity(turnId = turnId, toolName = "read_file", argsJson = "{}", resultJson = null, createdAt = 0L),
        )
        assertEquals(1, toolCallDao.listByTurn(turnId).size)

        // Delete the parent turn. Tool calls should cascade away.
        db.openHelper.writableDatabase.execSQL("DELETE FROM turns WHERE id = $turnId")
        assertEquals(0, toolCallDao.listByTurn(turnId).size)
    }
}
