package com.sidekick.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sidekick.app.agent.AgentEvent
import com.sidekick.app.agent.AgentLoop
import com.sidekick.app.data.ConversationEntity
import com.sidekick.app.data.ProviderConfigEntity
import com.sidekick.app.data.Seed
import com.sidekick.app.data.ToolCallEntity
import com.sidekick.app.data.TurnEntity
import com.sidekick.app.data.dao.ConversationDao
import com.sidekick.app.data.dao.ProviderConfigDao
import com.sidekick.app.data.dao.TeammateDao
import com.sidekick.app.data.dao.ToolCallDao
import com.sidekick.app.data.dao.TurnDao
import com.sidekick.app.data.provideDatabase
import com.sidekick.app.provider.ChatMessage
import com.sidekick.app.provider.LlmClient
import com.sidekick.app.provider.LlmException
import com.sidekick.app.provider.LlmRouter
import com.sidekick.app.provider.Provider
import com.sidekick.app.tools.ToolContext
import com.sidekick.app.tools.ToolRegistry
import com.sidekick.app.tools.builtins.ListDir
import com.sidekick.app.tools.builtins.ReadFile
import com.sidekick.app.tools.builtins.TakePhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ViewModel for [ConversationScreen].
 *
 * M3: drives the conversation through [AgentLoop] rather than directly
 * streaming from the provider. The loop owns the tool-dispatch resume
 * logic; this ViewModel:
 *
 *  1. Resolves the active provider and materialises a one-shot [LlmClient]
 *     from the [LlmRouter] cache.
 *  2. Builds the `messages` list (system prompt + persisted turns).
 *  3. Constructs a [ToolRegistry] with the three built-in tools.
 *  4. Calls [AgentLoop.run] and forwards [AgentEvent]s to the UI state.
 *  5. Persists the assistant turn and any emitted [ToolCallEntity] rows
 *     so the transcript reflects the full agent run.
 *
 * Construction is manual (no Hilt). The factory pattern at the bottom
 * is what production uses; tests construct the ViewModel directly.
 */
class ConversationViewModel(
    private val conversationDao: ConversationDao,
    private val turnDao: TurnDao,
    private val toolCallDao: ToolCallDao,
    private val teammateDao: TeammateDao,
    private val providerConfigDao: ProviderConfigDao,
    private val router: LlmRouter,
    private val teammateSlug: String,
    private val title: String,
    private val appContext: Context? = null,
    private val toolRegistryFactory: () -> ToolRegistry = ::defaultToolRegistry,
    private val agentLoopFactory: (LlmClient, ToolRegistry) -> AgentLoop = { c, r -> AgentLoop(c, r) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ioScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    /** Currently running streaming Job, if any. */
    @Volatile
    private var streamJob: Job? = null

    private var conversationId: Long? = null

    /**
     * Open (or create) the conversation row, load the teammate and
     * provider config, run [Seed.seedIfEmpty] lazily on first call, and
     * subscribe to the persisted turn + tool-call streams.
     *
     * Idempotent — safe to call once from the screen's `LaunchedEffect`.
     */
    fun start(context: Context? = null) {
        if (_state.value.conversationId != null) return

        ioScope.launch {
            val ctx = context ?: appContext
            if (ctx != null) {
                Seed.seedIfEmpty(dao = teammateDao, context = ctx)
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

            // Observe tool calls per turn so the transcript can render
            // "Coder used read_file(...)" inline. M3 collapses across all
            // turns in the conversation into a single map.
            turnDao.getByConversation(cid)
                .onEach { rows ->
                    val byTurn = mutableMapOf<Long, List<ToolCallEntity>>()
                    for (t in rows) {
                        val calls = toolCallDao.listByTurn(t.id)
                        if (calls.isNotEmpty()) byTurn[t.id] = calls
                    }
                    _state.value = _state.value.copy(toolCallsByTurn = byTurn)
                }
                .launchIn(ioScope)
        }
    }

    /**
     * Append [text] as a user turn, then run the agent loop.
     *
     * Flow:
     *  1. Insert a `user` turn.
     *  2. Resolve provider + system prompt + history into `messages`.
     *  3. Construct the tool registry + AgentLoop.
     *  4. Run the loop, forwarding `AgentEvent`s to the UI state.
     *  5. Persist each tool call as a [ToolCallEntity].
     *  6. On `TextDone`, persist the assistant `TurnEntity` with the
     *     accumulated text. On `MaxIterationsExceeded`, persist an
     *     "[error] max iterations" assistant turn. On `Error`, persist
     *     an "[error] …" assistant turn and surface the exception in
     *     state.
     */
    fun sendMessage(text: String) {
        val cid = conversationId ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val ctx = appContext ?: return

        streamJob = ioScope.launch {
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

            // 2. Insert the assistant placeholder turn up-front so tool
            //    calls emitted during this run can be FK-attached to it.
            //    The final text lands here in step 6 via update().
            val assistantPosition = position + 1
            val assistantTurnId = turnDao.insert(
                TurnEntity(
                    conversationId = cid,
                    role = "assistant",
                    content = "",
                    position = assistantPosition,
                    createdAt = clock(),
                ),
            )

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
                    finishWithError(cid, assistantTurnId, assistantPosition, LlmException.Network("No active provider configured"))
                    return@launch
                }

            // 4. Construct the tool registry and agent loop. We materialise
            //    the LlmClient from the router up-front so a router cache
            //    miss surfaces here (rather than mid-stream).
            val client = try {
                router.clientFor(provider)
            } catch (t: Throwable) {
                finishWithError(cid, assistantTurnId, assistantPosition, t.toLlmException())
                return@launch
            }
            val registry = toolRegistryFactory()
            val loop = agentLoopFactory(client, registry)

            // 5. Run the loop.
            _state.value = _state.value.copy(isStreaming = true, partialResponse = "")

            var failure: LlmException? = null
            val partialBuilder = StringBuilder()
            var assistantTurn = TurnEntity(
                id = assistantTurnId,
                conversationId = cid,
                role = "assistant",
                content = "",
                position = assistantPosition,
                createdAt = clock(),
            )
            // Buffer tool-call events so we can persist them after the
            // loop returns (the loop's onEvent callback is non-suspend;
            // mutating Room requires suspend). The agent loop emits
            // ToolCall before ToolResult for the same id, so we keep both
            // in order and pair them by sequence.
            val toolEvents = mutableListOf<ToolEvent>()

            val toolCtx = ToolContext(appContext = ctx, sessionId = cid)
            try {
                loop.run(messages, registry.descriptors(), toolCtx) { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            partialBuilder.append(event.delta)
                            val snapshot = partialBuilder.toString()
                            _state.value = _state.value.copy(partialResponse = snapshot)
                            assistantTurn = assistantTurn.copy(content = snapshot)
                            // Inline Room update; onEvent is called from
                            // a coroutine context (the agent loop uses
                            // Dispatchers.Unconfined for the callback),
                            // so a non-suspend update would be a race —
                            // we push the update to ioScope instead.
                            ioScope.launch {
                                turnDao.update(assistantTurn)
                            }
                        }
                        is AgentEvent.ToolCall -> {
                            toolEvents.add(ToolEvent.Call(event.name, event.args, event.callId))
                        }
                        is AgentEvent.ToolResult -> {
                            toolEvents.add(ToolEvent.Result(event.name, event.result, event.callId))
                        }
                        is AgentEvent.TextDone -> {
                            partialBuilder.clear()
                            val finalText = event.fullText
                            assistantTurn = assistantTurn.copy(content = finalText)
                            ioScope.launch {
                                turnDao.update(assistantTurn)
                            }
                        }
                        is AgentEvent.Error -> {
                            failure = event.error.toLlmException()
                        }
                        is AgentEvent.MaxIterationsExceeded -> {
                            failure = LlmException.Network(
                                "agent loop exceeded ${event.max} iterations",
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                failure = t.toLlmException()
            }

            // Persist tool-call rows after the loop completes. We pair
            // each ToolEvent.Call with the next ToolEvent.Result that
            // shares its callId; if no result is recorded (the model
            // emitted a call but the loop aborted before dispatching),
            // we still record the call with a null result.
            val paired = pairToolEvents(toolEvents)
            for ((call, result) in paired) {
                val callId = toolCallDao.insert(
                    ToolCallEntity(
                        turnId = assistantTurnId,
                        toolName = call.name,
                        argsJson = call.args.toString(),
                        resultJson = null,
                        createdAt = clock(),
                    ),
                )
                if (result != null) {
                    val inner = result.result
                    val json = when (inner) {
                        is com.sidekick.app.tools.ToolResult.Ok ->
                            "{\"ok\":${jsonString(inner.output)}}"
                        is com.sidekick.app.tools.ToolResult.Err ->
                            "{\"err\":${jsonString(inner.message)}}"
                    }
                    toolCallDao.setResult(callId, json)
                }
            }

            val finalFailure = failure
            if (finalFailure != null) {
                finishWithError(cid, assistantTurnId, assistantPosition, finalFailure)
                return@launch
            }

            // 6. Final touch-up: bump conversation's updatedAt and clear
            //    streaming state.
            conversationDao.touch(cid, clock())
            _state.value = _state.value.copy(
                isStreaming = false,
                partialResponse = "",
            )
        }
    }

    /**
     * Pair tool-call events with their results in arrival order. The
     * agent loop emits one Call followed by one Result for each
     * dispatch; we pair by that order, falling back to unmatched Call
     * rows when a Result is missing (e.g. on MaxIterationsExceeded).
     */
    private fun pairToolEvents(events: List<ToolEvent>): List<Pair<ToolEvent.Call, ToolEvent.Result?>> {
        val out = mutableListOf<Pair<ToolEvent.Call, ToolEvent.Result?>>()
        var pendingCall: ToolEvent.Call? = null
        for (e in events) {
            when (e) {
                is ToolEvent.Call -> {
                    // Close out any previous unmatched call (no result yet).
                    pendingCall?.let { out.add(it to null) }
                    pendingCall = e
                }
                is ToolEvent.Result -> {
                    val pc = pendingCall
                    if (pc != null) {
                        out.add(pc to e)
                        pendingCall = null
                    }
                    // Result without a Call — drop on the floor; shouldn't happen.
                }
            }
        }
        pendingCall?.let { out.add(it to null) }
        return out
    }

    /**
     * Internal carrier for tool-call events buffered during a single
     * agent-loop run. The ViewModel needs to pair Calls with their
     * Results before persisting [ToolCallEntity] rows.
     */
    private sealed class ToolEvent {
        data class Call(
            val name: String,
            val args: kotlinx.serialization.json.JsonObject,
            val callId: String,
        ) : ToolEvent()
        data class Result(
            val name: String,
            val result: com.sidekick.app.tools.ToolResult,
            val callId: String,
        ) : ToolEvent()
    }

    /**
     * Persist an "[error] ..." assistant turn and surface the failure in
     * UI state. The assistant turn was inserted up-front in [sendMessage],
     * so we update its content rather than inserting again.
     */
    private suspend fun finishWithError(cid: Long, assistantTurnId: Long, position: Int, err: LlmException) {
        val errorText = "[error] " + (err.message ?: err::class.simpleName.orEmpty())
        turnDao.update(
            TurnEntity(
                id = assistantTurnId,
                conversationId = cid,
                role = "assistant",
                content = errorText,
                position = position,
                createdAt = clock(),
            ),
        )
        conversationDao.touch(cid, clock())
        _state.value = _state.value.copy(
            isStreaming = false,
            partialResponse = "",
            error = err,
        )
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
         * Default tool registry for production — the three built-in tools
         * defined in [com.sidekick.app.tools.builtins]. M4 will extend
         * with image-tools; tests inject their own factory.
         */
        fun defaultToolRegistry(): ToolRegistry =
            ToolRegistry(listOf(ReadFile(), ListDir(), TakePhoto()))

        /**
         * Manual factory for production. Loads the database via
         * [provideDatabase]. Seeding happens lazily on the first [start] call.
         */
        fun factory(context: Context, teammateSlug: String, title: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = context.applicationContext
                    val db = provideDatabase(app)
                    return ConversationViewModel(
                        conversationDao = db.conversationDao(),
                        turnDao = db.turnDao(),
                        toolCallDao = db.toolCallDao(),
                        teammateDao = db.teammateDao(),
                        providerConfigDao = db.providerConfigDao(),
                        router = LlmRouter(),
                        teammateSlug = teammateSlug,
                        title = title,
                        appContext = app,
                    ) as T
                }
            }
    }
}

/**
 * Internal helper used by the ViewModel to coerce caught throwables into
 * [LlmException]. Same shape as the M2 helper but lives in the UI module.
 */
private fun Throwable.toLlmException(): LlmException = when (this) {
    is LlmException -> this
    else -> LlmException.Network(message ?: this::class.simpleName.orEmpty(), this)
}

/**
 * Wrap [s] as a JSON string literal (with escapes for the special chars
 * that show up in tool output). Used by the ViewModel to serialise
 * [com.sidekick.app.tools.ToolResult] into a flat JSON column.
 */
private fun jsonString(s: String): String {
    val escaped = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escaped\""
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
