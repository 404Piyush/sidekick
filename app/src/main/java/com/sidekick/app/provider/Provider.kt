package com.sidekick.app.provider

/**
 * A backend that can fulfil chat-completion requests.
 *  - [LocalOllama]   — on-device / on-host Ollama speaking NDJSON at /api/chat.
 *  - [CloudOpenAI]   — any OpenAI-compatible HTTPS endpoint speaking SSE at /chat/completions.
 *  - [LocalOnDevice] — on-device Google AI Edge LiteRT-LM inference (M7+).
 *
 * The router inspects which variant it was given and delegates to the matching
 * implementation. New backends (Anthropic, llama.cpp, …) plug in by adding a
 * fourth sealed-class variant and a corresponding branch in [LlmRouter].
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

    /**
     * On-device inference via Google AI Edge LiteRT-LM (M7+).
     *
     * @property modelPath Absolute filesystem path to a `.litertlm` model file
     *                       (downloaded from HuggingFace's `litert-community`
     *                       org, e.g. Gemma3-1B-IT). The LiteRT-LM runtime
     *                       mmap's this file directly — no extraction step.
     * @property backend   Which accelerator to dispatch the model on. NPU is
     *                     preferred on iQOO Z10 / Dimensity 9500; GPU works
     *                     on most modern Adreno / Mali parts; CPU is the
     *                     universal fallback. Note: LiteRT-LM exposes
     *                     [Backend] as factory functions
     *                     (`Backend.CPU()`, `Backend.GPU()`, `Backend.NPU(dir)`)
     *                     rather than enum entries — the [Backend] wrapper
     *                     here gives us a serialisable discriminator that
     *                     Room can persist.
     */
    data class LocalOnDevice(
        val modelPath: String,
        val backend: Backend = Backend.NPU,
    ) : Provider()
}

/**
 * Accelerator choice for [Provider.LocalOnDevice]. LiteRT-LM's Kotlin API
 * uses factory functions (`Backend.CPU()`, `Backend.GPU()`, `Backend.NPU(dir)`)
 * rather than a real enum, which is awkward to serialise through Room — this
 * wrapper picks the variant, and the provider turns it into the matching
 * factory call at construction time.
 *
 * Default is [NPU] per the iQOO Hackathon brief: the Dimensity 9500 SoC has a
 * dedicated NPU and is the best target for on-device inference. Users with a
 * weaker device can switch to [GPU] or [CPU] in Settings (M9 will wire that
 * UI; M7 only stores it).
 */
enum class Backend {
    NPU,
    GPU,
    CPU,
}