package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.diagnostics.MemoryDiagnostics
import org.junit.Assert.*
import org.junit.Test

class AgentDiagnosticEventsTest {
    @Test fun runtimeEventsKeepBoundariesButNeverConversationOrToolContent() {
        MemoryDiagnostics.buffer.clear()
        recordDiagnosticEvent(AgentEvent.ToolStarted(2, "SECRET_ID", "terminal", "SECRET_ARGS", "SECRET_COMMAND"))
        recordDiagnosticEvent(AgentEvent.ToolFinished(2, "SECRET_ID", "terminal", "SECRET_RESULT", 0, 0, true))
        recordDiagnosticEvent(AgentEvent.AssistantBlockDelta(2, AgentEvent.AssistantBlockKind.TEXT, 0, 12, "SECRET_TEXT"))
        recordDiagnosticEvent(AgentEvent.UserSupplementReceived(0, "SECRET_INPUT"))
        val entries = MemoryDiagnostics.buffer.snapshot().entries
        assertEquals(listOf("tool.started", "tool.finished"), entries.map { it.event })
        assertFalse(entries.joinToString { it.text() }.contains("SECRET_"))
        assertTrue(entries.last().details.contains("success=true"))
        MemoryDiagnostics.buffer.clear()
    }
}
