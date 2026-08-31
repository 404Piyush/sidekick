package com.sidekick.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A teammate profile — the canonical list of personas the user can pick from.
 *
 * Seeded once on first DB open (see [com.sidekick.app.data.Seed.seedIfEmpty])
 * from `assets/system-prompts/{id}.md`. The id is a stable slug used as the
 * primary key and as a foreign reference from
 * [ConversationEntity.teammate], so renaming the [name] later is safe.
 *
 * @property id Stable slug like `"coder"`. NEVER reuse across teammates.
 * @property name Display name like `"Coder"`.
 * @property tagline One-line teaser shown on the home screen.
 * @property systemPrompt System prompt prepended to every conversation
 *                        that targets this teammate.
 */
@Entity(tableName = "teammates")
data class TeammateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val tagline: String,
    val systemPrompt: String,
)
