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
 * Reads each teammate's system prompt from `assets/system-prompts/{id}.md`,
 * then inserts [TeammateEntity] rows for Coder, Builder, Researcher if and
 * only if the table is currently empty.
 *
 * Run from the application boot path or lazily from the first DAO call.
 */
object Seed {

    /**
     * Hard-coded list of teammates shipped with the APK. Slugs MUST match the
     * asset filenames under `assets/system-prompts/`. Display strings live
     * inline here because they're tiny and stable; M3 will move them to
     * `strings.xml` if localisation shows up.
     */
    private val builtInTeammates: List<TeammateSeed> = listOf(
        TeammateSeed(
            id = "coder",
            name = "Coder",
            tagline = "Refactors Kotlin, reads stack traces.",
        ),
        TeammateSeed(
            id = "builder",
            name = "Builder",
            tagline = "Drafts sites, scripts, configs.",
        ),
        TeammateSeed(
            id = "researcher",
            name = "Researcher",
            tagline = "Summarizes sources, cites links.",
        ),
    )

    /**
     * Insert the built-in teammates if the table is currently empty.
     *
     * Safe to call repeatedly — the early return on a non-empty table means
     * the only work after the first call is a single Flow emit.
     */
    suspend fun seedIfEmpty(dao: TeammateDao, context: Context) = withContext(Dispatchers.IO) {
        val existing = dao.getAll().first()
        if (existing.isNotEmpty()) return@withContext

        builtInTeammates.forEach { seed ->
            val prompt = readAsset(context, "system-prompts/${seed.id}.md")
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

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private data class TeammateSeed(
        val id: String,
        val name: String,
        val tagline: String,
    )
}
