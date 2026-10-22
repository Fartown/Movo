package io.github.fartown.movo.ui.app

import android.app.Application
import android.content.Context
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.data.db.MovoDatabase
import io.github.fartown.movo.ui.components.AgentConversationDraftStore
import io.github.fartown.movo.ui.model.AgentChatHomeUiState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 真机回归（10-22）：编辑 / 重新生成第一轮时，旧问答不能留在记录里。原来只把 journal 退回到前缀，前缀为空时
 * 被当成「没有 journal」退回整段旧历史，下一次重新生成就把编辑前的问题和旧回答一起发给了模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "en-rUS")
class AgentAppStateRewindTest {
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
        val turn = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "今天的科技新闻", messageId = "u1"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答：三条科技新闻", messageId = "a1"),
        )
        runBlocking {
            ConversationStoreTestDriver.save(
                context = context,
                selectedConversationId = "c1",
                conversationsById = mapOf(
                    "c1" to AgentChatHomeUiState(
                        messages = listOf(UserMessageUi(id = "u1", content = "今天的科技新闻"), AgentMessageUi(id = "a1", content = "旧回答：三条科技新闻")),
                        history = turn,
                        journal = turn,
                        input = "", isStreaming = false, thinkingEnabled = false,
                    ),
                ),
                titles = mapOf("c1" to "科技新闻"),
                updatedAt = mapOf("c1" to 1L),
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
    fun editingTheFirstTurnDropsTheOldQuestionAndAnswer() {
        val state = AgentAppState(context, scope)
        state.beginMessageEdit("u1")
        state.sendCurrentMessage("今天的 AI 新闻")
        assertEquals(listOf("今天的 AI 新闻"), state.homeState.history.map { it.content })
        assertEquals(listOf("今天的 AI 新闻"), state.homeState.journal.map { it.content })
    }

    @Test
    fun regeneratingTheFirstTurnDropsTheOldAnswer() {
        val state = AgentAppState(context, scope)
        state.regenerateMessage("a1")
        assertEquals(listOf("今天的科技新闻"), state.homeState.history.map { it.content })
        assertEquals(listOf("今天的科技新闻"), state.homeState.journal.map { it.content })
    }
}
