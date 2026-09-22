package io.github.mangi.eta.agent.voice.conversation

import io.github.mangi.eta.agent.runtime.AgentRuntimeWire

/** Speech is exclusively the final LLM body, never reasoning, tool events or runtime status. */
internal object VoiceReplyContent {
    fun body(result: AgentRuntimeWire.RunResult): String =
        if (result.ok && result.resultKind == "terminal") result.content else ""
}
