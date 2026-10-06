package io.github.fartown.movo.agent.tools.ui

import io.github.fartown.movo.agent.model.AgentModelClient
import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject

/** §9 ui_observe（只读）：观察屏幕，返回 observation_id、代际 gen、节点、可选截图。 */

internal data class UiObserveInput(
    val screenshot: Boolean,
    val nodes: Boolean,
    val maxNodes: Int,
    val query: String?,
) : ToolInput

internal data class UiObserveOutput(
    val observed: UiObserveResult.Observed,
    val screenshotRequested: Boolean = false,
) : ToolOutput

internal class UiObserveTool(
    private val backend: UiObserveBackend,
) : ToolContract<UiObserveInput, UiObserveOutput> {
    override val name = "ui_observe"
    override val domain = ToolDomain.UI
    override val summary =
        "观察当前屏幕：前台应用、可见节点、observation_id 与代际 gen，可选附截图。" +
            "节点为 0 或界面是 Canvas/地图/图片/二维码时传 screenshot=true。坐标在返回的 coord_space 中。"

    /** 无障碍和 root 都没有 → 整体不可用（§9）。 */
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.accessibilityUsable || env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.PERMISSION_REQUIRED, "需要无障碍权限才能观察屏幕")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        if (env.screenshotAvailable) boolean("screenshot", "是否附截图，默认 false；用户要求看图、节点为 0 或 Canvas/地图/图片界面时设 true")
        boolean("nodes", "是否返回节点列表，默认 true")
        integer("max_nodes", "节点数量上限 1–120，默认 60", min = 1, max = 120)
        string("query", "可选的文字过滤，只保留匹配节点，减少 token")
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): UiObserveInput = UiObserveInput(
        screenshot = args.bool("screenshot", false).also {
            if (it && !env.screenshotAvailable) io.github.fartown.movo.agent.tools.core.invalidArgs("本设备没有可用的截图接口")
        },
        nodes = args.bool("nodes", true),
        maxNodes = args.int("max_nodes", 60, 1..120),
        query = args.stringOrNull("query")?.trim()?.ifEmpty { null },
    )

    override fun resolve(input: UiObserveInput, env: ToolEnvironment): CallResolution = CallResolution(
        risk = Risk.READ,
        // 屏幕内容属个人数据：private，不进入持久会话。
        sensitivity = Sensitivity.PRIVATE,
        // 观察与动作共用屏幕子系统，按 SCREEN 串行，避免在动作中途观察到半态。
        resources = setOf(ResourceKey(ToolResource.SCREEN)),
    )

    override fun execute(input: UiObserveInput, resolution: CallResolution, ctx: ToolContext): Verdict<UiObserveOutput> {
        ctx.checkCancelled()
        if (!ctx.env.accessibilityUsable && !ctx.env.rootAvailable) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法观察屏幕", hint = "在设置里开启 Movo 无障碍"),
            )
        }
        val request = UiObserveRequest(input.screenshot, input.nodes, input.maxNodes, input.query)
        return when (val result = backend.observe(request, ctx.env)) {
            is UiObserveResult.PermissionRequired -> Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法观察屏幕", hint = "在设置里开启 Movo 无障碍"),
            )
            is UiObserveResult.Observed -> Verdict.Read(UiObserveOutput(result, input.screenshot))
            is UiObserveResult.Unavailable -> Verdict.Failed(
                ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, result.reason),
            )
        }
    }

    override fun uiTitle(input: UiObserveInput): String =
        input.query?.takeIf { it.isNotBlank() }?.let { "在屏幕上找「${it.forTitle()}」" } ?: "查看屏幕"

    /** 摘要写在哪个应用、看到多少元素；截图由 ContractTool 加进视图；没截图时列出看到的主要文字。 */
    override fun renderForUi(input: UiObserveInput, output: UiObserveOutput): ToolUiView {
        val o = output.observed
        val summary = listOfNotNull(
            o.packageName?.takeIf { it.isNotBlank() }?.let { "「${appLabel(it)}」" },
            "${o.nodes.size} 个元素".takeIf { o.nodes.isNotEmpty() },
            "截图".takeIf { o.screenshotAttached },
        ).joinToString(" · ").ifBlank { "已查看" }
        val texts = o.nodes.mapNotNull { node -> (node.text ?: node.desc)?.takeIf { it.isNotBlank() } }.distinct()
        val blocks = if (o.screenshotAttached || texts.isEmpty()) {
            emptyList()
        } else {
            listOf(ToolUiBlock.Items(texts.map { ToolUiBlock.Item(it) }))
        }
        // 屏幕文字和截图一样可能是别的应用的内容（银行、聊天），只在本次运行中显示。
        return ToolUiView(summary = summary, blocks = blocks, transient = blocks.isNotEmpty())
    }

    override fun renderForModel(output: UiObserveOutput): ModelContent {
        val o = output.observed
        val json = JSONObject()
            .put("observation_id", o.observationId)
            .put("gen", o.gen)
            .put("coord_space", JSONObject().put("width", o.coordWidth).put("height", o.coordHeight))
        o.packageName?.let { json.put("package", it) }
        o.focusedIndex?.let { json.put("focused_index", it) }
        if (o.nodes.isNotEmpty()) {
            val arr = JSONArray()
            for (n in o.nodes) arr.put(nodeJson(n))
            json.put("nodes", arr)
        }
        json.put("nodes_truncated", o.nodesTruncated)
        json.put(
            "screenshot",
            JSONObject().put("requested", output.screenshotRequested).put("attached", o.screenshotAttached)
                .apply {
                    o.screenshotQuality?.let { put("quality", it) }
                    o.screenshotFailure?.let { put("failure", it) }
                },
        )
        return ModelContent.Json(json)
    }

    /** 把截图作为本回合图片附给模型（合同新增 images()）。 */
    override fun images(output: UiObserveOutput): List<AgentModelClient.ModelImage> =
        listOfNotNull(output.observed.screenshot)

    private fun nodeJson(n: UiObservedNode): JSONObject = JSONObject().apply {
        put("index", n.index)
        n.text?.let { put("text", it) }
        n.desc?.let { put("desc", it) }
        n.role?.let { put("role", it) }
        n.viewId?.let { put("view_id", it) }
        if (n.bounds.isNotEmpty()) put("bounds", JSONArray(n.bounds))
        n.checked?.let { put("checked", it) }
        n.selected?.let { put("selected", it) }
        n.editable?.let { put("editable", it) }
        n.password?.let { put("password", it) }
        n.enabled?.let { put("enabled", it) }
        if (n.actions.isNotEmpty()) put("actions", JSONArray(n.actions))
    }
}
