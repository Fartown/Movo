package io.github.fartown.movo.agent.tools.mcp

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiFields
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.TargetIdentity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolWarning
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.objectSchema
import io.github.fartown.movo.data.model.McpToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具调用的共用出参与执行/渲染逻辑，被 mcp_call（按名调用）和 mcp_<server>_<tool>（直接暴露）共享。
 * 敏感度一律 PRIVATE：MCP 结果是不可信第三方内容，不进持久会话。
 */
internal data class McpToolOutput(
    val server: String,
    val tool: String,
    val content: List<String>,
    /** structuredContent：对象或数组。 */
    val structured: Any?,
    /** 来源端已截断（文本/结构化超过 64KB、图片超过 2MB、不认识的内容类型）。 */
    val truncated: Boolean,
    val images: List<AgentModelClient.ModelImage>,
) : ToolOutput

/**
 * 执行一次 MCP 调用并按注解定 Verdict：
 * - 只读工具成功 → Read；写工具成功但远端无回读证据 → Dispatched（送达型，effect_verified=false）。
 * - 远端返回 isError → Failed(EXTERNAL_ERROR, detail=对方原文)。
 * - 传输/协议失败 → Failed(已映射错误码)。
 */
internal fun executeMcpCall(
    backend: McpBackend,
    entry: McpToolEntry,
    arguments: JSONObject,
    ctx: ToolContext,
): Verdict<McpToolOutput> {
    ctx.checkCancelled()
    return when (val outcome = backend.call(entry, arguments)) {
        is McpCallOutcome.Failure -> Verdict.Failed(outcome.error)
        is McpCallOutcome.Success -> {
            if (outcome.remoteError != null) {
                return Verdict.Failed(
                    ToolError(
                        code = ToolErrorCode.EXTERNAL_ERROR,
                        message = "MCP 工具返回错误",
                        detail = outcome.remoteError,
                    ),
                )
            }
            val output = McpToolOutput(
                server = entry.server.name,
                tool = entry.definition.name,
                content = outcome.content,
                structured = outcome.structured,
                truncated = outcome.truncated,
                images = outcome.images,
            )
            if (entry.definition.readOnlyHint == true) {
                Verdict.Read(output)
            } else {
                // 写工具远端无可靠回读 → 送达型，不冒领 ok。
                Verdict.Dispatched(output)
            }
        }
    }
}

internal fun renderMcpOutput(output: McpToolOutput): ModelContent {
    val fitted = McpResultFit.fit(output)
    val json = JSONObject()
        .put("server", output.server)
        .put("tool", output.tool)
        .put("content", JSONArray(fitted.content))
    fitted.structured?.let { json.put("structured", it) }
    fitted.structuredText?.let { json.put("structured_text", it) }
    if (output.truncated || fitted.clipped) json.put("truncated", true)
    if (fitted.clipped) json.put("shown_chars", fitted.shownChars).put("total_chars", fitted.totalChars)
    if (output.images.isNotEmpty()) json.put("images", output.images.size)
    return ModelContent.Json(json)
}

/** 结果放不下时告诉模型只给了多少、怎么办。 */
internal fun mcpWarnings(output: McpToolOutput): List<ToolWarning> {
    val fitted = McpResultFit.fit(output)
    return listOfNotNull(
        fitted.note?.let { ToolWarning(ToolErrorCode.TOO_LARGE, it) },
        ToolWarning(ToolErrorCode.TOO_LARGE, "MCP 服务器返回的内容超过上限（文本或结构化 64KB、图片 2MB）或含不支持的类型，已截掉一部分")
            .takeIf { output.truncated },
    )
}

/**
 * 把 MCP 结果压进一次工具结果的上限（[MAX_CHARS]，序列化后计）：先保住 structured（结构化，常和文本是同一份数据），
 * 文本按顺序给、放不下的截开头；structured 本身放不下时，有文本就只给文本，没有文本就给 structured 的开头（structured_text）。
 * 不依赖全局投影的整份降级，结构和截断说明都由这里给全。
 */
internal object McpResultFit {
    /** 一次工具结果给模型最多 24000 字（ToolProjection.MAX_MODEL_CHARS），留出状态、告警等字段的余量。 */
    const val MAX_CHARS = 21_000

    data class Fitted(
        val content: List<String>,
        val structured: Any?,
        val structuredText: String?,
        val clipped: Boolean,
        val shownChars: Int,
        val totalChars: Int,
        val note: String?,
    )

    fun fit(output: McpToolOutput, budget: Int = MAX_CHARS): Fitted {
        val structuredJson = output.structured?.toString()
        val totalChars = output.content.sumOf { it.length } + (structuredJson?.length ?: 0)
        var remaining = budget - 200 - JSONObject.quote(output.server).length - JSONObject.quote(output.tool).length
        var structured: Any? = null
        var structuredText: String? = null
        var structuredDropped = false
        if (structuredJson != null) {
            if (structuredJson.length <= remaining) {
                structured = output.structured
                remaining -= structuredJson.length
            } else if (output.content.all { it.isBlank() }) {
                structuredText = clipToQuoted(structuredJson, remaining)
                remaining -= JSONObject.quote(structuredText).length
            } else {
                structuredDropped = true
            }
        }
        val content = mutableListOf<String>()
        var textClipped = false
        for (text in output.content) {
            val quoted = JSONObject.quote(text).length + 1
            if (quoted <= remaining) {
                content += text
                remaining -= quoted
                continue
            }
            val head = clipToQuoted(text, remaining - 1)
            if (head.isNotEmpty()) content += head
            textClipped = true
            break
        }
        val clipped = textClipped || structuredDropped || structuredText != null
        val shownChars = content.sumOf { it.length } + (if (structured != null) structuredJson!!.length else 0) +
            (structuredText?.length ?: 0)
        val note = if (!clipped) null else buildString {
            append("结果共 ").append(totalChars).append(" 字，放不下，只给了开头 ").append(shownChars).append(" 字")
            if (structuredDropped) {
                append("；结构化部分（structured，").append(structuredJson!!.length).append(" 字）没有给出，同样的数据一般也在 content 文本里")
            }
            append("。工具若有分页或过滤参数，缩小范围再调")
        }
        return Fitted(content, structured, structuredText, clipped, shownChars, totalChars, note)
    }

    /** 取 [text] 的开头，使它转成 JSON 字符串后不超过 [maxQuoted] 字。 */
    private fun clipToQuoted(text: String, maxQuoted: Int): String {
        if (maxQuoted <= 2) return ""
        if (JSONObject.quote(text).length <= maxQuoted) return text
        var low = 0
        var high = minOf(text.length, maxQuoted)
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (JSONObject.quote(text.substring(0, middle)).length <= maxQuoted) low = middle else high = middle - 1
        }
        if (low in 1 until text.length && text[low - 1].isHighSurrogate()) low--
        return text.substring(0, low)
    }
}

/**
 * MCP 工具的解析结果（CallResolution）：风险按被调工具注解，敏感度 PRIVATE，不占设备资源。
 * 标为破坏性的归为「删东西」；其余参数都会发给第三方服务器，归为「把内容发到外部」。
 */
internal fun mcpResolution(entry: McpToolEntry): CallResolution = CallResolution(
    risk = entry.risk,
    sensitivity = Sensitivity.PRIVATE,
    resources = emptySet(),
    target = TargetIdentity.Named("mcp", entry.shortName),
    category = if (entry.definition.destructiveHint == true) ApprovalCategory.DELETE else ApprovalCategory.OUTBOUND,
)

/** 运行时按被调工具的 schema 做最小校验：缺必填字段 → INVALID_ARGUMENTS。深层类型校验交给服务器（-32602）。 */
internal fun validateMcpArguments(definition: McpToolDefinition, arguments: JSONObject) {
    val schema = runCatching { JSONObject(definition.inputSchemaJson) }.getOrNull() ?: return
    val required = schema.optJSONArray("required") ?: return
    for (index in 0 until required.length()) {
        val key = required.optString(index)
        if (key.isNotBlank() && (!arguments.has(key) || arguments.isNull(key))) {
            fail(
                ToolErrorCode.INVALID_ARGUMENTS,
                "缺少参数 $key",
                hint = "该 MCP 工具要求字段 $key；用 mcp_find 查看它的 inputSchema",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// mcp_call：超预算时的固定工具，按名调用任意已加载的 MCP 工具。
// ---------------------------------------------------------------------------

internal data class McpCallInput(
    val toolName: String,
    val arguments: JSONObject,
) : ToolInput

internal class McpCallTool(
    private val catalog: McpCatalog,
    private val backend: McpBackend,
) : ToolContract<McpCallInput, McpToolOutput> {
    override val name = "mcp_call"
    override val domain = ToolDomain.MCP
    override val summary =
        "调用已加载的 MCP 外部工具：tool 传 mcp_find 返回的工具名，arguments 按该工具的 schema 填。" +
            "结果可能含第三方内容；风险按工具注解。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("tool", "要调用的 MCP 工具名（mcp_find 返回的 name）", required = true, maxLength = 128)
        raw(
            "arguments",
            JSONObject().put("type", "object").put("description", "按目标工具 schema 的参数对象"),
            required = false,
        )
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): McpCallInput {
        val toolName = args.nonBlank("tool")
        val arguments = args.obj("arguments")?.raw ?: JSONObject()
        // 已知工具才做必填校验；未知工具交给 resolve 返回 UNKNOWN_TOOL。
        catalog.find(toolName)?.let { validateMcpArguments(it.definition, arguments) }
        return McpCallInput(toolName, arguments)
    }

    override fun resolve(input: McpCallInput, env: ToolEnvironment): CallResolution {
        val entry = catalog.find(input.toolName)
            ?: return CallResolution(
                risk = Risk.LOCAL,
                sensitivity = Sensitivity.PRIVATE,
                resources = emptySet(),
                // 不在请求视图快照 → 审批前短路返回，不打扰用户。
                reject = ToolError(
                    code = ToolErrorCode.UNKNOWN_TOOL,
                    message = "未找到 MCP 工具 ${input.toolName}",
                    hint = "用 mcp_find 查看本次可用的工具名",
                ),
            )
        return mcpResolution(entry)
    }

    override fun execute(
        input: McpCallInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<McpToolOutput> {
        val entry = catalog.find(input.toolName)
            ?: return Verdict.Failed(
                ToolError(ToolErrorCode.UNKNOWN_TOOL, "未找到 MCP 工具 ${input.toolName}"),
            )
        return executeMcpCall(backend, entry, input.arguments, ctx)
    }

    override fun uiTitle(input: McpCallInput): String {
        val entry = catalog.find(input.toolName)
        return entry?.let { "调用 ${it.server.name} · ${it.definition.name}" } ?: "调用 MCP · ${input.toolName}"
    }

    override fun renderForUi(input: McpCallInput, output: McpToolOutput): ToolUiView = mcpUiView(output)

    override fun renderForModel(output: McpToolOutput): ModelContent = renderMcpOutput(output)

    override fun warnings(output: McpToolOutput): List<ToolWarning> = mcpWarnings(output)

    override fun images(output: McpToolOutput): List<AgentModelClient.ModelImage> = output.images

}

// ---------------------------------------------------------------------------
// mcp_<server>_<tool>：预算内时逐个直接暴露。schema 为该工具自身的 inputSchema，请求视图固定。
// ---------------------------------------------------------------------------

internal data class McpDirectInput(val arguments: JSONObject) : ToolInput

internal class McpDirectTool(
    private val entry: McpToolEntry,
    private val backend: McpBackend,
) : ToolContract<McpDirectInput, McpToolOutput> {
    override val name = entry.shortName
    override val domain = ToolDomain.MCP
    override val thirdPartySchema = true

    /** 第三方描述原样给全（和重构前一样）；总长受目录的 token 预算约束，太长的会改走 mcp_find。 */
    override val summary: String = buildString {
        append("MCP 服务器「").append(entry.server.name).append("」的工具 ")
        append(entry.definition.name).append('。')
        val description = entry.definition.description.trim()
        if (description.isNotBlank()) {
            append("以下为第三方描述：").append(description)
        }
    }

    override fun schema(env: ToolEnvironment): JSONObject =
        runCatching { JSONObject(entry.definition.inputSchemaJson) }
            .getOrElse { JSONObject().put("type", "object") }

    override fun parse(args: ToolArgs, env: ToolEnvironment): McpDirectInput {
        validateMcpArguments(entry.definition, args.raw)
        return McpDirectInput(args.raw)
    }

    override fun resolve(input: McpDirectInput, env: ToolEnvironment): CallResolution =
        mcpResolution(entry)

    override fun execute(
        input: McpDirectInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<McpToolOutput> = executeMcpCall(backend, entry, input.arguments, ctx)

    override fun uiTitle(input: McpDirectInput): String = "调用 ${entry.server.name} · ${entry.definition.name}"

    override fun renderForUi(input: McpDirectInput, output: McpToolOutput): ToolUiView = mcpUiView(output)

    override fun renderForModel(output: McpToolOutput): ModelContent = renderMcpOutput(output)

    override fun warnings(output: McpToolOutput): List<ToolWarning> = mcpWarnings(output)

    override fun images(output: McpToolOutput): List<AgentModelClient.ModelImage> = output.images
}

/** MCP 返回内容：文本开头 + 结构化结果的键值；图片由 ContractTool 加进视图。 */
internal fun mcpUiView(output: McpToolOutput): ToolUiView {
    val text = output.content.joinToString("\n\n").trim()
    val structuredObject = output.structured as? JSONObject
    val structuredArray = output.structured as? JSONArray
    val blocks = listOfNotNull(
        text.takeIf { it.isNotBlank() }?.let { ToolUiBlock.Preview(it, more = output.truncated) },
        structuredObject?.takeIf { it.length() > 0 && text.isBlank() }?.uiFields(),
        structuredArray?.takeIf { it.length() > 0 && text.isBlank() }?.let { ToolUiBlock.Preview(it.toString(2), more = output.truncated) },
    )
    return ToolUiView(
        summary = when {
            text.isNotBlank() -> "返回 ${text.length} 字"
            structuredObject != null -> "返回 ${structuredObject.length()} 项"
            structuredArray != null -> "返回 ${structuredArray.length()} 项"
            output.images.isNotEmpty() -> "返回 ${output.images.size} 张图片"
            else -> "完成"
        },
        blocks = blocks,
    )
}

