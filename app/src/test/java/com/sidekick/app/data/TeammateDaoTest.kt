package com.sidekick.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.data.dao.TeammateDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the [TeammateDao] and the idempotency of [Seed.seedIfEmpty].
 *
 * The seed reads from `assets/system-prompts/{id}.md`. The M2 test APK
 * carries those files (the Gradle `assets` source set flows into the test
 * APK the same way it does into production), so the production seeder can be
 * invoked unmodified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TeammateDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TeammateDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.teammateDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun seedIfEmptyPopulatesThreeTeammates() = runBlocking {
        assertEquals(0, dao.getAll().first().size)

        Seed.seedIfEmpty(dao, reader = classpathAssetReader())

        val rows = dao.getAll().first()
        assertEquals(3, rows.size)
        assertEquals(setOf("coder", "builder", "researcher"), rows.map { it.id }.toSet())
        // Each seeded row carries a non-blank system prompt pulled from assets.
        rows.forEach {
            assertTrue(
                "Teammate ${it.id} has empty system prompt",
                it.systemPrompt.isNotBlank(),
            )
        }
    }

    @Test
    fun seedIfEmptyIsIdempotent() = runBlocking {
        val reader = classpathAssetReader()
        Seed.seedIfEmpty(dao, reader = reader)
        Seed.seedIfEmpty(dao, reader = reader)
        Seed.seedIfEmpty(dao, reader = reader)

        val rows = dao.getAll().first()
        assertEquals("Idempotent seed must leave exactly 3 rows", 3, rows.size)
    }

    @Test
    fun getByIdReturnsSeedRow() = runBlocking {
        Seed.seedIfEmpty(dao, reader = classpathAssetReader())

        val coder = dao.getById("coder")
        assertEquals("Coder", coder!!.name)
        assertTrue(coder.systemPrompt.contains("Coder"))

        assertEquals(null, dao.getById("nope"))
    }

    /**
     * Robolectric's `AssetManager` doesn't always see files under
     * `src/main/assets/`. Read them straight off the JVM classpath instead —
     * the Gradle build copies `assets/` into the test classpath as resources.
     */
    private fun classpathAssetReader(): Seed.AssetReader = Seed.AssetReader { path ->
        val resource = "/$path"
        Seed::class.java.getResourceAsStream(resource)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Test asset not found on classpath: $resource")
    }

    @Test
    fun manualInsertReplacesById() = runBlocking {
        dao.insert(TeammateEntity(id = "x", name = "X", tagline = "t", systemPrompt = "p"))
        dao.insert(TeammateEntity(id = "x", name = "X2", tagline = "t2", systemPrompt = "p2"))

        assertEquals("X2", dao.getById("x")!!.name)
    }
}
