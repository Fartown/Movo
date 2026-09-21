package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.diagnostics.DiagnosticLevel
import io.github.mangi.eta.diagnostics.MemoryDiagnostics

/** Whitelist event metadata; streaming text, reasoning, arguments and tool results stay out. */
internal fun recordDiagnosticEvent(event: AgentEvent) {
    val (name, fields) = when (event) {
        is AgentEvent.RunStarted -> "run.ready" to mapOf("tools" to event.toolCount, "images" to event.initialImages)
        is AgentEvent.RoundStarted -> "round.started" to mapOf("round" to event.round, "messages" to event.messageCount)
        is AgentEvent.ToolStarted -> "tool.started" to mapOf("round" to event.round, "tool" to MemoryDiagnostics.token(event.name))
        is AgentEvent.ToolFinished -> "tool.finished" to mapOf("round" to event.round, "tool" to MemoryDiagnostics.token(event.name), "success" to event.success)
        is AgentEvent.HostedToolStarted -> "hosted_tool.started" to mapOf("round" to event.round, "tool" to MemoryDiagnostics.token(event.name))
        is AgentEvent.HostedToolFinished -> "hosted_tool.finished" to mapOf("round" to event.round, "tool" to MemoryDiagnostics.token(event.name), "success" to event.success)
        is AgentEvent.ContextCompaction -> "context.compaction" to mapOf(
            "phase" to event.phase, "tokens_before" to event.tokensBefore, "tokens_after" to event.tokensAfter,
            "code" to MemoryDiagnostics.token(event.reasonCode),
        )
        is AgentEvent.UsageReceived -> "model.usage" to mapOf(
            "round" to event.round, "input_tokens" to event.usage.inputTokens, "output_tokens" to event.usage.outputTokens,
            "context_tokens" to event.usage.contextTokens,
        )
        is AgentEvent.RunFinished -> "run.completed" to mapOf("round" to event.round)
        else -> return
    }
    val level = if (event is AgentEvent.ToolFinished && event.success == false) DiagnosticLevel.WARN else DiagnosticLevel.INFO
    MemoryDiagnostics.record("runtime", name, level, fields = fields)
}
