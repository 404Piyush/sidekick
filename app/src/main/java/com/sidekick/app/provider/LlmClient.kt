package com.sidekick.app.provider

import kotlinx.coroutines.Job

/**
 * Stream a chat-completion response, calling [onChunk] for every [LlmChunk]
 * the provider emits.
 *
 * Returns a [Job] so the caller can cancel an in-flight generation when the
 * user navigates away or sends a new message. Cancelling the job must
 * (a) stop calling [onChunk], and (b) close the underlying HTTP response
 * body so OkHttp doesn't leak the connection back to the pool.
 *
 * Exceptions are delivered through [onChunk] as a final best-effort signal in
 * future milestones; in M1 they surface as Kotlin exceptions from the suspend
 * call and the caller wraps the launch in try/catch.
 */
interface LlmClient {
    suspend fun stream(request: LlmRequest, onChunk: (LlmChunk) -> Unit): Job
}