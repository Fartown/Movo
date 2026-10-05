package io.github.fartown.movo.agent.tools.core

import io.github.fartown.movo.agent.model.AgentToolSchema
import org.json.JSONArray

/**
 * 一次运行的工具登记表。目录、可用性、系统提示分节都从这里派生，
 * 不再有按工具名登记的旁路表。
 */
internal class ToolRegistry(private val providers: List<ToolProvider>) : AutoCloseable {
    val tools: List<AgentTool> = providers.flatMap { it.tools }

    private val byName: Map<String, AgentTool> = buildMap {
        tools.forEach { tool ->
            check(put(tool.name, tool) == null) { "Duplicate tool: ${tool.name}" }
        }
    }

    fun find(name: String): AgentTool? = byName[name]

    fun isAvailable(tool: AgentTool, env: ToolEnvironment): Boolean = availability(tool, env) == ToolAvailability.Available

    /** 开关（[ToolSwitchGate]）优先，再看工具自己的条件（权限、Root、来源）。 */
    fun availability(tool: AgentTool, env: ToolEnvironment): ToolAvailability =
        ToolSwitchGate.check(tool.name, env.switches) ?: tool.availability(env)

    /** 本轮下发给模型的目录：常驻工具加已加载的按需工具，且当前可用。顺序稳定，以保住提示缓存。 */
    fun catalog(env: ToolEnvironment, loadedDeferred: Set<String>): JSONArray = JSONArray().also { array ->
        tools.forEach { tool ->
            if (tool.exposure == Exposure.DEFERRED && tool.name !in loadedDeferred) return@forEach
            if (!isAvailable(tool, env)) return@forEach
            array.put(AgentToolSchema.function(tool.name, tool.description, tool.parameters(env)))
        }
    }

    fun hasDeferred(env: ToolEnvironment, loadedDeferred: Set<String>): Boolean =
        tools.any { it.exposure == Exposure.DEFERRED && it.name !in loadedDeferred && isAvailable(it, env) }

    fun deferredTools(): List<AgentTool> = tools.filter { it.exposure == Exposure.DEFERRED }

    /** 至少有一个工具可用的领域，才注入它的用法分节。 */
    fun promptSections(env: ToolEnvironment): List<PromptSection> =
        providers.mapNotNull { provider ->
            provider.promptSection(env)?.takeIf { provider.tools.any { tool -> isAvailable(tool, env) } }
        }

    override fun close() {
        providers.forEach { provider -> runCatching { provider.close() } }
    }
}
