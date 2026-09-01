package com.sidekick.app.provider

import com.sidekick.app.tools.ToolDescriptor

/**
 * A single turn in a chat conversation. [role] is one of
 *  - `"system"` — instruction that primes the model's behaviour
 *  - `"user"` — the human's message
 *  - `"assistant"` — the model's previous reply
 *  - `"tool"` — a tool result that the model gets to read on the next round
 *
 * [content] is a [MessageContent] — either plain text or a list of
 * multimodal parts. Use [ChatMessage.text] for the common single-text
 * case (existing call sites stay readable).
 */
data class ChatMessage(
    val role: String,
    val content: MessageContent,
) {
    companion object {
        /**
         * Convenience constructor for a single-text [ChatMessage].
         *
         * ```
         * ChatMessage.text("user", "hi")
         * ```
         */
        fun text(role: String, text: String): ChatMessage =
            ChatMessage(role, MessageContent.Text(text))

        /**
         * Convenience constructor for a multimodal user message (typically
         * an image plus caption). When [text] is null/blank and [image] is
         * also null, this throws — empty messages are never useful.
         */
        fun multimodal(
            role: String,
            text: String?,
            image: MessagePart.ImagePart? = null,
        ): ChatMessage {
            val parts = buildList {
                if (!text.isNullOrEmpty()) add(MessagePart.TextPart(text))
                if (image != null) add(image)
            }
            require(parts.isNotEmpty()) {
                "ChatMessage.multimodal requires at least a text or image part"
            }
            return ChatMessage(role, MessageContent.Multimodal(parts))
        }
    }
}

/**
 * The body of a [ChatMessage]. Sealed so the `when` in every provider is
 * exhaustive: a text-only message never gets accidentally serialised as a
 * multimodal list, and vice-versa.
 *
 * - [Text] is the simple case — one string of content.
 * - [Multimodal] is for messages that carry images alongside text. The
 *   OpenAI provider serialises each part to its wire format
 *   (`{"type":"text","text":...}` or
 *   `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`).
 */
sealed class MessageContent {
    data class Text(val text: String) : MessageContent()

    /**
     * A list of typed parts in display order. The provider implementation
     * decides how to encode the list — OpenAI uses `content` as a JSON
     * array of `text`/`image_url` parts, Ollama currently treats any
     * multimodal message as text (with a warning logged).
     */
    data class Multimodal(val parts: List<MessagePart>) : MessageContent()

    /**
     * Flatten this content to a single string, joining text parts with
     * newlines and substituting a placeholder for images. Useful when a
     * provider can't render the multimodal shape (e.g. the Ollama M4 path)
     * or for test assertions.
     */
    fun asPlainText(): String = when (this) {
        is Text -> text
        is Multimodal -> parts.joinToString("\n") { part ->
            when (part) {
                is MessagePart.TextPart -> part.text
                is MessagePart.ImagePart -> "[image:${part.uri}]"
            }
        }
    }
}

/**
 * One piece of a multimodal message. Sealed so the OpenAI serialiser's
 * `when` stays exhaustive.
 *
 * - [TextPart] is plain UTF-8 text the model can read directly.
 * - [ImagePart] is an on-device image the model should perceive. [uri] is
 *   the canonical reference (file path, `content://`, or app-sandbox path);
 *   [base64] is the optional pre-encoded base64 payload — if `null`, the
 *   provider implementation must encode the bytes lazily from [uri]. Most
 *   callers leave [base64] null so encoding happens on the IO thread.
 */
sealed class MessagePart {
    data class TextPart(val text: String) : MessagePart()

    data class ImagePart(val uri: String, val base64: String? = null) : MessagePart()
}

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
 * @property tools Tool schemas to advertise to the model. `null` or empty
 *                 means "no tools available" — OpenAI's request body
 *                 omits the `tools` field in that case. The agent loop
 *                 populates this from the active [com.sidekick.app.tools.ToolRegistry].
 */
data class LlmRequest(
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val maxTokens: Int? = null,
    val stream: Boolean = true,
    val tools: List<ToolDescriptor>? = null,
)
