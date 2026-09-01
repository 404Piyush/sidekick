package com.sidekick.app.data

import android.content.Context
import com.sidekick.app.data.dao.TeammateDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * One-shot seeding for the teammate library. Idempotent: a second invocation
 * after the table is populated is a no-op.
 *
 * Reads each teammate's system prompt from `assets/system-prompts/{id}.md`
 * via [AssetReader]. Production uses [androidAssets]; tests can inject a
 * JVM-classloader-backed reader (Robolectric's asset manager doesn't always
 * see `src/main/assets/` files reliably).
 *
 * Run from the application boot path or lazily from the first DAO call.
 */
object Seed {

    /**
     * Indirection over asset reads. Production binds [androidAssets]; tests
     * can build their own (see TeammateDaoTest).
     */
    fun interface AssetReader {
        fun read(path: String): String
    }

    /** Production [AssetReader]: reads from `assets/` via [Context.getAssets]. */
    fun androidAssets(context: Context): AssetReader = AssetReader { path ->
        context.applicationContext.assets.open(path).bufferedReader().use { it.readText() }
    }

    /**
     * Hard-coded list of teammates shipped with the APK. Slugs MUST match the
     * asset filenames under `assets/system-prompts/`. Display strings live
     * inline here because they're tiny and stable; M3 will move them to
     * `strings.xml` if localisation shows up.
     */
    private val builtInTeammates: List<TeammateSeed> = listOf(
        TeammateSeed(id = "coder", name = "Coder", tagline = "Builds and fixes apps, scripts, and websites."),
        TeammateSeed(id = "builder", name = "Builder", tagline = "Turns an idea or a photo into a finished page."),
        TeammateSeed(id = "researcher", name = "Researcher", tagline = "Reads, summarises, and answers from anything you give it."),
    )

    /**
     * Insert the built-in teammates if the table is currently empty.
     *
     * Safe to call repeatedly — the early return on a non-empty table means
     * the only work after the first call is a single Flow emit.
     *
     * @param context Required to construct the default [androidAssets] reader
     *                when the caller doesn't pass one. Pass `null` only if
     *                you also pass an explicit [reader].
     */
    suspend fun seedIfEmpty(
        dao: TeammateDao,
        context: Context? = null,
        reader: AssetReader? = null,
    ) = withContext(Dispatchers.IO) {
        val activeReader = reader
            ?: requireNotNull(context) { "seedIfEmpty: pass either context or reader" }
                .let { androidAssets(it) }

        val existing = dao.getAll().first()
        if (existing.isNotEmpty()) return@withContext

        builtInTeammates.forEach { seed ->
            val prompt = activeReader.read("system-prompts/${seed.id}.md")
            dao.insert(
                TeammateEntity(
                    id = seed.id,
                    name = seed.name,
                    tagline = seed.tagline,
                    systemPrompt = prompt,
                ),
            )
        }
    }

    private data class TeammateSeed(
        val id: String,
        val name: String,
        val tagline: String,
    )
}
