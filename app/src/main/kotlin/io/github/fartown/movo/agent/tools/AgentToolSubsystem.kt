package io.github.fartown.movo.agent.tools

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolPipeline
import io.github.fartown.movo.agent.tools.core.ToolGuard
import io.github.fartown.movo.agent.tools.core.ToolProvider
import io.github.fartown.movo.agent.tools.core.ToolRegistry
import io.github.fartown.movo.agent.tools.core.UserInteraction
import io.github.fartown.movo.flavor.FlavorModule
import io.github.fartown.movo.agent.tools.mcp.McpCatalog
import io.github.fartown.movo.agent.tools.meta.MetaToolProvider

/**
 * 工具子系统总装：把本设备装配的领域 Provider（由 FlavorModule 按设备白名单给出）与元工具装配成一个
 * [ToolPipeline]（实现 AgentModelClient.ToolExecutor）。
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
    guards: List<ToolGuard> = emptyList(),
    /** 每个调用执行前现读用户开关（见 [ToolPipeline] 同名参数）。 */
    refreshSwitches: (ToolEnvironment) -> ToolEnvironment = { it },
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
        guards = guards,
        refreshSwitches = refreshSwitches,
    ).also { meta.pipeline = it }

    private fun buildProviders(
        mcpCatalog: McpCatalog,
        characterId: () -> String?,
        conversationLoader: () -> List<AgentModelClient.ConversationMessage>,
    ): List<ToolProvider> =
        FlavorModule.toolProviders(
            ToolProviderInputs(
                services = services,
                mcpCatalog = mcpCatalog,
                characterId = characterId,
                conversationLoader = conversationLoader,
            ),
        ) + meta

    override fun close() {
        pipeline.close()
    }
}

/** 装配领域 Provider 所需的输入；各设备在 FlavorModule.toolProviders 里按白名单取用。 */
internal class ToolProviderInputs(
    val services: ToolServices,
    val mcpCatalog: McpCatalog,
    val characterId: () -> String?,
    val conversationLoader: () -> List<AgentModelClient.ConversationMessage>,
)
