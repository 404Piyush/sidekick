package com.sidekick.app.tools.builtins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Parse a JSON Schema literal (typed as a string in source for readability)
 * into a [JsonObject]. Shared across the built-in tools so each schema
 * definition can be one inline `"""..."""` block in the file the model
 * reads.
 */
internal fun jsonSchema(literal: String): JsonObject {
    val element = SCHEMA_JSON.parseToJsonElement(literal)
    return element as? JsonObject
        ?: error("tool parameter schema must be a JSON object, got: ${element::class.simpleName}")
}

private val SCHEMA_JSON: Json = Json {
    // Provider tool-call schemas sometimes have vendor extensions we don't
    // model; ignoreUnknownKeys keeps the parse strict on shape only.
    ignoreUnknownKeys = true
}
