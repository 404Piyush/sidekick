package com.sidekick.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sidekick.app.data.ConversationEntity
import com.sidekick.app.data.ProviderConfigEntity
import com.sidekick.app.data.Seed
import com.sidekick.app.data.TurnEntity
import com.sidekick.app.data.dao.ConversationDao
import com.sidekick.app.data.dao.ProviderConfigDao
import com.sidekick.app.data.dao.TeammateDao
import com.sidekick.app.data.dao.TurnDao
import com.sidekick.app.data.provideDatabase
import com.sidekick.app.provider.ChatMessage
import com.sidekick.app.provider.LlmChunk
import com.sidekick.app.provider.LlmException
import com.sidekick.app.provider.LlmRequest
import com.sidekick.app.provider.LlmRouter
import com.sidekick.app.provider.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel for [ConversationScreen]. Wraps the provider streaming pipeline
 * and the Room DAOs in a [StateFlow]-backed [ConversationUiState].
 *
 * Construction is manual (no Hilt in M2). M3 will replace this with a Hilt
 * factory that injects the same DAOs. Tests construct the ViewModel directly
 * with stub DAOs and a fake [LlmRouter].
 *
 * Lifecycle: the ViewModel is owned by [ConversationScreen] via
 * `viewModel(factory = ...)`. [start] runs once per conversation (from a
 * [androidx.compose.runtime.LaunchedEffect]) and loads the teammate, the
 * active provider config, and the persisted turns.
 */
class ConversationViewModel(
    private val conversationDao: ConversationDao,
    private val turnDao: TurnDao,
    private val teammateDao: TeammateDao,
    private val providerConfigDao: ProviderConfigDao,
    private val router: LlmRouter,
    private val teammateSlug: String,
    private val title: String,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    /**
     * Currently running streaming Job, if any. Held outside the StateFlow
     * because the Job itself is the cancellation handle — putting it into
     * the UI state would be redundant.
     */
    @Volatile
    private var streamJob: Job? = null

    private var conversationId: Long? = null

    /**
     * Open (or create) the conversation row, load the teammate and provider
     * config, run [Seed.seedIfEmpty] lazily on first call, and subscribe to
     * the persisted turn stream. Idempotent — safe to call once from the
     * screen's `LaunchedEffect`.
     *
     * @param context Optional Android context. When provided the seeder
     *                reads system prompts from `assets/system-prompts/`. Tests
     *                pass `null` and pre-seed the database directly.
     */
    fun start(context: Context? = null) {
        if (_state.value.conversationId != null) return

        ioScope.launch {
            if (context != null) {
                Seed.seedIfEmpty(dao = teammateDao, context = context)
            }

            val teammate = teammateDao.getById(teammateSlug)
                ?: error("Teammate $teammateSlug not seeded")
            val activeConfig = providerConfigDao.getActive()

            val now = clock()
            val cid = conversationDao.insert(
                ConversationEntity(
                    teammate = teammateSlug,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            conversationId = cid

            _state.value = _state.value.copy(
                conversationId = cid,
                teammate = teammate,
                activeProvider = activeConfig,
            )

            // Observe turns for this conversation.
            turnDao.getByConversation(cid)
                .onEach { rows -> _state.value = _state.value.copy(messages = rows) }
                .launchIn(ioScope)
        }
    }

    /**
     * Append [text] as a user turn, then stream the assistant reply.
     *
     * Flow:
     *  1. Insert a `user` turn. The transcript Flow re-emits.
     *  2. Insert a placeholder `assistant` turn (empty content).
     *  3. Call [LlmRouter.stream] and pipe chunks through a [Channel] so the
     *     provider callback stays non-suspend (it can't call Room APIs).
     *  4. Drain the channel on the main coroutine, persisting each chunk
     *     into the placeholder turn.
     *  5. On error, persist an error message and surface [LlmException].
     */
    fun sendMessage(text: String) {
        val cid = conversationId ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        ioScope.launch {
            // 1. Persist user turn.
            val position = turnDao.countByConversation(cid)
            val now = clock()
            turnDao.insert(
                TurnEntity(
                    conversationId = cid,
                    role = "user",
                    content = trimmed,
                    position = position,
                    createdAt = now,
                ),
            )
            conversationDao.touch(cid, now)
            _state.value = _state.value.copy(error = null)

            // 2. Insert placeholder assistant turn.
            val assistantPosition = position + 1
            val assistantId = turnDao.insert(
                TurnEntity(
                    conversationId = cid,
                    role = "assistant",
                    content = "",
                    position = assistantPosition,
                    createdAt = clock(),
                ),
            )
            _state.value = _state.value.copy(isStreaming = true, partialResponse = "")

            // 3. Resolve provider + system prompt + history.
            val activeConfig = providerConfigDao.getActive()
            val teammate = _state.value.teammate
                ?: teammateDao.getById(teammateSlug)
                ?: error("Teammate $teammateSlug disappeared")
            val history = turnDao.getByConversation(cid).first()
            val messages = buildList {
                add(ChatMessage("system", teammate.systemPrompt))
                history.filter { it.role in setOf("user", "assistant") }.forEach {
                    add(ChatMessage(it.role, it.content))
                }
            }
            val provider: Provider = activeConfig?.toProvider()
                ?: run {
                    finishWithError(
                        cid = cid,
                        assistantId = assistantId,
                        position = assistantPosition,
                        err = LlmException.Network("No active provider configured"),
                    )
                    return@launch
                }
            val request = LlmRequest(messages = messages)

            // 4. Stream via a channel — keeps the provider callback non-suspend.
            val chunkChannel = Channel<StreamEvent>(capacity = Channel.UNLIMITED)
            val producerJob = ioScope.launch {
                try {
                    router.stream(provider, request) { chunk ->
                        chunkChannel.trySend(StreamEvent.Chunk(chunk))
                    }
                } catch (t: Throwable) {
                    chunkChannel.trySend(StreamEvent.Failure(t))
                } finally {
                    chunkChannel.close()
                }
            }
            streamJob = producerJob

            // Hold a local copy of the assistant turn so we can update it
            // without re-querying Room on every chunk (the Flow collector
            // would race with our writes).
            var assistantTurn = TurnEntity(
                id = assistantId,
                conversationId = cid,
                role = "assistant",
                content = "",
                position = assistantPosition,
                createdAt = clock(),
            )

            val collected = StringBuilder()
            var failure: Throwable? = null
            for (event in chunkChannel) {
                when (event) {
                    is StreamEvent.Chunk -> when (val chunk = event.chunk) {
                        is LlmChunk.Text -> {
                            collected.append(chunk.delta)
                            val snapshot = collected.toString()
                            val updated = assistantTurn.copy(content = snapshot)
                            turnDao.update(updated)
                            assistantTurn = updated
                            _state.value = _state.value.copy(partialResponse = snapshot)
                        }
                        is LlmChunk.Done -> {
                            // Final token accounting ignored for now (M3 will surface it).
                        }
                    }
                    is StreamEvent.Failure -> {
                        failure = event.throwable
                    }
                }
            }
            producerJob.join()
            streamJob = null

            if (failure != null) {
                val llm = failure.toLlmException()
                finishWithError(cid, assistantId, assistantPosition, llm)
                return@launch
            }

            conversationDao.touch(cid, clock())
            _state.value = _state.value.copy(isStreaming = false, partialResponse = "")
        }
    }

    private suspend fun finishWithError(
        cid: Long,
        assistantId: Long,
        position: Int,
        err: LlmException,
    ) {
        val current = turnDao.getByConversation(cid).first()
        val existing = current.firstOrNull { it.id == assistantId }
        val errorText = "[error] " + (err.message ?: err::class.simpleName.orEmpty())
        if (existing != null) {
            turnDao.update(existing.copy(content = errorText))
        } else {
            // Fallback — should never happen because sendMessage inserts it first.
            turnDao.insert(
                TurnEntity(
                    id = assistantId,
                    conversationId = cid,
                    role = "assistant",
                    content = errorText,
                    position = position,
                    createdAt = clock(),
                ),
            )
        }
        _state.value = _state.value.copy(isStreaming = false, partialResponse = "", error = err)
    }

    /**
     * Update the active provider row and evict the router's cached client
     * so the next [sendMessage] picks up the new endpoint / key.
     */
    fun setProvider(config: ProviderConfigEntity) {
        ioScope.launch {
            providerConfigDao.setActive(config.id)
            val provider = config.toProvider()
            if (provider != null) router.invalidate(provider)
            _state.value = _state.value.copy(activeProvider = config)
        }
    }

    /** Cancel the in-flight stream, if any. No-op when idle. */
    fun cancel() {
        streamJob?.cancel()
        streamJob = null
        _state.value = _state.value.copy(isStreaming = false, partialResponse = "")
    }

    override fun onCleared() {
        super.onCleared()
        streamJob?.cancel()
        ioScope.coroutineContext[Job]?.cancel()
    }

    companion object {
        /**
         * Manual factory for production. Loads the database via
         * [provideDatabase]. Seeding happens lazily on the first [start] call.
         */
        fun factory(context: Context, teammateSlug: String, title: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val db = provideDatabase(context.applicationContext)
                    return ConversationViewModel(
                        conversationDao = db.conversationDao(),
                        turnDao = db.turnDao(),
                        teammateDao = db.teammateDao(),
                        providerConfigDao = db.providerConfigDao(),
                        router = LlmRouter(),
                        teammateSlug = teammateSlug,
                        title = title,
                    ) as T
                }
            }
    }
}

/**
 * Internal channel event used to ferry either an [LlmChunk] from the
 * provider or a caught [Throwable] across the suspend boundary.
 */
private sealed class StreamEvent {
    data class Chunk(val chunk: LlmChunk) : StreamEvent()
    data class Failure(val throwable: Throwable) : StreamEvent()
}

private fun Throwable.toLlmException(): LlmException = when (this) {
    is LlmException -> this
    else -> LlmException.Network(message ?: this::class.simpleName.orEmpty(), this)
}

/**
 * Map a [ProviderConfigEntity] back to the sealed [Provider] used by
 * [LlmRouter]. Returns `null` for unknown `providerKind` values — callers
 * must surface that to the user rather than crash.
 */
fun ProviderConfigEntity.toProvider(): Provider? = when (providerKind) {
    "local_ollama" -> Provider.LocalOllama(baseUrl = baseUrl, modelName = modelName)
    "cloud_openai" -> Provider.CloudOpenAI(
        apiBaseUrl = baseUrl,
        apiKey = apiKey ?: "",
        modelName = modelName,
    )
    else -> null
}
