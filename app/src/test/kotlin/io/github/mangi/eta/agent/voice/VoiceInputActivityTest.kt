package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.voice.conversation.VoiceCommitGate
import io.github.mangi.eta.agent.voice.conversation.VoiceInputActivity
import io.github.mangi.eta.agent.voice.conversation.VoiceTurnCoordinator
import org.junit.Assert.*
import org.junit.Test

class VoiceInputActivityTest {
    private fun pcm(amplitude: Int, samples: Int = 320) = ByteArray(samples * 2).apply {
        repeat(samples) { i ->
            val value = if (i % 2 == 0) amplitude else -amplitude
            this[i * 2] = value.toByte(); this[i * 2 + 1] = (value shr 8).toByte()
        }
    }

    @Test fun silenceLowNoiseAndSingleClickDoNotHoldSubmission() {
        val activity = VoiceInputActivity()
        assertFalse(activity.accept(pcm(0, 16000), now = 1000))
        assertFalse(activity.accept(pcm(100, 16000), now = 2000))
        val click = ByteArray(1280).apply { this[1] = 127 }
        assertFalse(activity.accept(click, now = 3000))
        val gate = VoiceCommitGate().apply { endpoint(3000) }
        assertEquals(VoiceCommitGate.Decision.WAIT, gate.decision(3649))
        assertEquals(VoiceCommitGate.Decision.COMMIT, gate.decision(3650))
    }

    @Test fun sustainedAudioUsesFixedWindowsAcrossCallbackChunksAndThrottles() {
        val activity = VoiceInputActivity()
        repeat(3) { assertFalse(activity.accept(pcm(800, 160), now = it * 10L)) }
        assertTrue(activity.accept(pcm(800, 160), now = 30))
        assertFalse(activity.accept(pcm(800), now = 50))
        assertTrue(activity.accept(pcm(800), now = 130))
    }

    @Test fun realR9PauseDoesNotSendBeforeDelayedAsrAndSubmitsMergedUtteranceOnce() {
        val turns = VoiceTurnCoordinator().apply { start() }
        val gate = VoiceCommitGate()
        turns.speechStarted(2); turns.partial(2, "请告诉我刚才说的那个。"); turns.speechEnded(2)
        gate.endpoint(28689)
        gate.activity(28766) // R9 resumed speech at ~28726, observed after 40 ms of audio.
        assertEquals(VoiceCommitGate.Decision.WAIT, gate.decision(29343))
        assertNull(turns.running)
        turns.speechStarted(3) // Cloud ASR_INFO at 29650, 303 ms after the old premature send.
        assertTrue(turns.commit(2).isEmpty())
        turns.partial(3, "暗号，等一下，只读暗号，不要解释。")
        gate.activity(32492)
        turns.speechEnded(3); gate.endpoint(33648)
        assertEquals(VoiceCommitGate.Decision.COMMIT, gate.decision(34298))
        val submitted = turns.commit(3).single() as VoiceTurnCoordinator.Action.Submit
        assertEquals("请告诉我刚才说的那个。，暗号，等一下，只读暗号，不要解释。", submitted.turn.text)
        assertTrue(turns.commit(3).isEmpty())
    }

    @Test fun unmatchedActivityTimesOutAndRetainsDraftInsteadOfSendingHalfSentence() {
        val turns = VoiceTurnCoordinator().apply { start(); speechStarted(1); partial(1, "半句话"); speechEnded(1) }
        val gate = VoiceCommitGate().apply { endpoint(2000); activity(1900) }
        assertEquals(VoiceCommitGate.Decision.WAIT, gate.decision(2650))
        assertEquals(VoiceCommitGate.Decision.WAIT, gate.decision(7999))
        assertEquals(VoiceCommitGate.Decision.EXPIRE, gate.decision(8000))
        turns.end()
        assertNull(turns.running)
        assertEquals("半句话", turns.transcript)
    }

    @Test fun NormalUtteranceTailBeforeCloudEndpointDoesNotAddLatency() {
        val gate = VoiceCommitGate().apply { activity(5894); endpoint(6975) }
        assertEquals(VoiceCommitGate.Decision.COMMIT, gate.decision(7625))
    }
}
