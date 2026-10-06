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
    val structured: JSONObject?,
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
    val json = JSONObject()
        .put("server", output.server)
        .put("tool", output.tool)
        .put("content", JSONArray(output.content))
    output.structured?.let { json.put("structured", it) }
    if (output.truncated) json.put("truncated", true)
    if (output.images.isNotEmpty()) json.put("images", output.images.size)
    return ModelContent.Json(json)
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

    override val summary: String = buildString {
        append("MCP 服务器「").append(entry.server.name).append("」的工具 ")
        append(entry.definition.name).append('。')
        val description = entry.definition.description.trim()
        if (description.isNotBlank()) {
            append("以下为第三方描述：").append(description)
        }
    }.take(MAX_DESCRIPTION_CHARS)

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

    override fun images(output: McpToolOutput): List<AgentModelClient.ModelImage> = output.images

    private companion object {
        const val MAX_DESCRIPTION_CHARS = 200
    }
}

/** MCP 返回内容：文本开头 + 结构化结果的键值；图片由 ContractTool 加进视图。 */
internal fun mcpUiView(output: McpToolOutput): ToolUiView {
    val text = output.content.joinToString("\n\n").trim()
    val blocks = listOfNotNull(
        text.takeIf { it.isNotBlank() }?.let { ToolUiBlock.Preview(it, more = output.truncated) },
        output.structured?.takeIf { it.length() > 0 && text.isBlank() }?.uiFields(),
    )
    return ToolUiView(
        summary = when {
            text.isNotBlank() -> "返回 ${text.length} 字"
            output.structured != null -> "返回 ${output.structured.length()} 项"
            output.images.isNotEmpty() -> "返回 ${output.images.size} 张图片"
            else -> "完成"
        },
        blocks = blocks,
    )
}

