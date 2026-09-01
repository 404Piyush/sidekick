package com.sidekick.app.provider

import kotlinx.serialization.json.JsonObject

/**
 * A single piece of a streaming chat-completion response.
 *
 *  - [Text] is an incremental delta; concatenate [delta]s in arrival order to
 *    reconstruct the assistant's full reply. Deltas are not guaranteed to be
 *    word-aligned — Ollama may emit one token at a time, OpenAI may emit
 *    multi-token chunks.
 *  - [ToolCall] signals the model wants to invoke a registered tool. The
 *    agent loop emits [ToolCall]s as soon as the arguments are fully
 *    accumulated (OpenAI sends `function.arguments` across several deltas);
 *    subsequent deltas may add new `tool_calls` entries. [args] is the
 *    **fully parsed** [JsonObject] — the provider implementation is
 *    responsible for buffering the partial argument strings until the
 *    final delta for that index arrives.
 *  - [Done] marks the terminal event. Implementations always emit exactly one
 *    before returning. [usage] is best-effort: Ollama always populates it, but
 *    OpenAI's `stream_options.include_usage` is opt-in and many third-party
 *    OpenAI-compatible servers don't send it, so [usage] is nullable.
 */
sealed class LlmChunk {
    data class Text(val delta: String) : LlmChunk()

    /**
     * Model-emitted tool invocation. [id] is the OpenAI call id (used by
     * some servers to correlate follow-up `tool` messages); Sidekick
     * currently passes it through unchanged into the conversation so M4's
     * multi-tool runs can disambiguate.
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val args: JsonObject,
    ) : LlmChunk()

    data class Done(val usage: TokenUsage?) : LlmChunk()

    /**
     * Progress event from a model pull (`POST /api/pull`). M4.5 emits these
     * from [com.sidekick.app.provider.ollama.OllamaModelManager.pull].
     *
     * @property percent 0..100 for chunks that carry `total`/`completed`;
     *                   `-1` for status-only chunks (e.g. "pulling manifest")
     *                   where Ollama hasn't started transferring bytes yet.
     * @property status The raw Ollama `status` string (`"pulling <digest>"`,
     *                  `"verifying sha256 digest"`, `"success"`, …).
     * @property digest The blob digest the chunk refers to, or `null` when
     *                  the chunk is a header / footer line.
     */
    data class PullProgress(
        val percent: Int,
        val status: String,
        val digest: String? = null,
    ) : LlmChunk()
}