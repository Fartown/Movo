package io.github.fartown.movo.ui.components

import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.SuggestionChipsMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 定稿 24：正文一律在卡外、执行中只追加；一轮结束后最终回答上方的过程跟着摘要条收起。文案取自 10-06/07 方舟真实运行。 */
class TurnFoldTimelineTest {
    private fun thinking(round: Int, seconds: Int = 2, streaming: Boolean = false) =
        ThinkingMessageUi(id = "run-thinking-$round-0", content = "想", isStreaming = streaming, elapsedSeconds = seconds)
    private fun text(round: Int, content: String, streaming: Boolean = false, narration: Boolean = false) =
        AgentMessageUi(id = "assistant-run-$round-1", content = content, isStreaming = streaming, narration = narration)
    private fun tool(round: Int, start: Long = 1_000L, end: Long? = 1_300L) = ToolActivityMessageUi(
        id = "run-tool-$round-c$round", toolName = "device_read",
        status = if (end == null) ToolActivityStatusUi.Running else ToolActivityStatusUi.Success,
        argumentsSummary = "", startedAtMillis = start, finishedAtMillis = end,
    )
    private val user = UserMessageUi("u1", "查一下电量和存储空间，分两次查", runStartedAtMillis = 500L, runFinishedAtMillis = 18_500L)

    /** 查电量和存储、分两次查：思考 → 说一句 → 工具 → 思考 → 说一句 → 工具 → 思考 → 回答。 */
    private fun batteryAndStorage(answerStreaming: Boolean = false) = listOf(
        user,
        thinking(1),
        text(1, "我先查一下当前电量。"),
        tool(1),
        thinking(2, 1),
        text(2, "电量是 100%，正在充电。接下来查存储。"),
        tool(2, 2_000L, 2_400L),
        thinking(3, 1),
        text(3, "两项都查好了：电量 100%，存储可用约 201 GB。", streaming = answerStreaming),
    )

    @Test
    fun whileRunningTextsStayOutsideCardsAndNothingIsFolded() {
        val entries = batteryAndStorage(answerStreaming = true).toTimelineEntries(isStreaming = true)
        assertEquals(
            listOf(
                "u1", "work-run-thinking-1-0", "assistant-run-1-1", "work-run-tool-1-c1",
                "assistant-run-2-1", "work-run-tool-2-c2", "assistant-run-3-1",
            ),
            entries.map { it.key },
        )
        // 思考与工具连成一段：第二段是「工具 1 + 思考 2」。
        assertEquals(listOf("run-tool-1-c1", "run-thinking-2-0"), (entries[3] as AgentTimelineEntry.WorkProcess).messages.map { it.id })
        assertTrue(entries.none { it.foldGroup != null || it is AgentTimelineEntry.TurnSummary })
    }

    @Test
    fun finishedTurnFoldsEverythingAboveTheAnswerUnderOneSummary() {
        val entries = batteryAndStorage().toTimelineEntries(isStreaming = false)
        assertEquals("summary-u1", entries[1].key)
        val summary = entries[1] as AgentTimelineEntry.TurnSummary
        assertEquals(2, summary.toolCount)
        assertEquals(WorkTurnSpan(500L, 18_500L), summary.span)
        val process = entries.subList(2, entries.lastIndex)
        assertEquals(
            listOf("work-run-thinking-1-0", "assistant-run-1-1", "work-run-tool-1-c1", "assistant-run-2-1", "work-run-tool-2-c2"),
            process.map { it.key },
        )
        assertTrue(process.all { it.foldGroup == "summary-u1" })
        // 最终回答在摘要条外面、不收。
        assertEquals("assistant-run-3-1", entries.last().key)
        assertNull(entries.last().foldGroup)
    }

    @Test
    fun foldingKeepsEveryKeyItHadWhileRunning() {
        val running = batteryAndStorage(answerStreaming = true).toTimelineEntries(isStreaming = true).map { it.key }
        val finished = batteryAndStorage().toTimelineEntries(isStreaming = false).map { it.key }
        // 收起不删不搬：执行时的条目一个不少、顺序不变，只多一条摘要条。
        assertEquals(running, finished.filterNot { it == "summary-u1" })
    }

    @Test
    fun plainAnswerIsATurnLikeAnyOtherThinkingCardFolds() {
        val entries = listOf(user, thinking(1, 3), text(1, "杭州是浙江省的省会。")).toTimelineEntries()
        assertEquals(listOf("u1", "summary-u1", "work-run-thinking-1-0", "assistant-run-1-1"), entries.map { it.key })
        val summary = entries[1] as AgentTimelineEntry.TurnSummary
        assertEquals(0, summary.toolCount)
        assertTrue(summary.hasThinking)
        assertEquals(3, summary.thinkingSeconds)
    }

    @Test
    fun answerWithoutAnyProcessHasNoSummary() {
        val entries = listOf(user, text(1, "好的。")).toTimelineEntries()
        assertEquals(listOf("u1", "assistant-run-1-1"), entries.map { it.key })
    }

    /** 停止、失败、最后停在卡上的轮次同样合并（用户原话：最终结束的时候把历史全部折叠，只留最后一段正文）。 */
    @Test
    fun stoppedFailedOrUnansweredTurnsFoldToo() {
        val stopped = listOf(user, thinking(1), tool(1), SystemNoticeMessageUi("stopped-run", SystemNoticeCode.Stopped, "已停止"))
        stopped.toTimelineEntries().let { entries ->
            val summary = entries[1] as AgentTimelineEntry.TurnSummary
            assertEquals(WorkOutcome.Kind.Stopped, summary.ending)
            // 没有正文：卡收起，「已停止」留在外面，收起时钉住它。
            assertEquals("stopped-run", summary.anchorKey)
            assertEquals(listOf("stopped-run"), entries.drop(2).filter { it.foldGroup == null }.map { it.key })
        }
        val failed = listOf(user, thinking(1), text(1, "我先查一下。"), tool(1), SystemNoticeMessageUi("failed-run", SystemNoticeCode.RuntimeFailed, "运行失败"))
        failed.toTimelineEntries().let { entries ->
            val summary = entries[1] as AgentTimelineEntry.TurnSummary
            assertEquals(WorkOutcome.Kind.Unfinished, summary.ending)
            assertEquals(listOf("assistant-run-1-1", "failed-run"), entries.drop(2).filter { it.foldGroup == null }.map { it.key })
        }
        val endsOnCard = listOf(user, thinking(1), text(1, "我先查一下。"), tool(1))
        endsOnCard.toTimelineEntries().let { entries ->
            val summary = entries[1] as AgentTimelineEntry.TurnSummary
            assertNull(summary.ending)
            assertEquals(listOf("assistant-run-1-1"), entries.drop(2).filter { it.foldGroup == null }.map { it.key })
        }
    }

    /** 真机 10-07：两段之间说了一句话后停止——过程全部收起，只留最后说的那句话和「已停止」。 */
    @Test
    fun stoppedAfterTwoSegmentsKeepsOnlyTheLastTextAndTheStopNotice() {
        val stopped = listOf(
            user, thinking(1), text(1, "我先查一下当前电量。", narration = true), tool(1),
            text(2, "电量已读到：100%，正在充电。接着我查一下存储空间。", narration = true), tool(2, 2_000L, 2_400L),
            SystemNoticeMessageUi("assistant-run-1", SystemNoticeCode.Stopped, "已停止"),
        )
        val entries = stopped.toTimelineEntries()
        assertEquals(
            listOf("u1", "summary-u1", "work-run-thinking-1-0", "assistant-run-1-1", "work-run-tool-1-c1", "assistant-run-2-1", "work-run-tool-2-c2", "assistant-run-1"),
            entries.map { it.key },
        )
        assertEquals(listOf("u1", "summary-u1", "assistant-run-2-1", "assistant-run-1"), entries.filter { it.foldGroup == null }.map { it.key })
        assertEquals("assistant-run-2-1", (entries[1] as AgentTimelineEntry.TurnSummary).anchorKey)
        // 留下的那句话带复制、重试（停下的任务也能重新跑，main 原有设计）。
        assertEquals(setOf("assistant-run-2-1"), resolveFinalResultMessageIds(stopped))
    }

    @Test
    fun suggestionsAfterTheAnswerDoNotStopTheFold() {
        val entries = (batteryAndStorage() + SuggestionChipsMessageUi("chips", listOf("再查内存"))).toTimelineEntries()
        assertEquals("summary-u1", entries[1].key)
        assertEquals(listOf("assistant-run-3-1", "chips"), entries.takeLast(2).map { it.key })
        assertTrue(entries.takeLast(2).all { it.foldGroup == null })
    }

    @Test
    fun onlyTheLastTurnWaitsWhileStreaming() {
        val second = UserMessageUi("u2", "再查一下内存")
        val messages = batteryAndStorage() + listOf(second, AgentMessageUi("assistant-run2-1-1", "我看一下", isStreaming = true))
        val entries = messages.toTimelineEntries(isStreaming = true)
        assertTrue(entries.any { it.key == "summary-u1" })
        assertTrue(entries.none { it.key == "summary-u2" })
    }

    @Test
    fun legacyNarrationRecordsAreBodyTextAndFoldLikeNewOnes() {
        val legacy: List<AgentChatMessageUi> = batteryAndStorage().map { message ->
            if (message is AgentMessageUi && message.id != "assistant-run-3-1") message.copy(narration = true) else message
        }
        assertEquals(batteryAndStorage().toTimelineEntries(), legacy.toTimelineEntries().map { entry ->
            if (entry is AgentTimelineEntry.Message) entry.copy(message = (entry.message as? AgentMessageUi)?.copy(narration = false) ?: entry.message) else entry
        })
    }

    @Test
    fun finalResultIsTheLastTextOfEachFinishedTurn() {
        assertEquals(setOf("assistant-run-3-1"), resolveFinalResultMessageIds(batteryAndStorage()))
        assertEquals(emptySet<String>(), resolveFinalResultMessageIds(batteryAndStorage(answerStreaming = true), isStreaming = true))
    }

    /** 在底部看着结束：照「回答完成」收尾完才收起；收起之后再次进入收尾也不展开（真机 10-07 第 3 轮 N1）。 */
    @Test
    fun aTurnWaitsForItsSettleOnlyOnceBeforeFolding() {
        val hold = TurnEndHold(streaming = false)
        assertTrue(hold.live(isStreaming = true, isSettling = true, isAnchored = true))
        assertTrue(hold.live(isStreaming = false, isSettling = true, isAnchored = true))
        assertFalse(hold.live(isStreaming = false, isSettling = false, isAnchored = true))
        assertFalse(hold.live(isStreaming = false, isSettling = true, isAnchored = true))
        // 下一轮运行照常。
        assertTrue(hold.live(isStreaming = true, isSettling = false, isAnchored = true))
    }

    /** 往上滑开时结束：先不收（不动用户正在看的内容）；回到底部、收尾完再收，之后不再展开（真机 10-07 第 3c 轮 N5）。 */
    @Test
    fun aTurnEndingWhileScrolledAwayFoldsWhenTheUserComesBack() {
        val hold = TurnEndHold(streaming = true)
        assertTrue(hold.live(isStreaming = true, isSettling = false, isAnchored = false))
        assertTrue(hold.live(isStreaming = false, isSettling = false, isAnchored = false))
        // 回到底部，回答还在补显现：等它。
        assertTrue(hold.live(isStreaming = false, isSettling = true, isAnchored = true))
        assertFalse(hold.live(isStreaming = false, isSettling = false, isAnchored = true))
        // 收起后再滑开、再回来：不展开。
        assertFalse(hold.live(isStreaming = false, isSettling = false, isAnchored = false))
        assertFalse(hold.live(isStreaming = false, isSettling = true, isAnchored = true))
    }

    /** 载入历史：不在运行，直接是收起后的样子（不管在不在底部）。 */
    @Test
    fun historyIsNeverHeld() {
        assertFalse(TurnEndHold(streaming = false).live(isStreaming = false, isSettling = false, isAnchored = false))
    }
}
