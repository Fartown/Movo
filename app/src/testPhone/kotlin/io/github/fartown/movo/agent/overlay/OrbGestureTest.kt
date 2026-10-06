package io.github.fartown.movo.agent.overlay

import androidx.compose.ui.geometry.Offset
import io.github.fartown.movo.agent.overlay.OrbGesture.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球手势判定（悬浮球与展开卡的球侧通道共用，2026-10-05 审查：展开时盖住隐形真球，不能长按、拖动，失败态点球只会收起）。
 */
class OrbGestureTest {
    private val slop = 8f

    @Test
    fun pressAndReleaseInPlaceIsATap() {
        val gesture = OrbGesture(slop)
        gesture.down(100f, 100f)
        assertTrue(gesture.move(103f, 104f).isEmpty())
        assertEquals(Action.Tap, gesture.up(cancelled = false))
        assertEquals(OrbGesture.State.IDLE, gesture.state)
    }

    @Test
    fun cancelledPressIsNotATap() {
        val gesture = OrbGesture(slop)
        gesture.down(100f, 100f)
        assertNull(gesture.up(cancelled = true))
    }

    @Test
    fun movingPastTheSlopStartsADragThatReportsDeltasAndEnds() {
        val gesture = OrbGesture(slop)
        gesture.down(100f, 100f)
        assertEquals(listOf(Action.DragStart, Action.Drag(0f, 12f)), gesture.move(100f, 112f))
        assertEquals(listOf(Action.Drag(-5f, 3f)), gesture.move(95f, 115f))
        assertNull("a drag never becomes a long press", gesture.longPressTimeout())
        assertEquals(Action.DragEnd, gesture.up(cancelled = false))
    }

    @Test
    fun holdingStillBecomesALongPressAndNeverATapOrDrag() {
        val gesture = OrbGesture(slop)
        gesture.down(100f, 100f)
        assertEquals(Action.LongPress, gesture.longPressTimeout())
        assertTrue(gesture.move(140f, 100f).isEmpty())
        assertNull(gesture.up(cancelled = false))
    }

    @Test
    fun laneTouchesOnTheHiddenOrbAreTheOrbsAndTheRestOfTheLaneIsNot() {
        val center = Offset(1000f, 1800f)
        val half = 66f // 44dp × 1.5 density / 2
        assertTrue(orbLaneHit(1000f, 1800f, center, half))
        assertTrue(orbLaneHit(1066f, 1734f, center, half))
        assertFalse("above the orb: tap collapses the panel", orbLaneHit(1000f, 1700f, center, half))
        assertFalse(orbLaneHit(930f, 1800f, center, half))
        assertFalse("no orb, nothing to forward", orbLaneHit(1000f, 1800f, null, half))
    }
}
