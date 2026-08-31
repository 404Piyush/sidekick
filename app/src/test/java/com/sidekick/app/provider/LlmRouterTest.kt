package com.sidekick.app.provider

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Verifies that the router routes each [Provider] variant to the matching
 * concrete client. Uses hand-rolled stub clients so the test doesn't depend
 * on OkHttp or MockWebServer.
 */
class LlmRouterTest {

    @Test
    fun localOllamaRoutesToOllamaClient() {
        val router = LlmRouter(
            ollamaFactory = { StubClient("ollama-${it.modelName}") },
            openAiFactory = { StubClient("openai-${it.modelName}") },
        )
        val provider = Provider.LocalOllama(baseUrl = "http://x", modelName = "llama3")
        val client = router.clientFor(provider)
        assertSame(client, router.clientFor(provider)) // cached
        assertEquals("ollama-llama3", (client as StubClient).tag)
    }

    @Test
    fun cloudOpenAiRoutesToOpenAiClient() {
        val router = LlmRouter(
            ollamaFactory = { StubClient("ollama-${it.modelName}") },
            openAiFactory = { StubClient("openai-${it.modelName}") },
        )
        val provider = Provider.CloudOpenAI(
            apiBaseUrl = "https://api.example.com/v1",
            apiKey = "sk-x",
            modelName = "gpt-4o-mini",
        )
        val client = router.clientFor(provider)
        assertSame(client, router.clientFor(provider))
        assertEquals("openai-gpt-4o-mini", (client as StubClient).tag)
    }

    @Test
    fun invalidateEvictsCachedClient() {
        var constructions = 0
        val router = LlmRouter(
            ollamaFactory = { StubClient("o-${++constructions}") },
            openAiFactory = { StubClient("a") },
        )
        val provider = Provider.LocalOllama(modelName = "m")
        val first = router.clientFor(provider)
        val second = router.clientFor(provider)
        assertSame(first, second)
        assertEquals(1, constructions)
        router.invalidate(provider)
        val third = router.clientFor(provider)
        assertNotSame(first, third)
        assertEquals(2, constructions)
    }

    private class StubClient(val tag: String) : LlmClient {
        override suspend fun stream(request: LlmRequest, onChunk: (LlmChunk) -> Unit): Job {
            // Return an already-completed Job — the test only needs a
            // Job-shaped handle; it doesn't care about streaming.
            val job = SupervisorJob()
            job.complete()
            return job
        }
    }
}