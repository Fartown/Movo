package io.github.fartown.movo.agent.model

/**
 * 一轮用哪种模式跑（规范 8.2.2、定稿 25）：Agent = 现在的 Movo（工具、执行卡）；
 * 对话 = 最干净的模型调用，只把对话发给当前模型（docs/research/chat-agent-mode）。
 */
enum class InteractionMode(val wireValue: String) {
    AGENT("agent"),
    CHAT("chat");

    companion object {
        fun fromWire(value: String?): InteractionMode = entries.firstOrNull { it.wireValue == value } ?: AGENT
    }
}

/**
 * 对话模式的请求只看得到之前的文字（方案 §3.3）：用户消息原样保留（含监听事件、补充、图片占位），
 * 回答只留正文，工具调用、工具结果、思考和 Responses 原始输出都不发。库里的历史不变，只在组装请求时投影。
 */
internal object ChatModeHistory {
    fun project(history: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage> =
        history.mapNotNull { message ->
            when (message.role) {
                "user" -> message.copy(
                    toolCallId = "",
                    toolCallsJson = "",
                    reasoningContent = "",
                    responsesOutputJson = "",
                    responsesOrigin = "",
                )
                "assistant" -> message.content.takeIf { it.isNotBlank() }?.let { content ->
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = content,
                        contextSummary = message.contextSummary,
                        compactedUserTurns = message.compactedUserTurns,
                        summaryThroughUserTurn = message.summaryThroughUserTurn,
                        messageId = message.messageId,
                    )
                }
                else -> null
            }
        }
}
