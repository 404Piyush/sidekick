package com.sidekick.app.tools

import kotlinx.serialization.json.JsonObject

/**
 * Registry of [Tool]s the agent loop can dispatch to.
 *
 * The registry is a thin lookup layer. Construction takes a fixed list of
 * tool instances; the agent loop holds one registry for the life of the
 * process (or for the life of a single conversation, when we add scope
 * rules in a later milestone). [dispatch] is the only mutating entry point
 * — it routes a model-emitted tool call to the right [Tool.invoke].
 *
 * @property tools Immutable list of registered tools. Names must be
 *                  unique; duplicate-name behaviour is undefined but the
 *                  constructor doesn't crash because the lookup falls
 *                  back to the first match (which is the conventional
 *                  "last writer wins" pitfall we'd rather catch at boot).
 */
class ToolRegistry(private val tools: List<Tool>) {

    private val byName: Map<String, Tool> = tools.associateBy { it.name }

    init {
        require(tools.size == byName.size) {
            "duplicate tool names in registry: " +
                tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
        }
    }

    /** Read-only view of the registered tools — for the agent loop's tool list. */
    fun descriptors(): List<ToolDescriptor> = tools.map { t ->
        ToolDescriptor(name = t.name, description = t.description, parameters = t.parameters)
    }

    /** Schema-only view; same data as [descriptors] minus the description. */
    fun schemas(): List<JsonObject> = tools.map { it.parameters }

    /**
     * Look up [name] and invoke the matching tool's [Tool.invoke]. If no
     * tool is registered under that name, returns [ToolResult.Err] with a
     * "unknown tool: ..." message — this never throws, so the agent loop
     * can forward the error to the model without special-casing.
     *
     * Exceptions thrown by [Tool.invoke] are caught and wrapped in [ToolResult.Err]
     * so a misbehaving tool doesn't tear down the loop. The original
     * exception's message is preserved for debugging.
     */
    suspend fun dispatch(name: String, args: JsonObject, ctx: ToolContext): ToolResult {
        val tool = byName[name]
            ?: return ToolResult.Err("unknown tool: $name")
        return try {
            tool.invoke(args, ctx)
        } catch (t: Throwable) {
            ToolResult.Err("tool '$name' threw: ${t.message ?: t::class.simpleName.orEmpty()}")
        }
    }
}

/**
 * Provider-facing description of a [Tool] — name, description, and JSON Schema
 * for arguments. Passed to [com.sidekick.app.provider.LlmRequest] (well,
 * the next milestone's overload) so the model knows what's available.
 *
 * Decoupled from [Tool] so the schema can be sent over the wire without
 * dragging the implementation's class identity along.
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
