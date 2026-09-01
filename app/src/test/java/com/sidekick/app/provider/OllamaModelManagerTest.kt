package com.sidekick.app.provider

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OllamaModelManager].
 *
 * Drives the manager against [MockWebServer] so we can verify the
 * /api/tags and /api/pull wire formats without a live Ollama process.
 */
class OllamaModelManagerTest {

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
    fun listLocalParsesModelNames() = runBlocking {
        // The server's /api/tags response shape: { "models": [{ "name": "...", ...}, ...] }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "models": [
                        {"name": "qwen2.5-coder:7b", "size": 4683079384},
                        {"name": "llama3.1:8b", "size": 4661211808},
                        {"name": "phi4:14b", "size": 8994000384}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val manager = OllamaModelManager(baseUrl = server.url("/").toString().trimEnd('/'))
        val names = manager.listLocal()

        assertEquals(
            listOf("qwen2.5-coder:7b", "llama3.1:8b", "phi4:14b"),
            names,
        )

        val recorded = server.takeRequest()
        assertEquals("/api/tags", recorded.path)
        assertEquals("GET", recorded.method)
    }

    @Test
    fun listLocalDeDuplicatesByName() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"models":[
                      {"name":"qwen2.5-coder:7b"},
                      {"name":"qwen2.5-coder:7b"},
                      {"name":"llama3.1:8b"}
                    ]}
                    """.trimIndent(),
                ),
        )

        val manager = OllamaModelManager(baseUrl = server.url("/").toString().trimEnd('/'))
        val names = manager.listLocal()

        assertEquals(listOf("qwen2.5-coder:7b", "llama3.1:8b"), names)
    }

    @Test
    fun listLocalReturnsEmptyWhenNoModelsField() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{}"),
        )

        val manager = OllamaModelManager(baseUrl = server.url("/").toString().trimEnd('/'))
        assertEquals(emptyList<String>(), manager.listLocal())
    }

    @Test
    fun listLocalSurfacesHttpErrors() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("upstream down"),
        )

        val manager = OllamaModelManager(baseUrl = server.url("/").toString().trimEnd('/'))
        var thrown: Throwable? = null
        try {
            manager.listLocal()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected HttpStatus, got $thrown", thrown is LlmException.HttpStatus)
        assertEquals(500, (thrown as LlmException.HttpStatus).code)
    }

    @Test
    fun listCuratedReturnsEightRecommendedNames() {
        val manager = OllamaModelManager(baseUrl = "http://unused")
        val curated = manager.listCurated()
        assertEquals(8, curated.size)
        assertTrue("must include qwen2.5-coder:7b", curated.contains("qwen2.5-coder:7b"))
        assertTrue("must include llama3.1:8b", curated.contains("llama3.1:8b"))
        assertTrue("must include codestral:22b", curated.contains("codestral:22b"))
    }

    @Test
    fun pullEmitsPercentAndStatusProgressAndCompletesOnSuccess() = runBlocking {
        // Two progress lines (one with totals, one status-only), then success.
        val body = """
            {"status":"pulling manifest","digest":"sha256:abc"}
            {"status":"pulling sha256:abc","digest":"sha256:abc","total":1000,"completed":250}
            {"status":"pulling sha256:abc","digest":"sha256:abc","total":1000,"completed":1000}
            {"status":"success"}
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(body),
        )

        val manager = OllamaModelManager(baseUrl = server.url("/").toString().trimEnd('/'))
        // take(3) keeps the test fast — we just want the first three
        // progress events, then the flow naturally completes.
        val events = manager.pull("qwen2.5-coder:7b").take(3).toList()

        // 1. "pulling manifest" — no total/completed -> percent = -1
        assertEquals(-1, events[0].percent)
        assertEquals("pulling manifest", events[0].status)
        assertEquals("sha256:abc", events[0].digest)

        // 2. First progress line with totals -> 25%
        assertEquals(25, events[1].percent)
        assertEquals("pulling sha256:abc", events[1].status)

        // 3. Second progress line with totals -> 100%
        assertEquals(100, events[2].percent)

        // Verify the request shape — POST /api/pull with name + stream.
        val recorded = server.takeRequest()
        assertEquals("/api/pull", recorded.path)
        assertEquals("POST", recorded.method)
        val sent = recorded.body.readUtf8()
        assertTrue("expected name field, got: $sent", sent.contains("\"name\":\"qwen2.5-coder:7b\""))
        assertTrue("expected stream flag, got: $sent", sent.contains("\"stream\":true"))
    }

    @Test
    fun pullCompletesFlowOnSuccessLine() = runBlocking {
        val body = """{"status":"success"}"""
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(body),
        )

        val manager = OllamaModelManager(baseUrl = server.url("/").toString().trimEnd('/'))
        // Collecting to exhaustion must terminate without further values.
        val events = manager.pull("phi4:14b").toList()
        assertEquals(1, events.size)
        assertEquals("success", events[0].status)

        // Re-confirm the next pull picks up a fresh call (not a stale cache).
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"success"}"""),
        )
        val second = manager.pull("mistral:7b").first()
        assertNotNull(second)
        assertEquals("success", second.status)
    }

    @Test
    fun pullSurfacesProviderErrorEnvelope() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"error":"pull model manifest: file does not exist"}"""),
        )

        val manager = OllamaModelManager(baseUrl = server.url("/").toString().trimEnd('/'))
        var thrown: Throwable? = null
        try {
            manager.pull("does-not-exist:99b").toList()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected ProviderSpecific, got $thrown", thrown is LlmException.ProviderSpecific)
        assertTrue(
            "error message must surface: ${(thrown as? LlmException.ProviderSpecific)?.message}",
            thrown?.message?.contains("file does not exist") == true,
        )
    }
}
