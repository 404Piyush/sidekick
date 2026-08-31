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
        OpenAiProvider(it.apiBaseUrl, it.apiKey, it.modelName)
    },
) {

    private val cache = mutableMapOf<Provider, LlmClient>()

    /** Eagerly evict a provider's cached client — used when settings change. */
    open fun invalidate(provider: Provider) {
        cache.remove(provider)
    }

    open fun clientFor(provider: Provider): LlmClient = when (provider) {
        is Provider.LocalOllama -> cache.getOrPut(provider) { ollamaFactory(provider) }
        is Provider.CloudOpenAI -> cache.getOrPut(provider) { openAiFactory(provider) }
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