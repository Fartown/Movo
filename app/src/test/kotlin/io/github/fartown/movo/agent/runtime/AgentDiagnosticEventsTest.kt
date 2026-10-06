package io.github.fartown.movo.agent.runtime

import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 用量事件会顺带打一行 logcat（提示缓存核对用），要有 Android 的 Log。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentDiagnosticEventsTest {
    @Test fun runtimeEventsKeepBoundariesButNeverConversationOrToolContent() {
        MemoryDiagnostics.buffer.clear()
        recordDiagnosticEvent(AgentEvent.ToolStarted(2, "call_1", "terminal", "SECRET_ARGS", "SECRET_COMMAND"))
        recordDiagnosticEvent(AgentEvent.ToolFinished(2, "call_1", "terminal", "SECRET_RESULT", 0, 0, false, errorCode = "NOT_FOUND"))
        recordDiagnosticEvent(AgentEvent.AssistantBlockDelta(2, AgentEvent.AssistantBlockKind.TEXT, 0, 12, "SECRET_TEXT"))
        recordDiagnosticEvent(AgentEvent.UserSupplementReceived(0, "SECRET_INPUT"))
        val entries = MemoryDiagnostics.buffer.snapshot().entries
        assertEquals(listOf("tool.started", "tool.finished"), entries.map { it.event })
        assertFalse(entries.joinToString { it.text() }.contains("SECRET_"))
        // 运行日志补全 §5.7：结束记录带调用 id 和错误码，方便和完整日志对上。
        assertTrue(entries.last().details.contains("success=false"))
        assertTrue(entries.last().details.contains("call=call_1"))
        assertTrue(entries.last().details.contains("code=NOT_FOUND"))
        MemoryDiagnostics.buffer.clear()
    }

    @Test fun usageKeepsReasoningAndCachedTokens() {
        MemoryDiagnostics.buffer.clear()
        recordDiagnosticEvent(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 10, outputTokens = 3, reasoningTokens = 2, cachedTokens = 7)))
        val details = MemoryDiagnostics.buffer.snapshot().entries.single().details
        assertTrue(details.contains("reasoning_tokens=2"))
        assertTrue(details.contains("cached_tokens=7"))
        MemoryDiagnostics.buffer.clear()
    }
}
