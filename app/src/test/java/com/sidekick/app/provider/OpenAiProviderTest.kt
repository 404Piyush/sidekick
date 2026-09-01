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
 * Drives [OpenAiProvider] against [MockWebServer] with a queued fake SSE
 * stream and asserts that the provider emits the expected chunk sequence.
 */
class OpenAiProviderTest {

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
    fun streamsSseAndEmitsTextThenDone() = runTest {
        val body = """
            data: {"id":"chatcmpl-1","choices":[{"delta":{"content":"Hel"},"index":0}]}

            data: {"id":"chatcmpl-1","choices":[{"delta":{"content":"lo!"},"index":0}]}

            data: {"id":"chatcmpl-1","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}

            data: [DONE]

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )

        val provider = OpenAiProvider(
            apiBaseUrlInternal = server.url("/").toString().trimEnd('/'),
            apiKeyInternal = "sk-test",
            modelNameInternal = "gpt-4o-mini",
        )
        val request = LlmRequest(
            messages = listOf(ChatMessage.text("user", "Say hi")),
        )

        val collected = mutableListOf<LlmChunk>()
        val job = provider.stream(request) { chunk -> collected.add(chunk) }
        job.join()

        assertEquals(3, collected.size)
        assertEquals(LlmChunk.Text("Hel"), collected[0])
        assertEquals(LlmChunk.Text("lo!"), collected[1])
        val done = collected[2] as LlmChunk.Done
        assertEquals(TokenUsage(7, 2, 9), done.usage)
    }

    @Test
    fun sendsAuthorizationHeaderAndJsonBody() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n")
        )

        val provider = OpenAiProvider(
            apiBaseUrlInternal = server.url("/").toString().trimEnd('/'),
            apiKeyInternal = "sk-test-abc",
            modelNameInternal = "gpt-4o-mini",
        )
        provider.stream(
            LlmRequest(
                messages = listOf(
                    ChatMessage.text("system", "Be brief."),
                    ChatMessage.text("user", "Hi"),
                ),
                temperature = 0.3,
                maxTokens = 32,
            )
        ) {}.join()

        val recorded = server.takeRequest()
        assertEquals("/chat/completions", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("Bearer sk-test-abc", recorded.getHeader("Authorization"))
        assertTrue(
            "expected Accept: text/event-stream, got: ${recorded.getHeader("Accept")}",
            (recorded.getHeader("Accept") ?: "").contains("text/event-stream"),
        )
        val sent = recorded.body.readUtf8()
        assertTrue("expected model, got: $sent", sent.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue("expected system role, got: $sent", sent.contains("\"role\":\"system\""))
        assertTrue("expected temperature, got: $sent", sent.contains("\"temperature\":0.3"))
        assertTrue("expected max_tokens, got: $sent", sent.contains("\"max_tokens\":32"))
        assertTrue("expected stream=true, got: $sent", sent.contains("\"stream\":true"))
        assertTrue(
            "expected include_usage, got: $sent",
            sent.contains("\"include_usage\":true"),
        )
    }

    @Test
    fun non200StatusThrowsHttpStatus() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":{\"message\":\"bad key\"}}")
        )

        val provider = OpenAiProvider(
            apiBaseUrlInternal = server.url("/").toString().trimEnd('/'),
            apiKeyInternal = "wrong",
            modelNameInternal = "gpt-4o-mini",
        )
        var thrown: Throwable? = null
        try {
            provider.stream(LlmRequest(messages = listOf(ChatMessage.text("user", "x")))) {}.join()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected HttpStatus, got $thrown", thrown is LlmException.HttpStatus)
        assertEquals(401, (thrown as LlmException.HttpStatus).code)
    }
}