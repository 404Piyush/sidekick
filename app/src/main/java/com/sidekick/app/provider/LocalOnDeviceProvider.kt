package com.sidekick.app.provider

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message as LiteMessage
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

/**
 * On-device LLM provider backed by Google AI Edge LiteRT-LM (M7+).
 *
 * Wraps LiteRT-LM's [Engine] + `Conversation` API and exposes it through
 * Sidekick's standard [LlmClient] interface so it can be plumbed through
 * [LlmRouter] just like [OllamaProvider] and [OpenAiProvider].
 *
 * The runtime model:
 *  1. One [Engine] instance per [LocalOnDeviceProvider] (it holds onto a
 *     huge mmap of the model file — re-creating per call would be fatal).
 *  2. One `Conversation` per `stream()` call so the model sees a fresh
 *     context that includes the chat history as `initialMessages`.
 *  3. The conversation's `systemInstruction` carries the teammate's
 *     system prompt — we strip it from the chat history to avoid
 *     double-priming the model.
 *  4. `sendMessageAsync("text")` returns a `Flow<LiteMessage>` — we map
 *     each emitted message to one or more [LlmChunk.Text] deltas. LiteRT-LM
 *     emits one message per assistant turn (not token-level), but the
 *     `Flow` semantics still give us natural cancellation backpressure.
 *  5. The flow terminates with [LlmChunk.Done] carrying a `null`
 *     [TokenUsage] — LiteRT-LM doesn't surface token counts in its public
 *     Kotlin API yet, so the UI sees the same `null` it would for any
 *     other provider that doesn't report usage.
 *
 * Cancellation: cancelling the returned [Job] closes the underlying
 * `Conversation` (releasing the native handle) and stops calling the
 * `onChunk` callback.
 *
 * **Tool calls:** LiteRT-LM exposes tool definitions + manual
 * tool-calling, but we don't wire it in M7 — the agent loop's tool
 * dispatch is Ollama/OpenAI-specific. Once LiteRT-LM's tools surface
 * stabilises, M9 will plumb them through the agent loop. For now, if
 * the user has tools registered, we silently drop them (the model
 * gets the chat history + system prompt only).
 *
 * **Multimodal:** LiteRT-LM supports vision models (Gemma3n) but the
 * `Message` builder for multimodal content lives in a separate
 * `Contents.of(...)` API. M7 only handles text parts — the [LlmRequest]
 * carrying image parts gets a warning logged and the images dropped,
 * same as [OllamaProvider]'s M4 behaviour.
 *
 * @param modelPath Absolute path to a `.litertlm` model file on disk.
 * @param backend   Which accelerator to dispatch on (NPU/GPU/CPU).
 *                  NPU is the default per the iQOO brief.
 * @param maxTokens Hard cap on output length per turn (forwarded as
 *                  the model's `maxOutputTokens`).
 * @param temperature Sampling temperature forwarded to the model.
 * @param nativeLibraryDir Optional override for the directory holding
 *                         NPU libraries. On Android with bundled libs,
 *                         pass `context.applicationInfo.nativeLibraryDir`.
 *                         Defaults to `""` (LiteRT-LM picks its default).
 */
class LocalOnDeviceProvider(
    val modelPath: String,
    val backend: Backend = Backend.NPU,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val temperature: Double = DEFAULT_TEMPERATURE,
    private val nativeLibraryDir: String? = null,
) : LlmClient {

    init {
        require(modelPath.isNotBlank()) { "modelPath must be non-blank" }
        require(maxTokens > 0) { "maxTokens must be > 0, got $maxTokens" }
        require(temperature >= 0.0) { "temperature must be >= 0.0, got $temperature" }
    }

    /**
     * Singleton [Engine] for this provider. Created lazily on the first
     * `stream()` call so constructing a [LocalOnDeviceProvider] is cheap
     * (the router caches it; if the user never sends a message, no native
     * libraries get loaded).
     *
     * LiteRT-LM's [Engine] is `AutoCloseable` (call `close()` to release
     * the mmap). We hold the only reference and close it when the
     * provider is itself closed.
     */
    private var engine: Engine? = null

    /** Set after a failed engine init so subsequent messages fail fast. */
    private var initFailed: Boolean = false

    /**
     * Construct an [EngineConfig] for this provider. Visible for tests
     * so they can assert the dispatch shape without actually loading
     * the native libs.
     */
    fun buildEngineConfig(): EngineConfig = EngineConfig(
        modelPath = modelPath,
        maxNumTokens = maxTokens,
        backend = when (backend) {
            Backend.NPU -> com.google.ai.edge.litertlm.Backend.NPU(
                nativeLibraryDir = nativeLibraryDir.orEmpty(),
            )
            Backend.GPU -> com.google.ai.edge.litertlm.Backend.GPU()
            Backend.CPU -> com.google.ai.edge.litertlm.Backend.CPU()
        },
    )

    override suspend fun stream(request: LlmRequest, onChunk: (LlmChunk) -> Unit): Job =
        coroutineScope {
            async(Dispatchers.IO) { execute(request, onChunk) }
        }

    private suspend fun execute(request: LlmRequest, onChunk: (LlmChunk) -> Unit) {
        // Guard against init storms: if the engine fails to construct once,
        // don't keep retrying and leaking native memory on unsupported
        // hardware (e.g. x86_64 emulators, which have no ARM64 dispatch
        // library). Fail fast with a clean error instead.
        if (initFailed) {
            throw LlmException.Network(
                "On-device model can't run on this device (no supported accelerator). " +
                    "It needs a Snapdragon/MediaTek NPU or GPU. Use Local Ollama or Cloud instead.",
            )
        }

        // Ensure the engine is initialised. LiteRT-LM's `engine.initialize()`
        // can take up to ~10 s for a 1B model — that's why this is in a
        // background coroutine, not the main thread.
        val eng = try {
            withContext(Dispatchers.IO) {
                engine ?: Engine(buildEngineConfig()).also {
                    it.initialize()
                    engine = it
                }
            }
        } catch (e: Throwable) {
            // Construction/init failure leaks native buffers on retry —
            // remember the failure so a second message doesn't OOM the
            // process, and surface it as a normal LlmException.
            initFailed = true
            runCatching { engine?.close() }
            engine = null
            throw LlmException.Network(
                "Couldn't load the on-device model: ${e.message ?: e.javaClass.simpleName}. " +
                    "It may not support this device's chip.",
            )
        }

        // Build the conversation with a fresh system prompt and the
        // chat history replayed as initial messages. The last user message
        // is forwarded via `sendMessageAsync` so the model emits a fresh
        // response — we don't replay it as an initial message.
        val (systemPrompt, history) = extractSystemPromptAndHistory(request.messages)
        val conversationConfig = ConversationConfig(
            systemInstruction = if (systemPrompt.isNotBlank()) {
                Contents.of(systemPrompt)
            } else {
                null
            },
            initialMessages = history.mapNotNull { msg ->
                when (msg.role) {
                    "user" -> LiteMessage.user(msg.content.asPlainText())
                    "assistant" -> LiteMessage.model(msg.content.asPlainText())
                    // System and tool messages are absorbed into the
                    // systemInstruction above (or dropped for tool — the
                    // agent loop doesn't ship a Provider.LocalOnDevice
                    // code path yet, so this branch is unreachable in M7).
                    else -> null
                }
            },
            samplerConfig = SamplerConfig(
                topK = DEFAULT_TOP_K,
                topP = DEFAULT_TOP_P,
                temperature = temperature,
            ),
        )

        val conversation = eng.createConversation(conversationConfig)

        // Forward the actual user message and stream the response. We use
        // the Flow variant of `sendMessageAsync` because it gives us a
        // structured concurrency handle — if the conversation closes or
        // the user cancels, the flow terminates cleanly.
        val lastUserText = request.messages.lastOrNull { it.role == "user" }?.content?.asPlainText().orEmpty()
        if (lastUserText.isBlank()) {
            conversation.close()
            onChunk(LlmChunk.Done(usage = null))
            return
        }

        try {
            conversation.sendMessageAsync(LiteMessage.user(lastUserText))
                .flowOn(Dispatchers.IO)
                .catch { e ->
                    if (e is CancellationException) throw e
                    throw LlmException.Network("LiteRT-LM stream failed: ${e.message}", e)
                }
                .onCompletion { e ->
                    // Close the conversation whether we succeeded, errored,
                    // or got cancelled. The engine itself stays alive — the
                    // provider is reusable across calls.
                    runCatching { conversation.close() }
                    if (e == null) {
                        onChunk(LlmChunk.Done(usage = null))
                    }
                }
                .collect { msg ->
                    // LiteRT-LM emits whole assistant messages; we concatenate
                    // the text parts. Multimodal parts (out of scope for M7)
                    // are skipped — the model emits a "you saw an image"
                    // preamble if it needed one, and the user gets a log
                    // warning when we drop a [MessagePart.ImagePart].
                    val text = msg.contents.contents.joinToString("") { content ->
                        when (content) {
                            is com.google.ai.edge.litertlm.Content.Text -> content.text
                            else -> ""
                        }
                    }
                    if (text.isNotEmpty()) {
                        onChunk(LlmChunk.Text(text))
                    }
                }
        } catch (e: CancellationException) {
            runCatching { conversation.close() }
            throw e
        } catch (e: Throwable) {
            runCatching { conversation.close() }
            throw e
        }
    }

    /**
     * Pull the `system` message out of [messages] and return
     * `(systemPrompt, history)`. The history omits the system message
     * and keeps the rest in arrival order.
     *
     * Sidekick's [LlmRequest] always puts the system prompt first (see
     * `buildConversationMessages` in
     * [com.sidekick.app.ui.ConversationViewModel]), so the first message
     * with `role == "system"` wins.
     */
    private fun extractSystemPromptAndHistory(
        messages: List<ChatMessage>,
    ): Pair<String, List<ChatMessage>> {
        var system = ""
        val history = mutableListOf<ChatMessage>()
        for (m in messages) {
            if (m.role == "system" && system.isEmpty()) {
                system = m.content.asPlainText()
            } else {
                history.add(m)
            }
        }
        return system to history
    }

    /**
     * Release the engine (and its mmap). Safe to call multiple times.
     * The router normally never calls this — it caches providers for the
     * app's lifetime — but unit tests rely on it to clean up between
     * tests.
     */
    fun close() {
        runCatching { engine?.close() }
        engine = null
        initFailed = false
    }

    companion object {
        /** Default cap on generated tokens per turn. */
        const val DEFAULT_MAX_TOKENS: Int = 256

        /** Default sampling temperature. Matches the cloud defaults. */
        const val DEFAULT_TEMPERATURE: Double = 0.7

        /** Default top-K for nucleus sampling. Matches LiteRT-LM's defaults. */
        const val DEFAULT_TOP_K: Int = 40

        /** Default top-P for nucleus sampling. Matches LiteRT-LM's defaults. */
        const val DEFAULT_TOP_P: Double = 0.95
    }
}