package io.github.fartown.movo.agent.tools.device

import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject

/** 一条应用检索结果（含系统应用）。 */
internal data class AppMatch(val name: String, val packageName: String, val isSystem: Boolean)

internal data class AppSearchInput(val query: String, val limit: Int) : ToolInput

internal data class AppSearchOutput(val apps: List<AppMatch>) : ToolOutput

/** 可测后端：按名称/包名搜索已安装应用（数据源取桌面图标应用，默认含系统应用）。 */
internal interface AppSearchBackend {
    fun search(query: String, limit: Int): List<AppMatch>
}

/** app_search（只读）：按名称搜索已安装应用（含系统应用），返回应用名与包名。 */
internal class AppSearchTool(
    private val backend: AppSearchBackend,
) : ToolContract<AppSearchInput, AppSearchOutput> {
    override val name = "app_search"
    override val domain = ToolDomain.APP
    override val summary =
        "按名称搜索已安装应用（含相机、设置等系统应用），返回 name、package、is_system。" +
            "打开应用前不确定包名时用。无匹配返回空数组。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("query", "应用名或包名关键词，1–100", required = true, minLength = 1, maxLength = 100)
        integer("limit", "返回条数，1–20，默认 10", min = 1, max = 20)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): AppSearchInput =
        AppSearchInput(
            query = args.nonBlank("query"),
            limit = args.int("limit", default = 10, range = 1..20),
        )

    override fun resolve(input: AppSearchInput, env: ToolEnvironment): CallResolution =
        CallResolution(risk = Risk.READ, sensitivity = Sensitivity.NORMAL, resources = emptySet())

    override fun execute(
        input: AppSearchInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<AppSearchOutput> {
        ctx.checkCancelled()
        val apps = backend.search(input.query, input.limit).take(input.limit)
        return Verdict.Read(AppSearchOutput(apps))
    }

    override fun renderForModel(output: AppSearchOutput): ModelContent {
        val array = JSONArray()
        output.apps.forEach { app ->
            array.put(
                JSONObject()
                    .put("name", app.name)
                    .put("package", app.packageName)
                    .put("is_system", app.isSystem),
            )
        }
        return ModelContent.Json(JSONObject().put("apps", array).put("count", output.apps.size))
    }
}
