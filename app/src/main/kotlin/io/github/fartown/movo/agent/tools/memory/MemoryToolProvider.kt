package io.github.fartown.movo.agent.tools.memory

import android.content.Context
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.MemoryScope
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
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

    override fun promptSection(env: ToolEnvironment) = PromptSection(
        id = "memory",
        domain = ToolDomain.MEMORY,
        text = if (env.memoryScope == MemoryScope.CHARACTER) {
            // 重构前角色会话用单独的 character_memory_* 工具，约束写在它的说明里；现在合进 memory_write，约束写在这里。
            "这是角色会话：memory_write 写的是本角色的剧情记忆（虚构的经历、关系和场景连续性），这个角色的所有会话共用。" +
                "先 memory_read（scope=character）看现状再写；「# 核心记忆」保持简短，过时的事实就地改，不要重复追加。" +
                "剧情不能写进用户的现实记忆（scope=user 在角色会话里只读），也不要把剧情里的动作当成已经完成的设备操作。" +
                "写入方式：append 追加、replace 唯一匹配替换、clear 清空（需 revision）。"
        } else {
            "长期记忆跨会话保留：先 memory_read 看现状再写；只记稳定、长期有用的事实。" +
                "写入用 memory_write——append 追加、replace 唯一匹配替换、clear 清空（需 revision）。"
        },
    )
}
