package io.github.fartown.movo.ui.components

import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具前说明进执行卡（定稿 21）：时间线分组、旧记录推导与最终回答判定。文案取自 10-06 方舟真实运行。 */
class WorkNarrationTimelineTest {
    private fun thinking(round: Int) = ThinkingMessageUi(id = "run-thinking-$round-0", content = "想", isStreaming = false)
    private fun text(round: Int, index: Int, content: String, narration: Boolean = false) =
        AgentMessageUi(id = "assistant-run-$round-$index", content = content, isStreaming = false, narration = narration)
    private fun tool(round: Int, call: String) =
        ToolActivityMessageUi(id = "run-tool-$round-$call", toolName = "device_read", status = ToolActivityStatusUi.Success, argumentsSummary = "")

    private fun wifiRun(markNarration: Boolean): List<AgentChatMessageUi> = listOf(
        UserMessageUi("u1", "打开设置看看 Wi‑Fi 连的是哪个网络，然后把蓝牙也打开"),
        thinking(1),
        text(1, 1, "我先查一下网络状态，同时试着打开蓝牙。", markNarration),
        tool(1, "call_a"),
        tool(1, "call_b"),
        thinking(2),
        text(2, 1, "蓝牙已经打开。网络那项没返回，我直接打开设置里的 Wi‑Fi 页面看一下。", markNarration),
        tool(2, "call_c"),
        thinking(3),
        text(3, 1, "Wi‑Fi 连的是 Xiaomi_5G，蓝牙也已经打开了。"),
    )

    @Test
    fun narrationKeepsTheWholeRunInOneCardWithTheFirstThinkingAsFirstStep() {
        val entries = wifiRun(markNarration = true).toTimelineEntries()
        assertEquals(listOf("u1", "work-run-thinking-1-0", "assistant-run-3-1"), entries.map { it.key })
        val steps = (entries[1] as AgentTimelineEntry.WorkProcess).messages.map { it.id }
        assertEquals(
            listOf(
                "run-thinking-1-0", "assistant-run-1-1", "run-tool-1-call_a", "run-tool-1-call_b",
                "run-thinking-2-0", "assistant-run-2-1", "run-tool-2-call_c", "run-thinking-3-0",
            ),
            steps,
        )
        // 卡后面是真正的回答：回答开始时照常收成摘要条。
        assertEquals(setOf("work-run-thinking-1-0"), answeredWorkKeys(entries))
    }

    @Test
    fun legacyRecordsWithoutTheFlagAreGroupedTheSameWay() {
        val legacy = wifiRun(markNarration = false)
        val marked = markLegacyNarration(legacy)
        assertEquals(
            listOf("assistant-run-1-1", "assistant-run-2-1"),
            marked.filter { it is AgentMessageUi && it.narration }.map { it.id },
        )
        assertEquals(wifiRun(markNarration = true).toTimelineEntries(), marked.toTimelineEntries())
    }

    @Test
    fun textAfterAHostedToolInTheSameRoundStaysTheAnswer() {
        val messages = listOf(
            UserMessageUi("u1", "明天北京要带伞吗"),
            text(1, 0, "我搜一下明天北京的天气。"),
            tool(1, "ws_1"),
            text(1, 2, "明天小雨，建议带伞。"),
        )
        val marked = markLegacyNarration(messages)
        assertTrue((marked[1] as AgentMessageUi).narration)
        assertFalse((marked[3] as AgentMessageUi).narration)
        assertEquals(listOf("u1", "work-assistant-run-1-0", "assistant-run-1-2"), marked.toTimelineEntries().map { it.key })
    }

    @Test
    fun runIdsWithDashesAndOtherRunsAreNotConfused() {
        val messages = listOf(
            AgentMessageUi(id = "assistant-a1b2-c3d4-2-1", content = "先看看", isStreaming = false),
            ToolActivityMessageUi(id = "other-run-tool-2-call", toolName = "ui_tap", status = ToolActivityStatusUi.Success, argumentsSummary = ""),
            ToolActivityMessageUi(id = "a1b2-c3d4-tool-3-call", toolName = "ui_tap", status = ToolActivityStatusUi.Success, argumentsSummary = ""),
        )
        // 同一 run 只有第 3 轮有工具、另一 run 的第 2 轮工具不算：这段正文不是说明。
        assertSame(messages, markLegacyNarration(messages))
    }

    @Test
    fun narrationIsNeverTheFinalResult() {
        val messages = wifiRun(markNarration = true)
        assertEquals(setOf("assistant-run-3-1"), resolveFinalResultMessageIds(messages))
        // 说明之后任务失败、没有回答：没有可复制的「最终结果」，说明也不带回答操作。
        assertEquals(emptySet<String>(), resolveFinalResultMessageIds(messages.take(8)))
    }

    @Test
    fun textBeingWrittenAfterTheCardExistsStaysInTheCardUntilTheRunEnds() {
        val streaming = AgentMessageUi(id = "assistant-run-3-1", content = "Wi‑Fi 连的是", isStreaming = true, provisional = true)
        val messages = wifiRun(markNarration = true).dropLast(1) + streaming
        val entries = messages.toTimelineEntries()
        assertEquals(listOf("u1", "work-run-thinking-1-0"), entries.map { it.key })
        assertEquals("assistant-run-3-1", (entries[1] as AgentTimelineEntry.WorkProcess).messages.last().id)
        // 卡后面还没有回答：卡片不按「回答开始」收起，也没有可复制的最终结果。
        assertEquals(emptySet<String>(), answeredWorkKeys(entries))
        assertEquals(emptySet<String>(), resolveFinalResultMessageIds(messages, isStreaming = true))
    }

    @Test
    fun previewOfAnAnswerWithATableShowsCellsNotMarkup() {
        // 10-06 真机 V1b：最后一段在卡里写的时候，3 行预览露出了 `| 项目 | 结果 |`。
        val answer = "三次查询都完成了，分成了独立的调用：\n\n| 项目 | 结果 |\n|---|---|\n| 电量 | 100%，正在充电 |\n| Wi‑Fi | Inspire QA Lab |\n\n- 需要的话我可以再查内存。"
        assertEquals(
            "三次查询都完成了，分成了独立的调用： 项目 · 结果 电量 · 100%，正在充电 Wi‑Fi · Inspire QA Lab 需要的话我可以再查内存。",
            answer.plainPreview(),
        )
    }
}
