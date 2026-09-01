package com.sidekick.app.tools.builtins

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.sidekick.app.tools.Tool
import com.sidekick.app.tools.ToolContext
import com.sidekick.app.tools.ToolResult
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

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
 * `write_file` — create or overwrite a UTF-8 text file in the app sandbox.
 *
 * The inverse of [ReadFile]. Lets a teammate actually *produce* artefacts
 * (HTML pages, scripts, notes, configs) rather than only reading them.
 * Same sandbox rule as [ReadFile]: [path] is relative to `filesDir`, and
 * anything that canonicalises outside the sandbox is rejected.
 *
 * Directories are created on demand (`mkdirs`). The file is written
 * atomically-ish: content goes to a `.tmp` sibling first, then is renamed
 * into place so a crash mid-write never leaves a truncated file that
 * looks complete.
 *
 * Schema: `{ "path": string, "content": string }`, both required.
 */
class WriteFile : Tool {

    override val name: String = "write_file"
    override val description: String =
        "Create or overwrite a UTF-8 text file in the app sandbox. Path is relative to filesDir."

    override val parameters: JsonObject = jsonSchema(
        """
        {
          "type": "object",
          "properties": {
            "path": { "type": "string", "description": "Path relative to filesDir, e.g. 'site/index.html'." },
            "content": { "type": "string", "description": "Full text content to write." }
          },
          "required": ["path", "content"]
        }
        """.trimIndent(),
    )

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        val relPath = args["path"]?.jsonPrimitive?.contentOrNull
        if (relPath.isNullOrBlank()) {
            return ToolResult.Err("missing required argument: path")
        }
        val content = args["content"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Err("missing required argument: content")

        val resolved = resolveAgainstSandbox(ctx.appContext, relPath)
            ?: return ToolResult.Err("path escapes sandbox: $relPath")

        return try {
            val parent = resolved.parentFile ?: return ToolResult.Err("invalid path: $relPath")
            parent.mkdirs()
            val tmp = File(parent, resolved.name + ".tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(resolved)) {
                tmp.delete()
                return ToolResult.Err("write failed: could not finalise $relPath")
            }
            ToolResult.Ok("wrote ${content.length} bytes to $relPath")
        } catch (e: SecurityException) {
            ToolResult.Err("permission denied: ${e.message ?: relPath}")
        } catch (e: Exception) {
            ToolResult.Err("write failed: ${e.message ?: e::class.simpleName.orEmpty()}")
        }
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
 * `take_photo` — launch the device camera and save the captured image
 * into the app sandbox.
 *
 * M4 wires the real flow:
 *  1. Allocate an output destination under `filesDir/photos/<timestamp>.jpg`.
 *  2. Insert a `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` row on
 *     Android 10+ (falls back to a sandbox file URI on older devices).
 *  3. Ask the host activity to launch
 *     `ActivityResultContracts.TakePicture` via [CameraLauncher] and
 *     await the boolean result.
 *  4. On success, return the captured URI / file path in the
 *     `ToolResult.Ok.output` so the model can reference the image.
 *  5. On cancel, return `(cancelled)` so the agent loop doesn't loop
 *     forever waiting for an image.
 *
 * Tests inject a [CameraLauncher] via the [ToolContext]'s
 * `activityLauncher` field — production wires one from
 * `rememberLauncherForActivityResult` in
 * [com.sidekick.app.ui.ConversationScreen].
 */
class TakePhoto : Tool {

    override val name: String = "take_photo"
    override val description: String =
        "Capture a photo with the device camera. Returns the captured image URI or '(cancelled)' on cancel."

    override val parameters: JsonObject = jsonSchema(
        """
        {
          "type": "object",
          "properties": {}
        }
        """.trimIndent(),
    )

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        val launcher = ctx.activityLauncher
            ?: return ToolResult.Err(
                "camera not available in this context: no ActivityLauncher bound",
            )

        // Allocate the output URI up-front so we can abort cleanly on a
        // permission/IO failure before showing the camera UI.
        val target = createPhotoTarget(ctx.appContext)
            ?: return ToolResult.Err("could not allocate output destination for the captured photo")

        // Hand off to the activity. The launcher returns immediately with
        // a Deferred we await; cancellation of the surrounding coroutine
        // cancels the deferred but does NOT cancel the system camera
        // intent (Compose's launcher doesn't expose cancellation).
        val deferred = try {
            launcher.takePicture(target.uri)
        } catch (t: Throwable) {
            target.cleanup()
            return ToolResult.Err(
                "camera launch failed: ${t.message ?: t::class.simpleName.orEmpty()}",
            )
        }

        val captured: Boolean = try {
            deferred.await()
        } catch (t: Throwable) {
            target.cleanup()
            return ToolResult.Err(
                "camera capture failed: ${t.message ?: t::class.simpleName.orEmpty()}",
            )
        }

        if (!captured) {
            target.cleanup()
            return ToolResult.Ok("(cancelled)")
        }

        // Verify the file actually has bytes — some camera apps return
        // success=true without writing anything when the user backs out
        // at a permission dialog.
        if (!target.hasBytes()) {
            target.cleanup()
            return ToolResult.Err(
                "camera reported success but the output file is empty",
            )
        }

        return ToolResult.Ok(target.displayPath)
    }
}

/**
 * Where the camera should write the captured image. Two backends:
 *  - On Android 10+ (Q) we insert into MediaStore so the photo lands
 *    in the user's Pictures/Sidekick folder, scoped by the system.
 *  - Older devices use a sandbox `filesDir/photos/<timestamp>.jpg` path
 *    wrapped in a FileProvider URI (configured in
 *    `app/src/main/res/xml/file_paths.xml`).
 *
 * Either way the returned [Uri] is fed to
 * `ActivityResultContracts.TakePicture`, and the model gets back the
 * canonical reference path in [displayPath].
 */
internal sealed class PhotoTarget {
    abstract val uri: Uri
    abstract val displayPath: String

    /** True if the output file has at least one byte. */
    abstract fun hasBytes(): Boolean

    /** Remove the destination on a failed/cancelled capture. */
    abstract fun cleanup()

    /** A MediaStore-backed output (Android 10+). */
    data class MediaStoreTarget(
        override val uri: Uri,
        val resolver: android.content.ContentResolver,
    ) : PhotoTarget() {
        override val displayPath: String get() = uri.toString()

        override fun hasBytes(): Boolean = try {
            resolver.openInputStream(uri)?.use { it.available() > 0 } ?: false
        } catch (_: Exception) {
            false
        }

        override fun cleanup() {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
                // Best-effort — MediaStore rows are GC'd by the system.
            }
        }
    }

    /** A sandbox-file output (older Android versions). */
    data class SandboxTarget(
        override val uri: Uri,
        val file: File,
    ) : PhotoTarget() {
        override val displayPath: String get() = file.absolutePath

        override fun hasBytes(): Boolean = file.exists() && file.length() > 0

        override fun cleanup() {
            try {
                if (file.exists()) file.delete()
            } catch (_: Exception) {
                // ignore
            }
        }
    }
}

/**
 * Build the [PhotoTarget] for the next capture. Returns `null` if neither
 * MediaStore nor the sandbox directory is usable.
 */
internal fun createPhotoTarget(context: Context): PhotoTarget? {
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val name = "sidekick_$ts.jpg"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sidekick")
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return null
        return PhotoTarget.MediaStoreTarget(uri = uri, resolver = context.contentResolver)
    }

    // Pre-Q: write into the app sandbox and let the FileProvider expose it.
    return try {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val file = File(dir, name)
        val authority = "${context.packageName}.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        PhotoTarget.SandboxTarget(uri = uri, file = file)
    } catch (_: Exception) {
        null
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
