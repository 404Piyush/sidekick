package com.sidekick.app.tools.builtins

import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.tools.ToolContext
import com.sidekick.app.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * In-memory [ReadFile] tests against a fixture file written into the
 * app's `filesDir`. Uses Robolectric because [android.content.Context]
 * isn't available in pure-JVM tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReadFileTest {

    private lateinit var ctx: ToolContext
    private lateinit var appContext: android.content.Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        ctx = ToolContext(appContext = appContext, sessionId = 42L)
        // Reset filesDir between tests.
        appContext.filesDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        appContext.filesDir.deleteRecursively()
    }

    @Test
    fun readsExistingTextFile() = runBlocking {
        val file = File(appContext.filesDir, "notes/todo.md").apply {
            parentFile?.mkdirs()
            writeText("hello, world", Charsets.UTF_8)
        }

        val result = ReadFile().invoke(argsOf("path" to "notes/todo.md"), ctx)
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        assertEquals("hello, world", (result as ToolResult.Ok).output)
        // The file is still on disk and untouched.
        assertEquals("hello, world", file.readText(Charsets.UTF_8))
    }

    @Test
    fun missingPathReturnsErr() = runBlocking {
        val result = ReadFile().invoke(argsOf("path" to "does-not-exist.txt"), ctx)
        assertTrue("expected Err, got $result", result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("not found"))
    }

    @Test
    fun emptyPathReturnsErr() = runBlocking {
        val result = ReadFile().invoke(argsOf("path" to ""), ctx)
        assertTrue(result is ToolResult.Err)
    }

    @Test
    fun pathTraversalIsRejected() = runBlocking {
        // ../../etc/passwd resolves outside filesDir → Err.
        val result = ReadFile().invoke(argsOf("path" to "../../../etc/passwd"), ctx)
        assertTrue("expected Err for path traversal, got $result", result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("sandbox"))
    }

    @Test
    fun directoryPathReturnsErr() = runBlocking {
        File(appContext.filesDir, "subdir").mkdirs()
        val result = ReadFile().invoke(argsOf("path" to "subdir"), ctx)
        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("not a file"))
    }

    @Test
    fun oversizedFileReturnsErr() = runBlocking {
        // Default limit is 256 KiB; build a 300 KiB file.
        val big = "a".repeat(300 * 1024)
        File(appContext.filesDir, "big.txt").writeText(big, Charsets.UTF_8)

        val result = ReadFile().invoke(argsOf("path" to "big.txt"), ctx)
        assertTrue("expected Err for oversized file, got $result", result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("too large"))
    }

    @Test
    fun parametersAdvertisePathField() {
        val params = ReadFile().parameters
        assertNotNull(params["properties"])
        val props = params["properties"]!!.let { it as JsonObject }
        assertNotNull(props["path"])
    }

    /** Build a [JsonObject] from vararg `key to value` string pairs. */
    private fun argsOf(vararg pairs: Pair<String, String>): JsonObject {
        val map = pairs.associate { (k, v) -> k to JsonPrimitive(v) }
        return JsonObject(map)
    }
}
