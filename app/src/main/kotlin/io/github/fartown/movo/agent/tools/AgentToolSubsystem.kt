package io.github.fartown.movo.agent.tools

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.browser.BrowserToolProvider
import io.github.fartown.movo.agent.tools.clockmedia.ClockMediaToolProvider
import io.github.fartown.movo.agent.tools.conversation.ConversationToolProvider
import io.github.fartown.movo.agent.tools.core.ApprovalRuleStore
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolGuard
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.agent.tools.device.DeviceToolProvider
import io.github.fartown.movo.agent.tools.file.FileToolProvider
import io.github.fartown.movo.agent.tools.mcp.McpCatalog
import io.github.fartown.movo.agent.tools.mcp.McpToolProvider
import io.github.fartown.movo.agent.monitor.MonitorToolProvider
import io.github.fartown.movo.agent.tools.memory.MemoryToolProvider
import io.github.fartown.movo.agent.tools.meta.MetaToolProvider
import io.github.fartown.movo.agent.tools.personal.PersonalToolProvider
import io.github.fartown.movo.agent.tools.skill.SkillToolProvider
import io.github.fartown.movo.agent.tools.terminal.TerminalToolProvider
import io.github.fartown.movo.agent.tools.ui.UiToolProvider

/**
 * 工具子系统总装：把 11 个领域 Provider 与元工具装配成一个 [ToolPipeline]（实现 AgentModelClient.ToolExecutor）。
 * 一次运行构造一个实例，结束时 [close]。目录、系统提示分节、执行、审批、投影全部由 pipeline 统一出口。
 *
 * 领域所有权对应实施方案 §4.1：device / clockmedia / ui / personal / file / terminal / browser /
 * memory / skill / conversation / mcp，加 meta（tool_search、ask_user）。
 */
internal class AgentToolSubsystem(
    private val services: ToolServices,
    private val environment: () -> ToolEnvironment,
    interaction: UserInteraction = UserInteraction.NONE,
    cancelled: () -> Boolean = { false },
    mcpCatalog: McpCatalog = McpCatalog.EMPTY,
    characterId: () -> String? = { null },
    conversationLoader: () -> List<AgentModelClient.ConversationMessage> = { emptyList() },
    approvalRules: ApprovalRuleStore = ApprovalRuleStore.IN_MEMORY,
    guards: List<ToolGuard> = emptyList(),
) : AutoCloseable {
    private val meta = MetaToolProvider()

    private val providers: List<ToolProvider> = buildProviders(
        mcpCatalog = mcpCatalog,
        characterId = characterId,
        conversationLoader = conversationLoader,
    )

    val pipeline: ToolPipeline = ToolPipeline(
        registry = ToolRegistry(providers),
        environment = environment,
        appContext = services.context,
        logger = services.logger,
        runId = services.runId,
        cancelled = cancelled,
        interaction = interaction,
        approvalRules = approvalRules,
        guards = guards,
    ).also { meta.pipeline = it }

    private fun buildProviders(
        mcpCatalog: McpCatalog,
        characterId: () -> String?,
        conversationLoader: () -> List<AgentModelClient.ConversationMessage>,
    ): List<ToolProvider> {
        val context = services.context
        val logger = services.logger
        val root = services.root()
        val rootAvailable = services.rootAvailable
        return listOf(
            DeviceToolProvider(context, root, rootAvailable),
            ClockMediaToolProvider(context, logger, root, rootAvailable),
            UiToolProvider(context, logger, rootAvailable),
            PersonalToolProvider(context, root, rootAvailable),
            FileToolProvider(context, root, rootAvailable),
            TerminalToolProvider(logger),
            BrowserToolProvider(context),
            MemoryToolProvider(context, characterId),
            SkillToolProvider(context),
            ConversationToolProvider(conversationLoader),
            McpToolProvider(mcpCatalog),
            MonitorToolProvider(context),
            meta,
        )
    }

    override fun close() {
        pipeline.close()
    }
}
