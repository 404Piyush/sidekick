package com.sidekick.app.tools.builtins

import android.content.Context
import com.sidekick.app.tools.Tool
import com.sidekick.app.tools.ToolContext
import com.sidekick.app.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileNotFoundException

/**
 * `read_file` — read a UTF-8 text file from the app sandbox.
 *
 * The `path` argument is a **relative** path resolved against
 * [Context.getFilesDir]. Anything resolving outside `filesDir` (i.e. a
 * `../` escape) is rejected with [ToolResult.Err] — the tool refuses to
 * peek at neighbouring apps' data or the system partition. Files larger
 * than [maxBytes] (default 256 KiB) are rejected to keep prompts bounded.
 *
 * The output is the raw file content. The model is expected to consume
 * it as text; we don't attempt to parse JSON / detect encoding.
 *
 * Schema (advertised to providers as the `parameters` JSON Schema):
 * ```
 * {
 *   "type": "object",
 *   "properties": { "path": { "type": "string" } },
 *   "required": ["path"]
 * }
 * ```
 */
class ReadFile(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : Tool {

    override val name: String = "read_file"
    override val description: String =
        "Read a UTF-8 text file from the app sandbox. Path is relative to filesDir."

    override val parameters: JsonObject = jsonSchema(
        """
        {
          "type": "object",
          "properties": { "path": { "type": "string", "description": "Path relative to the app's filesDir, e.g. 'notes/todo.md'." } },
          "required": ["path"]
        }
        """.trimIndent(),
    )

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        val relPath = args["path"]?.jsonPrimitive?.contentOrNull
        if (relPath.isNullOrBlank()) {
            return ToolResult.Err("missing required argument: path")
        }
        val resolved = resolveAgainstSandbox(ctx.appContext, relPath)
            ?: return ToolResult.Err("path escapes sandbox: $relPath")
        if (!resolved.exists()) {
            return ToolResult.Err("file not found: $relPath")
        }
        if (!resolved.isFile) {
            return ToolResult.Err("not a file: $relPath")
        }
        if (resolved.length() > maxBytes) {
            return ToolResult.Err(
                "file too large: ${resolved.length()} bytes (limit $maxBytes) — $relPath",
            )
        }
        return try {
            ToolResult.Ok(resolved.readText(Charsets.UTF_8))
        } catch (e: FileNotFoundException) {
            ToolResult.Err("file not found: $relPath")
        } catch (e: SecurityException) {
            ToolResult.Err("permission denied: ${e.message ?: relPath}")
        } catch (e: Exception) {
            ToolResult.Err("read failed: ${e.message ?: e::class.simpleName.orEmpty()}")
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024L
    }
}

/**
 * `list_dir` — list the entries of a directory in the app sandbox.
 *
 * Like [ReadFile], the path is relative to [Context.getFilesDir] and
 * cannot escape the sandbox. Returns one entry per line: directories are
 * suffixed with `/` so the model can tell them from regular files.
 *
 * Hidden entries (`.` prefix) are skipped to avoid leaking the agent
 * loop's own bookkeeping. Empty directories yield an empty-string
 * output (not an error).
 */
class ListDir : Tool {

    override val name: String = "list_dir"
    override val description: String =
        "List entries in a directory in the app sandbox. Path is relative to filesDir; defaults to '.'."

    override val parameters: JsonObject = jsonSchema(
        """
        {
          "type": "object",
          "properties": { "path": { "type": "string", "description": "Path relative to filesDir, default '.'." } }
        }
        """.trimIndent(),
    )

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        val relPath = args["path"]?.jsonPrimitive?.contentOrNull ?: "."
        val resolved = resolveAgainstSandbox(ctx.appContext, relPath)
            ?: return ToolResult.Err("path escapes sandbox: $relPath")
        if (!resolved.exists()) {
            return ToolResult.Err("directory not found: $relPath")
        }
        if (!resolved.isDirectory) {
            return ToolResult.Err("not a directory: $relPath")
        }
        val children = resolved.listFiles()
            ?.filter { !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            .orEmpty()
        val output = children.joinToString(separator = "\n") { f ->
            if (f.isDirectory) "${f.name}/" else f.name
        }
        return ToolResult.Ok(output)
    }
}

/**
 * `take_photo` — **STUB for M3.** Returns a not-yet-implemented error and
 * a `TODO` marker so the agent loop can still dispatch it (and so tests
 * can prove the dispatch path works end-to-end).
 *
 * M4 will replace [invoke] with a real `ActivityResultContract.TakePicture`
 * launch that:
 *   1. Creates a temp file under `filesDir/photos/`.
 *   2. Returns the URI through `FileProvider`.
 *   3. Awaits the result and persists a [com.sidekick.app.data.ToolCallEntity]
 *      row pointing at the captured image.
 *   4. Returns Ok with a marker like `"captured:<path>"`.
 *
 * For M3 the stub keeps the tool registry well-formed and the dispatch
 * test exercising the camera branch; the agent loop forwards the error
 * message to the model verbatim.
 */
class TakePhoto : Tool {

    override val name: String = "take_photo"
    override val description: String =
        "Capture a photo with the device camera. STUB in M3 — returns not_implemented."

    override val parameters: JsonObject = jsonSchema(
        """
        {
          "type": "object",
          "properties": {}
        }
        """.trimIndent(),
    )

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        // TODO(M4): replace with ActivityResultContract.TakePicture + FileProvider.
        return ToolResult.Err("camera not yet implemented in M3; see M4")
    }
}

/**
 * Resolve [relPath] against the app's `filesDir`, returning `null` if the
 * resolved file escapes the sandbox (path-traversal attempt).
 *
 * Centralised so [ReadFile] and [ListDir] enforce the same rule.
 */
internal fun resolveAgainstSandbox(appContext: Context, relPath: String): File? {
    val sandbox = appContext.filesDir.canonicalFile
    val candidate = File(sandbox, relPath).canonicalFile
    // canonicalFile collapses ../, symlinks, etc. Anything not starting
    // with the sandbox root is an escape attempt — reject.
    return if (candidate.path.startsWith(sandbox.path + File.separator) ||
        candidate.path == sandbox.path
    ) {
        candidate
    } else {
        null
    }
}
