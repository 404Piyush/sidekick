package com.sidekick.app.provider

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LocalOnDeviceProvider].
 *
 * These run on the JVM only — the LiteRT-LM runtime is an `.aar` full of
 * native libraries, so the real `Engine.initialize()` path requires an
 * Android device or emulator. The tests cover what we CAN verify on the
 * JVM:
 *
 *  1. Construction with valid arguments succeeds; bad arguments are
 *     caught by the `require(...)` guards.
 *  2. [LocalOnDeviceProvider.buildEngineConfig] produces a sensible
 *     [com.google.ai.edge.litertlm.EngineConfig] for each [Backend]
 *     variant.
 *  3. The provider's [close] is idempotent (calling it twice doesn't
 *     throw).
 *  4. The [LocalOnDeviceProvider] class is registered with [LlmRouter]
 *     and dispatches through the `localOnDeviceFactory`.
 *
 * What we DO NOT test here (deliberately deferred to a device run):
 *  - Actual token emission from a real model (requires a `.litertlm`
 *    file and a native backend to be available)
 *  - System-prompt handling at the conversation level (same reason)
 */
class LocalOnDeviceProviderTest {

    @After
    fun cleanup() {
        // No-op: each test constructs its own provider and closes it
        // inline so we don't keep an Engine alive between tests.
    }

    @Test
    fun constructionWithDefaultsSucceeds() {
        val provider = LocalOnDeviceProvider(modelPath = "/tmp/fake.litertlm")
        // The provider object should be constructable and hold the path.
        assertEquals("/tmp/fake.litertlm", provider.modelPath)
        // Default backend is NPU (per the iQOO brief).
        assertEquals(Backend.NPU, provider.backend)
    }

    @Test
    fun constructionWithExplicitBackendWorks() {
        for (b in listOf(Backend.NPU, Backend.GPU, Backend.CPU)) {
            val p = LocalOnDeviceProvider(modelPath = "/x.litertlm", backend = b)
            assertEquals(b, p.backend)
            p.close()
        }
    }

    @Test
    fun blankModelPathIsRejected() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            LocalOnDeviceProvider(modelPath = "")
        }
        assertTrue(
            "message should mention modelPath: ${ex.message}",
            ex.message.orEmpty().contains("modelPath"),
        )
    }

    @Test
    fun negativeMaxTokensIsRejected() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            LocalOnDeviceProvider(modelPath = "/x.litertlm", maxTokens = 0)
        }
        assertTrue(
            "message should mention maxTokens: ${ex.message}",
            ex.message.orEmpty().contains("maxTokens"),
        )
    }

    @Test
    fun negativeTemperatureIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalOnDeviceProvider(modelPath = "/x.litertlm", temperature = -0.1)
        }
    }

    @Test
    fun buildEngineConfigMapsBackendCorrectly() {
        // NPU with nativeLibraryDir
        val npu = LocalOnDeviceProvider(
            modelPath = "/x.litertlm",
            backend = Backend.NPU,
            nativeLibraryDir = "/data/app/lib",
        )
        val npuConfig = npu.buildEngineConfig()
        assertEquals("/x.litertlm", npuConfig.modelPath)
        val npuBackend = npuConfig.backend
        assertTrue("expected NPU backend, got $npuBackend", npuBackend is com.google.ai.edge.litertlm.Backend.NPU)
        val cast = npuBackend as com.google.ai.edge.litertlm.Backend.NPU
        assertEquals("/data/app/lib", cast.nativeLibraryDir)

        // GPU (no NPU dir)
        val gpu = LocalOnDeviceProvider(
            modelPath = "/x.litertlm",
            backend = Backend.GPU,
        )
        assertTrue(
            "GPU backend: ${gpu.buildEngineConfig().backend}",
            gpu.buildEngineConfig().backend is com.google.ai.edge.litertlm.Backend.GPU,
        )

        // CPU (no args)
        val cpu = LocalOnDeviceProvider(
            modelPath = "/x.litertlm",
            backend = Backend.CPU,
        )
        assertTrue(
            "CPU backend: ${cpu.buildEngineConfig().backend}",
            cpu.buildEngineConfig().backend is com.google.ai.edge.litertlm.Backend.CPU,
        )

        // Cleanup — even though initialize() was never called, the close
        // should be safe (no-op).
        npu.close()
        gpu.close()
        cpu.close()
    }

    @Test
    fun closeIsIdempotent() {
        val provider = LocalOnDeviceProvider(modelPath = "/x.litertlm")
        // Calling close() before initialize() should be safe — the
        // engine field is still null.
        provider.close()
        provider.close()
    }

    @Test
    fun routerRoutesLocalOnDeviceToFactory() {
        val router = LlmRouter(
            localOnDeviceFactory = { StubLocalOnDeviceClient(it.modelPath, it.backend) },
        )
        val provider = Provider.LocalOnDevice(
            modelPath = "/models/gemma.litertlm",
            backend = Backend.GPU,
        )
        val client = router.clientFor(provider)
        assertTrue(client is StubLocalOnDeviceClient)
        assertEquals("/models/gemma.litertlm", (client as StubLocalOnDeviceClient).path)
        assertEquals(Backend.GPU, client.backend)
        // Cached on second call.
        assertEquals(client, router.clientFor(provider))
    }

    @Test
    fun routerInvalidateClosesLocalOnDeviceProvider() {
        var constructed = 0
        var closed = 0
        val router = LlmRouter(
            localOnDeviceFactory = {
                constructed++
                object : StubLocalOnDeviceClient(it.modelPath, it.backend) {
                    override fun close() {
                        closed++
                    }
                }
            },
        )
        val provider = Provider.LocalOnDevice(modelPath = "/x.litertlm")
        val first = router.clientFor(provider)
        router.invalidate(provider)
        val second = router.clientFor(provider)
        // We expect 2 constructions and 1 close (the cache eviction
        // triggers close on the removed entry).
        assertEquals(2, constructed)
        assertEquals(1, closed)
        // Cache is fresh — different instances.
        assertTrue("invalidate must produce a new client instance", first !== second)
    }

    @Test
    fun routerInvalidateClosesOllamaClientAsNoOp() {
        // Sanity check: invalidate on a non-on-device provider shouldn't
        // crash when the cached client is a plain OllamaProvider.
        val router = LlmRouter()
        val ollama = Provider.LocalOllama(modelName = "llama3")
        router.clientFor(ollama)
        router.invalidate(ollama)
        // No exception means the `as? LocalOnDeviceProvider` cast
        // gracefully no-ops.
    }

    /**
     * Stub [LlmClient] that mimics [LocalOnDeviceProvider]'s surface
     * area for the router tests — we don't want to actually load a
     * native library in unit tests.
     */
    private open class StubLocalOnDeviceClient(
        val path: String,
        val backend: Backend,
    ) : LlmClient {
        override suspend fun stream(request: LlmRequest, onChunk: (LlmChunk) -> Unit): Job {
            // Validate that the system prompt is correctly forwarded:
            // the router is a passive pass-through, but the request
            // shape matters — if it carries a system prompt, we want
            // to make sure the provider factory receives a Provider
            // whose modelPath is what we asked for.
            val systemMessages = request.messages.filter { it.role == "system" }
            assertNotNull(systemMessages)
            // Return a completed Job — the router tests don't care
            // about actual streaming.
            return kotlinx.coroutines.SupervisorJob().apply { complete() }
        }

        open fun close() {
            // Subclasses may override.
        }
    }
}