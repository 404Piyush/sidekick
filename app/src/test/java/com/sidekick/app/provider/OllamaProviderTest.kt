package com.sidekick.app.provider

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [OllamaProvider] against [MockWebServer] with a queued fake NDJSON
 * stream and asserts that the provider emits the expected chunk sequence.
 */
class OllamaProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun streamsNdjsonAndEmitsTextThenDone() = runTest {
        // Two incremental tokens ("Hel", "lo!") and a done:true line with usage.
        val body = """
            {"model":"qwen2.5-coder:7b","message":{"role":"assistant","content":"Hel"},"done":false}
            {"model":"qwen2.5-coder:7b","message":{"role":"assistant","content":"lo!"},"done":false}
            {"model":"qwen2.5-coder:7b","done":true,"prompt_eval_count":12,"eval_count":5}
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(body)
        )

        val provider = OllamaProvider(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "qwen2.5-coder:7b",
        )
        val request = LlmRequest(
            messages = listOf(ChatMessage("user", "Say hi")),
        )

        // Collect chunks via callback into a list.
        val collected = mutableListOf<LlmChunk>()
        val job = provider.stream(request) { chunk -> collected.add(chunk) }
        job.join()

        assertEquals(3, collected.size)
        assertEquals(LlmChunk.Text("Hel"), collected[0])
        assertEquals(LlmChunk.Text("lo!"), collected[1])
        val done = collected[2] as LlmChunk.Done
        assertEquals(TokenUsage(promptTokens = 12, completionTokens = 5, totalTokens = 17), done.usage)
    }

    @Test
    fun postsJsonRequestToChatEndpoint() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody("{\"done\":true}")
        )

        val provider = OllamaProvider(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "qwen2.5-coder:7b",
        )
        val request = LlmRequest(
            messages = listOf(
                ChatMessage("system", "You are Coder."),
                ChatMessage("user", "Hi"),
            ),
            temperature = 0.5,
            maxTokens = 64,
        )
        provider.stream(request) {}.join()

        val recorded = server.takeRequest()
        assertEquals("/api/chat", recorded.path)
        assertEquals("POST", recorded.method)
        val sent = recorded.body.readUtf8()
        assertTrue("expected model field, got: $sent", sent.contains("\"model\":\"qwen2.5-coder:7b\""))
        assertTrue("expected system message, got: $sent", sent.contains("\"role\":\"system\""))
        assertTrue("expected user message, got: $sent", sent.contains("\"content\":\"Hi\""))
        assertTrue("expected temperature, got: $sent", sent.contains("\"temperature\":0.5"))
        assertTrue("expected num_predict, got: $sent", sent.contains("\"num_predict\":64"))
    }

    @Test
    fun non200StatusThrowsHttpStatus() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("model not found")
        )

        val provider = OllamaProvider(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "nope",
        )
        var thrown: Throwable? = null
        try {
            provider.stream(LlmRequest(messages = listOf(ChatMessage("user", "x")))) {}.join()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected HttpStatus, got $thrown", thrown is LlmException.HttpStatus)
        assertEquals(404, (thrown as LlmException.HttpStatus).code)
    }
}