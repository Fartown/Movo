package io.github.fartown.movo.agent.tools.skill

import android.content.Context
import io.github.fartown.movo.agent.tools.core.AgentTool
import io.github.fartown.movo.agent.tools.core.ContractTool
import io.github.fartown.movo.agent.tools.core.PromptSection
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolProvider

/**
 * 技能领域工具 Provider：skill_read（§38）、skill_install（§39）。
 * install 后端持有 OkHttpClient 与 GitHub 来源，运行结束 close 时释放。
 */
internal class SkillToolProvider(
    context: Context,
) : ToolProvider {

    private val installBackend = AndroidSkillInstallBackend(context)

    override val tools: List<AgentTool> = listOf(
        ContractTool(SkillReadTool(AndroidSkillReadBackend(context))),
        ContractTool(SkillInstallTool(installBackend)),
    )

    override val promptSection = PromptSection(
        id = "skill",
        domain = ToolDomain.SKILL,
        text = "技能索引已在系统提示里。用 skill_read 读某个技能的 SKILL.md 或目录资源；脚本靠 terminal_run 执行。" +
            "用 skill_install 从公开 GitHub 发现（curated/inspect）和安装（install）技能，装完下一次任务才可用。",
    )

    override fun close() {
        runCatching { installBackend.close() }
    }
}
