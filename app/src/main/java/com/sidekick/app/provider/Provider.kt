package com.sidekick.app.provider

/**
 * A backend that can fulfil chat-completion requests. M1 ships two variants:
 *  - [LocalOllama] — on-device / on-host Ollama speaking NDJSON at /api/chat.
 *  - [CloudOpenAI] — any OpenAI-compatible HTTPS endpoint speaking SSE at /chat/completions.
 *
 * The router inspects which variant it was given and delegates to the matching
 * implementation. New backends (Anthropic, llama.cpp, …) plug in by adding a
 * third sealed-class variant and a corresponding branch in [LlmRouter].
 */
sealed class Provider {

    /**
     * Ollama running locally (Android emulator's `10.0.2.2` reaches the host
     * machine; on a real device the user enters their LAN IP via the Settings
     * sheet wired in M1).
     */
    data class LocalOllama(
        val baseUrl: String = "http://10.0.2.2:11434",
        val modelName: String = "qwen2.5-coder:7b",
    ) : Provider()

    /**
     * Any OpenAI-compatible cloud endpoint (api.openai.com, Together, Groq,
     * OpenRouter, self-hosted vLLM with the OpenAI adapter, etc.).
     * The API key is sent as a Bearer token on every request.
     */
    data class CloudOpenAI(
        val apiBaseUrl: String = "https://api.openai.com/v1",
        val apiKey: String,
        val modelName: String = "gpt-4o-mini",
    ) : Provider()
}