package com.sidekick.app.tools.builtins

import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.tools.ToolContext
import com.sidekick.app.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stub test for [TakePhoto]. M3's contract is: returns
 * [ToolResult.Err] with the documented "camera not yet implemented" message.
 *
 * M4 will replace this with the real ActivityResultContract.TakePicture
 * path; these tests are deliberately minimal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TakePhotoTest {

    @Test
    fun returnsNotImplementedError() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ctx = ToolContext(appContext = appContext, sessionId = 1L)
        val result = TakePhoto().invoke(JsonObject(emptyMap()), ctx)
        assertTrue("expected Err, got $result", result is ToolResult.Err)
        assertEquals(
            "camera not yet implemented in M3; see M4",
            (result as ToolResult.Err).message,
        )
    }

    @Test
    fun hasExpectedNameAndParameters() {
        val tool = TakePhoto()
        assertEquals("take_photo", tool.name)
        assertTrue(tool.description.contains("STUB"))
        // The parameters JSON Schema exists (object type, empty properties).
        val params = tool.parameters
        assertEquals("object", params["type"]?.toString()?.trim('"'))
    }
}
