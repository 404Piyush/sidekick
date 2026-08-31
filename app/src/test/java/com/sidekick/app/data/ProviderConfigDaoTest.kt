package com.sidekick.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.data.dao.ProviderConfigDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * In-memory Room tests for [ProviderConfigDao], especially the active-row
 * invariant enforced by [ProviderConfigDao.setActive].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProviderConfigDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ProviderConfigDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.providerConfigDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getActiveReturnsNullWhenEmpty() = runBlocking {
        assertNull(dao.getActive())
    }

    @Test
    fun setActiveFlipsExactlyOneRow() = runBlocking {
        val a = dao.insert(config("local_ollama", "http://x", null, "qwen"))
        val b = dao.insert(config("cloud_openai", "https://api.example.com/v1", "sk-x", "gpt-4o-mini"))

        // Nothing active yet.
        assertNull(dao.getActive())

        // Activate A.
        dao.setActive(a)
        assertEquals(a, dao.getActive()!!.id)
        assertTrue(dao.getAll().first().single { it.id == a }.isActive)
        assertFalse(dao.getAll().first().single { it.id == b }.isActive)

        // Switch to B. A must flip off.
        dao.setActive(b)
        val active = dao.getActive()
        assertNotNull(active)
        assertEquals(b, active!!.id)
        val all = dao.getAll().first()
        assertFalse("a must no longer be active", all.single { it.id == a }.isActive)
        assertTrue("b must be active", all.single { it.id == b }.isActive)
    }

    @Test
    fun updatePersistsFieldChanges() = runBlocking {
        val id = dao.insert(config("local_ollama", "http://old", null, "qwen"))
        dao.setActive(id)

        val updated = dao.getActive()!!.copy(baseUrl = "http://new", modelName = "llama3")
        dao.update(updated)

        assertEquals("http://new", dao.getActive()!!.baseUrl)
        assertEquals("llama3", dao.getActive()!!.modelName)
        assertTrue(dao.getActive()!!.isActive)
    }

    private fun config(kind: String, baseUrl: String, key: String?, model: String, active: Boolean = false) =
        ProviderConfigEntity(
            providerKind = kind,
            baseUrl = baseUrl,
            apiKey = key,
            modelName = model,
            isActive = active,
        )
}
