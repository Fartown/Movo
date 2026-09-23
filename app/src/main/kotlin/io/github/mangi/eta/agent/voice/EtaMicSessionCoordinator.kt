package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.WakePhraseRules
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Mic mutex + wake/dictation session state machine.
 * Pure logic — unit-testable without Android framework.
 */
internal class EtaMicSessionCoordinator(
    private val cooldownMs: Long = 2_500L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class Phase {
        Idle,
        ListeningWake,
        WakeCooldown,
        Dictating,
        PausedForCall,
    }

    private val phase = AtomicReference(Phase.Idle)
    private val lastWakeAt = AtomicLong(0L)
    private val activePhrase = AtomicReference(WakePhraseRules.DEFAULT)
    private val screenOn = AtomicBoolean(true)
    private val appVisible = AtomicBoolean(true)
    private val listenInBackground = AtomicBoolean(false)

    fun currentPhase(): Phase = phase.get()

    fun setPhrase(phrase: String) {
        activePhrase.set(WakePhraseRules.normalizeOrDefault(phrase))
    }

    fun phrase(): String = activePhrase.get()

    fun onWakeEnabled(): Boolean {
        return phase.compareAndSet(Phase.Idle, Phase.ListeningWake) ||
            phase.compareAndSet(Phase.WakeCooldown, Phase.ListeningWake) ||
            phase.get() == Phase.ListeningWake
    }

    fun onWakeDisabled() {
        phase.set(Phase.Idle)
    }

    fun onCallInterrupted() {
        if (phase.get() == Phase.ListeningWake || phase.get() == Phase.WakeCooldown) {
            phase.set(Phase.PausedForCall)
        }
    }

    fun onCallResumed(): Boolean {
        if (phase.compareAndSet(Phase.PausedForCall, Phase.ListeningWake)) return true
        return false
    }

    /** @return true if wake should trigger overlay/dictation */
    fun onWakeDetected(): Boolean {
        val now = clock()
        val current = phase.get()
        if (current != Phase.ListeningWake && current != Phase.WakeCooldown) return false
        if (lastWakeAt.get() > 0L && now - lastWakeAt.get() < cooldownMs) {
            phase.compareAndSet(current, Phase.WakeCooldown)
            return false
        }
        if (!phase.compareAndSet(current, Phase.Dictating)) return false
        lastWakeAt.set(now)
        return true
    }

    fun onDictationStarted(): Boolean {
        return phase.get() == Phase.Dictating ||
            phase.compareAndSet(Phase.ListeningWake, Phase.Dictating) ||
            phase.compareAndSet(Phase.WakeCooldown, Phase.Dictating) ||
            phase.compareAndSet(Phase.Idle, Phase.Dictating)
    }

    fun onDictationFinished(resumeWake: Boolean): Phase {
        val next = if (resumeWake) Phase.ListeningWake else Phase.Idle
        phase.set(next)
        return next
    }

    fun shouldRunWakeEngine(): Boolean =
        phase.get() == Phase.ListeningWake || phase.get() == Phase.WakeCooldown

    fun shouldPauseWakeForDictation(): Boolean = phase.get() == Phase.Dictating

    /** @return true if the screen state changed. */
    fun onScreenChanged(on: Boolean): Boolean = screenOn.getAndSet(on) != on

    fun isScreenOn(): Boolean = screenOn.get()

    /** @return true if Eta's visibility changed. */
    fun onAppVisibilityChanged(visible: Boolean): Boolean = appVisible.getAndSet(visible) != visible

    /** @return true if the listen scope changed. */
    fun setListenInBackground(allowed: Boolean): Boolean = listenInBackground.getAndSet(allowed) != allowed

    /** Scope is limited to Eta's own screens and none is visible. */
    fun isWaitingForApp(): Boolean = !listenInBackground.get() && !appVisible.get()

    /**
     * An open wake recording keeps the device awake, so capture only while the screen is on,
     * and only while Eta is visible unless the user chose to listen over other apps.
     */
    fun mayCaptureWake(): Boolean = screenOn.get() && !isWaitingForApp() && shouldRunWakeEngine()
}
