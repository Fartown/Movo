package io.github.fartown.movo.agent.voice

import io.github.fartown.movo.agent.runtime.AgentRuntimeWire
import io.github.fartown.movo.agent.voice.conversation.VoiceReplyContent
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceReplyContentTest {
    @Test fun onlyFinalBodyIsSpokenWithoutReasoningOrRewriting() {
        val result = AgentRuntimeWire.RunResult("run", true, "正文第一段。\n\n正文第二段。",
            reasoningContent = "这是思考过程，不能播报", error = "诊断状态不能播报")
        assertEquals(result.content, VoiceReplyContent.body(result))
    }
    @Test fun failureCancellationAndUnknownStateHaveNoSpokenBody() {
        for (error in listOf("已停止", "网络连接失败", "工具执行失败")) {
            assertEquals("", VoiceReplyContent.body(AgentRuntimeWire.RunResult("run", false, "残余正文", error)))
        }
        for (kind in listOf("unconfirmed", "rejected")) {
            assertEquals("", VoiceReplyContent.body(AgentRuntimeWire.RunResult("run", true, "未确认正文", resultKind = kind)))
        }
    }
}
