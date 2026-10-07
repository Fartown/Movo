package io.github.fartown.movo.agent.tools.browser

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalNeed
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
import io.github.fartown.movo.agent.tools.core.confirmConsequence
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

internal data class BrowserActInput(
    val request: BrowserActRequest,
) : ToolInput

internal data class BrowserActOutput(
    val result: BrowserActResult,
) : ToolOutput

/**
 * §35 browser_act：在当前网页操作 click / type / scroll / select / key。目标优先用 browser_read 的 ref，
 * 其次 selector，再次截图像素坐标（运行时换算回 CSS 像素）。type 语义是整体替换输入框的值。
 *
 * 动作是送达型 → Verdict.Dispatched（effect_verified=false），模型应再 browser_read 确认效果。
 * 命中提交点（表单含密码/支付、method=post、按钮文字命中提交点）且非 search/GET 表单 → 执行前确认。
 */
internal class BrowserActTool(
    private val backend: BrowserBackend,
) : ToolContract<BrowserActInput, BrowserActOutput> {
    override val name = "browser_act"
    override val domain = ToolDomain.BROWSER
    override val summary =
        "操作 Movo 离屏浏览器里的网页（不是手机屏幕上的网页）：click、type（整体替换输入框值，可 submit）、scroll、select、" +
            "key（回车、Esc、Tab、退格、方向键）。目标用 browser_read 的 ref；动作只是送达，需再读页面确认。独占浏览器。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.switches.browser) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.DISABLED, "网页功能已在设置中关闭")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string(
            "action", "操作类型", required = true,
            enum = BrowserActionType.entries.map { it.name.lowercase() },
        )
        string("ref", "browser_read elements 返回的 ref（首选）", maxLength = 128)
        string("selector", "CSS 选择器（次选）", maxLength = 512)
        integer("x", "截图像素横坐标（末选，与 y 同时给）")
        integer("y", "截图像素纵坐标（末选，与 x 同时给）")
        string("text", "type 要输入的文本（整体替换输入框值）", maxLength = 8_000)
        boolean("submit", "type 后是否提交（回车）")
        string("option", "select 要选择的选项（文本或 value）", maxLength = 512)
        string("key", "key 要发送的按键", enum = listOf("enter", "esc", "tab", "backspace", "up", "down", "left", "right"))
        string("direction", "scroll 方向", enum = listOf("up", "down"))
        integer("amount", "scroll 像素量，1–5000，默认 600", min = 1, max = 5_000)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): BrowserActInput {
        val action = args.enum<BrowserActionType>("action")
        val target = BrowserTargetSpec(
            ref = args.stringOrNull("ref")?.trim()?.ifEmpty { null },
            selector = args.stringOrNull("selector")?.trim()?.ifEmpty { null },
            x = args.intOrNull("x"),
            y = args.intOrNull("y"),
        )
        if ((target.x == null) != (target.y == null)) {
            fail(ToolErrorCode.INVALID_ARGUMENTS, "x 与 y 必须同时提供")
        }
        if (target.specifiedCount > 1) {
            fail(ToolErrorCode.INVALID_ARGUMENTS, "ref / selector / 坐标 三选一，只能提供一个")
        }

        when (action) {
            BrowserActionType.CLICK, BrowserActionType.SELECT -> if (target.specifiedCount == 0) {
                fail(ToolErrorCode.INVALID_ARGUMENTS, "${action.name.lowercase()} 需要 ref、selector 或坐标之一")
            }
            BrowserActionType.TYPE -> {
                if (target.specifiedCount == 0) {
                    fail(ToolErrorCode.INVALID_ARGUMENTS, "type 需要 ref、selector 或坐标之一")
                }
                if (!args.has("text")) fail(ToolErrorCode.INVALID_ARGUMENTS, "type 需要 text")
            }
            BrowserActionType.KEY -> if (!args.has("key")) {
                fail(ToolErrorCode.INVALID_ARGUMENTS, "key 需要指定按键")
            }
            BrowserActionType.SCROLL -> {} // direction/amount 有默认；selector 作为容器可选
        }
        if (action == BrowserActionType.SELECT && !args.has("option")) {
            fail(ToolErrorCode.INVALID_ARGUMENTS, "select 需要 option")
        }

        val request = BrowserActRequest(
            action = action,
            target = target,
            text = args.stringOrNull("text"),
            submit = args.bool("submit", default = false),
            option = args.stringOrNull("option"),
            key = args.stringOrNull("key")?.lowercase(),
            direction = args.stringOrNull("direction")?.lowercase() ?: "down",
            amount = args.int("amount", default = 600, range = 1..5_000),
        )
        return BrowserActInput(request)
    }

    override fun resolve(input: BrowserActInput, env: ToolEnvironment): CallResolution =
        // 页面交互为本机可逆（local）；提交点确认在 execute 里按运行时 DOM 分类决定（静态无法判定）。
        CallResolution(
            risk = Risk.LOCAL,
            sensitivity = if (input.request.action == BrowserActionType.TYPE) Sensitivity.PRIVATE else Sensitivity.NORMAL,
            resources = setOf(ResourceKey(ToolResource.BROWSER)),
        )

    override fun execute(
        input: BrowserActInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<BrowserActOutput> {
        ctx.checkCancelled()
        if (ctx.env.switches.browser.not()) {
            return Verdict.Failed(ToolError(ToolErrorCode.DISABLED, "网页功能已在设置中关闭"))
        }
        val state = runCatching { backend.state() }.getOrNull()
        if (state == null || !state.available) {
            return Verdict.Failed(
                ToolError(
                    ToolErrorCode.NOT_FOUND,
                    "Movo 的离屏浏览器里还没有网页",
                    hint = "要操作手机屏幕上浏览器 App 里正在显示的网页，用 ui_observe 看屏幕后用 ui_tap / ui_input；" +
                        "要在离屏浏览器里看网页，先 browser_open 打开网址",
                ),
            )
        }
        if (state.userControlling) {
            return Verdict.Failed(ToolError(ToolErrorCode.BUSY, "用户正在使用浏览器，请稍后再试"))
        }

        val request = input.request
        return try {
            // 需要具体元素的动作先探测目标（只读），判定可操作性与提交点。
            if (request.action.needsTarget()) {
                val target = backend.inspectActTarget(request)
                when {
                    target.stale -> return Verdict.Failed(
                        ToolError(
                            ToolErrorCode.STALE_OBSERVATION, "目标已失效（页面已变化）",
                            hint = "重新 browser_read elements 拿新的 ref",
                        ),
                    )
                    !target.exists -> return Verdict.Failed(
                        ToolError(ToolErrorCode.NOT_FOUND, "找不到目标元素"),
                    )
                    !target.visible -> return Verdict.Failed(
                        ToolError(ToolErrorCode.NOT_ACTIONABLE, "目标元素不可见"),
                    )
                    request.action == BrowserActionType.TYPE && !target.editable -> return Verdict.Failed(
                        ToolError(ToolErrorCode.NOT_ACTIONABLE, "目标元素不可输入"),
                    )
                }
                // 提交点：表单含密码/支付、method=post、按钮文字命中提交点；search/GET 表单不算。
                if (target.submitPoint && !target.searchRole) {
                    confirmSubmit(ctx, request, target)?.let { return it }
                }
            }

            ctx.checkCancelled()
            val result = backend.performAct(request, BrowserCall(ctx.runId, ctx.toolCallId))
            if (result.loadTimedOut) {
                Verdict.Unknown(
                    reason = "动作已派发，但之后页面加载超时，无法确认结果",
                    next = "先 browser_read 看当前页面状态，再决定是否重复",
                )
            } else {
                Verdict.Dispatched(BrowserActOutput(result))
            }
        } catch (failure: BrowserException) {
            Verdict.Failed(ToolError(failure.code, failure.message, failure.hint, failure.detail))
        }
    }

    /** 提交表单归为「发消息和提交表单」：手动审批时问用户。返回非 null 表示不执行（直接作为最终 Verdict）。 */
    private fun confirmSubmit(
        ctx: ToolContext,
        request: BrowserActRequest,
        target: BrowserActTarget,
    ): Verdict<BrowserActOutput>? {
        val need = ApprovalNeed(
            category = ApprovalCategory.SEND,
            title = "在网页上提交？",
            detail = target.summary.take(120),
        )
        return ctx.confirmConsequence(name, need, APPROVAL_TIMEOUT_MS)?.let { Verdict.Failed(it) }
    }

    override fun uiTitle(input: BrowserActInput): String {
        val r = input.request
        return when (r.action) {
            BrowserActionType.CLICK -> "网页上点按"
            // 网页输入框可能是密码框，执行前判断不了：只写字数，不写内容。
            BrowserActionType.TYPE -> r.text?.let { "网页上输入 ${it.length} 个字" } ?: "网页上输入"
            BrowserActionType.SCROLL -> "网页${r.direction?.let { d -> mapOf("up" to "向上", "down" to "向下", "left" to "向左", "right" to "向右")[d.lowercase()] }.orEmpty()}滚动"
            BrowserActionType.SELECT -> r.option?.let { "网页上选择「${it.forTitle()}」" } ?: "网页上选择"
            BrowserActionType.KEY -> "网页上按「${r.key.orEmpty()}」"
        } + if (r.submit) "并提交" else ""
    }

    override fun renderForUi(input: BrowserActInput, output: BrowserActOutput): ToolUiView {
        val result = output.result
        val target = result.targetSummary.takeIf { it.isNotBlank() }?.let { "「${it.forTitle()}」" }
        val summary = when {
            result.navigated -> "跳到" + (result.title?.takeIf { it.isNotBlank() }?.let { "《${it.forTitle(24)}》" } ?: uiHost(result.url))
            target != null -> "已操作 · $target"
            else -> "已操作"
        }
        return ToolUiView(summary = summary)
    }

    override fun renderForModel(output: BrowserActOutput): ModelContent {
        val result = output.result
        val json = JSONObject()
            .put("navigated", result.navigated)
            .put("url", result.url)
            .put("target", result.targetSummary)
            .put("effect_verified", false)
        result.title?.let { json.put("title", it) }
        return ModelContent.Json(json)
    }

    private fun BrowserActionType.needsTarget(): Boolean = when (this) {
        BrowserActionType.CLICK, BrowserActionType.TYPE, BrowserActionType.SELECT -> true
        // scroll 的 selector 只是容器、key 发给焦点/文档：不做提交点探测（见报告 TODO：key=Enter 焦点表单提交）。
        BrowserActionType.SCROLL, BrowserActionType.KEY -> false
    }

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 120_000L
    }
}
