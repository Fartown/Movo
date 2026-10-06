package io.github.fartown.movo.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.agent.monitor.MonitorTime
import io.github.fartown.movo.agent.runtime.ExecutionNotificationContent
import io.github.fartown.movo.ui.model.MonitorEventKindUi
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 规范 8.12 的监听行：叠放 / 接执行卡的间距、时刻跟随 12/24 小时与跨天、复数、结束行写退出码，常驻通知按钮。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MonitorRowsTest {
    private fun row(id: String, kind: MonitorEventKindUi = MonitorEventKindUi.Event, reason: String? = null) = MonitorEventMessageUi(
        id = id, taskId = "m1", name = "喝水提醒", kind = kind, seq = 1, atMillis = 0L, text = "", reason = reason,
    )

    @Test
    fun stackedRowsAre4ApartAndARowBeforeTheWorkCardLeaves8() {
        val tool = ToolActivityMessageUi(id = "run-tool-1", toolName = "ui_swipe", status = ToolActivityStatusUi.Success, argumentsSummary = "{}")
        val entries = listOf(
            AgentTimelineEntry.Message(UserMessageUi("u", "每 15 秒下滑一次")),
            AgentTimelineEntry.Message(row("e1")),
            AgentTimelineEntry.Message(row("e2")),
            AgentTimelineEntry.WorkProcess("work-run-tool-1", listOf(tool)),
            AgentTimelineEntry.Message(row("e3")),
            AgentTimelineEntry.WorkProcess("work-thinking", listOf(ThinkingMessageUi("t", "想", isStreaming = false))),
        )
        val spacing = monitorRowSpacings(entries)
        // 第一行默认上下 4；第二行叠在上一行后面（上 0，间距 4），后面接执行卡（下 0，卡自带 8）。
        assertFalse("e1" in spacing)
        assertEquals(MonitorRowSpacing(top = 0.dp, bottom = 0.dp), spacing["e2"])
        // 后面只是「已思考」一行时不算执行卡。
        assertFalse("e3" in spacing)
    }

    @Test
    fun clockFollowsThe12Or24HourSettingAndShowsTheDateOnOtherDays() {
        val utc = TimeZone.getTimeZone("UTC")
        val at = 1_759_676_580_000L // 2025-10-05 15:03 UTC
        val sameDay = at + 60_000L
        val nextDay = at + 24 * 60 * 60_000L
        assertEquals("15:03", MonitorTime.format(at, sameDay, Locale.SIMPLIFIED_CHINESE, use24HourClock = true, timeZone = utc))
        assertEquals("3:03 PM", MonitorTime.format(at, sameDay, Locale.US, use24HourClock = false, timeZone = utc))
        assertEquals("下午3:03", MonitorTime.format(at, sameDay, Locale.SIMPLIFIED_CHINESE, use24HourClock = false, timeZone = utc))
        assertEquals("10月5日 15:03", MonitorTime.format(at, nextDay, Locale.SIMPLIFIED_CHINESE, use24HourClock = true, timeZone = utc))
        assertEquals("Oct 5, 3:03 PM", MonitorTime.format(at, nextDay, Locale.US, use24HourClock = false, timeZone = utc))
    }

    @Test
    fun endRowsWriteExitCodeAndLimitAndEnglishCountsArePlural() {
        val context = localized("en-US")
        val exit = MonitorRowLabels.label(context, row("x", MonitorEventKindUi.Ended, "EXIT").copy(exitCode = 2))
        assertTrue(exit, exit.startsWith("Monitor ended·喝水提醒·exit code 2·"))
        val timeout = MonitorRowLabels.label(context, row("t", MonitorEventKindUi.Ended, "TIMEOUT").copy(limitMs = 45_000L))
        assertEquals("Monitor ended·喝水提醒·reached 45 sec limit", timeout)
        assertEquals(
            "Monitor interrupted·喝水提醒·Movo was closed by the system",
            MonitorRowLabels.label(context, row("i", MonitorEventKindUi.Ended, MonitorRowLabels.INTERRUPTED)),
        )
        assertEquals("Fired 1 time·ends at 5:00 PM", context.resources.getQuantityString(R.plurals.monitor_list_item_detail, 1, 1, "5:00 PM"))
        assertEquals("Fired 3 times·ends at 5:00 PM", context.resources.getQuantityString(R.plurals.monitor_list_item_detail, 3, 3, "5:00 PM"))
        val zh = localized("zh-CN")
        assertEquals("监听结束·喝水提醒·到 2 小时 上限", MonitorRowLabels.label(zh, row("t2", MonitorEventKindUi.Ended, "TIMEOUT").copy(limitMs = 7_200_000L)))
    }

    @Test
    fun ongoingNotificationKeepsMonitorsWhenStoppingTasks() {
        val tasksOnly = ExecutionNotificationContent.of(taskCount = 1, monitorCount = 0)
        assertEquals(ExecutionNotificationContent.Mode.TASKS_ONLY, tasksOnly.mode)
        assertTrue(tasksOnly.showStopTasks)
        assertFalse(tasksOnly.showStopAll)
        val monitorsOnly = ExecutionNotificationContent.of(taskCount = 0, monitorCount = 2)
        assertEquals(ExecutionNotificationContent.Mode.MONITORS_ONLY, monitorsOnly.mode)
        assertFalse(monitorsOnly.showStopTasks)
        assertTrue(monitorsOnly.showStopAll)
        val mixed = ExecutionNotificationContent.of(taskCount = 1, monitorCount = 2)
        assertEquals(ExecutionNotificationContent.Mode.MIXED, mixed.mode)
        assertTrue(mixed.showStopTasks && mixed.showStopAll)
        assertEquals("任务 1 项·后台监听 2 个", localized("zh-CN").getString(R.string.monitor_execution_mixed, 1, 2))
    }

    @Suppress("DEPRECATION")
    private fun localized(tag: String): Context {
        val base = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(configuration)
    }
}
