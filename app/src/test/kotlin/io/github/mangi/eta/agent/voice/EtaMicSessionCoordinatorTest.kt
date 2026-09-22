package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EtaMicSessionCoordinatorTest {
    @Test
    fun wakeDetect_entersDictating_andRespectsCooldown() {
        var now = 1_000L
        val coordinator = EtaMicSessionCoordinator(cooldownMs = 2_000L) { now }
        assertTrue(coordinator.onWakeEnabled())
        assertEquals(EtaMicSessionCoordinator.Phase.ListeningWake, coordinator.currentPhase())
        assertTrue(coordinator.onWakeDetected())
        assertEquals(EtaMicSessionCoordinator.Phase.Dictating, coordinator.currentPhase())

        coordinator.onDictationFinished(resumeWake = true)
        assertEquals(EtaMicSessionCoordinator.Phase.ListeningWake, coordinator.currentPhase())

        // Still inside cooldown window after finishing — first detect rejected.
        now = 1_500L
        // Move back to listening and try again too soon from lastWakeAt=1000
        assertFalse(coordinator.onWakeDetected())
        assertEquals(EtaMicSessionCoordinator.Phase.WakeCooldown, coordinator.currentPhase())

        now = 4_000L
        assertTrue(coordinator.onWakeDetected())
    }

    @Test
    fun callInterrupt_pausesAndResumes() {
        val coordinator = EtaMicSessionCoordinator()
        coordinator.onWakeEnabled()
        coordinator.onCallInterrupted()
        assertEquals(EtaMicSessionCoordinator.Phase.PausedForCall, coordinator.currentPhase())
        assertTrue(coordinator.onCallResumed())
        assertEquals(EtaMicSessionCoordinator.Phase.ListeningWake, coordinator.currentPhase())
    }

    @Test
    fun disable_returnsIdle() {
        val coordinator = EtaMicSessionCoordinator()
        coordinator.onWakeEnabled()
        coordinator.onWakeDisabled()
        assertEquals(EtaMicSessionCoordinator.Phase.Idle, coordinator.currentPhase())
        assertFalse(coordinator.onWakeDetected())
    }
}
