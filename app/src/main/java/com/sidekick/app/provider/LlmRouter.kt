package com.sidekick.app.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Single entry point for the rest of the app. The UI layer holds onto an
 * instance of this and asks it to stream — it never needs to know whether
 * it's talking to Ollama or a cloud endpoint.
 *
 * The router caches one [LlmClient] per concrete [Provider] so re-issuing
 * requests with the same [Provider] reuses the underlying OkHttp connection
 * pool. This matters for on-device Ollama where connection setup is non-trivial
 * on Android.
 *
 * The default [scope] uses a [SupervisorJob] so a failure in one streaming
 * call doesn't tear down sibling calls.
 */
open class LlmRouter(
    private val ollamaFactory: (Provider.LocalOllama) -> LlmClient = { OllamaProvider(it.baseUrl, it.modelName) },
    private val openAiFactory: (Provider.CloudOpenAI) -> LlmClient = {
        OpenAiProvider(
            apiBaseUrlInternal = it.apiBaseUrl,
            apiKeyInternal = it.apiKey,
            modelNameInternal = it.modelName,
        )
    },
    private val localOnDeviceFactory: (Provider.LocalOnDevice) -> LlmClient = {
        LocalOnDeviceProvider(modelPath = it.modelPath, backend = it.backend)
    },
) {

    private val cache = mutableMapOf<Provider, LlmClient>()

    /** Eagerly evict a provider's cached client — used when settings change. */
    open fun invalidate(provider: Provider) {
        // Capture the removed entry so we can close native resources
        // owned by LocalOnDeviceProvider (the on-device Engine holds a
        // mmap of the model file — leaking across provider switches would
        // leak memory until the process dies).
        val evicted = cache.remove(provider)
        (evicted as? LocalOnDeviceProvider)?.close()
    }

    /**
     * Materialise the [LlmClient] for [provider]. M4 added an optional
     * [android.content.Context] so the OpenAI provider can resolve
     * `content://` URIs when serialising multimodal image parts.
     */
    open fun clientFor(provider: Provider, context: android.content.Context? = null): LlmClient {
        val cached = cache[provider]
        if (cached != null) return cached
        val created = when (provider) {
            is Provider.LocalOllama -> ollamaFactory(provider)
            is Provider.CloudOpenAI -> openAiFactory(provider).withContext(context)
            is Provider.LocalOnDevice -> localOnDeviceFactory(provider)
        }
        cache[provider] = created
        return created
    }

    /**
     * Re-create an existing [LlmClient] with the given context. Default
     * implementations are no-ops; [OpenAiProvider] rebuilds itself with
     * the context so its multimodal encoder can resolve `content://` URIs.
     */
    private fun LlmClient.withContext(context: android.content.Context?): LlmClient = when (this) {
        is OpenAiProvider -> OpenAiProvider(
            apiBaseUrlInternal = this@withContext.apiBaseUrlForRouter,
            apiKeyInternal = this@withContext.apiKeyForRouter,
            modelNameInternal = this@withContext.modelNameForRouter,
            clientInternal = this@withContext.clientForRouter,
            context = context,
        )
        else -> this
    }

    /**
     * Stream from the given [provider]. The returned [Job] is the provider's
     * own coroutine handle — cancelling it cancels the in-flight HTTP call.
     */
    open suspend fun stream(
        provider: Provider,
        request: LlmRequest,
        onChunk: (LlmChunk) -> Unit,
    ): Job = clientFor(provider).stream(request, onChunk)
}