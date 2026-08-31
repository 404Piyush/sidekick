package com.sidekick.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted provider configuration. Only one row has [isActive] = true at any
 * time; [com.sidekick.app.data.dao.ProviderConfigDao.setActive] is the flip
 * point.
 *
 * `providerKind` is a forward-compatible discriminator (`"local_ollama"` or
 * `"cloud_openai"`) stored as a plain string so M3 can add `"anthropic"` or
 * other kinds without a destructive migration.
 *
 * @property baseUrl The provider's chat-completion endpoint base
 *                   (e.g. `http://10.0.2.2:11434` for Ollama,
 *                   `https://api.openai.com/v1` for OpenAI).
 * @property apiKey Bearer token for cloud providers, `null` for local.
 * @property modelName The model the user wants to talk to
 *                     (e.g. `qwen2.5-coder:7b`, `gpt-4o-mini`).
 */
@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val providerKind: String,
    val baseUrl: String,
    val apiKey: String?,
    val modelName: String,
    val isActive: Boolean,
)
