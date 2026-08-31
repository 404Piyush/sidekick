package com.sidekick.app.provider

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    private val apiBaseUrl: String,
    private val apiKey: String,
    private val modelName: String,
    private val client: OkHttpClient = defaultClient(),
) : LlmClient {

    override suspend fun stream(request: LlmRequest, onChunk: (LlmChunk) -> Unit): Job =
        coroutineScope {
            // Same propagation rule as OllamaProvider: the returned Job is
            // the async's Job — cancelling it cancels the OkHttp Call.
            async(Dispatchers.IO) { execute(request, onChunk) }
        }

    private suspend fun execute(request: LlmRequest, onChunk: (LlmChunk) -> Unit) {
        val httpRequest = buildRequest(request)
        val response = client.newCall(httpRequest).awaitResponseOrThrow()
        response.use { handleResponse(it, onChunk) }
    }

    private fun buildRequest(request: LlmRequest): Request {
        val body = OpenAiRequest(
            model = modelName,
            messages = request.messages.map { OpenAiMessage(it.role, it.content) },
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            stream = true,
            // Ask OpenAI to include usage in the streaming response.
            // Older / third-party servers that don't understand this field
            // ignore unknown keys because of `ignoreUnknownKeys = true`.
            streamOptions = OpenAiStreamOptions(includeUsage = true),
        )
        val json = json.encodeToString(OpenAiRequest.serializer(), body)
        val url = apiBaseUrl.trimEnd('/') + "/chat/completions"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
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
    private data class OpenAiMessage(val role: String, val content: String)

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