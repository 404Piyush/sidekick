package com.sidekick.app.provider

/**
 * A single piece of a streaming chat-completion response.
 *
 *  - [Text] is an incremental delta; concatenate [delta]s in arrival order to
 *    reconstruct the assistant's full reply. Deltas are not guaranteed to be
 *    word-aligned — Ollama may emit one token at a time, OpenAI may emit
 *    multi-token chunks.
 *  - [Done] marks the terminal event. Implementations always emit exactly one
 *    before returning. [usage] is best-effort: Ollama always populates it, but
 *    OpenAI's `stream_options.include_usage` is opt-in and many third-party
 *    OpenAI-compatible servers don't send it, so [usage] is nullable.
 */
sealed class LlmChunk {
    data class Text(val delta: String) : LlmChunk()
    data class Done(val usage: TokenUsage?) : LlmChunk()
}