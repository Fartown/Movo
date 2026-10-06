package io.github.fartown.movo.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** 常驻通知只有一个按钮（规范 8.12「22」）：平时「结束任务」，结束后等待撤销期满时「撤销」。 */
class ExecutionNotificationContentTest {
    @Test
    fun everyRunningModeOffersTheSingleEndTaskAction() {
        val tasks = ExecutionNotificationContent.of(taskCount = 1, monitorCount = 0)
        val monitors = ExecutionNotificationContent.of(taskCount = 0, monitorCount = 2)
        val mixed = ExecutionNotificationContent.of(taskCount = 1, monitorCount = 1, endingCount = 1)
        assertEquals(ExecutionNotificationContent.Mode.TASKS_ONLY, tasks.mode)
        assertEquals(ExecutionNotificationContent.Mode.MONITORS_ONLY, monitors.mode)
        assertEquals(ExecutionNotificationContent.Mode.MIXED, mixed.mode)
        listOf(tasks, monitors, mixed).forEach { assertEquals(ExecutionNotificationContent.Action.END_TASK, it.action) }
    }

    @Test
    fun onlyEndingMonitorsLeftShowsEndedWithUndo() {
        val ended = ExecutionNotificationContent.of(taskCount = 0, monitorCount = 0, endingCount = 1)
        assertEquals(ExecutionNotificationContent.Mode.ENDED, ended.mode)
        assertEquals(ExecutionNotificationContent.Action.UNDO, ended.action)
        // 还有别的监听在跑：仍是监听中，按钮仍是「结束任务」。
        val stillMonitoring = ExecutionNotificationContent.of(taskCount = 0, monitorCount = 1, endingCount = 1)
        assertEquals(ExecutionNotificationContent.Mode.MONITORS_ONLY, stillMonitoring.mode)
        assertEquals(ExecutionNotificationContent.Action.END_TASK, stillMonitoring.action)
    }
}
