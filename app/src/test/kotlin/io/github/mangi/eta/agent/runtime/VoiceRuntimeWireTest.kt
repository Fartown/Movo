package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceRuntimeWireTest {
    @Test fun conversationAndVoiceIdentitySurviveIpcWithoutChangingModelReasoningOrHistory() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "voice-turn-run", prompt = "继续刚才的话题", images = emptyList(),
            modelSessionId = "conversation-a", voiceSessionId = "voice-a",
            history = listOf(AgentModelClient.ConversationMessage("user", "暗号蓝鲸")),
            handoff = AgentRuntimeWire.EntryHandoff("run", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation-a", true),
            config = AgentModelClient.ModelConfig(baseUrl="https://example.invalid/v1", apiKey="test", model="chosen-model", systemPrompt="角色设定", reasoningEffort=ReasoningEffort.OFF),
        )
        val decoded = AgentRuntimeWire.runRequestFromBundle(AgentRuntimeWire.toLegacyBundle(request))
        assertEquals(request, decoded)
        assertEquals("conversation-a", decoded.effectiveModelSessionId)
        assertFalse(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(decoded))
    }
    @Test fun uncertainExecutionIsPreservedAcrossFullAndLegacyResultTransports() {
        val uncertain = AgentRuntimeWire.RunResult("run-1", false, "", "连接断开", resultKind="unconfirmed")
        val bundle = AgentRuntimeWire.toBundle(uncertain)
        assertEquals(uncertain, AgentRuntimeWire.runResultFromBundle(bundle))
        bundle.remove("complete_result_json")
        assertEquals("unconfirmed", AgentRuntimeWire.runResultFromBundle(bundle).resultKind)
    }
    @Test fun admissionRejectionIsNotACompletedExecution() {
        val result = AgentRuntimeWire.RunResult("run-busy", false, "", "任务仍在执行", resultKind="rejected")
        assertEquals("rejected", AgentRuntimeWire.runResultFromBundle(AgentRuntimeWire.toBundle(result)).resultKind)
        val legacy = AgentRuntimeWire.toBundle(result).apply { remove("complete_result_json"); remove("result_kind") }
        assertEquals("terminal", AgentRuntimeWire.runResultFromBundle(legacy).resultKind)
    }
}
