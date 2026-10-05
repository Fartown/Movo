package io.github.fartown.movo.ui.screens.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFollowPolicyTest {
    @Test fun draggingPausesAndOutputGrowthDoesNotResumeFollowing() {
        assertFalse(terminalFollowAfterGesture(current = true, dragging = true, atBottom = true))
        assertFalse(terminalFollowAfterGesture(current = false, dragging = false, atBottom = false))
    }

    @Test fun returningToActualEndResumesFollowing() {
        assertTrue(terminalFollowAfterGesture(current = false, dragging = false, atBottom = true))
    }
}
