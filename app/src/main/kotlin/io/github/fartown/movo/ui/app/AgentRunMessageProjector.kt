package io.github.fartown.movo.ui.app

import android.os.SystemClock
import io.github.fartown.movo.agent.runtime.AgentEvent
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeCode
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.ThinkingMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.ToolActivityStatusUi
import io.github.fartown.movo.ui.model.UserMessageUi

internal class AgentRunMessageProjector(
    private val nowElapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val thinkingStartedAt = mutableMapOf<String, Long>()
    /** 已经调过工具（含不显示的 tool_search）的 run：之后写的正文直接写进执行卡（定稿 21 修订）。 */
    private val runsWithTools = mutableSetOf<String>()

    /** 回放从该 run 的空轨迹重建；仅重排有回放事件的补充输入，旧 handoff 独有的输入必须保留。 */
    fun resetForReplay(
        runId: String,
        messages: List<AgentChatMessageUi>,
        replaySupplementIndexes: Set<Int> = emptySet(),
    ): List<AgentChatMessageUi> {
        if (runId.isBlank()) return messages
        clearRun(runId)
        val replaySupplementIds = replaySupplementIndexes.mapTo(mutableSetOf()) { index ->
            AgentPendingResultRecovery.supplementMessageId(runId, index)
        }
        return messages.filterNot { message ->
            when (message) {
                is AgentMessageUi -> isAssistantMessageForRun(message.id, runId)
                is ThinkingMessageUi -> message.id.startsWith("$runId-thinking-")
                is ToolActivityMessageUi -> message.id.startsWith("$runId-tool-")
                is SystemNoticeMessageUi ->
                    isAssistantMessageForRun(message.id, runId) || message.id == "interrupted-$runId"
                is UserMessageUi -> message.id in replaySupplementIds
                else -> false
            }
        }
    }

    fun finishContextCompaction(
        runId: String,
        messages: List<AgentChatMessageUi>,
        detail: String,
    ): List<AgentChatMessageUi> = messages.map { message ->
        if (message is SystemNoticeMessageUi && message.code == SystemNoticeCode.ContextCompaction &&
            message.id.startsWith("assistant-$runId-compaction-") && message.running) {
            message.copy(detail = detail, running = false)
        } else message
    }

    fun scheduleModelRetry(
        runId: String,
        event: AgentEvent.ModelRetryScheduled,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val finalized = finalizeTextRound(
            runId, event.round, finalizeThinkingRound(runId, event.round, messages),
        )
        val notice = SystemNoticeMessageUi(
            id = "assistant-$runId-retry-${event.round}",
            code = SystemNoticeCode.ModelRetry,
            detail = event.displayMessage,
        )
        return finalized.filterNot { it.id == notice.id } + notice
    }

    fun startAssistantBlock(
        runId: String,
        event: AgentEvent.AssistantBlockStart,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> = transitionVisibleBlock(
        runId = runId,
        round = event.round,
        kind = event.kind,
        index = event.index,
        messages = messages,
    )

    fun appendTextDelta(
        runId: String,
        round: Int,
        index: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty()) return messages

        val transitioned = transitionVisibleBlock(
            runId = runId,
            round = round,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = index,
            messages = messages,
        )
        val assistantId = assistantMessageId(runId, round, index)
        var updated = false
        val next = transitioned.map { message ->
            if (message is AgentMessageUi && message.id == assistantId) {
                updated = true
                message.copy(
                    content = message.content + delta,
                    isStreaming = true,
                    renderMarkdown = false,
                )
            } else {
                message
            }
        }
        if (updated) return next

        return next + AgentMessageUi(
            id = assistantId,
            content = delta,
            isStreaming = true,
            renderMarkdown = false,
            // 执行卡已经出现：先作为说明写在卡里，任务结束时才知道它是不是回答。
            provisional = hasWorkCard(runId, next),
        )
    }

    fun appendReasoningDelta(
        runId: String,
        round: Int,
        index: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty()) return messages

        val transitioned = transitionVisibleBlock(
            runId = runId,
            round = round,
            kind = AgentEvent.AssistantBlockKind.THINKING,
            index = index,
            messages = messages,
        )
        val thinkingId = thinkingMessageId(runId, round, index)
        val elapsedSeconds = elapsedSeconds(thinkingId)
        var updated = false
        val next = transitioned.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                updated = true
                message.copy(
                    content = message.content + delta,
                    isStreaming = true,
                    elapsedSeconds = elapsedSeconds,
                    collapsed = false,
                )
            } else {
                message
            }
        }
        if (updated) return next

        return next + ThinkingMessageUi(
            id = thinkingId,
            content = delta,
            isStreaming = true,
            elapsedSeconds = elapsedSeconds,
            collapsed = false,
        )
    }

    fun ensureCompletedThinking(
        runId: String,
        round: Int,
        content: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (messages.any {
                it is ThinkingMessageUi && isThinkingMessageForRound(it.id, runId, round)
            }
        ) {
            return finalizeThinkingRound(runId, round, messages)
        }

        val thinkingId = thinkingFallbackMessageId(runId, round)
        return messages.insertBeforeFirstAssistant(
            runId = runId,
            round = round,
            message = ThinkingMessageUi(
                id = thinkingId,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds(thinkingId),
                collapsed = true,
            )
        )
    }

    fun finalizeThinking(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ThinkingMessageUi && message.id.startsWith("$runId-thinking-")) {
                message.finished()
            } else {
                message
            }
        }

    /** 终态不依赖各块结束事件全部到齐；缺少工具结果时只能标为未知，不能推断执行成功。 */
    fun finalizeRun(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        finalizeText(runId, finalizeThinking(runId, messages)).map { message ->
            if (
                message is ToolActivityMessageUi &&
                message.id.startsWith("$runId-tool-") &&
                message.status == ToolActivityStatusUi.Running
            ) {
                message.copy(status = ToolActivityStatusUi.Unknown)
            } else {
                message
            }
        }

    fun finalizeThinkingRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        return messages.map { message ->
            if (message is ThinkingMessageUi && isThinkingMessageForRound(message.id, runId, round)) {
                message.finished()
            } else {
                message
            }
        }
    }

    fun finalizeThinkingBlock(
        runId: String,
        round: Int,
        index: Int,
        replacementContent: String?,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val thinkingId = thinkingMessageId(runId, round, index)
        return messages.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                message.finished(replacementContent)
            } else {
                message
            }
        }
    }

    /** 任务结束（完成、失败、停止、中断恢复都经过这里）：还写在卡里的那段在最后一个工具之后，移出卡片成为回答。 */
    fun finalizeText(
        runId: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is AgentMessageUi && isAssistantMessageForRun(message.id, runId)) {
                message.copy(
                    content = message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                    provisional = false,
                )
            } else {
                message
            }
        }

    fun finalizeTextRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 定稿时裁掉尾部空白：模型输出常以换行收尾，Markdown 渲染会把每个尾部
        // 换行节点变成一段固定间距，在正文与后续工具卡片之间形成莫名的空行。
        return messages.map { message ->
            if (message is AgentMessageUi && isAssistantMessageForRound(message.id, runId, round)) {
                message.copy(
                    content = message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }
    }

    fun finalizeTextBlock(
        runId: String,
        round: Int,
        index: Int,
        replacementContent: String?,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val assistantId = assistantMessageId(runId, round, index)
        return messages.map { message ->
            if (message is AgentMessageUi && message.id == assistantId) {
                message.copy(
                    content = replacementContent?.trimEnd() ?: message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }
    }

    fun startTool(
        runId: String,
        event: AgentEvent.ToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 这一轮已写出的正文是工具前说明；不显示的工具（tool_search）同样算，否则说明会留在卡外把执行卡切开。
        runsWithTools += runId
        val marked = markNarration(runId, event.round, messages)
        // tool_search 是模型给自己加载工具，对用户没有意义，不进执行卡（工具可视化方案 C 级）。
        if (event.name in HIDDEN_TOOLS) return marked
        // 工具执行发生在对应 assistant 工具块完整返回之后；此时直接追加即可保留
        // 工具前说明、工具活动与下一轮结果的真实时间顺序。
        val message = ToolActivityMessageUi(
            id = toolActivityMessageId(runId, event.round, event.toolCallId),
            toolName = event.name,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = event.argsPreview,
            command = event.command,
            startedAtMillis = event.atMillis.takeIf { it > 0L },
        )
        if (marked.any { it.id == message.id }) return marked
        return marked + message
    }

    fun finishTool(
        runId: String,
        event: AgentEvent.ToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        // success 字段优先；旧版本 Runtime/归档事件缺省时回退到摘要文本判断
        val status = when (event.success) {
            true -> ToolActivityStatusUi.Success
            false -> ToolActivityStatusUi.Failed
            null -> if (event.resultSummary.contains("ok=false", ignoreCase = true)) {
                ToolActivityStatusUi.Failed
            } else {
                ToolActivityStatusUi.Success
            }
        }
        val targetIndex = messages.indexOfLast { it is ToolActivityMessageUi && it.id == targetId }
        if (targetIndex < 0) return messages

        return messages.mapIndexed { index, message ->
            if (index == targetIndex && message is ToolActivityMessageUi) {
                message.copy(
                    status = status,
                    resultSummary = event.resultSummary,
                    imageCount = event.imageCount,
                    finishedAtMillis = event.atMillis.takeIf { it > 0L } ?: message.finishedAtMillis,
                    view = event.view ?: message.view,
                )
            } else {
                message
            }
        }
    }

    fun startHostedTool(
        runId: String,
        event: AgentEvent.HostedToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val message = ToolActivityMessageUi(
            id = toolActivityMessageId(runId, event.round, event.toolCallId),
            toolName = event.name,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "",
            startedAtMillis = event.atMillis.takeIf { it > 0L },
        )
        // 托管工具（网页搜索）之后同一轮里接着写的正文才是回答，此前写出的是说明。
        runsWithTools += runId
        val marked = markNarration(runId, event.round, messages)
        return if (marked.any { it.id == message.id }) marked else marked + message
    }

    fun finishHostedTool(
        runId: String,
        event: AgentEvent.HostedToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        return messages.map { message ->
            if (message is ToolActivityMessageUi && message.id == targetId) {
                message.copy(
                    status = if (event.success) ToolActivityStatusUi.Success else ToolActivityStatusUi.Failed,
                    resultSummary = null,
                    finishedAtMillis = event.atMillis.takeIf { it > 0L } ?: message.finishedAtMillis,
                )
            } else {
                message
            }
        }
    }

    fun failRunningTools(
        reason: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                message.copy(
                    status = ToolActivityStatusUi.Failed,
                    resultSummary = reason.take(MAX_TOOL_RESULT_PREVIEW_CHARS),
                )
            } else {
                message
            }
        }

    fun interruptRunningTools(
        reason: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                message.copy(
                    status = ToolActivityStatusUi.Unknown,
                    resultSummary = reason.take(MAX_TOOL_RESULT_PREVIEW_CHARS),
                )
            } else {
                message
            }
        }

    /**
     * 又开始调工具：此前写出的正文都在这个工具之前，都是说明（定稿 21）——本轮还写在卡外的那段（第一轮还没有执行卡时）
     * 收进执行卡，此前先写在卡里的那几段也就此定为说明。
     */
    private fun markNarration(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> = messages.map { message ->
        val settle = message is AgentMessageUi && message.content.isNotBlank() &&
            (message.provisional && isAssistantMessageForRun(message.id, runId) ||
                !message.narration && isAssistantMessageForRound(message.id, runId, round))
        if (settle && message is AgentMessageUi) {
            message.copy(
                content = message.content.trimEnd(),
                isStreaming = false,
                renderMarkdown = true,
                narration = true,
                provisional = false,
            )
        } else {
            message
        }
    }

    /** 执行卡已经出现：这个 run 调过工具，或者已经有说明在卡里。 */
    private fun hasWorkCard(runId: String, messages: List<AgentChatMessageUi>): Boolean =
        runId in runsWithTools || messages.any { message ->
            message is ToolActivityMessageUi && message.id.startsWith("$runId-tool-") ||
                message is AgentMessageUi && (message.narration || message.provisional) && isAssistantMessageForRun(message.id, runId)
        }

    fun clearRun(runId: String) {
        thinkingStartedAt.keys.removeAll { it.startsWith("$runId-thinking-") }
        runsWithTools -= runId
    }

    private fun ThinkingMessageUi.finished(authoritativeContent: String? = null): ThinkingMessageUi =
        copy(
            content = authoritativeContent ?: content,
            isStreaming = false,
            // 只给仍在输出的块结算时长；已结束的块（例如整次运行收尾时再次经过这里）保留原值，
            // 否则第一段思考会被算成「从它开始到整个任务结束」。
            elapsedSeconds = if (isStreaming || elapsedSeconds == null) {
                thinkingStartedAt[id]?.let { startedAt ->
                    ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
                } ?: elapsedSeconds
            } else {
                elapsedSeconds
            },
            collapsed = true,
        )

    private fun elapsedSeconds(thinkingId: String): Int {
        val startedAt = thinkingStartedAt.getOrPut(thinkingId, nowElapsedRealtime)
        return ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
    }

    private fun transitionVisibleBlock(
        runId: String,
        round: Int,
        kind: AgentEvent.AssistantBlockKind,
        index: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val activeAssistantId = if (kind == AgentEvent.AssistantBlockKind.TEXT) {
            assistantMessageId(runId, round, index)
        } else {
            null
        }
        val activeThinkingId = if (kind == AgentEvent.AssistantBlockKind.THINKING) {
            thinkingMessageId(runId, round, index)
        } else {
            null
        }
        return messages.map { message ->
            when {
                message is AgentMessageUi &&
                    message.isStreaming &&
                    isAssistantMessageForRound(message.id, runId, round) &&
                    message.id != activeAssistantId ->
                    message.copy(
                        content = message.content.trimEnd(),
                        isStreaming = false,
                        renderMarkdown = true,
                    )

                message is ThinkingMessageUi &&
                    message.isStreaming &&
                    isThinkingMessageForRound(message.id, runId, round) &&
                    message.id != activeThinkingId ->
                    message.finished()

                else -> message
            }
        }
    }

    private fun List<AgentChatMessageUi>.insertBeforeFirstAssistant(
        runId: String,
        round: Int,
        message: AgentChatMessageUi,
    ): List<AgentChatMessageUi> {
        val assistantIndex = indexOfFirst {
            it is AgentMessageUi && isAssistantMessageForRound(it.id, runId, round)
        }
        return if (assistantIndex >= 0) {
            toMutableList().apply { add(assistantIndex, message) }
        } else {
            this + message
        }
    }

    private fun assistantMessageId(runId: String, round: Int, index: Int): String =
        "${assistantMessagePrefix(runId)}$round-$index"

    private fun thinkingMessageId(runId: String, round: Int, index: Int): String =
        "$runId-thinking-$round-$index"

    private fun thinkingFallbackMessageId(runId: String, round: Int): String =
        "$runId-thinking-$round-fallback"

    private fun isAssistantMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val legacyId = "${assistantMessagePrefix(runId)}$round"
        return messageId == legacyId || messageId.startsWith("$legacyId-")
    }

    private fun isThinkingMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val prefix = "$runId-thinking-$round"
        return messageId == prefix || messageId.startsWith("$prefix-")
    }

    private fun toolActivityMessageId(runId: String, round: Int, toolCallId: String): String =
        "$runId-tool-$round-${toolCallId.ifBlank { "unknown" }}"

    companion object {
        /**
         * 手动压缩 run 的结果与压缩事件共用同一条时间线标记：事件标记已携带压缩前后的
         * token 信息，成功时原样保留；仍在进行中、失败或事件缺失时才改写或补写。
         */
        fun mergeCompactionResultNotice(
            runId: String,
            messages: List<AgentChatMessageUi>,
            ok: Boolean,
            detail: String,
        ): List<AgentChatMessageUi> {
            val index = messages.indexOfLast {
                it is SystemNoticeMessageUi && it.code == SystemNoticeCode.ContextCompaction &&
                    it.id.startsWith("assistant-$runId-compaction-")
            }
            if (index < 0) {
                return messages + SystemNoticeMessageUi(
                    id = "assistant-$runId-compaction-result",
                    code = SystemNoticeCode.ContextCompaction,
                    detail = detail,
                )
            }
            val notice = messages[index] as SystemNoticeMessageUi
            if (ok && !notice.running) return messages
            return messages.mapIndexed { i, message ->
                if (i == index && message is SystemNoticeMessageUi) {
                    message.copy(detail = detail, running = false)
                } else {
                    message
                }
            }
        }

        /** 终态只能补全最后一次重试之后的回答，不能覆盖已标记失败的半截输出，也不能覆盖工具前说明。 */
        fun resultTargetIndex(
            runId: String,
            messages: List<AgentChatMessageUi>,
            includeNotices: Boolean = false,
        ): Int {
            val retryIndex = lastRetryIndex(runId, messages)
            return messages.indices.lastOrNull { index ->
                val message = messages[index]
                index > retryIndex &&
                    (message is AgentMessageUi && !message.narration || includeNotices && message is SystemNoticeMessageUi) &&
                    isAssistantMessageForRun(message.id, runId)
            } ?: -1
        }

        fun resultFallbackId(runId: String, messages: List<AgentChatMessageUi>): String {
            val retry = messages.getOrNull(lastRetryIndex(runId, messages))
            if (retry == null) return "assistant-$runId-1"
            val round = retry.id.substringAfterLast('-').toIntOrNull()?.plus(1) ?: 1
            return "assistant-$runId-$round-result"
        }

        private fun lastRetryIndex(runId: String, messages: List<AgentChatMessageUi>): Int =
            messages.indexOfLast {
                it is SystemNoticeMessageUi && it.code == SystemNoticeCode.ModelRetry &&
                    it.id.startsWith("assistant-$runId-retry-")
            }

        private fun assistantMessagePrefix(runId: String): String =
            "assistant-$runId-"

        private fun isAssistantMessageForRun(messageId: String, runId: String): Boolean =
            messageId == "assistant-$runId" ||
                messageId.startsWith(assistantMessagePrefix(runId))
    }

}

private const val MAX_TOOL_RESULT_PREVIEW_CHARS = 48

/** 不在执行卡里显示的工具（只在运行日志里保留，工具可视化方案 C 级）。 */
private val HIDDEN_TOOLS = setOf("tool_search")
