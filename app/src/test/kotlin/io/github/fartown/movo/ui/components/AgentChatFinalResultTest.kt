package io.github.fartown.movo.ui.components

import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentChatFinalResultTest {

    @Test
    fun onlyLastAgentMessageOfEachTurnIsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "查一下资料"),
            AgentMessageUi(id = "agent-1", content = "我来帮你搜索。"),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-2", content = "从搜索结果可以看到……"),
            toolActivity("tool-2"),
            AgentMessageUi(id = "agent-3", content = "最终答案"),
        )

        assertEquals(setOf("agent-3"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun monitorEventTurnIsItsOwnTurn() {
        val event = io.github.fartown.movo.ui.model.MonitorEventMessageUi(
            id = "monitor-m1-event-1", taskId = "m1", name = "喝水提醒",
            kind = io.github.fartown.movo.ui.model.MonitorEventKindUi.Event, seq = 1, atMillis = 0L, text = "tick",
            startsTurn = true, historyAnchor = true,
        )
        val injected = event.copy(id = "monitor-m1-event-2", seq = 2, startsTurn = false, historyAnchor = true)
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "每 3 分钟提醒我喝水"),
            toolActivity("tool-start"),
            AgentMessageUi(id = "agent-1", content = "好的"),
            event,
            toolActivity("tool-notify"),
            injected,
            AgentMessageUi(id = "agent-2", content = "该喝水了"),
        )
        // 事件轮有自己的最终结果；运行中并入的事件不会把这一轮切开。
        assertEquals(setOf("agent-1", "agent-2"), resolveFinalResultMessageIds(messages))
        val entries = messages.toTimelineEntries()
        val work = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>()
        assertEquals(2, work.size)
        // 两张执行卡各自从第 1 步数起（事件轮是新的一轮）。
        assertEquals(listOf(0, 0), work.map { workStepOffsets(entries).getValue(it.key) })
    }

    @Test
    fun multipleTurnsEachHaveTheirOwnFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "第一问"),
            AgentMessageUi(id = "agent-1", content = "第一答"),
            UserMessageUi(id = "user-2", content = "第二问"),
            AgentMessageUi(id = "agent-2", content = "中间步骤"),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-3", content = "第二答"),
        )

        assertEquals(setOf("agent-1", "agent-3"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun turnInterruptedAfterToolKeepsPreviousAgentMessageAsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            AgentMessageUi(id = "agent-1", content = "我先试试。"),
            toolActivity("tool-1"),
        )

        assertEquals(setOf("agent-1"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun turnWithoutAgentMessageProducesNoFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            toolActivity("tool-1"),
        )

        assertEquals(emptySet<String>(), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun streamingTurnDoesNotMarkIntermediateMessageAsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            AgentMessageUi(id = "agent-1", content = "我来帮你搜索。"),
            toolActivity("tool-1"),
        )

        assertEquals(
            emptySet<String>(),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
    }

    @Test
    fun streamingKeepsFinalResultOfCompletedEarlierTurns() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "第一问"),
            AgentMessageUi(id = "agent-1", content = "第一答"),
            UserMessageUi(id = "user-2", content = "第二问"),
            AgentMessageUi(id = "agent-2", content = "中间步骤"),
            toolActivity("tool-1"),
        )

        assertEquals(
            setOf("agent-1"),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
    }

    @Test
    fun streamingEndRestoresFinalResultOfLastTurn() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            AgentMessageUi(id = "agent-1", content = "中间步骤"),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-2", content = "最终答案"),
        )

        assertEquals(
            emptySet<String>(),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
        assertEquals(
            setOf("agent-2"),
            resolveFinalResultMessageIds(messages, isStreaming = false),
        )
    }

    private fun toolActivity(id: String): AgentChatMessageUi = ToolActivityMessageUi(
        id = id,
        toolName = "browser_use",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "query",
    )
}
