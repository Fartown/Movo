package io.github.fartown.movo.agent.runtime

import io.github.fartown.movo.diagnostics.DiagnosticLevel
import io.github.fartown.movo.diagnostics.MemoryDiagnostics

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
        is AgentEvent.UsageReceived -> {
            // 提示缓存命中情况（只有数字）：真机上按它核对跨任务的缓存（提示缓存方案第 3 版）。
            io.github.fartown.movo.core.AndroidAgentLogger.info(
                "Model usage: round=${event.round}, in=${event.usage.inputTokens}, cached=${event.usage.cachedTokens}",
            )
            "model.usage" to mapOf(
                "round" to event.round, "input_tokens" to event.usage.inputTokens, "output_tokens" to event.usage.outputTokens,
                "context_tokens" to event.usage.contextTokens, "cached_tokens" to event.usage.cachedTokens,
            )
        }
        is AgentEvent.RunFinished -> "run.completed" to mapOf("round" to event.round)
        else -> return
    }
    val level = if (event is AgentEvent.ToolFinished && event.success == false) DiagnosticLevel.WARN else DiagnosticLevel.INFO
    MemoryDiagnostics.record("runtime", name, level, fields = fields)
}
