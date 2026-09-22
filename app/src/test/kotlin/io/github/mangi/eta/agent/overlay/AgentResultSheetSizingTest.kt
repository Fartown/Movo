package io.github.mangi.eta.agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentResultSheetSizingTest {
    @Test
    fun upwardDragGrowsTheWindowAndDownwardDragShrinksIt() {
        assertEquals(750, AgentResultSheetSizing.drag(550, -200f, 1000))
        assertEquals(720, AgentResultSheetSizing.drag(920, 200f, 1000))
    }

    @Test
    fun draggingCannotCoverTheEntireScreenOrLoseTheComposer() {
        assertEquals(920, AgentResultSheetSizing.drag(550, -2000f, 1000))
        assertEquals(550, AgentResultSheetSizing.drag(920, 2000f, 1000))
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
        assertEquals(736, AgentResultSheetSizing.height(800, true))
        assertEquals(1840, AgentResultSheetSizing.height(2000, true))
    }
}
