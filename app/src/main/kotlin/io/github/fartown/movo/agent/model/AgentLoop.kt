package io.github.fartown.movo.agent.model

import io.github.fartown.movo.agent.monitor.MonitorEventFormatter
import io.github.fartown.movo.agent.runtime.AgentEvent
import io.github.fartown.movo.agent.runtime.AgentRunController
import io.github.fartown.movo.agent.runtime.AgentTokenUsage
import io.github.fartown.movo.agent.runtime.SteeringItem
import io.github.fartown.movo.agent.roleplay.RoleplayRunContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。循环不设置本地轮次上限，
 * 由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    initialSupplementIndex: Int = 0,
    /** 这些工具的调用可以带 [FINISH_REPLY_ARG]：成功后直接用它作为回答结束本轮，不再请求一轮模型（见 Flavor.finishingTools）。 */
    private val finishingTools: Set<String> = emptySet(),
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var toolCallValidator = AgentToolCallValidator(tools)
    private val accumulatedReasoning = StringBuilder()
    private var pendingToolImageMessage: JSONObject? = null
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
    )
    private var supplementIndex = initialSupplementIndex

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var publishedTranscriptSize = 0

    private fun publishTranscript() {
        if (publishedTranscriptSize == transcript.length()) return
        onTranscript(AgentConversationCodec.transcript(transcript, 0))
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(): Result {
        context.compact(tools, force = true)
        return Result("", "")
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun run(): Result {
        var round = 1

        while (true) {
            runController.throwIfCancelled()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            val roundTools = if (purpose.allowsTools) withFinishReply(toolsForRound?.invoke() ?: tools) else JSONArray()
            toolCallValidator = AgentToolCallValidator(roundTools)
            publishTranscript()
            context.compact(roundTools)
            var requestMessages = roleplayContext?.projectMessages(messages, roundTools) ?: messages
            var requestEstimate = AgentContextBudget.rawEstimate(requestMessages, roundTools)
            var roundInputTokens: Int? = null
            var overflowAttempts = 0
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            var completedResponse: AgentModelRetry.Result? = null
            val completedRound = try {
                while (true) {
                    try {
                        val response = modelRetry.complete(
                            initialRound = round,
                            request = ProviderRequest(config, requestMessages, roundTools, sessionId, purpose),
                            provider = provider,
                            controller = runController,
                            onEvent = onEvent,
                            onProviderEvent = { attemptRound, providerEvent ->
                                if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                        providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                                    throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
                                }
                                if (providerEvent is ProviderEvent.Usage) {
                                roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                            }
                                if (providerEvent is ProviderEvent.BlockDelta &&
                                    providerEvent.kind == AssistantBlockKind.THINKING
                                ) {
                                    accumulatedReasoning.append(providerEvent.delta)
                                }
                                providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                            },
                            discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                        )
                        completedResponse = response
                        break
                    } catch (failure: AgentModelFailure) {
                        if (failure.code != "CONTEXT_OVERFLOW" || !failure.recoveryAllowed ||
                            overflowAttempts >= AgentContextBudget.MAX_OVERFLOW_ATTEMPTS) throw failure
                        overflowAttempts++
                        accumulatedReasoning.setLength(reasoningLengthBeforeRound)
                        context.compact(roundTools, force = true)
                        requestMessages = roleplayContext?.projectMessages(messages, roundTools) ?: messages
                        requestEstimate = AgentContextBudget.rawEstimate(requestMessages, roundTools)
                        roundInputTokens = null
                        round++
                    }
                }
                checkNotNull(completedResponse)
            } finally {
                // 同一回合的重试仍需原始观察；整个回合结束后才移除截图。
                discardPendingToolImageMessage()
            }
            context.budget.observe(
                roundInputTokens?.let { AgentTokenUsage(inputTokens = it) },
                requestEstimate,
            )
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw AgentModelFailure("REPLY_REWRITE_TOOL_CALL", false, "改写回复时模型请求了工具，已停止；原回复未改变。")
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure("REPLY_REWRITE_INCOMPLETE", false, "模型未返回完整的改写回复；原回复未改变。")
            }
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_movo_message_id", "assistant-$operationId-$round")
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )

            if (toolCalls.isNotEmpty()) {
                val outcomes = toolCalls.map { call ->
                    val outcome = when (providerResponse.stopReason) {
                        AssistantStopReason.TOOL_USE -> executeTool(round, call)
                        AssistantStopReason.OUTPUT_LIMIT -> rejectedToolOutcome(
                            round, call, "TRUNCATED_TOOL_CALL",
                            "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                        )
                        else -> rejectedToolOutcome(
                            round, call, "UNEXPECTED_TOOL_CALL",
                            "模型在 ${providerResponse.stopReason.name} 终止状态下返回了工具调用；本批调用未执行，请重新规划。",
                        )
                    }
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
                    publishTranscript()
                    outcome
                }
                appendToolImages(round, outcomes)
                publishTranscript()
                val finish = finishReply(providerResponse.stopReason, outcomes)
                if (finish == null) {
                    round += 1
                    continue
                }
                appendMessage(JSONObject().put("role", "assistant").put("content", finish)
                    .put("_movo_message_id", "assistant-$operationId-$round-finish"))
                publishTranscript()
                if (appendPendingSteeringOrSeal()) {
                    round += 1
                    continue
                }
                context.compact(roundTools, final = true)
                onEvent(AgentEvent.RunFinished(round = round, contentChars = finish.length))
                return Result(content = finish, reasoningContent = reasoningSnapshot())
            }

            publishTranscript()

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (purpose.allowsTools && appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            publishTranscript()
            if (purpose.allowsTools) context.compact(roundTools, final = true)
            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
            )
        }
    }

    /** 给 [finishingTools] 里的工具加上可选的 [FINISH_REPLY_ARG]；其他工具原样不动。 */
    private fun withFinishReply(roundTools: JSONArray): JSONArray {
        if (finishingTools.isEmpty()) return roundTools
        val result = JSONArray()
        for (index in 0 until roundTools.length()) {
            val tool = roundTools.optJSONObject(index)
            val function = tool?.optJSONObject("function")
            val properties = function?.optJSONObject("parameters")?.optJSONObject("properties")
            if (function == null || properties == null || function.optString("name") !in finishingTools) {
                result.put(roundTools.opt(index))
                continue
            }
            val copy = JSONObject(tool.toString())
            copy.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").put(FINISH_REPLY_ARG,
                JSONObject().put("type", "string").put("description", FINISH_REPLY_DESCRIPTION))
            result.put(copy)
        }
        return result
    }

    /** 这一轮只调用了一个 [finishingTools] 工具、带了回答且成功：返回这句回答，本轮就此结束。 */
    private fun finishReply(stopReason: AssistantStopReason, outcomes: List<ToolOutcome>): String? {
        val outcome = outcomes.singleOrNull() ?: return null
        if (stopReason != AssistantStopReason.TOOL_USE || outcome.call.name !in finishingTools) return null
        if (!traceFormatter.isSuccessResult(outcome.result)) return null
        // 只在效果已确认、没有警告时收尾：只送达（effect_verified=false）或有警告（音量被钳制等）时让模型看完结果再说。
        val toolOutcome = outcome.result.outcome
        if (toolOutcome?.effectVerified == false || toolOutcome?.warnings?.isNotEmpty() == true) return null
        return runCatching { JSONObject(outcome.call.argumentsJson).optString(FINISH_REPLY_ARG).trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 本轮已并入的监听事件条数（每次合并算一条）；到上限后剩下的留给下一个事件轮，一轮不会被事件拖着停不下来。 */
    private var injectedEventItems = 0

    private fun appendPendingSteeringMessage(): Boolean {
        val item = runController.pollSteeringMessage(allowEvents = injectedEventItems < MAX_EVENT_INJECTIONS) ?: return false
        appendSteeringItem(item)
        return true
    }

    /** 自然结束前只为用户补充续跑；监听事件不续跑本轮。 */
    private fun appendPendingSteeringOrSeal(): Boolean {
        val item = runController.pollSteeringOrSeal() ?: return false
        appendSteeringItem(item)
        return true
    }

    private fun appendSteeringItem(item: SteeringItem) {
        when (item) {
            is SteeringItem.User -> {
                appendMessage(steeringMessage(item.text))
                context.userAppended()
            }
            is SteeringItem.Event -> {
                // 模型真正读到事件时才告诉界面（插事件行、设历史锚点）。先发再写：停止恰好落在两者之间时，
                // 界面多出的那一批由 App 按结果 transcript 里的条数撤掉并放回队首；反过来先写后发，
                // 模型读到了界面却没有行，事件会再送一次、历史多出一条。
                item.events.forEach(onEvent)
                // 正文是「系统通知 - 非用户输入」，不包装成用户补充指令；标签段里的命令输出已转义。
                appendMessage(
                    AgentConversationCodec.userTextMessage(MonitorEventFormatter.wrap(item.text))
                        .put("_movo_message_id", "monitor-$operationId-${++monitorEventIndex}"),
                )
                context.eventAppended()
                injectedEventItems++
                publishTranscript()
            }
        }
    }

    private var monitorEventIndex = 0

    private fun steeringPrompt(supplement: String): String =
        "用户补充指令：$supplement\n\n请基于当前任务上下文继续执行，不要从头重复已经完成或已经验证过的操作。"

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_movo_message_id", "user-$operationId-supplement-${++supplementIndex}")

    private fun executeTool(
        round: Int,
        modelToolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        val toolCall = toolCallValidator.normalize(modelToolCall)
        toolCallValidator.validate(toolCall)?.let { validationError ->
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "INVALID_TOOL_ARGUMENTS",
                message = validationError,
            )
        }
        val started = AgentEvent.ToolStarted(
            round = round,
            toolCallId = toolCall.id,
            name = toolCall.name,
            // 工具自己给的标题（动作 + 对象）优先，老工具与元工具用格式化器兜底。
            argsPreview = runCatching { toolExecutor.stepTitle(toolCall) }.getOrNull()
                ?: traceFormatter.summarizeArguments(toolCall),
            command = traceFormatter.displayCommand(toolCall),
        ).stamped()
        val executedCall = withoutFinishReply(toolCall)
        io.github.fartown.movo.diagnostics.runlog.RunLogRecorder.toolStarted(round, modelToolCall, executedCall, started.argsPreview)
        onEvent(started)

        val result = try {
            toolExecutor.execute(executedCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun withoutFinishReply(call: AgentModelClient.ToolCall): AgentModelClient.ToolCall {
        if (call.name !in finishingTools) return call
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return call
        if (!args.has(FINISH_REPLY_ARG)) return call
        args.remove(FINISH_REPLY_ARG)
        return call.copy(argumentsJson = args.toString())
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        val started = AgentEvent.ToolStarted(
            round = round,
            toolCallId = toolCall.id,
            name = toolCall.name,
            argsPreview = traceFormatter.summarizeArguments(toolCall),
            command = traceFormatter.displayCommand(toolCall),
        ).stamped()
        io.github.fartown.movo.diagnostics.runlog.RunLogRecorder.toolStarted(round, toolCall, toolCall, started.argsPreview)
        onEvent(started)
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        io.github.fartown.movo.diagnostics.runlog.RunLogRecorder.toolFinished(toolCall, result)
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
                view = result.outcome?.view,
                errorCode = result.errorCode,
            ).stamped()
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // 每个已完成结果立即落盘；图片观察仍统一放在完整工具批次之后。
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // 工具截图是瞬时观察，不是会话资产。下一次思考消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).put("_movo_observation", true).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            ).stamped()
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            ).stamped()
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

    internal companion object {
        /** 一轮里最多并入几次监听事件（每次可合并多条）；超出的留给下一个事件轮。 */
        const val MAX_EVENT_INJECTIONS = 3
        const val FINISH_REPLY_ARG = "reply"
        const val FINISH_REPLY_DESCRIPTION =
            "这一步做完整个任务就结束时填：成功后直接对用户说的一句话（如「已打开哔哩哔哩」），不会再有下一轮。之后还要继续操作就不填。"
    }
}

/** 给工具事件记下发生时刻，供执行卡与执行详情计算每步用时（规范 8.1、8.8）。 */
private fun <T : AgentEvent> T.stamped(): T = apply {
    val now = System.currentTimeMillis()
    when (this) {
        is AgentEvent.ToolStarted -> atMillis = now
        is AgentEvent.ToolFinished -> atMillis = now
        is AgentEvent.HostedToolStarted -> atMillis = now
        is AgentEvent.HostedToolFinished -> atMillis = now
        else -> Unit
    }
}
