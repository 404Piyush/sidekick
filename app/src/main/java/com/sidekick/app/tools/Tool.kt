package com.sidekick.app.tools

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.JsonObject

/**
 * A tool the agent loop can dispatch to on behalf of the model.
 *
 * Sidekick's tool surface is intentionally minimal: each tool is a small
 * function the model can ask for by name, parameterised by a JSON-Schema-shaped
 * argument object. The agent loop ([com.sidekick.app.agent.AgentLoop]) reads
 * the schema to forward tool-call opportunities to the provider, and on a
 * model-emitted tool call invokes [invoke] with the parsed arguments.
 *
 * Implementations live in [com.sidekick.app.tools.builtins]. They are
 * stateless with respect to the conversation — any per-conversation state
 * lives in [ToolContext.sessionId].
 *
 * @property name Stable identifier (e.g. `"read_file"`). Must match the
 *               schema advertised to the provider and the function name
 *               the model emits. Stored verbatim in
 *               [com.sidekick.app.data.ToolCallEntity.toolName] for the
 *               transcript log.
 * @property description One-line, human-readable explanation shown to the
 *                       model so it knows when to call the tool. Keep under
 *                       200 chars to fit provider context windows cheaply.
 * @property parameters JSON Schema (Draft 7-ish) describing the argument
 *                      shape. The agent loop passes this verbatim to
 *                      OpenAI-style providers; the model is then expected
 *                      to emit a `function.arguments` string that parses
 *                      back to a [JsonObject] matching the schema. Sidekick
 *                      doesn't validate the schema at runtime — the model is
 *                      trusted to conform.
 */
interface Tool {
    val name: String
    val description: String
    val parameters: JsonObject

    /**
     * Invoke the tool with [args] (already parsed into a [JsonObject]).
     *
     * Implementations MUST NOT block: the agent loop calls this on the
     * IO-bound dispatcher. Implementations MUST return a [ToolResult] —
     * either [ToolResult.Ok] for a successful invocation or [ToolResult.Err]
     * for any failure (missing argument, IO error, permission denied, etc.).
     * The agent loop surfaces both to the model as the tool message, so the
     * caller should put enough context in [ToolResult.Err.message] for the
     * model to retry or give up.
     *
     * @param ctx Sandbox context carrying the Android [Context] (for file
     *            reads scoped to `filesDir`) and the active conversation
     *            id (for any tool that needs to attach artefacts, e.g. the
     *            camera tool which writes photos into `filesDir/photos/`).
     */
    suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult
}

/**
 * Result of a [Tool.invoke] call.
 *
 * Sealed so the agent loop's `when` exhaustively covers both branches.
 * [Ok.output] is a plain string — the model is a text consumer, so tools
 * format structured results as JSON-in-string or human-readable text. [Err]
 * is for any failure mode including exceptions thrown by the implementation
 * (the loop catches those and turns them into [Err] before forwarding to
 * the model).
 */
sealed class ToolResult {
    data class Ok(val output: String) : ToolResult()
    data class Err(val message: String) : ToolResult()
}

/**
 * Bridge between a tool and the host activity's
 * `registerForActivityResult(ActivityResultContracts.TakePicture())` hook.
 *
 * The tool runs inside the agent loop on `Dispatchers.IO`; launching an
 * Activity Result contract from a non-main thread is fragile (the underlying
 * `ActivityResultRegistry` writes to its `mKeyToCallback` map without a
 * lock). So the UI binds the contract up-front and exposes a
 * coroutine-friendly [takePicture] here. Tests substitute a fake that
 * returns a predetermined `Boolean` without spinning up an Activity.
 *
 * `takePicture` is a non-suspending function that returns immediately
 * with a [kotlinx.coroutines.CompletableDeferred] the caller can await.
 * This avoids the chicken-and-egg of `suspendCancellableCoroutine` plus a
 * Compose `rememberLauncherForActivityResult` callback (Compose's
 * launcher fires the callback off-thread; a coroutine continuation
 * resumed there is not guaranteed to land on the original dispatcher).
 */
fun interface CameraLauncher {
    /**
     * Launch the system camera, write the captured image into [outputUri],
     * and resolve the returned [kotlinx.coroutines.CompletableDeferred]
     * with `true` on success or `false` if the user cancelled.
     *
     * Implementations MUST surface the result through the deferred
     * exactly once. The caller ([TakePhoto]) awaits the deferred as a
     * `suspend` operation.
     */
    fun takePicture(outputUri: Uri): kotlinx.coroutines.CompletableDeferred<Boolean>
}

/**
 * Per-invocation context for [Tool.invoke].
 *
 * M3 keeps this intentionally tiny. Tools that need more (per-user
 * settings, network clients, etc.) take them through their constructor —
 * the registry passes a single shared instance per tool across calls.
 *
 * M4 added [activityLauncher] for tools that need to talk back to the
 * host Activity (camera capture, future share/file-pickers). It is
 * `null` when the tool is invoked outside a Compose screen — e.g. by
 * a headless test fixture — and tools should fail closed (`ToolResult.Err`)
 * when they can't proceed without one.
 *
 * @property appContext The application [Context]. Used by file-system
 *                      tools (read_file, list_dir) so paths can resolve
 *                      against `context.filesDir` and be sandboxed.
 * @property sessionId  The active conversation id, for tools that
 *                      associate output with a conversation (e.g. an
 *                      image attachment).
 * @property activityLauncher Camera-launcher bridge. `null` when the
 *                      tool runs headlessly (tests, background jobs).
 *                      See [CameraLauncher].
 */
data class ToolContext(
    val appContext: Context,
    val sessionId: Long,
    val activityLauncher: CameraLauncher? = null,
)
