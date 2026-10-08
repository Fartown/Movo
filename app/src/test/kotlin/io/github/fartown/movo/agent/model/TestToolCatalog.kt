package io.github.fartown.movo.agent.model

import io.github.fartown.movo.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject

/**
 * 测试用的小工具目录：给直接调 [AgentModelClient.complete] / [AgentLoop] 的测试声明它们脚本里会调用的工具。
 * 线上目录由类型化工具子系统给出（typedCatalog）。terminal 的 root 身份和 set_setting 只在有 Root 时出现，
 * 用来测「每轮按同一份能力快照声明、校验」。
 */
internal object TestToolCatalog {
    fun build(capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = false)): JSONArray =
        JSONArray()
            .put(function("get_current_context", JSONObject()))
            .put(function("observe_screen", JSONObject()))
            .put(
                function(
                    "tap",
                    JSONObject().put("x", type("integer")).put("y", type("integer")),
                    required = listOf("x", "y"),
                ),
            )
            .put(
                function(
                    "terminal",
                    JSONObject()
                        .put("action", type("string"))
                        .put("command", type("string"))
                        .put(
                            "identity",
                            type("string").put(
                                "enum",
                                JSONArray().put("user").also { if (capabilities.rootAvailable) it.put("root") },
                            ),
                        ),
                ),
            )
            .also { tools ->
                if (capabilities.rootAvailable) tools.put(function("set_setting", JSONObject()))
            }

    private fun type(name: String) = JSONObject().put("type", name)

    private fun function(name: String, properties: JSONObject, required: List<String> = emptyList()): JSONObject =
        AgentToolSchema.function(
            name,
            name,
            JSONObject().put("type", "object").put("properties", properties).also { parameters ->
                if (required.isNotEmpty()) parameters.put("required", JSONArray(required))
            },
        )
}
