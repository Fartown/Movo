package io.github.fartown.movo.ui.app

import android.app.Application
import android.content.Context
import android.os.Looper
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.ui.components.AgentConversationDraftStore
import io.github.fartown.movo.ui.model.AgentChatHomeUiState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 对话按需读进内存（docs/solutions/conversation-storage 第 3 步）：启动只读选中的，用到别的再读，闲了卸掉。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "en-rUS")
class AgentAppStateConversationLoadingTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MovoDatabase.closeForTests()
        context.deleteDatabase("movo.db")
        AgentConversationDraftStore.shared.clear()
        shadowOf(context as Application).declareComponentUnbindable(
            io.github.fartown.movo.agent.runtime.AgentRuntimeWire.serviceIntent().component,
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        runBlocking {
            ConversationStoreTestDriver.save(
                context = context,
                selectedConversationId = "new",
                conversationsById = mapOf(
                    "old" to conversation("old", "看看罗翔的视频", "找到了罗翔最新的一期"),
                    "mid" to conversation("mid", "打开设置", "已打开设置"),
                    "new" to conversation("new", "播放庆余年", "开始播放庆余年第二季"),
                ),
                titles = mapOf("old" to "罗翔", "mid" to "设置", "new" to "庆余年"),
                updatedAt = mapOf("old" to 1L, "mid" to 2L, "new" to 3L),
            )
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        AgentConversationDraftStore.shared.clear()
        MovoDatabase.closeForTests()
    }

    @Test
    fun startupLoadsOnlyTheSelectedConversationButListsThemAll() {
        val state = AgentAppState(context, scope)

        assertEquals(setOf("new"), state.loadedConversationIdsForTests)
        assertEquals("开始播放庆余年第二季", (state.homeState.messages.last() as AgentMessageUi).content)
        val listed = state.conversationPaneState.conversations
        assertEquals(listOf("new", "mid", "old"), listed.map { it.id })
        assertEquals(listOf("开始播放庆余年第二季", "已打开设置", "找到了罗翔最新的一期"), listed.map { it.preview })
    }

    @Test
    fun selectingAStoredConversationLoadsItAndUnloadsTheIdleOne() {
        val state = AgentAppState(context, scope)

        state.selectConversation("old")
        awaitUntil { state.conversationPaneState.selectedConversationId == "old" }

        assertEquals("找到了罗翔最新的一期", (state.homeState.messages.last() as AgentMessageUi).content)
        assertEquals(setOf("old"), state.loadedConversationIdsForTests)
        // 选中项落库：下次启动打开的就是它。
        runBlocking { ConversationRepository.get(context).flush() }
        val restarted = AgentAppState(context, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        assertEquals("old", restarted.conversationPaneState.selectedConversationId)
    }

    @Test
    fun deletingTheSelectedConversationOpensTheMostRecentRemainingOne() {
        val state = AgentAppState(context, scope)

        state.deleteConversation("new")
        awaitUntil { state.conversationPaneState.selectedConversationId == "mid" }

        assertEquals("已打开设置", (state.homeState.messages.last() as AgentMessageUi).content)
        assertEquals(listOf("mid", "old"), state.conversationPaneState.conversations.map { it.id })
        assertFalse(runBlocking { ConversationRepository.get(context).exists("new") })
    }

    @Test
    fun renamingAConversationThatIsNotLoadedWritesOnlyItsTitle() {
        val state = AgentAppState(context, scope)

        state.renameConversation("old", "罗翔说刑法")

        assertEquals("罗翔说刑法", state.conversationPaneState.conversations.first { it.id == "old" }.title)
        assertEquals(setOf("new"), state.loadedConversationIdsForTests)
        val stored = runBlocking { ConversationRepository.get(context).load("old")!! }
        assertEquals("罗翔说刑法", stored.title)
        assertEquals("找到了罗翔最新的一期", (stored.state.messages.last() as AgentMessageUi).content)
    }

    @Test
    fun searchFindsContentInConversationsThatAreNotLoaded() {
        val state = AgentAppState(context, scope)

        state.updateSearchQuery("最新的一期")
        awaitUntil { state.conversationPaneState.conversations.any { it.id == "old" } }

        val hit = state.conversationPaneState.conversations.single()
        assertEquals("old", hit.id)
        assertTrue(hit.matchSnippet.orEmpty().contains("最新的一期"))
        assertEquals(setOf("new"), state.loadedConversationIdsForTests)
    }

    @Test
    fun exportingAConversationThatIsNotLoadedReadsItFromTheDatabase() {
        val state = AgentAppState(context, scope)

        val markdown = scope.async { state.exportConversationMarkdown("mid") }
        awaitUntil { markdown.isCompleted }

        val text = markdown.getCompleted().orEmpty()
        assertTrue(text.contains("打开设置"))
        assertTrue(text.contains("已打开设置"))
    }

    private fun conversation(id: String, ask: String, answer: String) = AgentChatHomeUiState(
        messages = listOf(UserMessageUi(id = "$id-user", content = ask), AgentMessageUi(id = "$id-agent", content = answer)),
        input = "",
        isStreaming = false,
        thinkingEnabled = false,
    )

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待超时" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
