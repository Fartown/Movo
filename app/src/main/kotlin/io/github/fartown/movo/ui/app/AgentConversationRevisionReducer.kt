package io.github.fartown.movo.ui.app

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentChatUiState
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.MonitorEventMessageUi
import io.github.fartown.movo.ui.model.SystemNoticeMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi

/**
 * 在模型历史里对应一条 user 条目的消息：用户消息（含执行中的补充），以及后台监听行里每批的第一行
 * （事件轮的起点、运行中模型读到的每一批）。编辑 / 删除轮次时按它们与历史对齐。
 */
private fun AgentChatMessageUi.isHistoryUserAnchor(): Boolean =
    this is UserMessageUi || (this is MonitorEventMessageUi && historyAnchor)

/** 以用户轮次为边界同步裁剪展示消息与模型上下文。 */
internal object AgentConversationRevisionReducer {
    data class Boundary(
        /** 这一轮的用户消息；事件轮（由后台监听唤醒）没有用户原话，为 null：不能编辑 / 重新生成，只能删除。 */
        val userMessage: UserMessageUi?,
        val userMessageIndex: Int,
        val historyPrefix: List<AgentModelClient.ConversationMessage>,
        val journalPrefix: List<AgentModelClient.ConversationMessage> = historyPrefix,
        val laterTurnCount: Int,
        val contextWasCompacted: Boolean,
    )

    /** 删除 / 编辑用：[targetMessageId] 往前最近的一个历史锚点。 */
    fun boundary(state: AgentChatUiState, targetMessageId: String): Boundary? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val anchorIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index].isHistoryUserAnchor()
        } ?: return null
        return boundaryAt(state, anchorIndex)
    }

    /**
     * 「重新生成」「重试」用：从 [targetMessageId] 往前回到这一轮里用户自己说的话（用户消息或补充），
     * 跳过运行中并入的监听事件行。碰到事件轮的起点（没有用户原话）时返回 null。
     */
    fun regenerationBoundary(state: AgentChatUiState, targetMessageId: String): Boundary? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val anchorIndex = (targetIndex downTo 0).firstOrNull { index ->
            val message = state.messages[index]
            message is UserMessageUi || (message is MonitorEventMessageUi && message.startsTurn)
        } ?: return null
        if (state.messages[anchorIndex] !is UserMessageUi) return null
        return boundaryAt(state, anchorIndex)
    }

    /**
     * 事件轮里的回答与失败卡：这一轮没有用户原话，「重新生成」「重试」不可用（界面隐藏这两个按钮，删除仍可用）。
     * 与 [regenerationBoundary] 同一口径：往前先碰到事件轮起点、而不是用户消息的，就是事件轮里的。
     */
    fun nonRegenerableMessageIds(messages: List<AgentChatMessageUi>): Set<String> {
        val result = HashSet<String>()
        var inEventTurn = false
        for (message in messages) {
            when {
                message is UserMessageUi -> inEventTurn = false
                message is MonitorEventMessageUi && message.startsTurn -> inEventTurn = true
                inEventTurn && (message is AgentMessageUi || message is SystemNoticeMessageUi) -> result += message.id
            }
        }
        return result
    }

    private fun boundaryAt(state: AgentChatUiState, anchorIndex: Int): Boundary? {
        val userMessage = state.messages[anchorIndex] as? UserMessageUi
        val anchorIndices = state.messages.indices.filter { state.messages[it].isHistoryUserAnchor() }
        val targetUserOrdinal = anchorIndices.indexOf(anchorIndex)
        if (targetUserOrdinal < 0) return null

        val source = state.journal.ifEmpty { state.history }
        val historyUserIndices = source.indices.filter { source[it].role == "user" }
        // 旧版本可能已经丢失前缀；只能对已有记录做尾部对齐，不能伪造恢复。
        val retainedUserOrdinal = historyUserIndices.size - (anchorIndices.size - targetUserOrdinal)
        val historyIndex = historyUserIndices.getOrNull(retainedUserOrdinal)
        val prefix = historyIndex?.let(source::take).orEmpty()
        val targetTurn = prefix.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 } + 1
        val invalidatesSummary = prefix.any { it.contextSummary && it.summaryThroughUserTurn >= targetTurn }
        val compacted = historyIndex == null || invalidatesSummary

        return Boundary(
            userMessage = userMessage,
            userMessageIndex = anchorIndex,
            historyPrefix = prefix.filterNot { it.contextSummary && it.summaryThroughUserTurn >= targetTurn },
            laterTurnCount = anchorIndices.size - targetUserOrdinal - 1,
            contextWasCompacted = compacted,
        )
    }

    fun deleteFromTurn(state: AgentChatUiState, targetMessageId: String): AgentChatUiState? {
        val boundary = boundary(state, targetMessageId) ?: return null
        return state.copy(
            messages = state.messages.take(boundary.userMessageIndex),
            history = boundary.historyPrefix,
            journal = boundary.journalPrefix,
            messageEdit = null,
        )
    }

    fun visibleMessagesForEdit(
        messages: List<AgentChatMessageUi>,
        targetMessageId: String?,
    ): List<AgentChatMessageUi> {
        if (targetMessageId == null) return messages
        val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
        return if (targetIndex < 0) messages else messages.take(targetIndex + 1)
    }
}

/**
 * 后台监听唤醒的事件轮没真正开始时的撤回（纯逻辑，AgentAppState 调用）：
 * App 判断空闲之后、运行时收到请求之前，别处先发起了运行，事件轮会被拒、在准备时被替换或刚开始就被取消。
 * 这时撤掉事件行与历史里的事件正文，事件放回队首稍后再发，对话里不留「当前任务仍在执行」这类失败卡。
 */
internal object MonitorTurnRollback {
    /** AgentRuntimeService 在请求准备期间被新任务替换时的结果。 */
    const val RUN_REPLACED_ERROR = "已被新的 Agent 任务替换"
    private val STOPPED_ERRORS = setOf("已停止", "movo_status:stopped")

    /**
     * [madeProgress]：这一轮已有模型输出、工具调用或读到的事件（之后被取消也不撤回，模型可能已经做了事）。
     * [stoppedByUser]：用户自己点的停止（不撤回，照常显示「已停止」）；为用户运行让路不算。
     */
    fun shouldRollBack(
        result: io.github.fartown.movo.agent.runtime.AgentRuntimeWire.RunResult,
        madeProgress: Boolean,
        stoppedByUser: Boolean,
    ): Boolean {
        if (result.ok || result.resultKind == "unconfirmed") return false
        if (madeProgress || result.transcript.isNotEmpty()) return false
        if (result.resultKind == "rejected" || result.error == RUN_REPLACED_ERROR) return true
        return result.error in STOPPED_ERRORS && !stoppedByUser
    }

    /**
     * 撤回后的对话：去掉这一轮插入的事件行与这一轮的消息，历史与完整记录里去掉事件正文（`user-<runId>`），
     * 并把 [runId] 记为已处理（之后的结果恢复不会再合并）。发起前收起的推荐追问在消息没有别的变化时一并恢复。
     */
    fun rollBack(
        state: AgentChatUiState,
        runId: String,
        rowIds: Set<String>,
        preLaunchMessages: List<AgentChatMessageUi>?,
    ): AgentChatUiState {
        val withoutRun = state.messages.filterNot { it.id in rowIds || it.belongsToRun(runId) }
        val messages = preLaunchMessages?.takeIf { AgentFollowUpSuggestions.strip(it) == withoutRun } ?: withoutRun
        val userMessageId = "user-$runId"
        return state.copy(
            messages = messages,
            history = state.history.filterNot { it.messageId == userMessageId },
            journal = state.journal.filterNot { it.messageId == userMessageId },
            appliedRuntimeRunIds = if (runId in state.appliedRuntimeRunIds) state.appliedRuntimeRunIds else state.appliedRuntimeRunIds + runId,
            isStreaming = false,
            isCompacting = false,
            isPaused = false,
        )
    }

    private fun AgentChatMessageUi.belongsToRun(runId: String): Boolean =
        id == "assistant-$runId" || id.startsWith("assistant-$runId-") || id.startsWith("$runId-") || id == "interrupted-$runId"
}
