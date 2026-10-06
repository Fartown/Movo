package io.github.fartown.movo.agent.overlay

import io.github.fartown.movo.agent.overlay.OverlayLifecyclePolicy.Finish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 悬浮层生命周期规则（2026-10-05 审查：光晕常亮、监听轮次吞掉待查看的结果、常驻关时服务不停）。 */
class OverlayLifecyclePolicyTest {
    @Test
    fun aRunThatOperatedAnotherAppKeepsItsResultOnTheOrb() {
        var standbyRequested = false
        val finish = OverlayLifecyclePolicy.finish(
            executedForegroundTool = true,
            resultConversation = false,
            standbyOrbPresent = true,
            keepStandbyOrb = { standbyRequested = true; true },
        )
        assertEquals(Finish.SHOW_RESULT, finish)
        assertFalse("standby orb is only built when the result is not shown", standbyRequested)
        assertEquals(Finish.SHOW_RESULT, OverlayLifecyclePolicy.finish(false, true, false) { false })
    }

    @Test
    fun aRevealedButNeverStartedToolReturnsToStandbyInsteadOfLeavingTheGlowRunning() {
        // 流式阶段已按前台工具揭开浮层（球已存在、不在待命），工具却没开始就被停止 / 中断 / 改成纯文本回答。
        assertEquals(
            Finish.RETIRE_TO_STANDBY,
            OverlayLifecyclePolicy.finish(
                executedForegroundTool = false,
                resultConversation = false,
                standbyOrbPresent = false,
                keepStandbyOrb = { true },
            ),
        )
        // 已在待命的常驻球：同样回到待命（同一条收尾路径，撤光晕、收展开卡）。
        assertEquals(Finish.RETIRE_TO_STANDBY, OverlayLifecyclePolicy.finish(false, false, true) { error("not evaluated") })
        assertEquals(Finish.STOP, OverlayLifecyclePolicy.finish(false, false, false) { false })
    }

    @Test
    fun onlyMonitorRunsKeepAnUnviewedResult() {
        assertTrue(OverlayLifecyclePolicy.keepsPendingResult(monitorOrigin = true, fromResultCard = false, resultPending = true))
        assertFalse(OverlayLifecyclePolicy.keepsPendingResult(monitorOrigin = false, fromResultCard = false, resultPending = true))
        assertFalse(OverlayLifecyclePolicy.keepsPendingResult(monitorOrigin = true, fromResultCard = true, resultPending = true))
        assertFalse(OverlayLifecyclePolicy.keepsPendingResult(monitorOrigin = true, fromResultCard = false, resultPending = false))
    }

    @Test
    fun aResultIsPendingOnlyWhileTheOrbShowsCheckOrFailureForNoRunningTask() {
        fun pending(
            orb: Boolean = true,
            standby: Boolean = false,
            running: Boolean = false,
            target: Boolean = true,
            phase: AgentOverlayPhase = AgentOverlayPhase.FINISHED,
        ) = OverlayLifecyclePolicy.resultPending(orb, standby, running, target, phase)

        assertTrue(pending())
        assertTrue(pending(phase = AgentOverlayPhase.FAILED))
        assertFalse(pending(orb = false))
        assertFalse(pending(standby = true))
        assertFalse(pending(running = true))
        assertFalse(pending(target = false))
        assertFalse(pending(phase = AgentOverlayPhase.RUNNING))
    }

    @Test
    fun withKeepOrbOffTheServiceStopsOnceNothingIsLeftToView() {
        fun viewable(now: Long, until: Long = 1_000L, panelOpen: Boolean = false, standby: Boolean = false) =
            OverlayLifecyclePolicy.resultViewable(
                orbPresent = true,
                standby = standby,
                phase = AgentOverlayPhase.FINISHED,
                nowUptimeMillis = now,
                resultVisibleUntilUptimeMillis = until,
                panelOpen = panelOpen,
            )

        assertTrue("✓ is held for a moment", viewable(now = 500L))
        assertFalse(viewable(now = 1_000L))
        assertTrue("failure reason panel still open", viewable(now = 2_000L, panelOpen = true))
        assertFalse("viewed result went back to standby", viewable(now = 500L, standby = true))

        assertTrue(OverlayLifecyclePolicy.overlayUnneeded(false, runActive = false, preparingRun = false, openingResult = false, resultViewable = false))
        assertFalse(OverlayLifecyclePolicy.overlayUnneeded(true, runActive = false, preparingRun = false, openingResult = false, resultViewable = false))
        assertFalse(OverlayLifecyclePolicy.overlayUnneeded(false, runActive = true, preparingRun = false, openingResult = false, resultViewable = false))
        assertFalse(OverlayLifecyclePolicy.overlayUnneeded(false, runActive = false, preparingRun = true, openingResult = false, resultViewable = false))
        assertFalse(OverlayLifecyclePolicy.overlayUnneeded(false, runActive = false, preparingRun = false, openingResult = true, resultViewable = false))
        assertFalse(OverlayLifecyclePolicy.overlayUnneeded(false, runActive = false, preparingRun = false, openingResult = false, resultViewable = true))
    }

    /**
     * 真机反馈：不操作其他 App 的任务（查东西、跑命令）在跑时，用户在别的 App 里看到的常驻悬浮球没有状态、完成也没有 ✓。
     * 悬浮球显示过这一轮、结束时用户不在 Movo 里：保持结果待查看；用户在 Movo 里看到了结果才回到待命。
     */
    @Test
    fun aRunShownOnTheOrbKeepsItsResultWhenTheUserIsOutsideMovo() {
        assertEquals(
            Finish.SHOW_RESULT,
            OverlayLifecyclePolicy.finish(
                executedForegroundTool = false,
                resultConversation = false,
                standbyOrbPresent = false,
                resultAwaitedOnOrb = true,
            ) { error("not evaluated") },
        )
        assertEquals(
            Finish.RETIRE_TO_STANDBY,
            OverlayLifecyclePolicy.finish(
                executedForegroundTool = false,
                resultConversation = false,
                standbyOrbPresent = false,
                resultAwaitedOnOrb = false,
            ) { true },
        )
    }
}
