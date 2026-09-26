package io.github.fartown.movo.agent.runtime

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.Callable

class VoiceRuntimeAdmissionTest {
    private fun owner(id: String, voice: Boolean) = AgentRuntimeAdmission.Owner(id, voice)
    @Test fun voiceCannotReplaceEitherTextOrVoiceTasksInActiveOrPreparingState() {
        for (existingVoice in listOf(false, true)) {
            val existing = owner("old", existingVoice)
            assertEquals(AgentRuntimeAdmission.Decision.BUSY, AgentRuntimeAdmission.decide(owner("new", true), existing, null))
            assertEquals(AgentRuntimeAdmission.Decision.BUSY, AgentRuntimeAdmission.decide(owner("new", true), null, existing))
        }
    }
    @Test fun textCannotReplaceAcceptedVoiceButKeepsExistingTextReplacementBehavior() {
        assertEquals(AgentRuntimeAdmission.Decision.BUSY, AgentRuntimeAdmission.decide(owner("new", false), owner("voice", true), null))
        assertEquals(AgentRuntimeAdmission.Decision.BUSY, AgentRuntimeAdmission.decide(owner("new", false), null, owner("voice", true)))
        assertEquals(AgentRuntimeAdmission.Decision.START, AgentRuntimeAdmission.decide(owner("new", false), owner("text", false), null))
    }
    @Test fun repeatedActiveIdentityAttachesAndPendingIdentityCannotRestartPreparation() {
        assertEquals(AgentRuntimeAdmission.Decision.ATTACH, AgentRuntimeAdmission.decide(owner("same", true), owner("same", true), null))
        assertEquals(AgentRuntimeAdmission.Decision.BUSY, AgentRuntimeAdmission.decide(owner("same", true), null, owner("same", true)))
        assertEquals(AgentRuntimeAdmission.Decision.START, AgentRuntimeAdmission.decide(owner("new", true), null, null))
    }
    @Test fun durableReceiptRejectsConcurrentReplayAndSurvivesNewStoreInstance() {
        val directory = Files.createTempDirectory("voice-admission").toFile()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val store = VoiceRunReceiptStore(directory)
            val outcomes = pool.invokeAll((1..20).map { Callable { store.claim("one-run") } }).map { it.get() }
            assertEquals(1, outcomes.count { it })
            assertFalse(VoiceRunReceiptStore(directory).claim("one-run"))
            assertTrue(VoiceRunReceiptStore(directory).claim("different-run"))
            assertEquals(2, directory.listFiles()!!.size)
        } finally { pool.shutdownNow(); directory.deleteRecursively() }
    }
}
