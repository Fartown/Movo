package io.github.fartown.movo.ui.app

import io.github.fartown.movo.agent.model.AgentModelClient.ConversationMessage
import io.github.fartown.movo.agent.runtime.AgentRuntimeWire
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentChatUiState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.MonitorEventKindUi
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.model.SuggestionChipsMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 后台监听的事件行与模型历史对齐：运行中读到的事件、事件轮、补充数、重新生成与撤回。 */
class MonitorHistoryAlignmentTest {

    private fun eventRow(id: String, startsTurn: Boolean, anchor: Boolean, kind: MonitorEventKindUi = MonitorEventKindUi.Event) =
        MonitorEventMessageUi(
            id = id, taskId = "m1", name = "喝水提醒", kind = kind, seq = 1, atMillis = 0L, text = "tick",
            startsTurn = startsTurn, historyAnchor = anchor,
        )

    @Test
    fun eventsReadMidRunAlignWithTheirTranscriptEntryForDeleteAndRegenerate() {
        // 第一轮：用户提问 → 工具 → 运行中读到一批两条监听事件（第一条是锚点）→ 回答；第二轮普通问答。
        val beforeRun = AgentChatUiState(
            messages = listOf(
                UserMessageUi("user-run-1", "看一下电量"),
                eventRow("monitor-m1-event-1", startsTurn = false, anchor = true),
                eventRow("monitor-m1-event-2", startsTurn = false, anchor = false),
                AgentMessageUi("assistant-run-1-2", "电量 80%，另外该喝水了"),
            ),
            history = listOf(ConversationMessage("user", "看一下电量", messageId = "user-run-1")),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        val transcript = listOf(
            ConversationMessage("assistant", toolCallsJson = "[]", messageId = "assistant-run-1-1"),
            ConversationMessage("tool", "80%"),
            ConversationMessage("user", "[系统通知 - 非用户输入] …", messageId = "monitor-run-1-1"),
            ConversationMessage("assistant", "电量 80%，另外该喝水了", messageId = "assistant-run-1-2"),
        )
        val afterFirst = AgentRuntimeHistoryReducer.apply(beforeRun, "run-1", transcript).state
        val state = afterFirst.copy(
            messages = afterFirst.messages + UserMessageUi("user-run-2", "谢谢") + AgentMessageUi("assistant-run-2-1", "不客气"),
            history = afterFirst.history + ConversationMessage("user", "谢谢", messageId = "user-run-2") +
                ConversationMessage("assistant", "不客气"),
            journal = emptyList(),
        )

        // 删第二轮：历史前缀正好是第一轮的全部（含读到的事件）。
        val second = AgentConversationRevisionReducer.boundary(state, "assistant-run-2-1")!!
        assertEquals("user-run-2", second.userMessage?.id)
        assertEquals(listOf("user-run-1", "assistant-run-1-1", "", "monitor-run-1-1", "assistant-run-1-2"), second.historyPrefix.map { it.messageId })

        // 从第一轮的回答删：最近的锚点是事件行，界面与历史都裁到事件之前。
        val fromEvent = AgentConversationRevisionReducer.deleteFromTurn(state, "assistant-run-1-2")!!
        assertEquals(listOf("user-run-1"), fromEvent.messages.map { it.id })
        assertEquals(listOf("user-run-1", "assistant-run-1-1", ""), fromEvent.history.map { it.messageId })

        // 重新生成第一轮：回到用户原话，跳过运行中读到的事件行。
        val regenerate = AgentConversationRevisionReducer.regenerationBoundary(state, "assistant-run-1-2")!!
        assertEquals("user-run-1", regenerate.userMessage?.id)
        assertEquals(0, regenerate.userMessageIndex)
        assertTrue(regenerate.historyPrefix.isEmpty())
        assertTrue(AgentConversationRevisionReducer.nonRegenerableMessageIds(state.messages).isEmpty())
    }

    @Test
    fun failedRunKeepsUnconsumedSupplementsEvenWhenEventsWereRead() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("user-run", "开始"),
                UserMessageUi("user-run-supplement-1", "补充一"),
                eventRow("monitor-m1-event-1", startsTurn = false, anchor = true),
                UserMessageUi("user-run-supplement-2", "补充二（还没被读到）"),
            ),
            history = listOf(ConversationMessage("user", "开始", messageId = "user-run")),
            input = "", isStreaming = true, thinkingEnabled = false,
        )
        val transcript = listOf(
            ConversationMessage("user", "用户补充指令：补充一", messageId = "user-run-supplement-1"),
            ConversationMessage("user", "[系统通知 - 非用户输入] …", messageId = "monitor-run-1"),
        )

        // 失败（保留没被消费的补充）：读到的事件不能算成「已消费的补充」，补充二要留在历史里。
        val result = AgentRuntimeHistoryReducer.apply(state, "run", transcript, retainPendingSupplements = true).state

        assertEquals(
            listOf("user-run", "user-run-supplement-1", "monitor-run-1", "user-run-supplement-2"),
            result.history.map { it.messageId },
        )
    }

    @Test
    fun eventTurnsHaveNoRegenerateButUserTurnsWithEventsDo() {
        val messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi("user-1", "每 3 分钟提醒我喝水"),
            AgentMessageUi("assistant-1", "好的"),
            eventRow("monitor-m1-event-1", startsTurn = true, anchor = true),
            AgentMessageUi("assistant-e", "该喝水了"),
            SystemNoticeMessageUi("assistant-e-failed", SystemNoticeCode.RuntimeFailed, "网络错误"),
            UserMessageUi("user-2", "顺便看看电量"),
            eventRow("monitor-m1-event-2", startsTurn = false, anchor = true),
            AgentMessageUi("assistant-2", "80%"),
            // 「已停止监听」不唤醒 Movo、不是一轮的起点：后面接的仍是上一轮。
            eventRow("monitor-m1-ended-2", startsTurn = false, anchor = false, kind = MonitorEventKindUi.Ended),
        )
        val state = AgentChatUiState(messages, input = "", isStreaming = false, thinkingEnabled = false)

        assertEquals(setOf("assistant-e", "assistant-e-failed"), AgentConversationRevisionReducer.nonRegenerableMessageIds(messages))
        assertNull(AgentConversationRevisionReducer.regenerationBoundary(state, "assistant-e"))
        assertNull(AgentConversationRevisionReducer.regenerationBoundary(state, "assistant-e-failed"))
        assertEquals("user-2", AgentConversationRevisionReducer.regenerationBoundary(state, "assistant-2")?.userMessage?.id)
        assertEquals("user-2", AgentConversationRevisionReducer.regenerationBoundary(state, "monitor-m1-ended-2")?.userMessage?.id)
    }

    @Test
    fun eventTurnRollsBackOnlyWhenItNeverReallyStarted() {
        fun failed(error: String, kind: String = "terminal", transcript: List<ConversationMessage> = emptyList()) =
            AgentRuntimeWire.RunResult("run-e", false, "", error, transcript = transcript, resultKind = kind)

        // 运行时忙（BUSY）、准备时被新任务替换、被别处顶掉 / 为用户运行让路：撤回。
        assertTrue(MonitorTurnRollback.shouldRollBack(failed("当前任务仍在执行，请等完成后再说", "rejected"), false, false))
        assertTrue(MonitorTurnRollback.shouldRollBack(failed(MonitorTurnRollback.RUN_REPLACED_ERROR), false, false))
        assertTrue(MonitorTurnRollback.shouldRollBack(failed("已停止"), madeProgress = false, stoppedByUser = false))
        // 用户自己点的停止、已经开始干活、已有 transcript、成功、结果待恢复、真正的失败：不撤回。
        assertFalse(MonitorTurnRollback.shouldRollBack(failed("已停止"), madeProgress = false, stoppedByUser = true))
        assertFalse(MonitorTurnRollback.shouldRollBack(failed("已停止"), madeProgress = true, stoppedByUser = false))
        assertFalse(MonitorTurnRollback.shouldRollBack(failed("已停止", transcript = listOf(ConversationMessage("assistant", "x"))), false, false))
        assertFalse(MonitorTurnRollback.shouldRollBack(AgentRuntimeWire.RunResult("run-e", true, "好"), false, false))
        assertFalse(MonitorTurnRollback.shouldRollBack(failed("连接断开", "unconfirmed"), false, false))
        assertFalse(MonitorTurnRollback.shouldRollBack(failed("请先配置模型"), false, false))
    }

    @Test
    fun rollingBackRemovesTheRowsAndPromptAndRestoresStrippedSuggestions() {
        val suggestions = SuggestionChipsMessageUi("suggest-1", listOf("再来一个"))
        val before = listOf(UserMessageUi("user-1", "你好"), AgentMessageUi("assistant-1", "你好呀"), suggestions)
        val row = eventRow("monitor-m1-event-1", startsTurn = true, anchor = true)
        val prompt = ConversationMessage("user", "[系统通知 - 非用户输入] …", messageId = "user-run-e")
        val launched = AgentChatUiState(
            // 发起事件轮时推荐追问被收起、插入事件行。
            messages = AgentFollowUpSuggestions.strip(before) + row,
            history = listOf(ConversationMessage("user", "你好"), ConversationMessage("assistant", "你好呀"), prompt),
            journal = listOf(ConversationMessage("user", "你好"), ConversationMessage("assistant", "你好呀"), prompt),
            input = "", isStreaming = true, thinkingEnabled = false,
        )

        val rolled = MonitorTurnRollback.rollBack(launched, "run-e", setOf(row.id), before)

        assertEquals(before, rolled.messages)
        assertEquals(listOf("你好", "你好呀"), rolled.history.map { it.content })
        assertEquals(listOf("你好", "你好呀"), rolled.journal.map { it.content })
        assertTrue("run-e" in rolled.appliedRuntimeRunIds)
        assertFalse(rolled.isStreaming)
    }
}
