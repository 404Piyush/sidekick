package com.sidekick.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.data.dao.ConversationDao
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
 * In-memory Room tests for [ConversationDao] and the
 * `conversations -> turns` CASCADE foreign key.
 *
 * Uses Robolectric because in-memory Room needs a real Android
 * [android.content.Context] (we reuse it for the [Seed] tests too).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationDaoTest {

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
    fun insertAndLookupRoundTrip() = runBlocking {
        val now = 1_700_000_000_000L
        val id = conversationDao.insert(
            ConversationEntity(
                teammate = "coder",
                title = "Hello",
                createdAt = now,
                updatedAt = now,
            ),
        )
        assertTrue("id should be > 0 after autoGenerate insert", id > 0)

        val fetched = conversationDao.getById(id)
        assertNotNull(fetched)
        assertEquals("coder", fetched!!.teammate)
        assertEquals("Hello", fetched.title)
        assertEquals(now, fetched.updatedAt)
    }

    @Test
    fun getByIdReturnsNullWhenMissing() = runBlocking {
        assertNull(conversationDao.getById(99_999L))
    }

    @Test
    fun getAllEmitsInUpdatedAtDescendingOrder() = runBlocking {
        val base = 1_700_000_000_000L
        val old = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "old", createdAt = base, updatedAt = base),
        )
        val mid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "mid", createdAt = base + 1, updatedAt = base + 100),
        )
        val new = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "new", createdAt = base + 2, updatedAt = base + 200),
        )

        val rows = conversationDao.getAll().first()
        assertEquals(listOf(new, mid, old), rows.map { it.id })
    }

    @Test
    fun getByTeammateFiltersAndOrdersByUpdatedAtDesc() = runBlocking {
        val base = 1_700_000_000_000L
        val c1 = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "a", createdAt = base, updatedAt = base + 5),
        )
        val b1 = conversationDao.insert(
            ConversationEntity(teammate = "builder", title = "b", createdAt = base, updatedAt = base + 50),
        )
        val c2 = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "c", createdAt = base, updatedAt = base + 30),
        )

        val coderRows = conversationDao.getByTeammate("coder").first()
        assertEquals(listOf(c2, c1), coderRows.map { it.id })

        val builderRows = conversationDao.getByTeammate("builder").first()
        assertEquals(listOf(b1), builderRows.map { it.id })
    }

    @Test
    fun touchUpdatesUpdatedAt() = runBlocking {
        val id = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "x", createdAt = 0L, updatedAt = 0L),
        )
        conversationDao.touch(id, now = 9_999L)
        assertEquals(9_999L, conversationDao.getById(id)!!.updatedAt)
    }

    @Test
    fun turnsAreOrderedByPositionAscending() = runBlocking {
        val cid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "t", createdAt = 0L, updatedAt = 0L),
        )
        turnDao.insert(TurnEntity(conversationId = cid, role = "user", content = "u0", position = 0, createdAt = 1L))
        turnDao.insert(TurnEntity(conversationId = cid, role = "assistant", content = "a0", position = 1, createdAt = 2L))
        turnDao.insert(TurnEntity(conversationId = cid, role = "user", content = "u1", position = 2, createdAt = 3L))

        val turns = turnDao.getByConversation(cid).first()
        assertEquals(listOf("u0", "a0", "u1"), turns.map { it.content })
    }

    @Test
    fun deletingConversationCascadesTurns() = runBlocking {
        val cid = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "t", createdAt = 0L, updatedAt = 0L),
        )
        turnDao.insert(TurnEntity(conversationId = cid, role = "user", content = "x", position = 0, createdAt = 0L))
        turnDao.insert(TurnEntity(conversationId = cid, role = "assistant", content = "y", position = 1, createdAt = 0L))
        assertEquals(2, turnDao.countByConversation(cid))

        conversationDao.delete(cid)

        assertEquals(0, turnDao.countByConversation(cid))
        assertNull(conversationDao.getById(cid))
    }
}
