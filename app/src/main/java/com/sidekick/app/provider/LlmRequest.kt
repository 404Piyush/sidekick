package com.sidekick.app.provider

/**
 * A single turn in a chat conversation. [role] is one of
 *  - `"system"` — instruction that primes the model's behaviour
 *  - `"user"` — the human's message
 *  - `"assistant"` — the model's previous reply
 */
data class ChatMessage(
    val role: String,
    val content: String,
)

/**
 * Inputs for one streaming chat-completion call.
 *
 * @property messages Conversation history, oldest first. The router forwards
 *                    this verbatim — providers do not rewrite it.
 * @property temperature Sampling temperature forwarded to the provider. Default
 *                       `0.7`. Provider-specific ranges are not enforced here.
 * @property maxTokens Optional cap on generated tokens. `null` means "let the
 *                     provider decide" (e.g. Ollama's `num_predict` defaults to
 *                     128 when omitted, OpenAI ignores `max_tokens`).
 * @property stream Whether to stream deltas. M1 always sets this to `true`; the
 *                  field is left explicit so a future non-streaming code path
 *                  can flip it without changing call sites.
 */
data class LlmRequest(
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val stream: Boolean = true,
)