package io.github.fartown.movo.agent.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorEventFormatterTest {

    @Test
    fun eventsAreMarkedAsSystemNoticeAndNotUserReply() {
        val text = MonitorEventFormatter.format(
            listOf(
                MonitorNotice.Event("m1", "c1", "喝水提醒", 0L, seq = 3, text = "tick", suppressedBefore = 0),
                MonitorNotice.Event("m2", "c1", "电量播报", 0L, seq = 1, text = "71%", suppressedBefore = 2),
            ),
        )
        assertTrue(text.startsWith("[系统通知 - 非用户输入]"))
        assertTrue(text.contains("<monitor-event task=\"m1\" name=\"喝水提醒\" seq=\"3\""))
        assertTrue(text.contains("tick"))
        assertTrue(text.contains("此前有 2 批事件被丢弃"))
        assertTrue(text.contains("不是用户的回复"))
        assertTrue(text.contains("notify_user"))
    }

    @Test
    fun endedNoticeExplainsReasonAndKeepsTail() {
        val text = MonitorEventFormatter.format(
            listOf(
                MonitorNotice.Ended(
                    "m1", "c1", "喝水提醒", 0L, MonitorEndReason.TIMEOUT, exitCode = null, tail = null,
                    eventCount = 40, timeoutMs = 2 * 60 * 60_000L, suppressed = 0,
                ),
                MonitorNotice.Ended(
                    "m2", "c1", "下载完成", 0L, MonitorEndReason.EXIT, exitCode = 0, tail = "DONE",
                    eventCount = 1, timeoutMs = 60_000L, suppressed = 0,
                ),
            ),
        )
        assertTrue(text.contains("reason=\"timeout\""))
        assertTrue(text.contains("到了最长时长 2 小时"))
        assertTrue(text.contains("退出码 0"))
        assertTrue(text.contains("DONE"))
    }

    @Test
    fun durationLabels() {
        assertEquals("30 分钟", MonitorEventFormatter.durationLabel(30 * 60_000L))
        assertEquals("2 小时", MonitorEventFormatter.durationLabel(2 * 60 * 60_000L))
        assertEquals("1 小时 30 分钟", MonitorEventFormatter.durationLabel(90 * 60_000L))
    }
}
