package io.github.fartown.movo.agent.overlay

import io.github.fartown.movo.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentOverlayStateTest {
    private val paused = AgentOverlayState(phase = AgentOverlayPhase.PAUSED, round = 2, status = AgentOverlayStatus.Paused)

    @Test
    fun lateEventsAfterPauseDoNotFlipTheOverlayBackToRunning() {
        val afterTool = paused.applyEvent(AgentEvent.ToolFinished(2, "call", "tap", "ok", 0, 0, true))
        assertEquals(AgentOverlayPhase.PAUSED, afterTool.phase)
        assertEquals(AgentOverlayStatus.Paused, afterTool.status)
        val afterSupplement = afterTool.applyEvent(AgentEvent.UserSupplementReceived(0, "改成大杯"))
        assertEquals(AgentOverlayPhase.PAUSED, afterSupplement.phase)
    }

    @Test
    fun terminalEventsStillEndAPausedRun() {
        assertEquals(AgentOverlayPhase.FAILED, paused.applyEvent(AgentEvent.RunFailed("取消")).phase)
    }

    @Test
    fun runningStateKeepsFollowingEvents() {
        val running = AgentOverlayState.Initial.applyEvent(AgentEvent.ToolStarted(1, "call", "tap", "点击"))
        assertEquals(AgentOverlayPhase.RUNNING, running.phase)
        assertEquals(AgentOverlayStatus.RunningTool("tap"), running.status)
    }

    @Test
    fun orbShowsResultBeforeVoiceAndStandbyHidesEverything() {
        // 规范 8.1：完成 / 失败待查看优先；语音对话中为聆听；待命只有玻璃圆 + 光球。
        assertEquals(OrbMode.FINISHED, orbMode(AgentOverlayPhase.FINISHED, standby = false, listening = true))
        assertEquals(OrbMode.FAILED, orbMode(AgentOverlayPhase.FAILED, standby = false, listening = false))
        assertEquals(OrbMode.STANDBY, orbMode(AgentOverlayPhase.FAILED, standby = false, listening = false, stopped = true))
        assertEquals(OrbMode.LISTENING, orbMode(AgentOverlayPhase.PAUSED, standby = false, listening = true))
        assertEquals(OrbMode.PAUSED, orbMode(AgentOverlayPhase.PAUSED, standby = false, listening = false))
        assertEquals(OrbMode.RUNNING, orbMode(AgentOverlayPhase.RUNNING, standby = false, listening = false))
        assertEquals(OrbMode.STANDBY, orbMode(AgentOverlayPhase.RUNNING, standby = true, listening = true))
    }

    @Test
    fun anIdleTaskWithMonitorsIsMonitoringNotStandbyOrDone() {
        // 规范 8.12「22」：没有在跑的一轮、还有监听在等 = 监听中；叫醒的一轮在跑时照常是执行中。
        assertEquals(OrbMode.MONITORING, orbMode(AgentOverlayPhase.RUNNING, standby = true, listening = false, monitoring = true))
        // 用户停掉了这一轮，别的监听还在：监听中，不是待命。
        assertEquals(
            OrbMode.MONITORING,
            orbMode(AgentOverlayPhase.FAILED, standby = false, listening = false, stopped = true, monitoring = true),
        )
        assertEquals(OrbMode.RUNNING, orbMode(AgentOverlayPhase.RUNNING, standby = false, listening = false, monitoring = true))
        assertEquals(OrbMode.PAUSED, orbMode(AgentOverlayPhase.PAUSED, standby = false, listening = false, monitoring = true))
        // 失败要人看：优先于监听中。
        assertEquals(OrbMode.FAILED, orbMode(AgentOverlayPhase.FAILED, standby = false, listening = false, monitoring = true))
    }
}
