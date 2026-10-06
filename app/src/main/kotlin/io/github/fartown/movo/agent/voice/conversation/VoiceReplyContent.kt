package io.github.fartown.movo.agent.voice.conversation

import io.github.fartown.movo.agent.runtime.AgentRuntimeWire

/**
 * Speech is exclusively the final LLM body, never reasoning, tool events or runtime status.
 * 念之前去掉 Markdown 等符号（[VoiceSpeechText]），屏幕上的对话仍是原文。
 */
internal object VoiceReplyContent {
    fun body(result: AgentRuntimeWire.RunResult): String =
        if (result.ok && result.resultKind == "terminal") VoiceSpeechText.normalize(result.content) else ""
}
