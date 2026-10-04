package io.github.fartown.movo.agent.tools.conversation

import io.github.fartown.movo.agent.model.AgentModelClient

/**
 * 真实会话后端：由主流程在运行启动时绑定 [loader]，返回当前持久会话的脱敏历史。
 * 每条消息渲染成「角色 + 文本」：优先取 content，其次工具调用/推理摘要，避免把整条 JSON 塞给模型。
 *
 * [loader] 的接线（绑定当前运行的 transcript 快照）由主流程完成（见返回报告）。
 */
internal class RuntimeConversationBackend(
    private val loader: () -> List<AgentModelClient.ConversationMessage>,
) : ConversationBackend {
    override fun load(): List<ConversationEntryView> =
        loader().mapIndexed { index, message ->
            ConversationEntryView(
                index = index,
                role = message.role,
                text = renderText(message),
            )
        }

    private fun renderText(message: AgentModelClient.ConversationMessage): String = when {
        message.content.isNotBlank() -> message.content
        message.toolCallsJson.isNotBlank() -> "[工具调用] ${message.toolCallsJson}"
        message.reasoningContent.isNotBlank() -> "[推理] ${message.reasoningContent}"
        message.contentJson.isNotBlank() -> message.contentJson
        else -> ""
    }
}
