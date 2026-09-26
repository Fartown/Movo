package io.github.fartown.movo.agent.runtime

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import io.github.fartown.movo.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class AgentConversationHandoffTest {
    @Test
    fun appResultsKeepTheOriginalConversationIncludingLegacyPayloads() {
        val source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE
        val target = AgentConversationTarget(source, "original-chat")
        assertEquals(target, AgentConversationTarget.from(handoff(source, "original-chat")))
        assertEquals(target, AgentConversationTarget.from(handoff(source,
            AgentUiHandoffPayload("original-chat", supplements = listOf(
                AgentUiHandoffPayload.Supplement(1, "继续执行", 42),
            )).toJson())))
    }

    @Test
    fun externalResultsKeepTheirSourceAndArchiveKeyInsteadOfUsingAnAppId() {
        val payload = AgentExternalArchivePayload("打开设置", "shared-key", "设置").toJson()
        val voice = AgentConversationTarget.from(handoff(AgentRuntimeWire.MOVO_VOICE_HANDOFF_SOURCE, payload))!!
        val external = AgentConversationTarget.from(handoff("external_assistant", payload))!!
        assertEquals("shared-key", voice.key)
        assertEquals("shared-key", external.key)
        assertNotEquals(voice, external)
        assertNull(AgentConversationTarget.from(handoff("external_assistant", "an-app-conversation-id")))
    }

    @Test
    fun missingOrMalformedHandoffsCannotOpenANewEmptyChat() {
        assertNull(AgentConversationTarget.from(null))
        assertNull(AgentConversationTarget.from(handoff(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, " ")))
        assertNull(AgentConversationTarget.from(handoff(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "{invalid")))
        assertNull(AgentConversationTarget.from(handoff(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "{\"type\":\"wrong\"}")))
    }

    @Test
    fun fullScreenReusesMainActivityAndAcknowledgesOnlyWhenTheChatIsReady() {
        val target = AgentConversationTarget(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "original-chat")
        val received = mutableListOf<Int>()
        val receiver = receiver(received)
        val intent = AgentConversationHandoff.intent(RuntimeEnvironment.getApplication(), target, "run-1", receiver)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        val request = AgentConversationHandoff.from(intent)!!
        assertEquals(target, request.target)
        assertEquals("run-1", request.runId)
        assertTrue(received.isEmpty())
        request.acknowledge(true)
        assertEquals(listOf(AgentConversationHandoff.RESULT_READY), received)
        AgentConversationHandoff.consume(intent)
        assertNull(AgentConversationHandoff.from(intent))
        assertFalse(intent.hasExtra("conversation_receiver"))
    }

    @Test
    fun unavailableHistoryReturnsFailureSoTheOverlayCanKeepItsResult() {
        val received = mutableListOf<Int>()
        val intent = AgentConversationHandoff.intent(RuntimeEnvironment.getApplication(),
            AgentConversationTarget(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "missing-chat"), "run-2", receiver(received))
        AgentConversationHandoff.from(intent)!!.acknowledge(false)
        assertEquals(listOf(AgentConversationHandoff.RESULT_FAILED), received)
    }

    @Test
    fun assistantSheetCanOpenItsExistingConversationBeforeAnyRunCompletes() {
        val target = AgentConversationTarget(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "voice-chat")
        val received = mutableListOf<Int>()
        val intent = AgentConversationHandoff.intent(RuntimeEnvironment.getApplication(), target, null, receiver(received))
        val request = AgentConversationHandoff.from(intent)!!
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(target, request.target)
        assertNull(request.runId)
        request.acknowledge(true)
        assertEquals(listOf(AgentConversationHandoff.RESULT_READY), received)
        AgentConversationHandoff.consume(intent)
        assertFalse(intent.hasExtra("current_conversation"))
        assertNull(AgentConversationHandoff.from(intent))
    }

    @Test
    fun resultLinksCannotSilentlyLoseTheirRunValidation() {
        val target = AgentConversationTarget(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "original-chat")
        val intent = AgentConversationHandoff.intent(RuntimeEnvironment.getApplication(), target, "run-1", receiver(mutableListOf()))
        intent.removeExtra("conversation_run_id")
        assertNull(AgentConversationHandoff.from(intent))
        assertNull(AgentConversationHandoff.from(AgentConversationHandoff.intent(
            RuntimeEnvironment.getApplication(), target, " ", receiver(mutableListOf()))))
        assertNull(AgentConversationHandoff.from(AgentConversationHandoff.intent(
            RuntimeEnvironment.getApplication(), AgentConversationTarget("external_assistant", "archive-chat"),
            null, receiver(mutableListOf()))))
    }

    @Test
    fun normalAppLaunchesAndIncompleteLinksDoNotSelectAConversation() {
        assertNull(AgentConversationHandoff.from(Intent(Intent.ACTION_MAIN)))
        assertNull(AgentConversationHandoff.from(Intent(AgentConversationHandoff.ACTION_OPEN)))
    }

    private fun handoff(source: String, payload: String) = AgentRuntimeWire.EntryHandoff("entry-1", source, payload)
    private fun receiver(codes: MutableList<Int>) = object : ResultReceiver(null) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { codes += resultCode }
    }
}
