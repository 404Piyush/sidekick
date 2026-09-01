package com.sidekick.app.agent

import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.provider.ChatMessage
import com.sidekick.app.provider.LlmChunk
import com.sidekick.app.provider.LlmClient
import com.sidekick.app.provider.LlmRequest
import com.sidekick.app.provider.TokenUsage
import com.sidekick.app.tools.ToolContext
import com.sidekick.app.tools.ToolRegistry
import com.sidekick.app.tools.ToolResult
import com.sidekick.app.tools.builtins.ListDir
import com.sidekick.app.tools.builtins.ReadFile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.resume

/**
 * Tests for [AgentLoop]. Uses [FakeLlmClient] (defined at the bottom) to
 * script per-call streaming responses without OkHttp.
 *
 * Verifies:
 *  1. `run` returns without any tool calls when the model emits text + Done.
 *  2. `run` re-enters the provider after a tool result, then accumulates
 *     the second response and returns.
 *  3. `run` aborts with [AgentEvent.MaxIterationsExceeded] when the model
 *     loops forever.
 *  4. Two parallel `run` calls with different `messages` lists never
 *     cross-contaminate (this is also exercised structurally in
 *     [com.sidekick.app.tools.ThreeToolIsolationTest], but worth
 *     re-checking at the loop level too).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentLoopTest {

    private lateinit var ctx: ToolContext
    private lateinit var appContext: android.content.Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        ctx = ToolContext(appContext = appContext, sessionId = 1L)
        appContext.filesDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        appContext.filesDir.deleteRecursively()
    }

    @Test
    fun terminatesOnFinalTextWithoutToolCalls() = runBlocking {
        val provider = FakeLlmClient().apply {
            respondOnce {
                listOf(
                    LlmChunk.Text("Hello"),
                    LlmChunk.Text(", "),
                    LlmChunk.Text("world!"),
                    LlmChunk.Done(TokenUsage(1, 3, 4)),
                )
            }
        }
        val registry = ToolRegistry(listOf(ReadFile(), ListDir()))
        val loop = AgentLoop(provider = provider, registry = registry)

        val events = mutableListOf<AgentEvent>()
        val messages = listOf(
            ChatMessage.text("system", "sys"),
            ChatMessage.text("user", "hi"),
        )

        val out = loop.run(messages, registry.descriptors(), ctx) { events.add(it) }

        assertEquals(1, provider.callCount)
        // Final message list: original 2 + 1 assistant message.
        assertEquals(3, out.size)
        assertEquals("assistant", out.last().role)
        assertEquals("Hello, world!", out.last().content.asPlainText())

        // We expect TextDelta x3 + TextDone x1, no tool events.
        val toolEvents = events.filter { it is AgentEvent.ToolCall || it is AgentEvent.ToolResult }
        assertEquals(0, toolEvents.size)
        assertTrue(events.any { it is AgentEvent.TextDone })
        val done = events.filterIsInstance<AgentEvent.TextDone>().single()
        assertEquals("Hello, world!", done.fullText)
    }

    @Test
    fun reentersProviderAfterToolResult() = runBlocking {
        // Set up a fixture file the read_file tool will pick up.
        java.io.File(appContext.filesDir, "notes/todo.md").apply {
            parentFile?.mkdirs()
            writeText("buy milk", Charsets.UTF_8)
        }

        val provider = FakeLlmClient().apply {
            // First call: emit a tool call.
            respondOnce { listOf(
                LlmChunk.ToolCall(
                    id = "call_1",
                    name = "read_file",
                    args = buildJsonObject { put("path", JsonPrimitive("notes/todo.md")) },
                ),
                LlmChunk.Done(null),
            ) }
            // Second call: emit a final text reply.
            respondOnce { listOf(
                LlmChunk.Text("Got it: "),
                LlmChunk.Text("buy milk"),
                LlmChunk.Done(null),
            ) }
        }
        val registry = ToolRegistry(listOf(ReadFile()))
        val loop = AgentLoop(provider = provider, registry = registry, maxIterations = 5)

        val events = mutableListOf<AgentEvent>()
        val out = loop.run(listOf(ChatMessage.text("user", "what's on my list?")), registry.descriptors(), ctx) { events.add(it) }

        // Two provider calls — once for the tool call, once after.
        assertEquals(2, provider.callCount)

        // Events: 1 ToolCall + 1 ToolResult, then TextDelta x2 + TextDone x1.
        val toolCalls = events.filterIsInstance<AgentEvent.ToolCall>()
        val toolResults = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(1, toolCalls.size)
        assertEquals("read_file", toolCalls.single().name)
        assertEquals(1, toolResults.size)
        assertTrue(toolResults.single().result is ToolResult.Ok)
        assertTrue(events.any { it is AgentEvent.TextDone })

        // Final conversation: user, assistant(text-empty since text only came AFTER
        // the tool call), tool message, assistant("Got it: buy milk").
        // Per AgentLoop contract: when a response emits ONLY a tool call with no
        // text, no assistant message is appended for that iteration. The tool
        // message goes in immediately, then the second iteration's text becomes
        // the assistant message.
        val roles = out.map { it.role }
        assertEquals(listOf("user", "tool", "assistant"), roles)
        assertEquals("buy milk", out[1].content.asPlainText())
        assertEquals("Got it: buy milk", out[2].content.asPlainText())
    }

    @Test
    fun abortsAfterMaxIterations() = runBlocking {
        val provider = FakeLlmClient().apply {
            // Always emit a tool call, never Done.
            respondForever { listOf(
                LlmChunk.ToolCall(
                    id = "loop",
                    name = "read_file",
                    args = buildJsonObject { put("path", JsonPrimitive(".")) },
                ),
                // No Done — force the loop to continue.
            ) }
        }
        val registry = ToolRegistry(listOf(ReadFile(), ListDir()))
        val loop = AgentLoop(provider = provider, registry = registry, maxIterations = 3)

        val events = mutableListOf<AgentEvent>()
        // We expect the loop to call the provider exactly 3 times (maxIterations).
        val out = loop.run(listOf(ChatMessage.text("user", "loop")), registry.descriptors(), ctx) { events.add(it) }

        assertEquals(3, provider.callCount)
        val exceeded = events.filterIsInstance<AgentEvent.MaxIterationsExceeded>()
        assertEquals(1, exceeded.size)
        assertEquals(3, exceeded.single().iterations)
        assertEquals(3, exceeded.single().max)
        // No TextDone should have fired.
        assertTrue(events.none { it is AgentEvent.TextDone })
        // The final messages list ends with a tool message — assistant text
        // never materialised.
        assertEquals("tool", out.last().role)
    }

    @Test
    fun parallelRunsDoNotCrossContaminate(): Unit = runBlocking {
        // Two scripted providers — one for "alpha" conversation, one for "beta".
        // They run concurrently and each must only see its own messages.
        val alphaProvider = FakeLlmClient().apply {
            respondOnce { listOf(
                LlmChunk.Text("alpha reply"),
                LlmChunk.Done(null),
            ) }
        }
        val betaProvider = FakeLlmClient().apply {
            respondOnce { listOf(
                LlmChunk.Text("beta reply"),
                LlmChunk.Done(null),
            ) }
        }

        val registry = ToolRegistry(emptyList())
        val loopAlpha = AgentLoop(provider = alphaProvider, registry = registry)
        val loopBeta = AgentLoop(provider = betaProvider, registry = registry)

        val alphaMessages = listOf(
            ChatMessage.text("system", "alpha system"),
            ChatMessage.text("user", "alpha question"),
        )
        val betaMessages = listOf(
            ChatMessage.text("system", "beta system"),
            ChatMessage.text("user", "beta question"),
        )

        val alphaScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val betaScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val alphaResult = async {
            alphaScope.run {
                loopAlpha.run(alphaMessages, emptyList(), ctx) { /* ignore */ }
            }
        }
        val betaResult = async {
            betaScope.run {
                loopBeta.run(betaMessages, emptyList(), ctx) { /* ignore */ }
            }
        }
        val alphaOut = alphaResult.await()
        val betaOut = betaResult.await()

        // Each loop must have produced its own assistant message.
        assertEquals("alpha reply", alphaOut.last().content.asPlainText())
        assertEquals("beta reply", betaOut.last().content.asPlainText())
        // Each must contain its own system prompt.
        assertEquals("alpha system", alphaOut.first().content.asPlainText())
        assertEquals("beta system", betaOut.first().content.asPlainText())
        // And neither contains the other's user question.
        assertTrue(alphaOut.none { it.content.asPlainText() == "beta question" })
        assertTrue(betaOut.none { it.content.asPlainText() == "alpha question" })

        alphaScope.coroutineContext[Job]?.cancel()
        betaScope.coroutineContext[Job]?.cancel()
    }
}

/**
 * Test double for [LlmClient]. The agent loop is provider-agnostic, so
 * driving it with a scripted list of chunks (instead of a real
 * MockWebServer stream) is faster and avoids flakiness.
 *
 * `respondOnce` queues one response that the next `stream` call consumes;
 * `respondForever` makes every `stream` call emit the same response.
 */
internal class FakeLlmClient : LlmClient {
    @Volatile var callCount: Int = 0
    private val queue: ArrayDeque<(LlmRequest) -> List<LlmChunk>> = ArrayDeque()
    @Volatile private var forever: ((LlmRequest) -> List<LlmChunk>)? = null
    private val seenRequests = mutableListOf<LlmRequest>()

    /** All requests observed, in call order. */
    fun observedRequests(): List<LlmRequest> = seenRequests.toList()

    fun respondOnce(handler: (LlmRequest) -> List<LlmChunk>) {
        queue.addLast(handler)
    }

    fun respondForever(handler: (LlmRequest) -> List<LlmChunk>) {
        forever = handler
    }

    override suspend fun stream(request: LlmRequest, onChunk: (LlmChunk) -> Unit): Job =
        coroutineScope {
            callCount += 1
            seenRequests.add(request)
            val handler = forever ?: queue.removeFirstOrNull()
                ?: error("FakeLlmClient: no scripted response for call #$callCount")
            async(Dispatchers.Unconfined) {
                val chunks = handler(request)
                // Yield once so the consumer has a chance to subscribe before
                // we emit; mirrors real provider timing.
                delay(0)
                chunks.forEach { onChunk(it) }
            }
        }
}

private suspend fun <T> CoroutineScope.run(block: suspend () -> T): T = block()
