package com.sidekick.app.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device Ollama model manager.
 *
 * Wraps three Ollama HTTP endpoints that the rest of the app shouldn't have
 * to know about:
 *  - [listLocal]    -> `GET  {baseUrl}/api/tags` returns the model names
 *    already pulled to disk.
 *  - [listCurated]  -> in-memory list of recommended models the picker
 *    offers as quick-tap chips.
 *  - [pull]         -> `POST {baseUrl}/api/pull` (NDJSON stream) drives a
 *    model download, emitting one [LlmChunk.PullProgress] per line.
 *
 * Network errors propagate as [LlmException]; HTTP non-200 responses
 * surface as [LlmException.HttpStatus]; NDJSON parse failures as
 * [LlmException.Decode]. The [client] is injectable so tests can drive
 * the manager against a [okhttp3.mockwebserver.MockWebServer] without
 * touching the real Ollama process.
 */
class OllamaModelManager(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) {

    /**
     * GET `/api/tags`, returning the `name` field of every model already
     * present on the server. Duplicates are removed while preserving the
     * server's natural ordering (Ollama sorts by name).
     */
    suspend fun listLocal(): List<String> = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/tags")
            .get()
            .build()

        val call = client.newCall(request)
        cont.invokeOnCancellation {
            try {
                call.cancel()
            } catch (_: Throwable) {
                // OkHttp may have already raced the cancel; ignore.
            }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(
                    LlmException.Network("Could not list local models: ${e.message}", e),
                )
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        val snippet = r.body?.string()?.take(200).orEmpty()
                        cont.resumeWithException(
                            LlmException.HttpStatus(
                                code = r.code,
                                message = "Ollama /api/tags returned HTTP ${r.code}: $snippet",
                            ),
                        )
                        return
                    }
                    val text = r.body?.string().orEmpty()
                    val names = try {
                        parseTagsBody(text)
                    } catch (e: Exception) {
                        cont.resumeWithException(
                            LlmException.Decode("Could not parse /api/tags response", e),
                        )
                        return
                    }
                    cont.resume(names)
                }
            }
        })
    }

    /**
     * Curated recommendation list shown as chips in the pull dialog. The
     * names map to Ollama library entries that work on a modern phone;
     * `pull()` accepts any Ollama library name though, including ones
     * not on this list.
     */
    fun listCurated(): List<String> = curated

    /**
     * POST `/api/pull` with `{ "name": <modelId>, "stream": true }`.
     *
     * Ollama emits one JSON object per line:
     *   {"status":"pulling manifest","digest":"sha256:..."}
     *   {"status":"pulling <digest>","digest":"sha256:...","total":12345,"completed":6789}
     *   ...
     *   {"status":"success"}
     *
     * Each line is mapped to a [LlmChunk.PullProgress] and emitted through
     * the returned [Flow]. `total`/`completed`-bearing lines get a real
     * 0..100 percentage; status-only lines use `-1` so the UI knows the
     * pull is alive without claiming a percentage. The terminal
     * `{"status":"success"}` line completes the flow.
     */
    fun pull(modelId: String): Flow<LlmChunk.PullProgress> = callbackFlow {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/pull")
            .post(
                json.encodeToString(PullRequest.serializer(), PullRequest(name = modelId, stream = true))
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()

        val call = client.newCall(request)
        // Close the OkHttp call when the consumer cancels the flow so the
        // socket is released back to the pool. callbackFlow's awaitClose
        // is the only place this is guaranteed to run.
        awaitClose {
            try {
                call.cancel()
            } catch (_: Throwable) {
                // Ignore: the call may already have completed or been cancelled.
            }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(
                    LlmException.Network("Could not pull $modelId: ${e.message}", e),
                )
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        val snippet = r.body?.string()?.take(200).orEmpty()
                        close(
                            LlmException.HttpStatus(
                                code = r.code,
                                message = "Ollama /api/pull returned HTTP ${r.code}: $snippet",
                            ),
                        )
                        return
                    }
                    val source = r.body?.source()
                    if (source == null) {
                        close(LlmException.Decode("Ollama /api/pull returned empty body"))
                        return
                    }
                    try {
                        streamLines(source) { progress -> trySend(progress) }
                        close()
                    } catch (e: Exception) {
                        close(e.toLlmException())
                    }
                }
            }
        })
    }.flowOn(Dispatchers.IO)

    private fun streamLines(source: BufferedSource, emit: (LlmChunk.PullProgress) -> Unit) {
        source.use { s ->
            while (!s.exhausted()) {
                val line = s.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val element: JsonElement = try {
                    json.parseToJsonElement(line)
                } catch (e: Exception) {
                    throw LlmException.Decode(
                        "Could not parse /api/pull NDJSON line: ${line.take(80)}",
                        e,
                    )
                }
                val obj = element.jsonObject
                val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: continue

                // Provider error envelope: {"error": "..."}
                obj["error"]?.let { err ->
                    val msg = err.jsonPrimitive.contentOrNull ?: err.toString()
                    throw LlmException.ProviderSpecific("Ollama pull error: $msg")
                }

                val digest = obj["digest"]?.jsonPrimitive?.contentOrNull
                val total = obj["total"]?.jsonPrimitive?.longOrNull
                val completed = obj["completed"]?.jsonPrimitive?.longOrNull
                val percent = if (total != null && completed != null && total > 0) {
                    ((completed * 100L) / total).toInt().coerceIn(0, 100)
                } else {
                    -1
                }
                emit(LlmChunk.PullProgress(percent = percent, status = status, digest = digest))
            }
        }
    }

    private fun parseTagsBody(text: String): List<String> {
        val root = json.parseToJsonElement(text).jsonObject
        val models = root["models"]?.jsonArray ?: return emptyList()
        val seen = LinkedHashSet<String>(models.size)
        for (entry in models) {
            val name = entry.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            if (name != null) seen.add(name)
        }
        return seen.toList()
    }

    @Serializable
    private data class PullRequest(
        val name: String,
        val stream: Boolean,
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val curated = listOf(
            "qwen2.5-coder:7b",
            "qwen2.5-coder:3b",
            "llama3.1:8b",
            "phi4:14b",
            "mistral:7b",
            "gemma2:9b",
            "deepseek-coder-v2:16b",
            "codestral:22b",
        )
    }
}

private fun Throwable.toLlmException(): LlmException = when (this) {
    is LlmException -> this
    else -> LlmException.Network(message ?: this::class.simpleName.orEmpty(), this)
}
