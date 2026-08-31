package com.sidekick.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.data.dao.ConversationDao
import com.sidekick.app.data.dao.TurnDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * In-memory Room tests for [TurnDao]: position arithmetic, Flow emission,
 * update-during-stream, and per-conversation counting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TurnDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var turnDao: TurnDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversationDao = db.conversationDao()
        turnDao = db.turnDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun countByConversationReflectsInserts() = runBlocking {
        val cid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "t", createdAt = 0L, updatedAt = 0L),
        )
        assertEquals(0, turnDao.countByConversation(cid))
        turnDao.insert(TurnEntity(conversationId = cid, role = "user", content = "a", position = 0, createdAt = 1L))
        assertEquals(1, turnDao.countByConversation(cid))
        turnDao.insert(TurnEntity(conversationId = cid, role = "assistant", content = "b", position = 1, createdAt = 2L))
        assertEquals(2, turnDao.countByConversation(cid))
    }

    @Test
    fun countIgnoresOtherConversations() = runBlocking {
        val a = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "a", createdAt = 0L, updatedAt = 0L),
        )
        val b = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "b", createdAt = 0L, updatedAt = 0L),
        )
        turnDao.insert(TurnEntity(conversationId = a, role = "user", content = "x", position = 0, createdAt = 0L))
        turnDao.insert(TurnEntity(conversationId = b, role = "user", content = "y", position = 0, createdAt = 0L))
        turnDao.insert(TurnEntity(conversationId = b, role = "user", content = "z", position = 1, createdAt = 0L))
        assertEquals(1, turnDao.countByConversation(a))
        assertEquals(2, turnDao.countByConversation(b))
    }

    @Test
    fun getByConversationIsReactive() = runBlocking {
        val cid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "t", createdAt = 0L, updatedAt = 0L),
        )
        turnDao.insert(TurnEntity(conversationId = cid, role = "user", content = "u", position = 0, createdAt = 1L))

        val first = turnDao.getByConversation(cid).first()
        assertEquals(1, first.size)

        turnDao.insert(TurnEntity(conversationId = cid, role = "assistant", content = "a", position = 1, createdAt = 2L))
        val second = turnDao.getByConversation(cid).first()
        assertEquals(2, second.size)
    }

    @Test
    fun updateChangesContentInPlace() = runBlocking {
        val cid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "t", createdAt = 0L, updatedAt = 0L),
        )
        val id = turnDao.insert(
            TurnEntity(conversationId = cid, role = "assistant", content = "", position = 0, createdAt = 1L),
        )

        turnDao.update(TurnEntity(id = id, conversationId = cid, role = "assistant", content = "hello world", position = 0, createdAt = 1L))

        val turns = turnDao.getByConversation(cid).first()
        assertEquals("hello world", turns.single { it.id == id }.content)
    }

    @Test
    fun reorderByChangingPosition() = runBlocking {
        val cid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "t", createdAt = 0L, updatedAt = 0L),
        )
        val a = turnDao.insert(TurnEntity(conversationId = cid, role = "user", content = "A", position = 0, createdAt = 0L))
        val b = turnDao.insert(TurnEntity(conversationId = cid, role = "user", content = "B", position = 1, createdAt = 0L))
        val c = turnDao.insert(TurnEntity(conversationId = cid, role = "user", content = "C", position = 2, createdAt = 0L))

        // Move B to the front by rewriting everyone's position.
        turnDao.update(TurnEntity(id = b, conversationId = cid, role = "user", content = "B", position = 0, createdAt = 0L))
        turnDao.update(TurnEntity(id = a, conversationId = cid, role = "user", content = "A", position = 1, createdAt = 0L))
        turnDao.update(TurnEntity(id = c, conversationId = cid, role = "user", content = "C", position = 2, createdAt = 0L))

        val ordered = turnDao.getByConversation(cid).first().map { it.content }
        assertEquals(listOf("B", "A", "C"), ordered)
    }
}
