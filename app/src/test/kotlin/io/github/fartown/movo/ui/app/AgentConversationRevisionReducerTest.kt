package io.github.fartown.movo.ui.app

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.ui.model.AgentChatUiState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRevisionReducerTest {
    @Test
    fun editingUserTurnCoveredBySummaryDropsStaleSummary() {
        val summary = AgentModelClient.ConversationMessage("assistant", "旧操作结果", contextSummary = true,
            compactedUserTurns = 1, summaryThroughUserTurn = 2)
        val state = AgentChatUiState(
            messages = listOf(UserMessageUi("old", "旧请求"), UserMessageUi("latest", "最新请求")),
            history = listOf(summary, AgentModelClient.ConversationMessage("user", "最新请求")),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        val boundary = AgentConversationRevisionReducer.boundary(state, "latest")!!
        assertTrue(boundary.contextWasCompacted)
        assertTrue(boundary.historyPrefix.isEmpty())
        val next = state.copy(messages = state.messages + UserMessageUi("next", "后续请求"),
            history = state.history + AgentModelClient.ConversationMessage("user", "后续请求"))
        assertTrue(AgentConversationRevisionReducer.boundary(next, "next")!!.historyPrefix.contains(summary))
    }

    @Test
    fun boundaryMapsAssistantToItsUserTurnAndKeepsToolTranscriptPrefix() {
        val state = conversationState()

        val boundary = AgentConversationRevisionReducer.boundary(state, "assistant-2")!!

        assertEquals("user-2", boundary.userMessage?.id)
        assertEquals(4, boundary.userMessageIndex)
        assertEquals(1, boundary.laterTurnCount)
        assertFalse(boundary.contextWasCompacted)
        assertEquals(
            listOf("user", "assistant", "tool", "assistant"),
            boundary.historyPrefix.map { it.role },
        )
    }

    @Test
    fun deleteFromMiddleTurnTruncatesMessagesAndHistoryTogether() {
        val state = conversationState().copy(appliedRuntimeRunIds = listOf("run-1", "run-2"))

        val revised = AgentConversationRevisionReducer.deleteFromTurn(state, "assistant-2")!!

        assertEquals(
            listOf("user-1", "thinking-1", "tool-1", "assistant-1"),
            revised.messages.map { it.id },
        )
        assertEquals(listOf("user", "assistant", "tool", "assistant"), revised.history.map { it.role })
        assertEquals(listOf("run-1", "run-2"), revised.appliedRuntimeRunIds)
    }

    @Test
    fun compactedCheckpointAlignsRetainedTurnsFromTheTail() {
        val full = conversationState()
        val compacted = full.copy(
            history = listOf(
                AgentModelClient.ConversationMessage(role = "system", content = "已压缩"),
                AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
                AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
            )
        )

        val missing = AgentConversationRevisionReducer.boundary(compacted, "user-1")!!
        val retained = AgentConversationRevisionReducer.boundary(compacted, "user-2")!!

        assertTrue(missing.contextWasCompacted)
        assertTrue(missing.historyPrefix.isEmpty())
        assertFalse(retained.contextWasCompacted)
        assertEquals(listOf("system"), retained.historyPrefix.map { it.role })
    }

    @Test
    fun visibleMessagesStopAtEditedUserWithoutMutatingTheSource() {
        val messages = conversationState().messages

        val visible = AgentConversationRevisionReducer.visibleMessagesForEdit(messages, "user-2")

        assertEquals(listOf("user-1", "thinking-1", "tool-1", "assistant-1", "user-2"), visible.map { it.id })
        assertEquals(8, messages.size)
    }

    @Test
    fun invalidMessageDoesNotChangeConversation() {
        val state = conversationState()

        assertNull(AgentConversationRevisionReducer.boundary(state, "missing"))
        assertNull(AgentConversationRevisionReducer.deleteFromTurn(state, "missing"))
        assertEquals(
            state.messages,
            AgentConversationRevisionReducer.visibleMessagesForEdit(state.messages, "missing"),
        )
    }

    @Test
    fun monitorEventTurnsCountAsHistoryAnchorsSoEarlierTurnsStayAligned() {
        // 第一轮用户问答 → 一轮由后台监听唤醒的事件轮（历史里是一条 user 系统通知）→ 第二轮用户问答。
        val event = io.github.fartown.movo.ui.model.MonitorEventMessageUi(
            id = "monitor-m1-event-1", taskId = "m1", name = "喝水提醒",
            kind = io.github.fartown.movo.ui.model.MonitorEventKindUi.Event, seq = 1, atMillis = 0L, text = "tick",
            startsTurn = true, historyAnchor = true,
        )
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi(id = "user-1", content = "每 3 分钟提醒我喝水"),
                AgentMessageUi(id = "assistant-1", content = "好的"),
                event,
                AgentMessageUi(id = "assistant-e", content = "该喝水了"),
                UserMessageUi(id = "user-2", content = "谢谢"),
                AgentMessageUi(id = "assistant-2", content = "不客气"),
            ),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "每 3 分钟提醒我喝水"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "好的"),
                AgentModelClient.ConversationMessage(role = "user", content = "[系统通知 - 非用户输入] …"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "该喝水了"),
                AgentModelClient.ConversationMessage(role = "user", content = "谢谢"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "不客气"),
            ),
            input = "", isStreaming = false, thinkingEnabled = false,
        )

        val first = AgentConversationRevisionReducer.boundary(state, "assistant-1")!!
        assertEquals("user-1", first.userMessage?.id)
        assertTrue(first.historyPrefix.isEmpty())
        assertEquals(2, first.laterTurnCount)

        val second = AgentConversationRevisionReducer.boundary(state, "assistant-2")!!
        assertEquals("user-2", second.userMessage?.id)
        assertEquals(4, second.historyPrefix.size)

        // 事件轮没有用户原话：边界存在（可删除），但没有可编辑 / 重新生成的用户消息。
        val eventTurn = AgentConversationRevisionReducer.boundary(state, "assistant-e")!!
        assertNull(eventTurn.userMessage)
        assertEquals(2, eventTurn.historyPrefix.size)
        val deleted = AgentConversationRevisionReducer.deleteFromTurn(state, "assistant-e")!!
        assertEquals(listOf("user-1", "assistant-1"), deleted.messages.map { it.id })
        assertEquals(2, deleted.history.size)
    }

    private fun conversationState(): AgentChatUiState = AgentChatUiState(
        messages = listOf(
            UserMessageUi(id = "user-1", content = "第一问"),
            ThinkingMessageUi(id = "thinking-1", content = "思考", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-1",
                toolName = "test",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            AgentMessageUi(id = "assistant-1", content = "第一答"),
            UserMessageUi(id = "user-2", content = "第二问", images = listOf("data:image/png;base64,AA==")),
            AgentMessageUi(id = "assistant-2", content = "第二答"),
            UserMessageUi(id = "user-3", content = "第三问"),
            AgentMessageUi(id = "assistant-3", content = "第三答"),
        ),
        history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "第一问"),
            AgentModelClient.ConversationMessage(role = "assistant", toolCallsJson = "[]"),
            AgentModelClient.ConversationMessage(role = "tool", content = "结果"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第一答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
        ),
        input = "草稿",
        isStreaming = false,
        thinkingEnabled = false,
    )
}
