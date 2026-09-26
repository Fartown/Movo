package io.github.fartown.movo.ui.app

import io.github.fartown.movo.ui.components.isRunSupplement
import io.github.fartown.movo.ui.model.AgentChatMessageUi
import io.github.fartown.movo.ui.model.AgentMessageUi
import io.github.fartown.movo.ui.model.SuggestionChipsMessageUi
import io.github.fartown.movo.ui.model.ToolActivityMessageUi
import io.github.fartown.movo.ui.model.UserMessageUi

/** 推荐追问在消息列表里的位置规则：只挂在最新一轮回答之后，新一轮开始即移除。 */
internal object AgentFollowUpSuggestions {
    /** 生成追问所需的这一轮内容；回答必须是本次 run 产出且仍是列表最后一条。 */
    data class Target(
        val answerMessageId: String,
        val userText: String,
        val answer: String,
        val usedTools: Boolean,
    )

    fun target(runId: String, messages: List<AgentChatMessageUi>): Target? {
        val answer = messages.lastOrNull() as? AgentMessageUi ?: return null
        if (answer.isStreaming || answer.content.isBlank() || !answer.id.startsWith("assistant-$runId-")) return null
        val turnStart = messages.indexOfLast { it is UserMessageUi && !it.isRunSupplement() }
        val user = messages.getOrNull(turnStart) as? UserMessageUi ?: return null
        return Target(
            answerMessageId = answer.id,
            userText = user.content,
            answer = answer.content,
            usedTools = messages.subList(turnStart + 1, messages.size).any { it is ToolActivityMessageUi },
        )
    }

    /** 请求期间用户已发下一句、编辑、删除或重新生成时回答不再是最后一条，返回 null 放弃。 */
    fun attach(
        messages: List<AgentChatMessageUi>,
        answerMessageId: String,
        prompts: List<String>,
    ): List<AgentChatMessageUi>? {
        if (prompts.isEmpty() || messages.lastOrNull()?.id != answerMessageId) return null
        return messages + SuggestionChipsMessageUi(id = "suggestions-$answerMessageId", prompts = prompts)
    }

    fun strip(messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        if (messages.none { it is SuggestionChipsMessageUi }) messages
        else messages.filterNot { it is SuggestionChipsMessageUi }

    /** 展示兜底：执行中或追问不在最后时一律不显示（例如外部任务结果并入了会话）。 */
    fun visible(messages: List<AgentChatMessageUi>, isStreaming: Boolean): List<AgentChatMessageUi> {
        if (messages.none { it is SuggestionChipsMessageUi }) return messages
        val tail = messages.lastOrNull()
        return messages.filterNot { it is SuggestionChipsMessageUi && (isStreaming || it !== tail) }
    }
}
