package com.sidekick.app.tools

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sidekick.app.agent.AgentLoop
import com.sidekick.app.data.AppDatabase
import com.sidekick.app.data.ConversationEntity
import com.sidekick.app.data.TeammateEntity
import com.sidekick.app.data.TurnEntity
import com.sidekick.app.data.dao.ConversationDao
import com.sidekick.app.data.dao.TeammateDao
import com.sidekick.app.data.dao.TurnDao
import com.sidekick.app.provider.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Structural isolation tests for Sidekick's "three isolated sessions" USP.
 *
 * The MVP's central claim is that a user's conversations with Coder,
 * Builder, and Researcher never share state. This test enforces that
 * claim at the data layer:
 *
 *  1. `TurnDao.getByConversation(coderId)` does NOT contain Builder's
 *     turns and vice versa — even when both conversations exist in the
 *     same database.
 *  2. Two concurrent `AgentLoop.run` calls with distinct `messages`
 *     lists never cross-contaminate — the loop's contract is that the
 *     caller's list is the only state the loop touches.
 *
 * The package name `tools` (rather than `data` or `agent`) is
 * deliberate: the isolation guarantee is what enables tool-driven
 * conversations to remain scoped, so it lives next to the tools it
 * protects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThreeToolIsolationTest {

    private lateinit var db: AppDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var turnDao: TurnDao
    private lateinit var teammateDao: TeammateDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        conversationDao = db.conversationDao()
        turnDao = db.turnDao()
        teammateDao = db.teammateDao()
        // Pre-seed the three built-in teammates so foreign-key resolution
        // (e.g. teammate slug in ConversationEntity) is consistent with
        // production.
        runBlocking {
            listOf(
                TeammateEntity("coder", "Coder", "Refactors.", "You are Coder."),
                TeammateEntity("builder", "Builder", "Drafts sites.", "You are Builder."),
                TeammateEntity("researcher", "Researcher", "Summarises.", "You are Researcher."),
            ).forEach { teammateDao.insert(it) }
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun turnsAreScopedToTheirOwnConversation() = runBlocking {
        val coderConvId = conversationDao.insert(
            ConversationEntity(
                teammate = "coder",
                title = "Coder thread",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )
        val builderConvId = conversationDao.insert(
            ConversationEntity(
                teammate = "builder",
                title = "Builder thread",
                createdAt = 0L,
                updatedAt = 0L,
            ),
        )

        turnDao.insert(TurnEntity(conversationId = coderConvId, role = "user", content = "fix bug", position = 0, createdAt = 0L))
        turnDao.insert(TurnEntity(conversationId = coderConvId, role = "assistant", content = "what bug?", position = 1, createdAt = 0L))
        turnDao.insert(TurnEntity(conversationId = builderConvId, role = "user", content = "draft site", position = 0, createdAt = 0L))
        turnDao.insert(TurnEntity(conversationId = builderConvId, role = "assistant", content = "sure", position = 1, createdAt = 0L))

        val coderTurns = turnDao.getByConversation(coderConvId).first()
        val builderTurns = turnDao.getByConversation(builderConvId).first()

        // Coder thread sees only Coder turns.
        assertEquals(2, coderTurns.size)
        assertTrue(coderTurns.all { it.conversationId == coderConvId })
        assertTrue(coderTurns.none { it.content == "draft site" })
        assertTrue(coderTurns.none { it.content == "sure" })

        // Builder thread sees only Builder turns.
        assertEquals(2, builderTurns.size)
        assertTrue(builderTurns.all { it.conversationId == builderConvId })
        assertTrue(builderTurns.none { it.content == "fix bug" })
        assertTrue(builderTurns.none { it.content == "what bug?" })
    }

    @Test
    fun deletingOneConversationDoesNotTouchTheOther() = runBlocking {
        val coderConvId = conversationDao.insert(
            ConversationEntity(teammate = "coder", title = "c", createdAt = 0L, updatedAt = 0L),
        )
        val builderConvId = conversationDao.insert(
            ConversationEntity(teammate = "builder", title = "b", createdAt = 0L, updatedAt = 0L),
        )
        turnDao.insert(TurnEntity(conversationId = coderConvId, role = "user", content = "c1", position = 0, createdAt = 0L))
        turnDao.insert(TurnEntity(conversationId = builderConvId, role = "user", content = "b1", position = 0, createdAt = 0L))

        conversationDao.delete(coderConvId)

        // Builder must be untouched.
        assertNotNull(conversationDao.getById(builderConvId))
        assertEquals(1, turnDao.countByConversation(builderConvId))
        assertEquals("b1", turnDao.getByConversation(builderConvId).first().single().content)
    }

    @Test
    fun agentLoopRunIsConversationScoped() = runBlocking {
        // Two scripted providers, one per conversation. Verify each loop
        // only sees its own message list (no cross-talk).
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ctx = ToolContext(appContext = appContext, sessionId = 1L)
        val registry = ToolRegistry(emptyList())

        val coderProvider = ScriptedClient(
            listOf(
                listOf(
                    com.sidekick.app.provider.LlmChunk.Text("Coder: I'll refactor."),
                    com.sidekick.app.provider.LlmChunk.Done(null),
                ),
            ),
        )
        val builderProvider = ScriptedClient(
            listOf(
                listOf(
                    com.sidekick.app.provider.LlmChunk.Text("Builder: I'll draft a site."),
                    com.sidekick.app.provider.LlmChunk.Done(null),
                ),
            ),
        )

        val coderLoop = AgentLoop(provider = coderProvider, registry = registry)
        val builderLoop = AgentLoop(provider = builderProvider, registry = registry)

        val coderMessages = listOf(
            ChatMessage("system", "You are Coder."),
            ChatMessage("user", "fix bug"),
        )
        val builderMessages = listOf(
            ChatMessage("system", "You are Builder."),
            ChatMessage("user", "draft site"),
        )

        coroutineScope {
            val coderJob = async { coderLoop.run(coderMessages, emptyList(), ctx) { } }
            val builderJob = async { builderLoop.run(builderMessages, emptyList(), ctx) { } }
            val coderOut = coderJob.await()
            val builderOut = builderJob.await()

            // Coder loop saw Coder's messages and produced Coder's reply.
            assertEquals(3, coderOut.size)
            assertEquals("Coder: I'll refactor.", coderOut.last().content)
            assertEquals(1, coderProvider.requestCount)
            assertEquals(
                "fix bug",
                coderProvider.lastRequest().messages.last { it.role == "user" }.content,
            )

            // Builder loop saw Builder's messages and produced Builder's reply.
            assertEquals(3, builderOut.size)
            assertEquals("Builder: I'll draft a site.", builderOut.last().content)
            assertEquals(1, builderProvider.requestCount)
            assertEquals(
                "draft site",
                builderProvider.lastRequest().messages.last { it.role == "user" }.content,
            )

            // Cross-contamination guards: Coder's output must not contain
            // Builder's user message or system prompt, and vice versa.
            assertTrue(coderOut.none { it.content == "draft site" })
            assertTrue(coderOut.none { it.content == "You are Builder." })
            assertTrue(builderOut.none { it.content == "fix bug" })
            assertTrue(builderOut.none { it.content == "You are Coder." })
        }
    }
}

/**
 * Minimal scripted client — emits the next queued chunk list on each call.
 * Records the request for later inspection.
 */
private class ScriptedClient(
    private val responses: List<List<com.sidekick.app.provider.LlmChunk>>,
) : com.sidekick.app.provider.LlmClient {

    @Volatile var requestCount: Int = 0
    private val seen: MutableList<com.sidekick.app.provider.LlmRequest> = mutableListOf()

    fun lastRequest(): com.sidekick.app.provider.LlmRequest =
        seen.lastOrNull() ?: error("no requests seen")

    override suspend fun stream(
        request: com.sidekick.app.provider.LlmRequest,
        onChunk: (com.sidekick.app.provider.LlmChunk) -> Unit,
    ): Job {
        requestCount += 1
        seen.add(request)
        val response = responses[(requestCount - 1).coerceAtMost(responses.lastIndex)]
        val job = kotlinx.coroutines.CompletableDeferred(Unit)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            try {
                response.forEach { onChunk(it) }
                job.complete(Unit)
            } catch (t: Throwable) {
                job.completeExceptionally(t)
            }
        }
        return job
    }
}
