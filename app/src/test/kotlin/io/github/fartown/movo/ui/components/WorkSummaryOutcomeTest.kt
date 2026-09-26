package io.github.fartown.movo.ui.components

import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkSummaryOutcomeTest {
    private fun tool(id: String, status: ToolActivityStatusUi) =
        ToolActivityMessageUi(id = id, toolName = "input_text", status = status, argumentsSummary = "")

    private fun work(key: String) = AgentTimelineEntry.WorkProcess(key, listOf(tool("$key-t", ToolActivityStatusUi.Success)))

    private fun message(message: io.github.fartown.movo.ui.model.AgentChatMessageUi) = AgentTimelineEntry.Message(message)

    @Test
    fun recoveredFailureIsNotReportedAsUnfinishedStep() {
        val tools = listOf(tool("a", ToolActivityStatusUi.Failed), tool("b", ToolActivityStatusUi.Success))
        assertEquals(-1, unrecoveredFailedStep(tools))
        assertEquals(2, unrecoveredFailedStep(tools + tool("c", ToolActivityStatusUi.Failed)))
    }

    @Test
    fun workFollowedByFailureCardIsUnfinished() {
        val entries = listOf(
            message(UserMessageUi("u1", "打开设置")),
            work("w1"),
            message(SystemNoticeMessageUi("n1", SystemNoticeCode.RuntimeFailed)),
            message(UserMessageUi("u2", "再试一次")),
            work("w2"),
        )
        assertEquals(mapOf("w1" to WorkOutcome.Unfinished), workOutcomes(entries))
    }

    @Test
    fun failureAfterNextUserMessageDoesNotMarkEarlierWork() {
        val entries = listOf(
            work("w1"),
            message(UserMessageUi("u2", "继续")),
            message(SystemNoticeMessageUi("n1", SystemNoticeCode.Interrupted)),
        )
        assertEquals(emptyMap<String, WorkOutcome>(), workOutcomes(entries))
    }

    @Test
    fun stoppedRunIsStoppedNotFinished() {
        val entries = listOf(work("w1"), message(SystemNoticeMessageUi("n1", SystemNoticeCode.Stopped)))
        assertEquals(mapOf("w1" to WorkOutcome.Stopped), workOutcomes(entries))
    }

    @Test
    fun recoveredRetryKeepsOneWorkCard() {
        val messages = listOf(
            UserMessageUi("u1", "打开设置"),
            tool("t1", ToolActivityStatusUi.Success),
            SystemNoticeMessageUi("r1", SystemNoticeCode.ModelRetry),
            tool("t2", ToolActivityStatusUi.Success),
        )
        val entries = messages.toTimelineEntries()
        assertEquals(2, entries.size)
        assertEquals(listOf("t1", "t2"), (entries[1] as AgentTimelineEntry.WorkProcess).messages.map { it.id })
    }

    @Test
    fun pendingRetryStaysVisibleAfterWorkCard() {
        val messages = listOf(
            tool("t1", ToolActivityStatusUi.Success),
            SystemNoticeMessageUi("r1", SystemNoticeCode.ModelRetry),
            SystemNoticeMessageUi("f1", SystemNoticeCode.RuntimeFailed),
        )
        assertEquals(listOf("work-t1", "r1", "f1"), messages.toTimelineEntries().map { it.key })
    }
}
