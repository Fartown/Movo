package io.github.fartown.movo.agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class AgentResultSheetSizingTest {
    @Test
    fun fractionalSlowDragDoesNotLoseMovementAtEachWindowUpdate() {
        var height = 2117f
        repeat(1000) {
            height = AgentResultSheetSizing.drag(height, -0.45f, 2670, 648)
        }
        assertEquals(2567, height.roundToInt())
    }

    @Test
    fun slowScreenDragAccumulatesAcrossWindowRelayoutsWithKeyboard() {
        val gesture = AgentResultSheetScreenDrag()
        var height = 2117f
        gesture.start(585f)
        for (screenY in 584 downTo 20) {
            gesture.move(screenY.toFloat(), 12f)?.let {
                height = AgentResultSheetSizing.drag(height, it, 2670, 648)
            }
        }
        assertEquals(2670, height.roundToInt())
        assertTrue(gesture.finish())
        assertTrue(AgentResultSheetSizing.settleExpanded(height.roundToInt(), 2670, 0f, 600f, 648))
    }

    @Test
    fun screenDragCanReverseAfterReachingAnAnchor() {
        val gesture = AgentResultSheetScreenDrag()
        gesture.start(585f)
        val full = AgentResultSheetSizing.drag(2117f, gesture.move(0f, 12f)!!, 2670, 648)
        val shrinking = AgentResultSheetSizing.drag(full, gesture.move(200f, 12f)!!, 2670, 648)
        assertEquals(2470, shrinking.roundToInt())
        assertTrue(gesture.finish())
    }

    @Test
    fun tapsAndCancelledPointersCannotResizeOrLeakIntoTheNextGesture() {
        val gesture = AgentResultSheetScreenDrag()
        gesture.start(100f)
        assertEquals(null, gesture.move(103f, 12f))
        assertFalse(gesture.finish())
        assertEquals(null, gesture.move(300f, 12f))
        gesture.start(500f)
        assertEquals(-20f, gesture.move(480f, 12f))
        assertTrue(gesture.finish())
        assertFalse(gesture.finish())
    }

    @Test
    fun upwardDragGrowsTheWindowAndDownwardDragShrinksIt() {
        assertEquals(750, AgentResultSheetSizing.drag(550f, -200f, 1000).roundToInt())
        assertEquals(720, AgentResultSheetSizing.drag(920f, 200f, 1000).roundToInt())
    }

    @Test
    fun draggingReachesFullScreenAndKeepsTheCollapsedComposerVisible() {
        assertEquals(1000, AgentResultSheetSizing.drag(550f, -2000f, 1000).roundToInt())
        assertEquals(550, AgentResultSheetSizing.drag(920f, 2000f, 1000).roundToInt())
    }

    @Test
    fun slowReleaseUsesNearestAnchorAndFlingUsesDirection() {
        assertFalse(AgentResultSheetSizing.settleExpanded(600, 1000, 0f, 600f))
        assertTrue(AgentResultSheetSizing.settleExpanded(850, 1000, 0f, 600f))
        assertTrue(AgentResultSheetSizing.settleExpanded(600, 1000, -1000f, 600f))
        assertFalse(AgentResultSheetSizing.settleExpanded(850, 1000, 1000f, 600f))
    }

    @Test
    fun anchorsAdaptToShortAndTallWindows() {
        assertEquals(440, AgentResultSheetSizing.height(800, false))
        assertEquals(800, AgentResultSheetSizing.height(800, true))
        assertEquals(2000, AgentResultSheetSizing.height(2000, true))
    }

    @Test
    fun keyboardPreservesCollapsedReadableHeightAndHidingItRestoresHalfScreen() {
        val screen = 2670
        val navigation = 153
        val keyboard = 801
        val collapsed = AgentResultSheetSizing.height(screen, false)
        val lifted = AgentResultSheetSizing.height(screen, false, keyboard - navigation)
        assertEquals(collapsed - navigation, lifted - keyboard)
        assertEquals(1469, AgentResultSheetSizing.height(screen, false, 0))
        assertEquals(screen, AgentResultSheetSizing.height(screen, true, keyboard - navigation))
        assertEquals(screen, AgentResultSheetSizing.height(screen, false, screen))
    }

    @Test
    fun keyboardDragAndReleaseUseTheVisibleWindowAnchors() {
        val lift = 300
        assertEquals(850, AgentResultSheetSizing.drag(1000f, 1000f, 1000, lift).roundToInt())
        assertEquals(1000, AgentResultSheetSizing.drag(850f, -1000f, 1000, lift).roundToInt())
        assertFalse(AgentResultSheetSizing.settleExpanded(900, 1000, 0f, 600f, lift))
        assertTrue(AgentResultSheetSizing.settleExpanded(950, 1000, 0f, 600f, lift))
    }

    @Test
    fun dragDownDismissesPastAThirdOrOnAFling() {
        // 规范 8.9：向下拖过 1/3 关闭；快速下甩也关闭；上甩回弹。
        assertFalse(AgentResultSheetSizing.shouldDismiss(300f, 1200, 0f, 1500f))
        assertTrue(AgentResultSheetSizing.shouldDismiss(401f, 1200, 0f, 1500f))
        assertTrue(AgentResultSheetSizing.shouldDismiss(60f, 1200, 1600f, 1500f))
        assertFalse(AgentResultSheetSizing.shouldDismiss(500f, 1200, -1600f, 1500f))
    }
}
