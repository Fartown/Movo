package io.github.fartown.movo.ui.app

import android.content.Context
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.data.db.ConversationContextCheckpointEntity
import io.github.fartown.movo.data.db.ConversationDao
import io.github.fartown.movo.data.db.ConversationEntity
import io.github.fartown.movo.data.db.ConversationModelMessageEntity
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.data.model.ReasoningEffort
import io.github.fartown.movo.ui.model.AgentChatHomeUiState
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class ConversationRepositoryTest {
    private lateinit var context: Context
    private lateinit var dao: ConversationDao
    private lateinit var scope: CoroutineScope
    private lateinit var repository: ConversationRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MovoDatabase.closeForTests()
        context.deleteDatabase("movo.db")
        dao = MovoDatabase.get(context).conversationDao()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = ConversationRepository({ dao }, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        MovoDatabase.closeForTests()
    }

    @Test
    fun appendedMessagesGetGappedKeysAndLoadInOrder() = runBlocking {
        val first = listOf(user("u1", "打开哔哩哔哩"), agent("a1", "已打开"))
        repository.saveConversation("c1", state(first), "B 站", updatedAt = 10).await()
        repository.syncMessages("c1", emptyList(), first).await()
        val second = first + listOf(user("u2", "搜罗翔"), agent("a2", "搜到了"))
        repository.syncMessages("c1", first, second).await()

        assertEquals(listOf(1024L, 2048L, 3072L, 4096L), keys("c1"))
        assertEquals(second, repository.load("c1")!!.state.messages)
    }

    @Test
    fun insertingInTheMiddleOnlyWritesTheNewRow() = runBlocking {
        val before = listOf(user("u1", "播放庆余年"), agent("a1", "正在找"))
        repository.saveConversation("c1", state(before), "", updatedAt = 1).await()
        repository.syncMessages("c1", emptyList(), before).await()
        val after = listOf(before[0], user("user-r1-supplement-1", "要第二季"), before[1])
        repository.syncMessages("c1", before, after).await()

        assertEquals(listOf(1024L, 1536L, 2048L), keys("c1"))
        assertEquals(after, repository.load("c1")!!.state.messages)
    }

    @Test
    fun reorderedOrCrowdedMessagesAreRenumberedOnce() = runBlocking {
        val before = listOf(user("u1", "一"), agent("a1", "二"), user("u2", "三"))
        repository.saveConversation("c1", state(before), "", updatedAt = 1).await()
        repository.syncMessages("c1", emptyList(), before).await()
        val reordered = listOf(before[2], before[0], before[1])
        repository.syncMessages("c1", before, reordered).await()

        assertEquals(listOf(1024L, 2048L, 3072L), keys("c1"))
        assertEquals(reordered, repository.load("c1")!!.state.messages)
    }

    @Test
    fun changedMessageIsRewrittenInPlaceAndRemovedOnesAreDeleted() = runBlocking {
        val running = ToolActivityMessageUi("t1", "app_open", ToolActivityStatusUi.Running, "哔哩哔哩")
        val before = listOf(user("u1", "打开哔哩哔哩"), running, agent("a1", "x".repeat(40_000)))
        repository.saveConversation("c1", state(before), "", updatedAt = 1).await()
        repository.syncMessages("c1", emptyList(), before).await()
        val finished = running.copy(status = ToolActivityStatusUi.Success, resultSummary = "已到前台")
        val after = listOf(before[0], finished)
        repository.syncMessages("c1", before, after).await()

        assertEquals(after, repository.load("c1")!!.state.messages)
        assertEquals(listOf(1024L, 2048L), keys("c1"))
        assertEquals(0, chunkCount("conversation_messages"))
    }

    @Test
    fun modelLogAppendsEachRunAndRewritesOnlyThisConversationWhenItCannot() = runBlocking {
        val u1 = message("user", "打开哔哩哔哩")
        val a1 = message("assistant", "已打开哔哩哔哩")
        repository.saveConversation("c1", state(emptyList()), "", updatedAt = 1).await()
        repository.saveConversation("c2", state(emptyList()), "", updatedAt = 1).await()
        repository.syncModelLog("c2", ConversationModelMessageEntity.LOG_HISTORY, emptyList(), listOf(u1)).await()
        repository.syncModelLog("c1", ConversationModelMessageEntity.LOG_HISTORY, emptyList(), listOf(u1)).await()
        repository.syncModelLog("c1", ConversationModelMessageEntity.LOG_HISTORY, listOf(u1), listOf(u1, a1), runId = "r1").await()
        assertEquals(listOf(null, "r1"), dao.modelMessageRows("c1", ConversationModelMessageEntity.LOG_HISTORY).map { it.runId })

        val summary = message("user", "此前对话摘要", contextSummary = true)
        repository.syncModelLog("c1", ConversationModelMessageEntity.LOG_HISTORY, listOf(u1, a1), listOf(summary)).await()

        assertEquals(listOf(summary), repository.load("c1")!!.state.history)
        assertEquals(listOf(u1), repository.load("c2")!!.state.history)
    }

    @Test
    fun modelMessagesKeepTheRawResponsesOutputForReplay() = runBlocking {
        // 提示缓存（#21）：助手消息带着模型原始输出项和来源，按行存储后必须原样读回，才能在下一轮原样回放。
        val raw = message("assistant", "已打开").copy(
            responsesOutputJson = """[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"已打开"}]}]""",
            responsesOrigin = "ark|doubao-seed",
        )
        repository.saveConversation("c1", state(emptyList()), "", updatedAt = 1).await()
        repository.syncModelLog("c1", ConversationModelMessageEntity.LOG_HISTORY, emptyList(), listOf(raw)).await()

        assertEquals(listOf(raw), repository.load("c1")!!.state.history)
    }

    @Test
    fun longModelMessageIsChunkedAndDeletingTheConversationRemovesEverything() = runBlocking {
        val long = message("tool", "节点".repeat(20_000))
        repository.saveConversation("c1", state(listOf(user("u1", "看看屏幕"))), "", updatedAt = 1).await()
        repository.syncMessages("c1", emptyList(), listOf(user("u1", "看看屏幕"))).await()
        repository.syncModelLog("c1", ConversationModelMessageEntity.LOG_JOURNAL, emptyList(), listOf(long)).await()
        assertEquals(listOf(long), repository.load("c1")!!.state.journal)
        assertTrue(chunkCount(ConversationModelMessageEntity.TABLE) > 1)

        repository.deleteConversation("c1").await()

        assertNull(repository.load("c1"))
        assertEquals(0, chunkCount(ConversationModelMessageEntity.TABLE))
        assertEquals(0, dao.modelMessageCount("c1", ConversationModelMessageEntity.LOG_JOURNAL))
    }

    @Test
    fun loadFallsBackToTheLegacyCheckpointBeforeTheModelLogExists() = runBlocking {
        val legacy = message("user", "旧的上下文")
        dao.insertConversations(listOf(ConversationEntity(id = "old", title = "新对话", thinkingEnabled = false,
            createdAt = 1, updatedAt = 1)))
        dao.insertContextCheckpoints(listOf(ConversationContextCheckpointEntity(
            conversationId = "old",
            historyJson = io.github.fartown.movo.agent.model.AgentConversationCodec.encodeTranscriptForStorage(listOf(legacy)),
            journalJson = "",
        )))

        val loaded = repository.load("old")!!

        assertEquals("", loaded.title)
        assertEquals(listOf(legacy), loaded.state.history)
        assertEquals(listOf(legacy), loaded.state.journal)
    }

    @Test
    fun summariesListNewestFirstWithTheirLastMessage() = runBlocking {
        repository.saveConversation("old", state(emptyList()), "旧的", updatedAt = 1).await()
        repository.syncMessages("old", emptyList(), listOf(user("o1", "今天天气"))).await()
        repository.saveConversation("new", state(emptyList()), "新的", updatedAt = 2).await()
        val newMessages = listOf(user("n1", "打开设置"), agent("n2", "已打开设置"))
        repository.syncMessages("new", emptyList(), newMessages).await()
        repository.saveConversation("empty", state(emptyList()), "", updatedAt = 3).await()

        val summaries = repository.summaries(limit = 10)

        assertEquals(listOf("empty", "new", "old"), summaries.map { it.id })
        assertNull(summaries[0].lastMessage)
        assertEquals(newMessages.last(), summaries[1].lastMessage)
        assertEquals(listOf("old"), repository.summaries(limit = 1, offset = 2).map { it.id })
    }

    @Test
    fun searchMatchesTitlesContentToolsAndLongContentWithLiteralWildcards() = runBlocking {
        repository.saveConversation("title", state(emptyList()), "狂飙第 5 集", updatedAt = 1).await()
        repository.saveConversation("content", state(emptyList()), "", updatedAt = 1).await()
        repository.syncMessages("content", emptyList(), listOf(user("c1", "音量调到 100%"))).await()
        repository.saveConversation("tool", state(emptyList()), "", updatedAt = 1).await()
        repository.syncMessages("tool", emptyList(), listOf(
            ToolActivityMessageUi("t1", "video_search", ToolActivityStatusUi.Success, "庆余年"),
        )).await()
        repository.saveConversation("long", state(emptyList()), "", updatedAt = 1).await()
        repository.syncMessages("long", emptyList(), listOf(agent("l1", "x".repeat(20_000) + "罗翔"))).await()

        assertEquals(setOf("title"), repository.search("狂飙"))
        assertEquals(setOf("content"), repository.search("100%"))
        assertEquals(emptySet<String>(), repository.search("10_"))
        assertEquals(setOf("tool"), repository.search("庆余年"))
        assertEquals(setOf("long"), repository.search("罗翔"))
    }

    @Test
    fun conversationKeepsItsCreationTimeAndSelectionIsStored() = runBlocking {
        repository.saveConversation("c1", state(emptyList()), "第一版", updatedAt = 5).await()
        repository.saveConversation("c1", state(emptyList(), effort = ReasoningEffort.HIGH), "第二版", updatedAt = 9).await()
        repository.select("c1").await()

        val row = dao.conversationMetadata("c1")!!
        assertEquals(5, row.createdAt)
        assertEquals(9, row.updatedAt)
        assertEquals("第二版", row.title)
        assertEquals(ReasoningEffort.HIGH, repository.load("c1")!!.state.reasoningEffort)
        assertEquals("c1", repository.selectedConversationId())
        repository.select(null).await()
        assertNull(repository.selectedConversationId())
        assertFalse(repository.exists("missing"))
    }

    @Test
    fun aFailedWriteDoesNotBlockLaterWrites() = runBlocking {
        val failed = repository.syncMessages("missing-conversation", emptyList(), listOf(user("u1", "孤儿消息")))
        assertTrue(runCatching { failed.await() }.isFailure)

        repository.saveConversation("c1", state(emptyList()), "", updatedAt = 1).await()
        repository.syncMessages("c1", emptyList(), listOf(user("u1", "正常消息"))).await()

        assertEquals(listOf(user("u1", "正常消息")), repository.load("c1")!!.state.messages)
    }

    private suspend fun keys(conversationId: String) =
        dao.messageSortKeys(conversationId).map { it.sortIndex }.sorted()

    private fun chunkCount(table: String): Int = MovoDatabase.get(context).openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM agent_text_chunks WHERE owner_table = ?", arrayOf(table))
        .use { it.moveToFirst(); it.getInt(0) }

    private fun user(id: String, content: String) = UserMessageUi(id = id, content = content)

    private fun agent(id: String, content: String) = AgentMessageUi(id = id, content = content, isStreaming = false)

    private fun message(role: String, content: String, contextSummary: Boolean = false) =
        AgentModelClient.ConversationMessage(role = role, content = content, contextSummary = contextSummary)

    private fun state(messages: List<AgentChatMessageUi>, effort: ReasoningEffort = ReasoningEffort.DEFAULT) =
        AgentChatHomeUiState(messages = messages, input = "", isStreaming = false,
            thinkingEnabled = effort.enablesReasoning, reasoningEffort = effort)
}
