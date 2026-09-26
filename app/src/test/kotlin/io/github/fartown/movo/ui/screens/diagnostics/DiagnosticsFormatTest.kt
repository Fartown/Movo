package io.github.fartown.movo.ui.screens.diagnostics

import io.github.fartown.movo.diagnostics.DiagnosticBuffer
import io.github.fartown.movo.diagnostics.DiagnosticContext
import io.github.fartown.movo.diagnostics.DiagnosticLevel
import io.github.fartown.movo.diagnostics.DiagnosticTraceBuilder
import io.github.fartown.movo.diagnostics.TimelineItem
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsFormatTest {
    private val format = DiagnosticsFormat(toolName = { if (it == "get_current_context") "读取当前上下文" else it }, zone = ZoneId.of("Asia/Shanghai"))

    @Test
    fun unitsFollowListAndTimelineConventions() {
        assertEquals("645 毫秒", format.duration(645))
        assertEquals("18.4 秒", format.duration(18_400))
        // 规范 8.10：大于等于 1 秒一律写一位小数的秒，不再换算成「分」。
        assertEquals("1.0 秒", format.duration(1_000))
        assertEquals("125.0 秒", format.duration(125_000))
        assertEquals("420ms", format.compact(420))
        assertEquals("3.7s", format.compact(3_700))
        assertEquals("125.0s", format.compact(125_000))
        assertEquals("+0s", format.offset(10))
        assertEquals("+3.4s", format.offset(3_400))
        assertEquals("+125.0s", format.offset(125_000))
        assertEquals("00:45", format.timer(45_300))
        assertEquals("1.2K", format.tokens(1_200))
    }

    @Test
    fun failedRunExplainsReasonAdviceAndExportsWithoutConversationIds() {
        val buffer = DiagnosticBuffer()
        fun add(time: Long, event: String, run: String = "", request: String = "", level: DiagnosticLevel = DiagnosticLevel.INFO, details: String = "") =
            buffer.append(WALL + time, time, level, "test", event, DiagnosticContext(run, request), details)
        add(0, "run.started", "R1")
        add(1, "run.bound", "R1", details = "wire_run=run-abc\nconversation=c1")
        add(10, "attempt.started", "R1", "Q1", details = "purpose=CHAT\nprovider=openai_responses\nround=2\nattempt=1")
        add(600, "attempt.failed", "R1", "Q1", DiagnosticLevel.ERROR, "code=HTTP_429\nhttp_status=429")
        add(610, "retry.scheduled", "R1", "Q1", details = "delay_ms=2000")
        add(700, "tool.started", "R1", details = "round=2\ntool=get_current_context")
        add(900, "tool.finished", "R1", details = "round=2\ntool=get_current_context\nsuccess=true")
        add(2_610, "attempt.started", "R1", "Q2", details = "purpose=CHAT\nprovider=openai_responses\nround=3\nattempt=2")
        add(3_000, "attempt.failed", "R1", "Q2", DiagnosticLevel.ERROR, "code=HTTP_429\nhttp_status=429")
        add(3_010, "run.ended", "R1", details = "duration_ms=3010")
        val trace = DiagnosticTraceBuilder.build(buffer.snapshot().entries)
        val run = trace.runs.single()

        val explanation = format.explain(run)!!
        assertEquals("模型接口限流（HTTP 429）", explanation.title)
        assertEquals("连续 2 次请求都失败，自动重试已用完。", explanation.detail)
        assertEquals(FailureAction.MODEL_SETTINGS, explanation.action)
        assertEquals("${format.clock(WALL)}·模型接口限流（HTTP 429）", format.listSubtitle(run, 0))
        val first = run.timeline.first() as TimelineItem.Request
        assertEquals("模型请求·第 2 轮", format.requestTitle(first.request))
        assertEquals("+0s·HTTP 429·2.0s 后重试", format.requestSubtitle(first.request, first.offsetMs - 10))
        val retry = run.timeline.last() as TimelineItem.Request
        assertEquals("第 2 轮·第 2 次尝试", format.requestTitle(retry.request))
        assertEquals("失败·第 2 轮模型请求", format.statusLine(run))
        assertEquals("用时 3.0 秒", format.summaryTimer(run, 0))
        assertEquals("${format.dayClock(WALL)} 开始·${format.clock(WALL + 3_010)} 结束·R1", format.summaryMeta(run))
        val tool = run.timeline.filterIsInstance<TimelineItem.Tool>().single()
        assertEquals("工具·读取当前上下文", format.toolTitle(tool.tool))

        // 26 对话中失败卡：原因标题 + 说明与建议一句 + 用时。
        val chat = format.chatFailure(run)!!
        assertEquals("模型接口限流（HTTP 429）", chat.title)
        assertEquals("连续 2 次请求都失败，自动重试已用完。稍后再试，或换一个模型。", chat.message)
        assertEquals("用时 3.0 秒", chat.duration)
        assertEquals(FailureAction.MODEL_SETTINGS, chat.action)

        val markdown = format.exportMarkdown(
            header = ExportHeader("Xiaomi 2312", "Android 15（API 35）", "3.0.9（debug）"),
            runs = trace.runs,
            system = trace.system,
            raw = buffer.snapshot().entries,
            generatedAt = WALL,
            scope = "单个任务 R1",
        )
        assertTrue(markdown.indexOf("- 原因：模型接口限流") < markdown.indexOf("## 原始事件"))
        assertTrue(markdown.contains("- 建议：稍后再试，或换一个模型"))
        assertTrue(markdown.contains("工具·读取当前上下文（get_current_context）"))
        assertTrue(markdown.contains("## R1·失败·第 2 轮模型请求·3.0 秒"))
        assertFalse(markdown.contains(" · "))
        assertFalse(markdown.contains("run-abc"))
        assertFalse(markdown.contains("conversation="))
    }

    @Test
    fun runningFooterOnlyAfterThirtySecondsOfSilence() {
        // 规范 8.10：进行中任务详情的底部栏只在 30 秒没有收到数据时出现。
        assertEquals(null, format.stallHint(null))
        assertEquals(null, format.stallHint(29_999))
        assertEquals("已 38 秒没有收到数据，网络可能不稳定", format.stallHint(38_400))

        val buffer = DiagnosticBuffer()
        buffer.append(WALL, 0, DiagnosticLevel.INFO, "test", "run.started", DiagnosticContext("R2", ""), "")
        buffer.append(WALL + 10, 10, DiagnosticLevel.INFO, "test", "attempt.started", DiagnosticContext("R2", "Q1"), "purpose=CHAT\nprovider=openai_responses\nround=1\nattempt=1")
        val run = DiagnosticTraceBuilder.build(buffer.snapshot().entries).runs.single()
        assertEquals("等待模型回复·第 1 轮", format.statusLine(run))
        assertEquals("${format.clock(WALL)}·等待模型回复·已 45 秒", format.listSubtitle(run, 45_010))
        assertEquals("00:45", format.listValue(run, 45_000))
        // 没卡住时的实时状态写在时间线卡标题右侧；卡住时交给底部栏。
        assertEquals("3 秒前收到过数据", format.liveNote(run, 45_000, 3_200))
        assertEquals("正在收到数据", format.liveNote(run, 45_000, 200))
        assertEquals(null, format.liveNote(run, 45_000, 30_000))
        assertEquals(null, format.chatFailure(run))
    }

    private companion object {
        const val WALL = 1_790_000_000_000L
    }
}
