package io.github.fartown.movo.agent.tools.memory

import android.content.Context
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolProvider

/**
 * 记忆领域工具 Provider：memory_read（§36）、memory_write（§37）。
 * 可用性由各工具 availability 按 env.memoryScope 判定，禁用时整体不进目录。
 *
 * [characterId] 由主流程接线当前角色会话标识（非角色会话返回 null）。
 */
internal class MemoryToolProvider(
    context: Context,
    characterId: () -> String? = { null },
) : ToolProvider {

    private val backend = AndroidMemoryBackend(context, characterId)

    override val tools: List<AgentTool> = listOf(
        ContractTool(MemoryReadTool(backend)),
        ContractTool(MemoryWriteTool(backend)),
    )

    override val promptSection = PromptSection(
        id = "memory",
        domain = ToolDomain.MEMORY,
        text = "长期记忆跨会话保留：先 memory_read 看现状再写；只记稳定、长期有用的事实。" +
            "写入用 memory_write——append 追加、replace 唯一匹配替换、clear 清空（需 revision）。",
    )
}
