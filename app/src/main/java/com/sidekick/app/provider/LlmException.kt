package com.sidekick.app.provider

/**
 * Failure modes a provider can surface to the UI layer. The router does not
 * try to translate these into a friendlier shape — the caller decides how to
 * render them (snackbar, retry button, etc.).
 */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** TCP / DNS / TLS / socket-reset. Caller may retry transparently. */
    class Network(message: String, cause: Throwable? = null) : LlmException(message, cause)

    /** Provider responded with an HTTP status we didn't expect. [code] is the raw value. */
    class HttpStatus(val code: Int, message: String) : LlmException(message)

    /** Body was empty or wasn't JSON we could parse. Almost always a bug, not a retry candidate. */
    class Decode(message: String, cause: Throwable? = null) : LlmException(message, cause)

    /** Provider returned a structured error (e.g. Ollama's `{"error": "..."}` or OpenAI's `error` object). */
    class ProviderSpecific(message: String) : LlmException(message)
}