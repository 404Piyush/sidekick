package com.sidekick.app.tools.builtins

import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.tools.ToolContext
import com.sidekick.app.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for [ListDir]. Verifies entry listing, directory-vs-file
 * differentiation, sandbox rejection, and the `path` default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ListDirTest {

    private lateinit var ctx: ToolContext
    private lateinit var appContext: android.content.Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        ctx = ToolContext(appContext = appContext, sessionId = 7L)
        appContext.filesDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        appContext.filesDir.deleteRecursively()
    }

    @Test
    fun listsFilesAndDirectoriesWithTrailingSlash() = runBlocking {
        val root = appContext.filesDir
        File(root, "a.txt").writeText("a")
        File(root, "sub").mkdirs()
        File(root, "b.txt").writeText("b")

        val result = ListDir().invoke(argsOf("path" to "."), ctx)
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val output = (result as ToolResult.Ok).output
        // Sorted alphabetically. Subdirectories are suffixed with /.
        assertEquals(listOf("a.txt", "b.txt", "sub/").joinToString("\n"), output)
    }

    @Test
    fun defaultsToFilesDirWhenNoPath() = runBlocking {
        File(appContext.filesDir, "only.txt").writeText("x")

        val result = ListDir().invoke(JsonObject(emptyMap()), ctx)
        assertTrue(result is ToolResult.Ok)
        assertEquals("only.txt", (result as ToolResult.Ok).output)
    }

    @Test
    fun emptyDirectoryYieldsEmptyString() = runBlocking {
        File(appContext.filesDir, "empty").mkdirs()

        val result = ListDir().invoke(argsOf("path" to "empty"), ctx)
        assertTrue(result is ToolResult.Ok)
        assertEquals("", (result as ToolResult.Ok).output)
    }

    @Test
    fun missingDirectoryReturnsErr() = runBlocking {
        val result = ListDir().invoke(argsOf("path" to "nope"), ctx)
        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("not found"))
    }

    @Test
    fun pathAgainstFileReturnsErr() = runBlocking {
        File(appContext.filesDir, "x.txt").writeText("x")
        val result = ListDir().invoke(argsOf("path" to "x.txt"), ctx)
        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("not a directory"))
    }

    @Test
    fun pathTraversalIsRejected() = runBlocking {
        val result = ListDir().invoke(argsOf("path" to "../../etc"), ctx)
        assertTrue(result is ToolResult.Err)
        assertTrue((result as ToolResult.Err).message.contains("sandbox"))
    }

    @Test
    fun hiddenEntriesAreSkipped() = runBlocking {
        val root = appContext.filesDir
        File(root, "visible.txt").writeText("v")
        File(root, ".hidden").writeText("h")

        val result = ListDir().invoke(argsOf("path" to "."), ctx)
        assertTrue(result is ToolResult.Ok)
        assertEquals("visible.txt", (result as ToolResult.Ok).output)
    }

    private fun argsOf(vararg pairs: Pair<String, String>): JsonObject {
        val map = pairs.associate { (k, v) -> k to JsonPrimitive(v) }
        return JsonObject(map)
    }
}
