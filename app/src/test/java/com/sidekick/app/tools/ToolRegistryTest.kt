package com.sidekick.app.tools

import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.tools.builtins.ListDir
import com.sidekick.app.tools.builtins.ReadFile
import com.sidekick.app.tools.builtins.TakePhoto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [ToolRegistry] lookup, dispatch, and schema-export behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistryTest {

    @Test
    fun dispatchInvokesMatchingTool() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ctx = ToolContext(appContext = appContext, sessionId = 1L)
        // Write a fixture file ReadFile will pick up.
        java.io.File(appContext.filesDir, "x.txt").writeText("hello")

        val registry = ToolRegistry(listOf(ReadFile(), ListDir()))
        val result = registry.dispatch("read_file", argsOf("path" to "x.txt"), ctx)
        assertTrue(result is ToolResult.Ok)
        assertEquals("hello", (result as ToolResult.Ok).output)
    }

    @Test
    fun dispatchUnknownToolReturnsErr() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ctx = ToolContext(appContext = appContext, sessionId = 1L)
        val registry = ToolRegistry(listOf(ReadFile(), ListDir()))
        val result = registry.dispatch("nope", JsonObject(emptyMap()), ctx)
        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("unknown tool"))
    }

    @Test
    fun dispatchWrapsExceptionsInErr() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ctx = ToolContext(appContext = appContext, sessionId = 1L)
        val registry = ToolRegistry(listOf(ThrowingTool()))
        val result = registry.dispatch("thrower", JsonObject(emptyMap()), ctx)
        assertTrue("expected Err, got $result", result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("boom"))
    }

    @Test
    fun descriptorsAndSchemasExposed() {
        val registry = ToolRegistry(listOf(ReadFile(), ListDir(), TakePhoto()))
        val descriptors = registry.descriptors()
        assertEquals(3, descriptors.size)
        assertEquals(setOf("read_file", "list_dir", "take_photo"), descriptors.map { it.name }.toSet())

        val schemas = registry.schemas()
        assertEquals(3, schemas.size)
        // Every schema must be an object (JSON Schema top-level type).
        schemas.forEach { s ->
            assertNotNull(s["type"])
            assertEquals("object", (s["type"] as JsonPrimitive).content)
        }
    }

    @Test
    fun duplicateNamesRejectedAtConstruction() {
        val a = ReadFile()
        val b = ReadFile()
        try {
            ToolRegistry(listOf(a, b))
            error("expected IllegalArgumentException for duplicate tool names")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    private fun argsOf(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    /** Always-throws tool used by the exception-wrapping test. */
    private class ThrowingTool : Tool {
        override val name = "thrower"
        override val description = "throws on invoke"
        override val parameters = JsonObject(mapOf("type" to JsonPrimitive("object")))
        override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
            throw RuntimeException("boom")
        }
    }
}
