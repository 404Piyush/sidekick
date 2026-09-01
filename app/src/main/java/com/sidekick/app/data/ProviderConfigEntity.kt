package com.sidekick.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted provider configuration. Only one row has [isActive] = true at any
 * time; [com.sidekick.app.data.dao.ProviderConfigDao.setActive] is the flip
 * point.
 *
 * `providerKind` is a forward-compatible discriminator (`"local_ollama"`,
 * `"cloud_openai"`, or `"local_on_device"`) stored as a plain string so M3+
 * can add new kinds without a destructive migration.
 *
 * M7 added two columns for the on-device inference path:
 *  - [modelPath]: absolute path to a `.litertlm` model file. `null` for
 *    cloud and network-ollama providers (those don't need a local file).
 *  - [backend]: serialised [com.sidekick.app.provider.Backend] enum name
 *    (`"NPU"`, `"GPU"`, or `"CPU"`). `null` for non-on-device providers.
 *
 * Both columns default to `null` so the v2 → v3 migration can backfill
 * existing rows without taking a default-side hit.
 *
 * @property baseUrl The provider's chat-completion endpoint base
 *                   (e.g. `http://10.0.2.2:11434` for Ollama,
 *                   `https://api.openai.com/v1` for OpenAI).
 *                   Ignored for `local_on_device`.
 * @property apiKey Bearer token for cloud providers, `null` for local.
 * @property modelName The model the user wants to talk to
 *                     (e.g. `qwen2.5-coder:7b`, `gpt-4o-mini`).
 *                     For `local_on_device` we still record a display
 *                     name (e.g. `Gemma3-1B-IT`) so the UI can show what
 *                     the user picked; the LiteRT-LM runtime only cares
 *                     about [modelPath].
 */
@Entity(tableName = "provider_configs")
data class ProviderConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val providerKind: String,
    val baseUrl: String,
    val apiKey: String?,
    val modelName: String,
    val isActive: Boolean,
    val modelPath: String? = null,
    val backend: String? = null,
)