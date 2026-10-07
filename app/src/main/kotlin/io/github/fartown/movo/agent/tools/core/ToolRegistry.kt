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

    /**
     * 至少有一个工具可用的领域，才注入它的用法分节；通用规则分节只要有任何工具可用就注入。
     * 最后加一节「这次用不了的能力」（[unavailableSection]）。
     */
    fun promptSections(env: ToolEnvironment): List<PromptSection> {
        val anyAvailable = tools.any { tool -> isAvailable(tool, env) }
        return providers.mapNotNull { provider ->
            provider.promptSection(env)?.takeIf {
                if (provider.promptSectionCoversAllTools) anyAvailable
                else provider.tools.any { tool -> isAvailable(tool, env) }
            }
        } + listOfNotNull(unavailableSection(env))
    }

    /**
     * 因为缺权限或没有 Root 而不在目录里的工具：写明能做什么、缺什么。不写的话模型不知道「能做、只是缺授权」，
     * 会去系统设置里翻、用终端绕（真机：查使用时长 171 秒、找订单 224 秒，最后也没做成），没核实就说做到了。
     * 用户在设置里关掉的能力另有「用户关闭的能力」说明，这里不重复。
     */
    fun unavailableSection(env: ToolEnvironment): PromptSection? {
        val lines = tools.mapNotNull { tool ->
            val unavailable = tool.availability(env) as? ToolAvailability.Unavailable ?: return@mapNotNull null
            if (unavailable.code != ToolErrorCode.PERMISSION_REQUIRED && unavailable.code != ToolErrorCode.ROOT_REQUIRED) {
                return@mapNotNull null
            }
            if (ToolSwitchGate.check(tool.name, env.switches) != null) return@mapNotNull null
            "- ${tool.name}（${purposeOf(tool)}）：${unavailable.reason}"
        }
        if (lines.isEmpty()) return null
        return PromptSection(
            id = "unavailable",
            domain = ToolDomain.META,
            text = "## 这次用不了的能力\n" +
                "下面这些工具因为缺权限或没有 Root，这次不在目录里。用户要用到时，直接告诉他缺什么、去哪里开；" +
                "不要自己去系统设置里找、不要用终端或界面操作绕过去，没做到就不要说做到了。\n" +
                lines.joinToString("\n"),
        )
    }

    /** 工具能做什么：说明的第一句，太长截断。 */
    private fun purposeOf(tool: AgentTool): String {
        val first = tool.description.substringBefore('。').substringBefore('；').trim()
        return if (first.length > 30) first.take(30) + "…" else first
    }

    override fun close() {
        providers.forEach { provider -> runCatching { provider.close() } }
    }
}
