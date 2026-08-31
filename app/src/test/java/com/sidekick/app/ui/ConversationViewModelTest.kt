package com.sidekick.app.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.data.AppDatabase
import com.sidekick.app.data.ProviderConfigEntity
import com.sidekick.app.data.TeammateEntity
import com.sidekick.app.provider.LlmChunk
import com.sidekick.app.provider.LlmException
import com.sidekick.app.provider.LlmRequest
import com.sidekick.app.provider.LlmRouter
import com.sidekick.app.provider.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ViewModel tests using an in-memory Room database and a fake [LlmRouter]
 * (the [FakeRouter] subclass at the bottom of the file).
 *
 * `runBlocking` is used in place of `runTest` because the ViewModel's
 * coroutines hop between `Dispatchers.Main.immediate` (via `viewModelScope`)
 * and Room's internal executor, which is awkward to coordinate with a
 * virtual-time scheduler. Real-time `runBlocking` is simpler and reliable
 * for M2 — the streaming test work completes in milliseconds.
 *
 * Verifies:
 *  - [ConversationViewModel.start] opens a conversation and exposes the
 *    teammate + active provider in state.
 *  - [ConversationViewModel.sendMessage] persists the user turn, streams
 *    the assistant reply chunk by chunk, and finalizes when `Done` arrives.
 *  - An exception raised by the router is captured and surfaced in state
 *    while the persisted assistant turn holds the error message.
 *  - [ConversationViewModel.cancel] clears the partial response and the
 *    streaming flag.
 *  - [ConversationViewModel.setProvider] persists the new active config and
 *    invalidates the router's cached client.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var router: FakeRouter
    private lateinit var viewModel: ConversationViewModel
    private lateinit var ioScope: CoroutineScope

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        // Pre-seed: one teammate (coder) and one active provider.
        db.teammateDao().insert(
            TeammateEntity(
                id = "coder",
                name = "Coder",
                tagline = "Refactors Kotlin, reads stack traces.",
                systemPrompt = "You are Coder.",
            ),
        )
        val providerId = db.providerConfigDao().insert(
            ProviderConfigEntity(
                providerKind = "local_ollama",
                baseUrl = "http://localhost:11434",
                apiKey = null,
                modelName = "qwen2.5-coder:7b",
                isActive = false,
            ),
        )
        db.providerConfigDao().setActive(providerId)

        router = FakeRouter()
        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        viewModel = ConversationViewModel(
            conversationDao = db.conversationDao(),
            turnDao = db.turnDao(),
            teammateDao = db.teammateDao(),
            providerConfigDao = db.providerConfigDao(),
            router = router,
            teammateSlug = "coder",
            title = "Test conv",
            ioScope = ioScope,
        )
    }

    @After
    fun tearDown() {
        ioScope.cancel()
        db.close()
    }

    @Test
    fun startSeedsConversationAndExposesTeammateAndProvider() = runBlocking {
        assertNull(viewModel.state.value.conversationId)

        viewModel.start()
        // Unconfined dispatcher means the launch runs synchronously up to the
        // first suspension; we just need a yield to flush the StateFlow.
        delay(10)
        val state = viewModel.state.value
        assertNotNull(state.conversationId)
        assertEquals("Coder", state.teammate?.name)
        assertEquals("You are Coder.", state.teammate?.systemPrompt)
        assertEquals("local_ollama", state.activeProvider?.providerKind)
        assertTrue(state.messages.isEmpty())
        assertEquals(false, state.isStreaming)
    }

    @Test
    fun sendMessagePersistsUserAndAssistantTurns() = runBlocking {
        viewModel.start()
        delay(10)

        // Script: the fake router emits three text chunks then Done.
        router.script = { _, onChunk ->
            onChunk(LlmChunk.Text("Hello"))
            onChunk(LlmChunk.Text(", "))
            onChunk(LlmChunk.Text("world!"))
            onChunk(LlmChunk.Done(usage = null))
            SupervisorJob().also { it.complete() }
        }

        viewModel.sendMessage("hi there")
        // Wait until the stream finishes — state must be both not-streaming
        // and partial-cleared. The state goes false → true → false across the
        // pipeline, so poll until it stabilizes on false with empty partial.
        while (viewModel.state.value.isStreaming || viewModel.state.value.partialResponse.isNotEmpty()) {
            delay(10)
        }

        val cid = viewModel.state.value.conversationId!!
        db.waitForAssistant(cid) { it == "Hello, world!" }

        val turns = db.readTurns(cid)
        assertEquals(2, turns.size)
        assertEquals("user", turns[0].role)
        assertEquals("hi there", turns[0].content)
        assertEquals("assistant", turns[1].role)
        assertEquals("Hello, world!", turns[1].content)
        assertEquals(0, turns[0].position)
        assertEquals(1, turns[1].position)

        val final = viewModel.state.value
        assertEquals(false, final.isStreaming)
        assertEquals("", final.partialResponse)
        assertNull(final.error)
    }

    @Test
    fun sendMessagePartialTextAccumulates() = runBlocking {
        viewModel.start()
        delay(10)

        router.script = { _, onChunk ->
            onChunk(LlmChunk.Text("part-1"))
            // No Done; returning a completing Job closes the producer's channel.
            SupervisorJob().also { it.complete() }
        }

        viewModel.sendMessage("hi")
        while (viewModel.state.value.isStreaming) delay(10)

        val cid = viewModel.state.value.conversationId!!
        db.waitForAssistant(cid) { it == "part-1" }

        val turns = db.readTurns(cid)
        val assistant = turns.first { it.role == "assistant" }
        assertEquals("part-1", assistant.content)
    }

    @Test
    fun sendMessageSurfacesRouterFailure() = runBlocking {
        viewModel.start()
        delay(10)

        router.script = { _, _ ->
            throw LlmException.Network("connection refused")
        }

        viewModel.sendMessage("hi")
        while (viewModel.state.value.isStreaming) delay(10)

        val cid = viewModel.state.value.conversationId!!
        db.waitForAssistant(cid) { it?.startsWith("[error]") == true }

        val turns = db.readTurns(cid)
        val assistant = turns.first { it.role == "assistant" }
        assertTrue(
            "Assistant turn must reflect error: ${assistant.content}",
            assistant.content.startsWith("[error]"),
        )
        assertTrue(assistant.content.contains("connection refused"))

        val final = viewModel.state.value
        assertNotNull(final.error)
        assertEquals(false, final.isStreaming)
    }

    @Test
    fun cancelAbortsInFlightStream() = runBlocking {
        viewModel.start()
        delay(10)

        router.script = { _, onChunk ->
            onChunk(LlmChunk.Text("first"))
            // Return a non-completing Job — keeps the producer open.
            Job()
        }

        viewModel.sendMessage("hi")
        // Wait until isStreaming becomes true (placeholder inserted).
        while (!viewModel.state.value.isStreaming) delay(10)

        viewModel.cancel()
        delay(10)

        val state = viewModel.state.value
        assertEquals(false, state.isStreaming)
        assertEquals("", state.partialResponse)
    }

    @Test
    fun setProviderPersistsAndInvalidates() = runBlocking {
        viewModel.start()
        delay(10)

        val newId = db.providerConfigDao().insert(
            ProviderConfigEntity(
                providerKind = "cloud_openai",
                baseUrl = "https://api.openai.com/v1",
                apiKey = "sk-test",
                modelName = "gpt-4o-mini",
                isActive = false,
            ),
        )
        val newConfig = ProviderConfigEntity(
            id = newId,
            providerKind = "cloud_openai",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            modelName = "gpt-4o-mini",
            isActive = true,
        )

        val invalidationsBefore = router.invalidations
        viewModel.setProvider(newConfig)
        // Wait until state reflects the new active provider.
        while (viewModel.state.value.activeProvider?.id != newId) delay(10)

        val active = db.providerConfigDao().getActive()
        assertEquals(newId, active?.id)
        assertEquals("cloud_openai", active?.providerKind)
        assertTrue(active!!.isActive)
        assertEquals("cloud_openai", viewModel.state.value.activeProvider?.providerKind)
        assertEquals(invalidationsBefore + 1, router.invalidations)
    }
}

/**
 * Read the ordered turns for a conversation via raw SQL. Bypasses Room's
 * Flow-based DAO which can return stale snapshots under `allowMainThreadQueries`.
 */
private fun AppDatabase.readTurns(conversationId: Long): List<com.sidekick.app.data.TurnEntity> =
    openHelper.readableDatabase.query(
        "SELECT id, conversationId, role, content, position, createdAt FROM turns WHERE conversationId = ? ORDER BY position ASC",
        arrayOf<Any?>(conversationId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    com.sidekick.app.data.TurnEntity(
                        id = cursor.getLong(0),
                        conversationId = cursor.getLong(1),
                        role = cursor.getString(2),
                        content = cursor.getString(3),
                        position = cursor.getInt(4),
                        createdAt = cursor.getLong(5),
                    ),
                )
            }
        }
    }

/** Poll a single column from the assistant row until it satisfies [predicate]. */
private suspend fun AppDatabase.waitForAssistant(
    conversationId: Long,
    predicate: (String?) -> Boolean,
) {
    while (true) {
        delay(10)
        val content = openHelper.readableDatabase.query(
            "SELECT content FROM turns WHERE conversationId = ? AND role = 'assistant'",
            arrayOf<Any?>(conversationId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (predicate(content)) return
    }
}

/**
 * Hand-rolled test double for [LlmRouter] — exposes a [script] lambda the
 * caller can set per-test. Records invalidations for assertions.
 *
 * `LlmRouter` was opened in M2 so tests can subclass it (the original M1
 * tests use the default implementation directly).
 */
private class FakeRouter : LlmRouter() {
    @Volatile var script: (LlmRequest, (LlmChunk) -> Unit) -> Job = { _, _ ->
        SupervisorJob().also { it.complete() }
    }

    @Volatile var invalidations: Int = 0

    override suspend fun stream(
        provider: Provider,
        request: LlmRequest,
        onChunk: (LlmChunk) -> Unit,
    ): Job = script(request, onChunk)

    override fun invalidate(provider: Provider) {
        invalidations += 1
        super.invalidate(provider)
    }
}
