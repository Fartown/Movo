package io.github.fartown.movo.diagnostics.runlog

import io.github.fartown.movo.agent.model.AgentConversationCodec
import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.model.AgentModelFailure
import io.github.fartown.movo.agent.model.AssistantBlockKind
import io.github.fartown.movo.agent.model.ProviderEvent
import io.github.fartown.movo.agent.model.ProviderRawPart
import io.github.fartown.movo.agent.model.ProviderRequestPurpose
import io.github.fartown.movo.agent.runtime.AgentEvent
import io.github.fartown.movo.agent.runtime.AgentRuntimeWire
import io.github.fartown.movo.agent.runtime.AgentTokenUsage
import io.github.fartown.movo.agent.tools.interaction.InteractionKind
import io.github.fartown.movo.agent.tools.interaction.InteractionPrompt
import io.github.fartown.movo.agent.tools.interaction.InteractionReply
import io.github.fartown.movo.diagnostics.MemoryDiagnostics
import org.json.JSONObject

/**
 * 运行日志的记录点（方案 §5.2）：把执行中已经产生的对象原样组装成一行。
 * 全部吞掉异常——记日志不能影响任务本身。
 */
internal object RunLogRecorder {
    /** run_start 的字段：用户原话、附图、模型和档位。 */
    fun startFields(request: AgentRuntimeWire.RunRequest): Map<String, Any?> {
        val config = request.config
        return linkedMapOf<String, Any?>(
            "operation" to request.operation,
            "request" to RunLogLimits.cap(request.prompt),
        ).apply {
            if (request.images.isNotEmpty()) put("images", request.images.map(::image))
            put("model", config.model)
            put("provider", config.providerId)
            put("provider_type", config.providerType)
            put("endpoint_mode", config.openAiEndpointMode)
            put("base_url", config.baseUrl)
            put("effort", config.effectiveReasoningEffort.name)
            put("voice", request.spokenReply.name)
            put("monitor", request.isMonitorOrigin)
        }
    }

    /** 一次尝试开始；不记时返回 null。 */
    fun attemptStarted(
        attempt: String,
        round: Int,
        retryOf: String?,
        purpose: ProviderRequestPurpose,
    ): RunLogAttempt? = runCatching {
        val session = RunLog.current() ?: return null
        val fields = linkedMapOf<String, Any?>("attempt" to attempt, "round" to round)
        retryOf?.let { fields["retry_of"] = it }
        fields["purpose"] = purpose.name.lowercase()
        if (purpose == ProviderRequestPurpose.CHAT) session.permissionMode?.let { fields["permission_mode"] = it }
        session.record("attempt_start", fields)
        RunLogAttempt(session, attempt)
    }.getOrNull()

    /**
     * 服务商生成最终请求字符串之后、发送之前调用（Responses 在 ChatGPT 的 applyBody 之后）。
     * 只在重试器里的一次尝试中记；推荐追问等不属于任务的请求不记。
     */
    fun requestBody(body: String) {
        runCatching {
            val attempt = io.github.fartown.movo.agent.model.ModelRequestTrace.current()?.context?.request
                ?.takeIf { it.isNotBlank() } ?: return
            RunLog.request(attempt, body)
        }
    }

    /** 本轮 ToolPipeline 已加载的权限档位，下一次尝试开始时写进 attempt_start。 */
    fun permissionMode(mode: String) {
        runCatching { RunLog.current()?.permissionMode = mode }
    }

    /** 工具执行前。[executed] 的参数和模型给的不同时（规范化、去掉 reply）才记。 */
    fun toolStarted(
        round: Int,
        modelCall: AgentModelClient.ToolCall,
        executed: AgentModelClient.ToolCall,
        title: String,
    ) {
        runCatching {
            val session = RunLog.current() ?: return
            val el = session.elapsed()
            session.toolStarts[modelCall.id] = el
            val fields = linkedMapOf<String, Any?>("round" to round, "id" to modelCall.id, "name" to modelCall.name, "title" to title)
            if (executed.argumentsJson != modelCall.argumentsJson) fields["args_exec"] = RunLogLimits.cap(executed.argumentsJson)
            session.record("tool_start", fields, el = el)
        }
    }

    /** 工具返回后：原始结果类型、错误码、交给模型的原文、图片、耗时。 */
    fun toolFinished(call: AgentModelClient.ToolCall, result: AgentModelClient.ToolResult) {
        runCatching {
            val session = RunLog.current() ?: return
            val el = session.elapsed()
            val fields = linkedMapOf<String, Any?>("id" to call.id, "name" to call.name, "status" to result.status)
            val outcome = result.outcome
            outcome?.verdict?.let { fields["outcome"] = it }
            result.errorCode?.let { fields["code"] = it }
            outcome?.error?.message?.let { fields["message"] = RunLogLimits.cap(it) }
            outcome?.error?.hint?.let { fields["hint"] = RunLogLimits.cap(it) }
            outcome?.error?.detail?.let { fields["detail"] = RunLogLimits.cap(it) }
            if (result.sensitive) fields["sensitive"] = true
            fields["result"] = result.content
            if (result.images.isNotEmpty()) fields["images"] = result.images.map(::image)
            session.toolStarts.remove(call.id)?.let { fields["dur_ms"] = el - it }
            session.record("tool_end", fields, el = el)
        }
    }

    fun interactionShown(prompt: InteractionPrompt) {
        runCatching {
            val session = RunLog.current() ?: return
            val kind = if (prompt.kind == InteractionKind.APPROVAL) "approval_shown" else "question_shown"
            val fields = linkedMapOf<String, Any?>("kind" to kind, "request_id" to prompt.requestId, "text" to prompt.title)
            if (prompt.detail.isNotBlank()) fields["detail"] = prompt.detail
            if (prompt.options.isNotEmpty()) fields["options"] = prompt.options
            prompt.note?.takeIf { it.isNotBlank() }?.let { fields["note"] = it }
            prompt.reason?.takeIf { it.isNotBlank() }?.let { fields["reason"] = it }
            session.record("user", fields)
        }
    }

    /** 作答结果原样记：允许 / 拒绝 / 超时 / 取消，提问的回答文字。 */
    fun interactionReplied(prompt: InteractionPrompt, reply: InteractionReply?) {
        runCatching {
            val session = RunLog.current() ?: return
            val approval = prompt.kind == InteractionKind.APPROVAL
            val fields = linkedMapOf<String, Any?>(
                "kind" to if (approval) "approval_reply" else "answer",
                "request_id" to prompt.requestId,
            )
            when (reply) {
                is InteractionReply.Approval -> fields["reply"] = if (reply.approved) "allow" else "deny"
                is InteractionReply.Answer -> {
                    fields["reply"] = "answer"
                    fields["text"] = RunLogLimits.cap(reply.text)
                    reply.optionIndex?.let { fields["option"] = it }
                }
                InteractionReply.Cancelled -> fields["reply"] = "cancel"
                null -> fields["reply"] = "timeout"
            }
            session.record("user", fields)
        }
    }

    /** 任务中的其他事件：上下文压缩、后台监听并入。服务商或系统没给的字段写 not_provided。 */
    fun agentEvent(event: AgentEvent) {
        runCatching {
            val (kind, data) = when (event) {
                is AgentEvent.ContextCompaction -> "compaction" to linkedMapOf<String, Any?>(
                    "phase" to event.phase,
                    "tokens_before" to event.tokensBefore,
                    "tokens_after" to (event.tokensAfter ?: NOT_PROVIDED),
                    "code" to event.reasonCode,
                )
                is AgentEvent.MonitorEventReceived -> "monitor" to linkedMapOf<String, Any?>(
                    "task_id" to event.taskId,
                    "name" to event.name,
                    "kind" to event.kind,
                    "seq" to event.seq,
                    "at_millis" to event.atMillis,
                    "text" to RunLogLimits.cap(event.text),
                    "reason" to event.reason,
                    "anchor" to event.anchor,
                )
                else -> return
            }
            RunLog.current()?.record("event", linkedMapOf("kind" to kind, "data" to data))
        }
    }

    fun image(image: AgentModelClient.ModelImage): RunLogImage = RunLogImage(
        reference = image.reference,
        mimeType = image.mimeType,
        bytes = image.bytes,
        width = image.width,
        height = image.height,
        source = image.source,
    )

    const val NOT_PROVIDED = "not_provided"
}

/**
 * 一次尝试的输出（方案 §5.1 attempt_*）：思考和正文边收边按批写（每满 16K 字或 2 秒一批），
 * 结束时写 attempt_end；成功、失败、取消各恰好一次。
 */
internal class RunLogAttempt(private val session: RunLogSession, val attempt: String) {
    private val thinking = ArrayList<Pair<String, StringBuilder>>()
    private val text = StringBuilder()
    private var pendingChars = 0
    private var lastFlush = session.elapsed()
    private var sawThinking = false
    private var sawText = false
    private var receiveStart: Long? = null
    private var usage: AgentTokenUsage? = null
    private var ended = false

    @Synchronized
    fun onProviderEvent(event: ProviderEvent) {
        runCatching {
            if (ended) return
            if (receiveStart == null && event !is ProviderEvent.RequestStarted) receiveStart = session.elapsed()
            when (event) {
                is ProviderEvent.BlockDelta -> when (event.kind) {
                    AssistantBlockKind.THINKING -> {
                        sawThinking = true
                        val parts = event.rawParts ?: listOf(ProviderRawPart(NO_TYPE, event.delta))
                        parts.forEach { appendThinking(it.type, it.text) }
                    }
                    AssistantBlockKind.TEXT -> {
                        sawText = true
                        text.append(event.delta)
                        pendingChars += event.delta.length
                    }
                    AssistantBlockKind.TOOL_CALL -> Unit
                }
                is ProviderEvent.Usage -> usage = event.usage
                is ProviderEvent.HostedToolStarted -> session.record(
                    "event",
                    linkedMapOf(
                        "kind" to "hosted_tool",
                        "data" to linkedMapOf("attempt" to attempt, "phase" to "started", "id" to event.id, "name" to event.name),
                    ),
                )
                is ProviderEvent.HostedToolFinished -> session.record(
                    "event",
                    linkedMapOf(
                        "kind" to "hosted_tool",
                        "data" to linkedMapOf(
                            "attempt" to attempt, "phase" to "finished", "id" to event.id, "name" to event.name,
                            "success" to event.success,
                        ),
                    ),
                )
                else -> Unit
            }
            if (pendingChars >= FLUSH_CHARS || (pendingChars > 0 && session.elapsed() - lastFlush >= FLUSH_MS)) flush()
        }
    }

    /** 成功：模型给的全部工具调用原样记。没有思考或正文增量时，用最终消息里的补上。 */
    @Synchronized
    fun succeeded(message: JSONObject) {
        runCatching {
            if (ended) return
            if (!sawThinking) {
                message.optString("reasoning_content").takeIf { it.isNotBlank() }?.let { appendThinking("reasoning_content", it) }
            }
            if (!sawText) {
                (message.opt("content") as? String)?.takeIf { it.isNotEmpty() }?.let {
                    text.append(it)
                    pendingChars += it.length
                }
            }
            val calls = AgentConversationCodec.parseToolCalls(message).map { call ->
                linkedMapOf<String, Any?>("id" to call.id, "name" to call.name, "args" to RunLogLimits.cap(call.argumentsJson))
            }
            val extra = linkedMapOf<String, Any?>()
            if (calls.isNotEmpty()) extra["tool_calls"] = calls
            message.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { extra["finish_reason"] = it }
            end("ok", null, null, extra)
        }
    }

    /** 失败或取消：错误码、异常类名、异常原文，以及服务商返回的错误原文。 */
    @Synchronized
    fun failed(failure: Throwable, cancelled: Boolean) {
        runCatching {
            if (ended) return
            val code = (failure as? Exception)?.let(AgentModelFailure::transport)?.code
            val extra = linkedMapOf<String, Any?>()
            failure.message?.takeIf { it.isNotBlank() }?.let { extra["message"] = RunLogLimits.cap(it) }
            generateSequence(failure) { it.cause }.take(8).filterIsInstance<AgentModelFailure>().firstOrNull()?.let { model ->
                model.providerCode?.takeIf { it.isNotBlank() }?.let { extra["provider_code"] = it }
                model.providerType?.takeIf { it.isNotBlank() }?.let { extra["provider_type"] = it }
                model.responseBody?.takeIf { it.isNotBlank() }?.let { extra["response_body"] = RunLogLimits.cap(it) }
            }
            end(if (cancelled) "cancelled" else "failed", code, MemoryDiagnostics.causes(failure), extra)
        }
    }

    private fun appendThinking(type: String, delta: String) {
        if (delta.isEmpty()) return
        val last = thinking.lastOrNull()
        if (last != null && last.first == type) last.second.append(delta) else thinking += type to StringBuilder(delta)
        pendingChars += delta.length
    }

    private fun pendingFields(): LinkedHashMap<String, Any?> {
        val fields = linkedMapOf<String, Any?>()
        if (thinking.isNotEmpty()) {
            fields["thinking"] = thinking.map { (type, content) ->
                linkedMapOf("type" to type, "text" to RunLogLimits.cap(content.toString(), RunLogLimits.THINKING_CHARS))
            }
        }
        if (text.isNotEmpty()) fields["text"] = RunLogLimits.cap(text.toString())
        thinking.clear()
        text.setLength(0)
        pendingChars = 0
        return fields
    }

    private fun flush() {
        val fields = linkedMapOf<String, Any?>("attempt" to attempt)
        fields.putAll(pendingFields())
        session.record("attempt_delta", fields)
        lastFlush = session.elapsed()
    }

    private fun end(status: String, code: String?, exception: String?, extra: Map<String, Any?>) {
        ended = true
        val fields = linkedMapOf<String, Any?>("attempt" to attempt, "status" to status)
        code?.let { fields["code"] = it }
        exception?.let { fields["exception"] = it }
        fields.putAll(pendingFields())
        fields.putAll(extra)
        usage?.let { tokens ->
            fields["usage"] = linkedMapOf<String, Any?>().apply {
                tokens.inputTokens?.let { put("input", it) }
                tokens.cachedTokens?.let { put("cached", it) }
                tokens.outputTokens?.let { put("output", it) }
                tokens.reasoningTokens?.let { put("reasoning", it) }
                tokens.contextTokens?.let { put("context", it) }
            }
        }
        receiveStart?.let { fields["recv_start_el"] = it }
        fields["recv_end_el"] = session.elapsed()
        session.record("attempt_end", fields)
    }

    private companion object {
        const val FLUSH_CHARS = 16 * 1024
        const val FLUSH_MS = 2_000L
        /** 服务商没有带出原始类型（旧代码路径）。 */
        const val NO_TYPE = "not_provided"
    }
}
