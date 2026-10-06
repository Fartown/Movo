package io.github.fartown.movo.agent.model

import io.github.fartown.movo.agent.runtime.AgentEvent
import io.github.fartown.movo.agent.runtime.AgentRunController
import io.github.fartown.movo.diagnostics.DiagnosticLevel

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        var previousAttempt: String? = null
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            val trace = ModelRequestTrace(request, provider.id, round, retries + 1)
            val traceBinding = ModelRequestTrace.bind(trace)
            // 完整运行日志：每次尝试恰好一条 attempt_start 和一条 attempt_end（压缩请求也经过这里）。
            val attemptLog = io.github.fartown.movo.diagnostics.runlog.RunLogRecorder.attemptStarted(
                trace.context.request, round, previousAttempt, request.purpose,
            )
            previousAttempt = trace.context.request
            try {
                val response = provider.complete(request, controller) { event ->
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    attemptLog?.onProviderEvent(event)
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                trace.success()
                attemptLog?.succeeded(response.assistantMessage)
                return Result(round, response)
            } catch (failure: Exception) {
                attemptLog?.failed(failure, controller.isCancelled || Thread.currentThread().isInterrupted)
                trace.failed(failure, controller.isCancelled || Thread.currentThread().isInterrupted, callbackFailed)
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified, recoveryAllowed = false,
                )
                if (!classified.retryable) throw classified
                if (retries == MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                trace.record("retry.scheduled", DiagnosticLevel.WARN, mapOf(
                    "code" to classified.code, "retry_number" to retries,
                    "max_retries" to MAX_RETRIES, "delay_ms" to delayMs,
                ))
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), classified.code))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终思考摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            } finally {
                trace.close()
                traceBinding.close()
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L
    }
}
