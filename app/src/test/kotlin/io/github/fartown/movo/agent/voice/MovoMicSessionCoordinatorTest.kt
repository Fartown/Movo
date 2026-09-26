package io.github.fartown.movo.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovoMicSessionCoordinatorTest {
    @Test
    fun wakeDetect_entersDictating_andRespectsCooldown() {
        var now = 1_000L
        val coordinator = MovoMicSessionCoordinator(cooldownMs = 2_000L) { now }
        assertTrue(coordinator.onWakeEnabled())
        assertEquals(MovoMicSessionCoordinator.Phase.ListeningWake, coordinator.currentPhase())
        assertTrue(coordinator.onWakeDetected())
        assertEquals(MovoMicSessionCoordinator.Phase.Dictating, coordinator.currentPhase())

        coordinator.onDictationFinished(resumeWake = true)
        assertEquals(MovoMicSessionCoordinator.Phase.ListeningWake, coordinator.currentPhase())

        // Still inside cooldown window after finishing — first detect rejected.
        now = 1_500L
        // Move back to listening and try again too soon from lastWakeAt=1000
        assertFalse(coordinator.onWakeDetected())
        assertEquals(MovoMicSessionCoordinator.Phase.WakeCooldown, coordinator.currentPhase())

        now = 4_000L
        assertTrue(coordinator.onWakeDetected())
    }

    @Test
    fun callInterrupt_pausesAndResumes() {
        val coordinator = MovoMicSessionCoordinator()
        coordinator.onWakeEnabled()
        coordinator.onCallInterrupted()
        assertEquals(MovoMicSessionCoordinator.Phase.PausedForCall, coordinator.currentPhase())
        assertTrue(coordinator.onCallResumed())
        assertEquals(MovoMicSessionCoordinator.Phase.ListeningWake, coordinator.currentPhase())
    }

    @Test
    fun screenOff_stopsWakeCapture_untilScreenOn() {
        val coordinator = MovoMicSessionCoordinator()
        coordinator.onWakeEnabled()
        assertTrue(coordinator.mayCaptureWake())
        assertTrue(coordinator.onScreenChanged(on = false))
        assertFalse(coordinator.onScreenChanged(on = false))
        assertFalse(coordinator.mayCaptureWake())
        assertEquals(MovoMicSessionCoordinator.Phase.ListeningWake, coordinator.currentPhase())
        assertTrue(coordinator.onScreenChanged(on = true))
        assertTrue(coordinator.mayCaptureWake())
    }

    @Test
    fun dictationEndingWhileScreenOff_waitsForScreenOn() {
        val coordinator = MovoMicSessionCoordinator()
        coordinator.onWakeEnabled()
        assertTrue(coordinator.onWakeDetected())
        coordinator.onScreenChanged(on = false)
        coordinator.onDictationFinished(resumeWake = true)
        assertFalse(coordinator.mayCaptureWake())
        coordinator.onScreenChanged(on = true)
        assertTrue(coordinator.mayCaptureWake())
    }

    @Test
    fun screenOnDuringDictation_keepsWakeCapturePaused() {
        val coordinator = MovoMicSessionCoordinator()
        coordinator.onWakeEnabled()
        assertTrue(coordinator.onWakeDetected())
        coordinator.onScreenChanged(on = false)
        coordinator.onScreenChanged(on = true)
        assertFalse(coordinator.mayCaptureWake())
    }

    @Test
    fun appOpenScope_capturesOnlyWhileMovoIsVisible() {
        val coordinator = MovoMicSessionCoordinator()
        coordinator.onWakeEnabled()
        assertTrue(coordinator.mayCaptureWake())
        assertTrue(coordinator.onAppVisibilityChanged(visible = false))
        assertTrue(coordinator.isWaitingForApp())
        assertFalse(coordinator.mayCaptureWake())
        assertTrue(coordinator.onAppVisibilityChanged(visible = true))
        assertTrue(coordinator.mayCaptureWake())
    }

    @Test
    fun screenOnScope_keepsCapturingOverOtherApps_butStillPausesWhenScreenIsOff() {
        val coordinator = MovoMicSessionCoordinator()
        coordinator.onWakeEnabled()
        assertTrue(coordinator.setListenInBackground(allowed = true))
        coordinator.onAppVisibilityChanged(visible = false)
        assertFalse(coordinator.isWaitingForApp())
        assertTrue(coordinator.mayCaptureWake())
        coordinator.onScreenChanged(on = false)
        assertFalse(coordinator.mayCaptureWake())
        coordinator.onScreenChanged(on = true)
        assertTrue(coordinator.setListenInBackground(allowed = false))
        assertFalse(coordinator.mayCaptureWake())
    }

    @Test
    fun disable_returnsIdle() {
        val coordinator = MovoMicSessionCoordinator()
        coordinator.onWakeEnabled()
        coordinator.onWakeDisabled()
        assertEquals(MovoMicSessionCoordinator.Phase.Idle, coordinator.currentPhase())
        assertFalse(coordinator.onWakeDetected())
    }
}
