package io.github.fartown.movo.agent.tools.mcp

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

/**
 * mcp_find：超预算时的固定只读工具。按关键词返回匹配工具的名字与 inputSchema 文本（默认前 8 个）。
 * schema 固定，不随加载变化，不破坏缓存。拿到 name 后用 mcp_call 调用。
 */
internal data class McpFindInput(
    val query: String,
    val limit: Int,
) : ToolInput

internal data class McpFindMatch(
    val name: String,
    val server: String,
    val description: String,
    val inputSchema: String,
    val readOnly: Boolean,
)

internal data class McpFindOutput(
    val matches: List<McpFindMatch>,
    val total: Int,
) : ToolOutput

internal class McpFindTool(
    private val catalog: McpCatalog,
) : ToolContract<McpFindInput, McpFindOutput> {
    override val name = "mcp_find"
    override val domain = ToolDomain.MCP
    override val summary =
        "按关键词查已加载的 MCP 外部工具，返回名字、所属服务器与参数 schema（默认前 8 个）；" +
            "拿到 name 再用 mcp_call 调用。留空 query 则按顺序列出。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("query", "关键词，匹配工具名/标题/描述；留空列出全部", required = false, maxLength = 128)
        integer("limit", "最多返回几个，默认 8", required = false, min = 1, max = 50)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): McpFindInput {
        val query = args.string("query", "").trim()
        val limit = args.int("limit", DEFAULT_LIMIT, 1..MAX_LIMIT)
        return McpFindInput(query, limit)
    }

    override fun resolve(input: McpFindInput, env: ToolEnvironment): CallResolution = CallResolution(
        risk = Risk.READ,
        sensitivity = Sensitivity.PRIVATE,
        resources = emptySet(),
    )

    override fun execute(
        input: McpFindInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<McpFindOutput> {
        val keyword = input.query.lowercase()
        val filtered = catalog.entries.filter { entry ->
            keyword.isEmpty() ||
                entry.shortName.lowercase().contains(keyword) ||
                entry.definition.name.lowercase().contains(keyword) ||
                entry.definition.title.lowercase().contains(keyword) ||
                entry.definition.description.lowercase().contains(keyword)
        }
        val matches = filtered.take(input.limit).map { entry ->
            McpFindMatch(
                name = entry.shortName,
                server = entry.server.name,
                description = entry.definition.description.trim().take(MAX_DESCRIPTION_CHARS),
                inputSchema = entry.definition.inputSchemaJson,
                readOnly = entry.definition.readOnlyHint == true,
            )
        }
        return Verdict.Read(McpFindOutput(matches, filtered.size))
    }

    override fun renderForModel(output: McpFindOutput): ModelContent {
        val array = JSONArray()
        output.matches.forEach { match ->
            val descriptionBlock = if (match.description.isBlank()) {
                JSONObject.NULL
            } else {
                "以下为第三方描述：${match.description}"
            }
            array.put(
                JSONObject()
                    .put("name", match.name)
                    .put("server", match.server)
                    .put("read_only", match.readOnly)
                    .put("description", descriptionBlock)
                    .put("input_schema", runCatching { JSONObject(match.inputSchema) }.getOrElse { match.inputSchema }),
            )
        }
        val json = JSONObject()
            .put("matches", array)
            .put("total", output.total)
            .put("shown", output.matches.size)
        return ModelContent.Json(json)
    }

    private companion object {
        const val DEFAULT_LIMIT = 8
        const val MAX_LIMIT = 50
        const val MAX_DESCRIPTION_CHARS = 200
    }
}
