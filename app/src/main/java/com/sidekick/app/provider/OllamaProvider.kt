package com.sidekick.app.provider

import android.util.Log
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
 * Ollama HTTP provider.
 *
 * Wire format (NDJSON, one JSON object per line):
 *   {"model":"…","message":{"role":"assistant","content":"Hel"},"done":false}
 *   {"model":"…","message":{"role":"assistant","content":"lo!"},"done":false}
 *   {"model":"…","done":true,"done_reason":"stop","prompt_eval_count":42,"eval_count":7}
 *
 * Each non-terminal line carries an incremental [message.content] delta. The
 * terminal line has `done:true` and may carry token accounting fields.
 *
 * Cancellation: if the returned [Job] is cancelled, OkHttp's [Call] is
 * cancelled through [suspendCancellableCoroutine], which releases the
 * connection back to OkHttp's pool rather than dropping it.
 */
class OllamaProvider(
    private val baseUrl: String,
    private val modelName: String,
    private val client: OkHttpClient = defaultClient(),
) : LlmClient {

    override suspend fun stream(request: LlmRequest, onChunk: (LlmChunk) -> Unit): Job =
        coroutineScope {
            // The returned Job IS the async's Job — when the caller cancels
            // it, the inner suspendCancellableCoroutine cancels the OkHttp
            // Call, and the response body gets released back to the pool.
            async(Dispatchers.IO) { execute(request, onChunk) }
        }

    private suspend fun execute(request: LlmRequest, onChunk: (LlmChunk) -> Unit) {
        val httpRequest = buildRequest(request)
        val response = client.newCall(httpRequest).awaitResponseOrThrow()
        response.use { handleResponse(it, onChunk) }
    }

    private fun buildRequest(request: LlmRequest): Request {
        // Ollama doesn't speak the OpenAI multimodal wire format. M4 keeps
        // the simple text-only payload and warns if a multimodal message
        // sneaks through — image parts get dropped, the model's text part
        // gets sent as a plain string.
        request.messages.forEachIndexed { i, msg ->
            if (msg.content is MessageContent.Multimodal) {
                Log.w(
                    "OllamaProvider",
                    "message #$i has multimodal content; image parts will be dropped " +
                        "(Ollama multimodal support is out of scope for M4)",
                )
            }
        }
        val body = OllamaRequest(
            model = modelName,
            messages = request.messages.map { OllamaMessage(it.role, it.content.asPlainText()) },
            stream = true,
            options = OllamaOptions(
                temperature = request.temperature,
                numPredict = request.maxTokens,
            ),
        )
        val json = json.encodeToString(OllamaRequest.serializer(), body)
        val url = baseUrl.trimEnd('/') + "/api/chat"
        return Request.Builder()
            .url(url)
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private suspend fun handleResponse(response: Response, onChunk: (LlmChunk) -> Unit) {
        if (!response.isSuccessful) {
            val snippet = response.body?.string()?.take(200).orEmpty()
            throw LlmException.HttpStatus(
                code = response.code,
                message = "Ollama returned HTTP ${response.code}: $snippet",
            )
        }
        val source = response.body?.source()
            ?: throw LlmException.Decode("Ollama response had empty body")

        source.use { s ->
            while (!s.exhausted()) {
                val line = s.readUtf8Line() ?: break
                if (line.isBlank()) continue
                parseLine(line, onChunk)
            }
        }
    }

    private fun parseLine(line: String, onChunk: (LlmChunk) -> Unit) {
        val element: JsonElement = try {
            json.parseToJsonElement(line)
        } catch (e: Exception) {
            throw LlmException.Decode("Could not parse NDJSON line: ${line.take(80)}", e)
        }
        val obj = element.jsonObject

        // Provider error envelope: {"error": "..."}
        obj["error"]?.let { err ->
            val msg = err.jsonPrimitive.contentOrNull ?: err.toString()
            throw LlmException.ProviderSpecific("Ollama error: $msg")
        }

        val done = obj["done"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (done) {
            val usage = parseUsage(obj)
            onChunk(LlmChunk.Done(usage))
            return
        }

        val messageContent = obj["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        if (messageContent != null && messageContent.isNotEmpty()) {
            onChunk(LlmChunk.Text(messageContent))
        }
    }

    private fun parseUsage(obj: kotlinx.serialization.json.JsonObject): TokenUsage? {
        val prompt = obj["prompt_eval_count"]?.jsonPrimitive?.longOrNull
        val completion = obj["eval_count"]?.jsonPrimitive?.longOrNull
        return if (prompt != null && completion != null) {
            TokenUsage(
                promptTokens = prompt.toInt(),
                completionTokens = completion.toInt(),
                totalTokens = (prompt + completion).toInt(),
            )
        } else null
    }

    /**
     * Bridge OkHttp's callback API to coroutines. Honours cancellation so
     * OkHttp can recycle the connection.
     */
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
                cont.resumeWithException(LlmException.Network("Ollama request failed: ${e.message}", e))
            }
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    @Serializable
    private data class OllamaMessage(val role: String, val content: String)

    @Serializable
    private data class OllamaOptions(
        val temperature: Double,
        @SerialName("num_predict") val numPredict: Int?,
    )

    @Serializable
    private data class OllamaRequest(
        val model: String,
        val messages: List<OllamaMessage>,
        val stream: Boolean,
        val options: OllamaOptions,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Long timeouts because local LLMs are slow on the first token.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}