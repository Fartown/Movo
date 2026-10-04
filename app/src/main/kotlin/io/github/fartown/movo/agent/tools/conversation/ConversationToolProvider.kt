package io.github.fartown.movo.agent.tools.conversation

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.ToolProvider

/**
 * 会话历史领域工具 Provider：conversation_read（§40）。
 *
 * §40「会话」没有专属 ToolDomain，这里把工具归到 ToolDomain.MEMORY（最接近的持久上下文读取），
 * 可用性由工具 availability 按 env.conversationBound 判定（见返回报告）。
 *
 * [loader] 由主流程绑定当前运行会话的脱敏 transcript 快照。
 */
internal class ConversationToolProvider(
    loader: () -> List<AgentModelClient.ConversationMessage> = { emptyList() },
) : ToolProvider {

    override val tools: List<AgentTool> = listOf(
        ContractTool(ConversationReadTool(RuntimeConversationBackend(loader))),
    )
}
