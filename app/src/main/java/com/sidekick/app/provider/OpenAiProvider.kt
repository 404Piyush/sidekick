package com.sidekick.app.provider

import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import android.net.Uri
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import com.sidekick.app.tools.ToolDescriptor
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI-compatible chat-completions provider.
 *
 * Wire format (Server-Sent Events over HTTP):
 *   data: {"id":"…","choices":[{"delta":{"content":"Hel"}}]}
 *
 *   data: {"id":"…","choices":[{"delta":{"content":"lo!"}}]}
 *
 *   data: {"id":"…","choices":[],"usage":{...}}
 *
 *   data: [DONE]
 *
 * - Each non-terminal `data:` line is a JSON object with a
 *   `choices[0].delta.content` field carrying the incremental text.
 * - The literal `data: [DONE]` line is the terminal marker.
 * - Some servers send a usage-only frame just before `[DONE]` — we capture it.
 *
 * Auth: the [apiKey] is sent as a Bearer token on every request. It must not
 * be logged; that's the caller's responsibility.
 */
class OpenAiProvider(
    internal val apiBaseUrlInternal: String,
    internal val apiKeyInternal: String,
    internal val modelNameInternal: String,
    internal val clientInternal: OkHttpClient = defaultClient(),
    internal val context: android.content.Context? = null,
) : LlmClient {

    override suspend fun stream(request: LlmRequest, onChunk: (LlmChunk) -> Unit): Job =
        coroutineScope {
            // Same propagation rule as OllamaProvider: the returned Job is
            // the async's Job — cancelling it cancels the OkHttp Call.
            async(Dispatchers.IO) { execute(request, onChunk) }
        }

    private suspend fun execute(request: LlmRequest, onChunk: (LlmChunk) -> Unit) {
        val httpRequest = buildRequest(request)
        val response = clientInternal.newCall(httpRequest).awaitResponseOrThrow()
        response.use { handleResponse(it, onChunk) }
    }

    private suspend fun buildRequest(request: LlmRequest): Request {
        // Convert [ToolDescriptor] into OpenAI's wire shape:
        //   [{ "type": "function", "function": { "name": ..., "description": ..., "parameters": <schema> } }]
        val toolDescriptors: List<ToolDescriptor> = request.tools.orEmpty()
        val toolsJson: List<JsonElement>? = if (toolDescriptors.isEmpty()) {
            null
        } else {
            toolDescriptors.map { d: ToolDescriptor ->
                buildJsonObject {
                    put("type", JsonPrimitive("function"))
                    put("function", buildJsonObject {
                        put("name", JsonPrimitive(d.name))
                        put("description", JsonPrimitive(d.description))
                        put("parameters", d.parameters)
                    })
                }
            }
        }
        val body = OpenAiRequest(
            model = modelNameInternal,
            messages = request.messages.map { encodeMessage(it) },
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            stream = true,
            // Ask OpenAI to include usage in the streaming response.
            // Older / third-party servers that don't understand this field
            // ignore unknown keys because of `ignoreUnknownKeys = true`.
            streamOptions = OpenAiStreamOptions(includeUsage = true),
            tools = toolsJson,
        )
        val json = json.encodeToString(OpenAiRequest.serializer(), body)
        val url = apiBaseUrlInternal.trimEnd('/') + "/chat/completions"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKeyInternal")
            .header("Accept", "text/event-stream")
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private suspend fun handleResponse(response: Response, onChunk: (LlmChunk) -> Unit) {
        if (!response.isSuccessful) {
            val snippet = response.body?.string()?.take(200).orEmpty()
            throw LlmException.HttpStatus(
                code = response.code,
                message = "OpenAI returned HTTP ${response.code}: $snippet",
            )
        }
        val source = response.body?.source()
            ?: throw LlmException.Decode("OpenAI response had empty body")

        source.use { s ->
            while (!s.exhausted()) {
                val line = s.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (line.startsWith("data:")) {
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") {
                        onChunk(LlmChunk.Done(usage = pendingUsage))
                        pendingUsage = null
                        return
                    }
                    parseDataPayload(payload, onChunk)
                }
                // Other SSE fields (event:, id:, retry:, comments) are ignored
                // — we only care about `data:` lines.
            }
        }
    }

    private fun parseDataPayload(payload: String, onChunk: (LlmChunk) -> Unit) {
        val element: JsonElement = try {
            json.parseToJsonElement(payload)
        } catch (e: Exception) {
            throw LlmException.Decode("Could not parse SSE data payload: ${payload.take(80)}", e)
        }
        // Provider error envelope: {"error": {"message": "..."}}
        element.jsonObject["error"]?.let { err ->
            val msg = err.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
            throw LlmException.ProviderSpecific("OpenAI error: $msg")
        }
        // Choices delta.
        element.jsonObject["choices"]?.jsonArray?.forEach { choiceEl ->
            val choice = choiceEl.jsonObject
            val content = choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            if (content != null && content.isNotEmpty()) {
                onChunk(LlmChunk.Text(content))
            }
            // Tool-call deltas: OpenAI streams tool_calls as a list where each
            // entry's `function.arguments` may be partial. We accumulate
            // per-index into [pendingToolCalls] until the index sends no more
            // deltas (we flush on each chunk — the LlmChunk.ToolCall emits
            // the latest snapshot, and the agent loop uses the final one).
            val toolCalls = choice["delta"]?.jsonObject?.get("tool_calls")?.jsonArray
            if (toolCalls != null) {
                for (tcEl in toolCalls) {
                    val tc = tcEl.jsonObject
                    val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val entry = pendingToolCalls.getOrPut(index) { ToolCallAccumulator() }
                    tc["id"]?.jsonPrimitive?.contentOrNull?.let { entry.id = it }
                    val fn = tc["function"]?.jsonObject
                    if (fn != null) {
                        fn["name"]?.jsonPrimitive?.contentOrNull?.let { entry.name = it }
                        fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { entry.argsBuffer.append(it) }
                    }
                }
            }
            // `finish_reason: "tool_calls"` is the natural point to emit a
            // fully-assembled ToolCall chunk. Some servers don't set it, so
            // we additionally flush on every delta with a non-empty name +
            // args buffer — the agent loop only acts on the final emitted
            // ToolCall per index, so re-emitting is harmless.
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (finishReason != null || toolCalls?.isNotEmpty() == true) {
                flushToolCalls(onChunk)
            }
        }
        // Usage frame (sent just before [DONE] when stream_options.include_usage is on).
        element.jsonObject["usage"]?.let { usageEl ->
            val usage = parseUsage(usageEl.jsonObject)
            // We can't emit Done yet — the [DONE] marker is the canonical
            // signal that there are no more choices. Buffer this usage so we
            // can attach it to the final Done chunk.
            pendingUsage = usage
        }
    }

    /**
     * State for one in-flight tool call (index is the OpenAI `tool_calls[i].index`).
     * Buffers `function.arguments` until we have a parseable JSON object.
     */
    private data class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val argsBuffer: StringBuilder = StringBuilder(),
    )

    private val pendingToolCalls = mutableMapOf<Int, ToolCallAccumulator>()

    private fun flushToolCalls(onChunk: (LlmChunk) -> Unit) {
        if (pendingToolCalls.isEmpty()) return
        // Emit one ToolCall chunk per accumulated index. We keep the entry
        // in place (rather than removing) so subsequent deltas extending the
        // same entry overwrite with a newer snapshot — the agent loop only
        // dispatches once per (name, args) tuple.
        pendingToolCalls.toSortedMap().forEach { (_, entry) ->
            if (entry.name.isBlank()) return@forEach
            val argsJson: JsonObject = if (entry.argsBuffer.isNotBlank()) {
                try {
                    val parsed = json.parseToJsonElement(entry.argsBuffer.toString())
                    (parsed as? JsonObject) ?: buildJsonObject { }
                } catch (_: Exception) {
                    // Partial JSON — emit an empty object so the loop has
                    // something to forward; the model will retry once more
                    // deltas arrive (or the agent loop will retry).
                    buildJsonObject { }
                }
            } else {
                buildJsonObject { }
            }
            onChunk(LlmChunk.ToolCall(id = entry.id, name = entry.name, args = argsJson))
        }
    }

    private var pendingUsage: TokenUsage? = null

    private fun parseUsage(obj: JsonObject): TokenUsage? {
        val prompt = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull
        val completion = obj["completion_tokens"]?.jsonPrimitive?.intOrNull
        val total = obj["total_tokens"]?.jsonPrimitive?.intOrNull
        return if (prompt != null && completion != null && total != null) {
            TokenUsage(prompt, completion, total)
        } else null
    }

    private suspend fun Call.awaitResponseOrThrow(): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
        cont.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
                // OkHttp may already have raced the cancel; ignore.
            }
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(LlmException.Network("OpenAI request failed: ${e.message}", e))
            }
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    @Serializable
    private data class OpenAiMessage(
        val role: String,
        // Polymorphic: a plain text message serialises to a string;
        // a multimodal message serialises to a JSON array of typed parts.
        // kotlinx.serialization encodes `JsonElement` as its raw shape.
        val content: JsonElement,
    )

    /**
     * Encode a [ChatMessage] into the OpenAI wire shape. The result's
     * `content` is a JSON string for text-only messages and a JSON array
     * of `{"type":"text",...}` / `{"type":"image_url",...}` parts for
     * multimodal messages.
     *
     * Image parts are base64-encoded on the IO dispatcher via [encodeImage].
     * Any IO failure is converted into an empty parts list with a sibling
     * error marker rather than aborting the whole call — the model still
     * gets the user's text, just without the image.
     */
    private suspend fun encodeMessage(message: ChatMessage): OpenAiMessage = when (val c = message.content) {
        is MessageContent.Text -> OpenAiMessage(
            role = message.role,
            content = JsonPrimitive(c.text),
        )
        is MessageContent.Multimodal -> OpenAiMessage(
            role = message.role,
            content = encodeMultimodal(c),
        )
    }

    private suspend fun encodeMultimodal(content: MessageContent.Multimodal): JsonElement {
        val parts = buildJsonArray {
            for (part in content.parts) {
                when (part) {
                    is MessagePart.TextPart -> add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(part.text))
                        }
                    )
                    is MessagePart.ImagePart -> {
                        val dataUrl = encodeImagePart(part)
                        if (dataUrl != null) {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("image_url"))
                                    put(
                                        "image_url",
                                        buildJsonObject {
                                            put("url", JsonPrimitive(dataUrl))
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        return parts
    }

    /**
     * Resolve an [MessagePart.ImagePart] into a `data:image/jpeg;base64,...`
     * URL suitable for the OpenAI vision API. Returns `null` on any IO
     * failure so the caller can drop the part rather than break the whole
     * call.
     */
    private suspend fun encodeImagePart(part: MessagePart.ImagePart): String? {
        // Already-encoded payloads skip re-encoding.
        val existing = part.base64
        if (existing != null) return "data:image/jpeg;base64,$existing"

        return try {
            // Resolve the URI/Path to raw bytes. We support:
            //  - `content://...` URIs (camera output / MediaStore)
            //  - `file://...` URIs
            //  - bare filesystem paths
            // Anything else returns null (the model just won't see the image).
            val rawBytes: ByteArray? = coroutineScope {
                val deferred = async(Dispatchers.IO) {
                    openInputStreamForUriOrPath(part.uri).use { it?.readBytes() }
                }
                deferred.await()
            }
            if (rawBytes == null) null else {
                val b64 = Base64.encodeToString(rawBytes, Base64.NO_WRAP)
                "data:image/jpeg;base64,$b64"
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun openInputStreamForUriOrPath(uriOrPath: String): InputStream? {
        // content:// URI — go through ContentResolver.
        val ctx = context
        if (uriOrPath.startsWith("content://")) {
            return if (ctx != null) {
                ctx.contentResolver.openInputStream(Uri.parse(uriOrPath))
            } else {
                null
            }
        }
        // file:// URI or bare path — open directly.
        val raw = if (uriOrPath.startsWith("file://")) {
            Uri.parse(uriOrPath).path ?: return null
        } else {
            uriOrPath
        }
        val file = File(raw)
        if (!file.exists() || !file.isFile) return null
        return FileInputStream(file)
    }

    @Serializable
    private data class OpenAiStreamOptions(
        @SerialName("include_usage") val includeUsage: Boolean = true,
    )

    @Serializable
    private data class OpenAiRequest(
        val model: String,
        val messages: List<OpenAiMessage>,
        val temperature: Double,
        @SerialName("max_tokens") val maxTokens: Int? = null,
        val stream: Boolean,
        @SerialName("stream_options") val streamOptions: OpenAiStreamOptions? = null,
        val tools: List<JsonElement>? = null,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}

/**
 * Internal accessors so [com.sidekick.app.provider.LlmRouter] can rebuild
 * an [OpenAiProvider] with a [android.content.Context] without forcing
 * those fields to be public. Not part of the public API.
 */
internal val OpenAiProvider.apiBaseUrlForRouter: String
    get() = this.apiBaseUrlInternal
internal val OpenAiProvider.apiKeyForRouter: String
    get() = this.apiKeyInternal
internal val OpenAiProvider.modelNameForRouter: String
    get() = this.modelNameInternal
internal val OpenAiProvider.clientForRouter: OkHttpClient
    get() = this.clientInternal