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
 * [loader] 由主流程绑定：这个对话在数据库里的完整记录（journal）+ 本轮到目前为止的步骤。
 */
internal class ConversationToolProvider(
    loader: () -> List<AgentModelClient.ConversationMessage> = { emptyList() },
) : ToolProvider {

    override val tools: List<AgentTool> = listOf(
        ContractTool(ConversationReadTool(RuntimeConversationBackend(loader))),
    )
}
