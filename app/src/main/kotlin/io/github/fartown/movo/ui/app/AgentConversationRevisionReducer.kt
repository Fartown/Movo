package io.github.fartown.movo.ui.app

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentChatUiState
import io.github.fartown.movo.ui.model.UserMessageUi

private fun AgentChatMessageUi.isHistoryUserAnchor(): Boolean =
    this is UserMessageUi || (this is io.github.fartown.movo.ui.model.MonitorEventMessageUi && historyAnchor)

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

    fun boundary(state: AgentChatUiState, targetMessageId: String): Boundary? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        // 对齐锚点：用户消息（含执行中的补充），以及在模型历史里对应一条 user 条目的后台监听行。
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index].isHistoryUserAnchor()
        } ?: return null
        val userMessage = state.messages[userMessageIndex] as? UserMessageUi
        val userMessageIndices = state.messages.indices.filter { state.messages[it].isHistoryUserAnchor() }
        val targetUserOrdinal = userMessageIndices.indexOf(userMessageIndex)
        if (targetUserOrdinal < 0) return null

        val source = state.journal.ifEmpty { state.history }
        val historyUserIndices = source.indices.filter { source[it].role == "user" }
        // 旧版本可能已经丢失前缀；只能对已有记录做尾部对齐，不能伪造恢复。
        val retainedUserOrdinal = historyUserIndices.size - (userMessageIndices.size - targetUserOrdinal)
        val historyIndex = historyUserIndices.getOrNull(retainedUserOrdinal)
        val prefix = historyIndex?.let(source::take).orEmpty()
        val targetTurn = prefix.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 } + 1
        val invalidatesSummary = prefix.any { it.contextSummary && it.summaryThroughUserTurn >= targetTurn }
        val compacted = historyIndex == null || invalidatesSummary

        return Boundary(
            userMessage = userMessage,
            userMessageIndex = userMessageIndex,
            historyPrefix = prefix.filterNot { it.contextSummary && it.summaryThroughUserTurn >= targetTurn },
            laterTurnCount = userMessageIndices.size - targetUserOrdinal - 1,
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
