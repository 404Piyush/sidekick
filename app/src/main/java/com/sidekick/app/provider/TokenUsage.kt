package com.sidekick.app.provider

/**
 * Token accounting reported by the provider at the end of a stream.
 *
 * Field names match what Ollama returns (`prompt_eval_count`, `eval_count`)
 * and what OpenAI returns (`prompt_tokens`, `completion_tokens`). Provider
 * implementations translate from their native shape to this one.
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
)