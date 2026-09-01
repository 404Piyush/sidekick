package com.sidekick.app.provider

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.io.IOException

/**
 * Downloads the on-device `.litertlm` model file from HuggingFace and
 * stores it in the app's `filesDir/models/` directory.
 *
 * The model is the AI's weights — the "brain" that [LocalOnDeviceProvider]
 * mmap's and runs through the LiteRT-LM engine. Without this file the
 * on-device path has nothing to run, so this manager is the gate between
 * "app installed" and "offline inference works".
 *
 * Why Qwen3-0.6B (328 MB, `dynamic_wi4b32_afp32`):
 *  - Ungated on HuggingFace (Apache 2.0 — no licence-accept wall), so the
 *    in-app download works for anyone without a HF account.
 *  - Small enough that a first-run download on WiFi takes well under a
 *    minute; large enough to be a genuinely useful coding assistant.
 *  - `wi4b32_afp32` = int4 weights + float activations, the scheme
 *    LiteRT-LM benchmarks as the best size/speed trade-off on mobile NPUs.
 *
 * The download is resumable-in-spirit: we stream to a `.part` file and
 * atomically rename on success, so a crashed download never leaves a
 * half-written file that looks complete.
 *
 * @param context Used to resolve the app-private `filesDir` — model files
 *                must live inside it (never external storage) so the
 *                LiteRT-LM engine can mmap them.
 * @param client  OkHttp client. Injected for testability; defaults to a
 *                fresh client with a generous read timeout (the download
 *                can stall for seconds on slow links).
 */
class OnDeviceModelManager(
    context: Context,
    private val client: OkHttpClient = defaultClient(),
) {
    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    /** Where the downloaded model will live. */
    val modelFile: File = File(modelsDir, MODEL_FILENAME)

    /** Absolute path the [Provider.LocalOnDevice] constructor expects. */
    val modelPath: String
        get() = modelFile.absolutePath

    /** Whether the model is already present and complete. */
    fun isDownloaded(): Boolean = modelFile.exists() && modelFile.length() > MIN_COMPLETE_BYTES

    /**
     * Stream the model download, emitting progress states.
     *
     * Emits:
     *  - [OnDeviceDownload.Started]
     *  - [OnDeviceDownload.Progress] (percent 0..100) as bytes land
     *  - [OnDeviceDownload.Complete] on success
     *
     * Throws [IOException] (or the underlying cause) on failure. The
     * `.part` temp file is deleted on error so a retry starts clean.
     */
    fun download(): Flow<OnDeviceDownload> = flow {
        if (isDownloaded()) {
            emit(OnDeviceDownload.Complete(modelPath))
            return@flow
        }

        emit(OnDeviceDownload.Started(totalBytes = MODEL_SIZE_BYTES))

        val partFile = File(modelsDir, "$MODEL_FILENAME.part")
        val request = Request.Builder()
            .url(MODEL_URL)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("download failed: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("empty response body")
                val total = body.contentLength().takeIf { it > 0 } ?: MODEL_SIZE_BYTES
                var written = 0L

                partFile.sink().buffer().use { out ->
                    body.source().use { source ->
                        val buffer = okio.Buffer()
                        var lastEmitPct = -1
                        while (true) {
                            val read = source.read(buffer, CHUNK_BYTES.toLong())
                            if (read == -1L) break
                            out.write(buffer, read)
                            written += read
                            val pct = if (total > 0) {
                                ((written * 100) / total).toInt().coerceIn(0, 99)
                            } else {
                                -1
                            }
                            // Throttle: only emit when the whole-percent
                            // changes, so the UI isn't spammed per chunk.
                            if (pct != lastEmitPct) {
                                lastEmitPct = pct
                                emit(OnDeviceDownload.Progress(percent = pct, bytes = written, totalBytes = total))
                            }
                        }
                    }
                }

                // Atomic-ish commit: rename the complete temp file into
                // place. If the file was somehow already there, replace it.
                if (modelFile.exists()) modelFile.delete()
                if (!partFile.renameTo(modelFile)) {
                    throw IOException("failed to finalise model file")
                }
                emit(OnDeviceDownload.Complete(modelPath))
            }
        } catch (e: Exception) {
            partFile.delete()
            throw e
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Public, ungated HuggingFace URL for the on-device model. */
        const val MODEL_URL: String =
            "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/" +
                "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"

        /** On-disk name for the downloaded model. */
        const val MODEL_FILENAME: String = "qwen3-0.6b.litertlm"

        /** Known size in bytes (matches HuggingFace `content-length`). */
        const val MODEL_SIZE_BYTES: Long = 344_437_808L

        /** A downloaded file smaller than this is treated as incomplete. */
        const val MIN_COMPLETE_BYTES: Long = 300_000_000L

        /** Read granularity for the streaming copy loop. */
        const val CHUNK_BYTES: Int = 256 * 1024

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}

/**
 * A single state update from [OnDeviceModelManager.download].
 * Sealed so the UI can `when` over it exhaustively.
 */
sealed class OnDeviceDownload {
    /** Download is starting; [totalBytes] is the expected size. */
    data class Started(val totalBytes: Long) : OnDeviceDownload()

    /** Progress so far. [percent] is -1 when the total is unknown. */
    data class Progress(
        val percent: Int,
        val bytes: Long,
        val totalBytes: Long,
    ) : OnDeviceDownload()

    /** Download finished; [modelPath] is ready to use. */
    data class Complete(val modelPath: String) : OnDeviceDownload()
}
