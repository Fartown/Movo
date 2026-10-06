package io.github.fartown.movo.agent.overlay

import io.github.fartown.movo.agent.overlay.InteractionCardCoordinator.Placement
import io.github.fartown.movo.agent.tools.interaction.AgentInteractionBroker
import io.github.fartown.movo.agent.tools.interaction.AgentInteractionRegistry
import io.github.fartown.movo.agent.tools.interaction.InteractionReply
import io.github.fartown.movo.ui.model.AgentInteractionUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批卡 / 提问卡任何时候只在一处显示（2026-10-05 审查）：App 内宿主 resumed 时在 App 内，离开后迁移到悬浮卡；
 * 对话浮层也是宿主；run 结束、被替换时卡片收起；审批卡的悬浮窗不可获焦。
 */
class InteractionCardCoordinatorTest {
    private val hosts = mutableListOf<Any>()

    @After
    fun tearDown() {
        hosts.forEach(InteractionCardCoordinator::unregisterHost)
        InteractionCardCoordinator.clearRun(null)
        InteractionCardCoordinator.setUnlockInProgress(false)
        InteractionCardCoordinator.setFloatingAttached(false)
        AgentInteractionRegistry.unregister("run-1")
    }

    @Test
    fun afterApprovingAFloatingCard_waitsUntilItIsGoneAndTheTargetAppIsActive() {
        // 悬浮卡上点了「继续」：卡窗口还挂着、活动窗口还是 Movo 时不放行；撤下且回到目标应用才放行（真机 P8）。
        InteractionCardCoordinator.publish(card("ix-9"))
        InteractionCardCoordinator.setFloatingAttached(true)
        InteractionCardCoordinator.reply(card("ix-9"), InteractionReply.Approval(approved = true))
        var clock = 0L
        var active = "io.github.fartown.movo"
        val settled = InteractionCardCoordinator.awaitSettled(
            activePackage = { active },
            selfPackage = "io.github.fartown.movo",
            now = { clock },
            sleep = { ms ->
                clock += ms
                if (clock >= 200) InteractionCardCoordinator.setFloatingAttached(false)
                if (clock >= 400) active = "com.android.settings"
            },
        )
        assertTrue(settled)
        assertTrue("等到卡片撤下、回到目标应用才放行", clock >= 400)

        // 一直撤不下来：到时限放行，不卡住任务。
        InteractionCardCoordinator.setFloatingAttached(true)
        clock = 0L
        assertFalse(
            InteractionCardCoordinator.awaitSettled(
                activePackage = { "com.android.settings" },
                selfPackage = "io.github.fartown.movo",
                timeoutMs = 300,
                now = { clock },
                sleep = { clock += it },
            ),
        )
    }

    @Test
    fun afterApprovingAnInAppCard_doesNotWaitForTheTargetApp() {
        val host = Any().also { hosts += it; InteractionCardCoordinator.registerHost(it) }
        InteractionCardCoordinator.publish(card("ix-10"))
        InteractionCardCoordinator.reply(card("ix-10"), InteractionReply.Approval(approved = true))
        var slept = 0L
        assertTrue(
            InteractionCardCoordinator.awaitSettled(
                activePackage = { "io.github.fartown.movo" },
                selfPackage = "io.github.fartown.movo",
                sleep = { slept += it },
            ),
        )
        assertEquals("App 内的卡不用等活动窗口换走", 0L, slept)
        InteractionCardCoordinator.unregisterHost(host)
    }

    @Test
    fun placementIsExactlyOneSurfaceWhileACardIsPending() {
        assertEquals(Placement.NONE, InteractionCardCoordinator.placement(hasPending = false, hasInAppHost = true))
        assertEquals(Placement.IN_APP, InteractionCardCoordinator.placement(hasPending = true, hasInAppHost = true))
        assertEquals(Placement.FLOATING, InteractionCardCoordinator.placement(hasPending = true, hasInAppHost = false))
    }

    @Test
    fun cardMigratesBetweenAppAndFloatingWhenTheHostResumesOrPauses() {
        InteractionCardCoordinator.publish(card("ix-1"))
        // 服务在主界面 resume 之后才建也不漏判：宿主状态是进程级的。
        assertEquals(Placement.FLOATING, InteractionCardCoordinator.currentPlacement)

        val main = host()
        InteractionCardCoordinator.registerHost(main)
        assertEquals(Placement.IN_APP, InteractionCardCoordinator.currentPlacement)
        assertSame(main, InteractionCardCoordinator.activeHost.value)

        InteractionCardCoordinator.unregisterHost(main)
        assertEquals(Placement.FLOATING, InteractionCardCoordinator.currentPlacement)
        assertEquals("the same request moves, it is not duplicated", "ix-1", InteractionCardCoordinator.pending.value?.requestId)
    }

    @Test
    fun theMostRecentlyResumedHostIsTheOnlyOneThatShowsTheCard() {
        val main = host()
        val sheet = host()
        InteractionCardCoordinator.registerHost(main)
        InteractionCardCoordinator.registerHost(sheet)
        assertSame(sheet, InteractionCardCoordinator.activeHost.value)

        InteractionCardCoordinator.unregisterHost(sheet)
        assertSame(main, InteractionCardCoordinator.activeHost.value)

        InteractionCardCoordinator.registerHost(sheet)
        InteractionCardCoordinator.registerHost(main)
        assertSame("re-registering moves the host to the front", main, InteractionCardCoordinator.activeHost.value)
    }

    @Test
    fun resolvedOrFinishedRunsDoNotLeaveACardBehind() {
        InteractionCardCoordinator.publish(card("ix-1"))
        InteractionCardCoordinator.resolve("ix-other")
        assertEquals("ix-1", InteractionCardCoordinator.pending.value?.requestId)
        InteractionCardCoordinator.resolve("ix-1")
        assertNull(InteractionCardCoordinator.pending.value)

        InteractionCardCoordinator.publish(card("ix-2", runId = "run-1"))
        InteractionCardCoordinator.clearRun("run-2")
        assertEquals("another run's end keeps this card", "ix-2", InteractionCardCoordinator.pending.value?.requestId)
        InteractionCardCoordinator.clearRun("run-1")
        assertNull(InteractionCardCoordinator.pending.value)

        InteractionCardCoordinator.publish(card("ix-3"))
        InteractionCardCoordinator.setUnlockInProgress(true)
        InteractionCardCoordinator.clearRun(null)
        assertNull(InteractionCardCoordinator.pending.value)
        assertFalse(InteractionCardCoordinator.unlockInProgress.value)
    }

    @Test
    fun replyCollapsesTheCardAndReachesTheWaitingRun() {
        val broker = AgentInteractionBroker()
        AgentInteractionRegistry.register("run-1", broker)
        broker.register("ix-1")
        val model = card("ix-1")
        InteractionCardCoordinator.publish(model)

        assertTrue(InteractionCardCoordinator.reply(model, InteractionReply.Approval(approved = true)))
        assertNull(InteractionCardCoordinator.pending.value)
        assertEquals(
            InteractionReply.Approval(approved = true),
            broker.await("ix-1", timeoutMs = 1_000L, cancelled = { false }),
        )
        assertFalse("only the first reply counts", InteractionCardCoordinator.reply(model, InteractionReply.Cancelled))
    }

    @Test
    fun approvalCardsNeverTakeFocusFromTheTargetWindow() {
        assertFalse(InteractionCardCoordinator.floatingFocusable(card("a", approval = true), locked = false))
        assertTrue(InteractionCardCoordinator.floatingFocusable(card("q", approval = false, freeText = true), locked = false))
        assertFalse(InteractionCardCoordinator.floatingFocusable(card("q", approval = false, freeText = false), locked = false))
        assertFalse("the unlock prompt has no input", InteractionCardCoordinator.floatingFocusable(card("q", approval = false), locked = true))
    }

    private fun host(): Any = Any().also { hosts += it }

    private fun card(
        requestId: String,
        runId: String = "run-1",
        approval: Boolean = true,
        freeText: Boolean = true,
    ) = AgentInteractionUiState(
        runId = runId,
        requestId = requestId,
        isApproval = approval,
        title = "发送消息",
        detail = "",
        allowFreeText = freeText,
    )
}
