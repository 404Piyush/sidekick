package com.sidekick.app.agent

import com.sidekick.app.provider.ChatMessage
import com.sidekick.app.provider.LlmChunk
import com.sidekick.app.provider.LlmClient
import com.sidekick.app.provider.LlmException
import com.sidekick.app.provider.LlmRequest
import com.sidekick.app.tools.ToolContext
import com.sidekick.app.tools.ToolDescriptor
import com.sidekick.app.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The Sidekick agent loop.
 *
 * Glue between an [LlmClient] (a provider streaming endpoint) and a
 * [ToolRegistry] (the model's toolbox). One `run` invocation drives one
 * user turn through the loop:
 *
 *  1. Build an [LlmRequest] from the supplied [messages] + [tools].
 *  2. Open a stream. Accumulate [LlmChunk.Text] into the current
 *     assistant message and forward each delta as [AgentEvent.TextDelta].
 *  3. When the stream emits [LlmChunk.ToolCall], dispatch via the
 *     registry, forward [AgentEvent.ToolCall] + [AgentEvent.ToolResult]
 *     to the caller, append a tool message to the conversation, and
 *     re-enter step 1.
 *  4. When [LlmChunk.Done] arrives without any tool call in this
 *     iteration, emit [AgentEvent.TextDone] and return.
 *  5. If the provider raises, emit [AgentEvent.Error] and return the
 *     conversation as-is (no exception propagates to the caller).
 *  6. If the model keeps emitting tool calls and never produces a final
 *     text, abort after [maxIterations] iterations with
 *     [AgentEvent.MaxIterationsExceeded].
 *
 * The loop is **stateless with respect to the conversation** — it takes
 * the whole message list as input and returns the (possibly extended)
 * message list as output. That makes it safe for parallel calls with
 * distinct conversations; see [ThreeToolIsolationTest].
 *
 * @property provider The chat-completion client to call. Injected so tests
 *                    can swap in a scripted stub.
 * @property registry Tool registry to dispatch model-emitted tool calls
 *                   against.
 * @property maxIterations Hard cap on tool-call iterations per `run` call.
 *                         Default [DEFAULT_MAX_ITERATIONS] = 10. A
 *                         misbehaving model that emits `read_file` → text
 *                         → `read_file` → … in a tight loop will trip
 *                         this after 10 dispatches.
 */
class AgentLoop(
    private val provider: LlmClient,
    private val registry: ToolRegistry,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
) {

    init {
        require(maxIterations > 0) { "maxIterations must be > 0, got $maxIterations" }
    }

    /**
     * Run one agent turn. See class docstring for the contract.
     *
     * @param messages Conversation history at the moment the user message
     *                 was appended. The loop MUTATES its own copy and
     *                 returns the result; the caller's list is untouched.
     * @param tools Tool descriptors to advertise to the model.
     * @param ctx Sandbox context for tool invocations.
     * @param onEvent Callback invoked once per [AgentEvent]. May be called
     *                from the provider's IO dispatcher — keep it
     *                thread-safe and quick (the UI side typically
     *                forwards onto the main dispatcher via Channel).
     * @return The final conversation history — includes the latest
     *         assistant message plus any tool messages appended during
     *         the run. Order is preserved.
     */
    suspend fun run(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        ctx: ToolContext,
        onEvent: (AgentEvent) -> Unit,
    ): List<ChatMessage> {
        // Local copy so we can append tool/assistant messages without
        // touching the caller's list. Index-based iteration is fine — we
        // never re-sort.
        val working = messages.toMutableList()
        var iterations = 0
        var lastFullText = ""

        while (true) {
            if (iterations >= maxIterations) {
                onEvent(AgentEvent.MaxIterationsExceeded(iterations, maxIterations))
                return working
            }

            val (text, toolCalls, done, error) = streamOnce(working, tools, ctx, onEvent)

            if (error != null) {
                onEvent(AgentEvent.Error(error))
                return working
            }

            // Accumulate text from this iteration. The model can emit both
            // text AND a tool call in the same response (e.g. "Let me check…
            // <tool_call>"). We concat the textual lead-in into the
            // assistant message that gets persisted at TextDone time.
            if (text.isNotEmpty()) {
                lastFullText = text
                onEvent(AgentEvent.TextDelta(text))
            }

            // Tool calls?
            if (toolCalls.isNotEmpty()) {
                iterations += 1

                // Persist any text the model emitted before the tool call
                // as the assistant message — otherwise the tool message
                // would be unanchored.
                if (lastFullText.isNotEmpty()) {
                    working.add(ChatMessage.text("assistant", lastFullText))
                    lastFullText = ""
                }

                // Dispatch each tool call sequentially. For M3 we don't
                // parallelise; the agent-loop contract serialises so the
                // model sees a clean cause/effect chain.
                for (call in toolCalls) {
                    onEvent(AgentEvent.ToolCall(call.name, call.args, call.id))
                    val result = registry.dispatch(call.name, call.args, ctx)
                    onEvent(AgentEvent.ToolResult(call.name, result, call.id))
                    val toolContent = when (val r = result) {
                        is com.sidekick.app.tools.ToolResult.Ok -> r.output
                        is com.sidekick.app.tools.ToolResult.Err -> "[error] ${r.message}"
                    }
                    working.add(ChatMessage.text("tool", toolContent))
                }

                // Loop back to the provider with the augmented conversation.
                continue
            }

            // Done with no tool calls — terminal.
            if (done) {
                onEvent(AgentEvent.TextDone(lastFullText))
                if (lastFullText.isNotEmpty()) {
                    working.add(ChatMessage.text("assistant", lastFullText))
                }
                return working
            }

            // Neither text nor tool calls nor done nor error — pathological,
            // but guard against an infinite loop.
            if (iterations > maxIterations) {
                onEvent(AgentEvent.MaxIterationsExceeded(iterations, maxIterations))
                return working
            }
            iterations += 1
        }
    }

    /**
     * Open one provider stream, collect chunks into the three result
     * buckets. The provider callback is non-suspend; we ferry chunks
     * across the suspend boundary via a [Channel].
     *
     * @return Quad of (accumulated-text, tool-call list, done-flag, error).
     *         Exactly one of error/done-or-tool-calls holds on any call —
     *         but we tolerate the provider emitting text + tool + done
     *         in one stream and surface all of the above (the run loop
     *         decides priority).
     */
    private suspend fun streamOnce(
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        @Suppress("UNUSED_PARAMETER") ctx: ToolContext,
        onEvent: (AgentEvent) -> Unit,
    ): StreamResult = coroutineScope {
        val channel = Channel<StreamEvent>(capacity = Channel.UNLIMITED)
        val producerJob = launch {
            try {
                provider.stream(LlmRequest(messages = messages, tools = tools)) { chunk ->
                    channel.trySend(StreamEvent.Chunk(chunk))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                channel.trySend(StreamEvent.Failure(t))
            } finally {
                channel.close()
            }
        }

        val text = StringBuilder()
        val toolCalls = mutableListOf<LlmChunk.ToolCall>()
        var done = false
        var error: Throwable? = null
        // De-dup tool calls by (id, name, args). The provider may emit the
        // same ToolCall several times if it flushes on every delta (some
        // OpenAI-compatible servers don't send finish_reason). The agent
        // loop only acts once per call, so we collapse here.
        val seenToolCalls = mutableSetOf<String>()

        for (event in channel) {
            when (event) {
                is StreamEvent.Chunk -> when (val c = event.chunk) {
                    is LlmChunk.Text -> {
                        text.append(c.delta)
                        onEvent(AgentEvent.TextDelta(c.delta))
                    }
                    is LlmChunk.ToolCall -> {
                        val key = c.id + "|" + c.name + "|" + c.args.toString()
                        if (seenToolCalls.add(key)) {
                            toolCalls.add(c)
                        }
                    }
                    is LlmChunk.Done -> {
                        done = true
                    }
                }
                is StreamEvent.Failure -> {
                    error = event.throwable
                }
            }
        }
        producerJob.join()
        StreamResult(text = text.toString(), toolCalls = toolCalls, done = done, error = error)
    }

    private data class StreamResult(
        val text: String,
        val toolCalls: List<LlmChunk.ToolCall>,
        val done: Boolean,
        val error: Throwable?,
    )

    private sealed class StreamEvent {
        data class Chunk(val chunk: LlmChunk) : StreamEvent()
        data class Failure(val throwable: Throwable) : StreamEvent()
    }

    companion object {
        /** Default cap on tool-call iterations per `run`. */
        const val DEFAULT_MAX_ITERATIONS: Int = 10
    }
}

/**
 * Event emitted by [AgentLoop.run] to its `onEvent` callback.
 *
 * Events arrive in roughly the order the model emits them. The text
 * deltas flow first, then either `ToolCall`+`ToolResult` pairs (followed
 * by another round of deltas after the loop re-enters the provider) or
 * a terminal `TextDone`. An `Error` is always the last event in its
 * iteration; `MaxIterationsExceeded` is always terminal for the run.
 */
sealed class AgentEvent {
    /** One token/delta of assistant text. Concatenate to reconstruct. */
    data class TextDelta(val delta: String) : AgentEvent()

    /** Final assembled assistant text — fires once per `run`. */
    data class TextDone(val fullText: String) : AgentEvent()

    /** Model wants to invoke [name] with [args]. [callId] is the provider's correlation id. */
    data class ToolCall(
        val name: String,
        val args: kotlinx.serialization.json.JsonObject,
        val callId: String,
    ) : AgentEvent()

    /** Dispatch result for the preceding [ToolCall]. */
    data class ToolResult(
        val name: String,
        val result: com.sidekick.app.tools.ToolResult,
        val callId: String,
    ) : AgentEvent()

    /** Provider raised an exception. The run is over; [error] is the cause. */
    data class Error(val error: Throwable) : AgentEvent()

    /** Cap reached. [iterations] is what fired; [max] is the configured limit. */
    data class MaxIterationsExceeded(
        val iterations: Int,
        val max: Int,
    ) : AgentEvent()
}

/**
 * Translate a caught [Throwable] from a provider into an [LlmException]
 * suitable for surfacing through [AgentEvent.Error]. Mirrors the helper
 * in [com.sidekick.app.ui.ConversationViewModel] but lives here so the
 * agent loop doesn't depend on the UI package.
 */
internal fun Throwable.toAgentLlmException(): LlmException = when (this) {
    is LlmException -> this
    else -> LlmException.Network(message ?: this::class.simpleName.orEmpty(), this)
}
