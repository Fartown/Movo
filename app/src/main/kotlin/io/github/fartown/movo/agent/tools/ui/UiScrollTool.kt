package io.github.fartown.movo.agent.tools.ui

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.InjectionBackend
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.TargetIdentity
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
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

/**
 * §11 ui_scroll（可判定）。按想看到的内容方向滚动，可用 index 指定可滚动节点。
 * effect_verified=moved：moved → Done(ReadBack)；没动 → Dispatched；界面反向 → Unknown。
 * until_text：一次一次滚，每滚完看一眼，找到这段文字、到头（没动）或滚满 [MAX_UNTIL_SCROLLS] 次就停，
 * 结果写滚了几次、找没找到；找到了 → Done(ReadBack)。滚动是导航动作，不读提交点，不触发确认。
 */

internal data class UiScrollInput(
    val direction: ScrollDirection,
    val element: UiTarget.Element?,
    val untilText: String?,
) : ToolInput

internal data class UiScrollOutput(
    val moved: Boolean,
    val atBoundary: Boolean?,
    val packageName: String?,
    /** 带了 until_text 才有。 */
    val until: UntilText? = null,
) : ToolOutput

/** until_text 的结果：找的文字、找没找到、滚动了几次（只数真的动了的）、中途停下的原因。 */
internal data class UntilText(
    val text: String,
    val found: Boolean,
    val scrolls: Int,
    val stopped: String? = null,
)

internal class UiScrollTool(
    private val registry: UiObservationRegistry,
    private val backend: UiActionBackend,
) : ToolContract<UiScrollInput, UiScrollOutput> {
    override val name = "ui_scroll"
    override val domain = ToolDomain.UI
    override val summary =
        "按想看到的内容方向滚动：up、down、left、right。方向明确的滑动/翻页（“往下滑/往上翻”）直接用它，不必先 ui_observe。" +
            "可用 index（需 observation_id）指定可滚动节点；until_text 一直滚到该文字出现（最多 $MAX_UNTIL_SCROLLS 次；带 index 时只有第一下滚它，之后滚页面上主要的可滚动区域）。" +
            "返回是否移动、是否到边界。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.accessibilityUsable || env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.PERMISSION_REQUIRED, "需要无障碍权限才能滚动")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string(
            "direction", "想看到的内容方向", required = true,
            enum = ScrollDirection.entries.map { it.name.lowercase() },
        )
        integer("index", "可滚动节点 index（可选）", min = 0)
        string("observation_id", "给出 index 时必填，绑定其观察代际", maxLength = 64)
        string(
            "until_text",
            "一直滚到这段文字出现为止（可选，文字或描述包含即可，不分大小写）；到头或滚满 $MAX_UNTIL_SCROLLS 次就停，" +
                "结果里写 found 和滚了几次 scrolls",
        )
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): UiScrollInput {
        val element = if (args.has("index")) {
            val obs = args.stringOrNull("observation_id")
                ?: invalidArgs("给出 index 时必须提供 observation_id")
            UiTarget.Element(obs, args.int("index"))
        } else {
            null
        }
        return UiScrollInput(
            direction = args.enum("direction"),
            element = element,
            untilText = args.stringOrNull("until_text")?.trim()?.ifEmpty { null },
        )
    }

    override fun resolve(input: UiScrollInput, env: ToolEnvironment): CallResolution {
        val element = input.element
        val target = if (element != null) {
            TargetIdentity.Observed(element.observationId, registry.genOf(element.observationId) ?: -1L, element.index)
        } else {
            TargetIdentity.None
        }
        // 滚动不是提交点动作：readableTarget=true，不走 blindCoordinate 确认。
        return CallResolution(
            risk = Risk.LOCAL,
            sensitivity = Sensitivity.NORMAL,
            resources = setOf(ResourceKey(ToolResource.SCREEN)),
            backend = backend.backend(env),
            target = target,
            readableTarget = true,
        )
    }

    override fun execute(input: UiScrollInput, resolution: CallResolution, ctx: ToolContext): Verdict<UiScrollOutput> {
        ctx.checkCancelled()
        if (resolution.backend == InjectionBackend.NONE) {
            return Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法滚动"))
        }
        // 只看观察还在不在；页面内容变没变由无障碍服务按节点身份核对（[observationError]）。
        (resolution.target as? TargetIdentity.Observed)?.let {
            observationError(registry, it.observationId)?.let { stale -> return Verdict.Failed(stale) }
        }
        input.untilText?.let { text -> return scrollUntil(input, text, resolution, ctx) }
        return verdictOf(backend.scroll(UiScrollRequest(input.direction, input.element, resolution.backend), ctx.env))
    }

    /** 滚一次的结果：动了 → Done(ReadBack)；没动 → Dispatched；没成按原因报错。 */
    private fun verdictOf(result: UiScrollResult): Verdict<UiScrollOutput> =
        when (result) {
            is UiScrollResult.Finished ->
                if (result.moved) {
                    Verdict.Done(
                        UiScrollOutput(true, result.atBoundary, result.afterPackage),
                        Evidence.ReadBack("moved"),
                    )
                } else {
                    // 没动（含到边界）：effect_verified=false → 送达型。
                    Verdict.Dispatched(UiScrollOutput(false, result.atBoundary, result.afterPackage))
                }
            is UiScrollResult.DirectionMismatch -> Verdict.Unknown(
                reason = "界面朝相反方向移动，结果不确定", next = "先 ui_observe 确认当前位置",
            )
            is UiScrollResult.NotActionable -> Verdict.Failed(
                if (result.stale) {
                    ToolError(ToolErrorCode.STALE_OBSERVATION, "页面已经变了：${result.reason}", hint = "重新 ui_observe，用新的 observation_id 再滚动")
                } else {
                    ToolError(ToolErrorCode.NOT_ACTIONABLE, "无法滚动：${result.reason}", hint = "该区域可能不可滚动")
                },
            )
            is UiScrollResult.OutcomeUnknown -> Verdict.Unknown(
                reason = "滚动已派发但无法确认", next = "先 ui_observe 确认，不要直接重复",
            )
            is UiScrollResult.PermissionRequired -> Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法滚动"),
            )
        }

    /**
     * 滚到 [text] 出现为止。先看一眼，已经在屏幕上就不滚；之后每滚一次看一次。
     * 停下：找到了、这一下没动（到头）、动了但已到边界、滚满 [MAX_UNTIL_SCROLLS] 次。
     * 确认不了动没动的一下：再看一眼有没有这段文字，没有就停下并写原因（不报 unknown）；
     * 确定滚不了的：第一下就这样按普通滚动报错，滚过几次之后停下并写原因。
     */
    private fun scrollUntil(
        input: UiScrollInput,
        text: String,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<UiScrollOutput> {
        var hit = backend.findText(text)
        var scrolls = 0
        var atBoundary: Boolean? = null
        var packageName: String? = null
        var stopped: String? = null
        var attempts = 0
        while (hit == null && scrolls < MAX_UNTIL_SCROLLS) {
            ctx.checkCancelled()
            // index 绑定它那次观察，滚一下内容就变了、index 随之失效（真机：设置页第二下报「窗口内容已经变化」）。
            // 第一下按 index 滚，之后改滚页面上最主要的可滚动区域。
            val element = input.element.takeIf { attempts == 0 }
            attempts++
            val step = backend.scroll(UiScrollRequest(input.direction, element, resolution.backend), ctx.env)
            if (step !is UiScrollResult.Finished) {
                if (step is UiScrollResult.DirectionMismatch || step is UiScrollResult.OutcomeUnknown) {
                    // 这一下确认不了动没动（真机：设置页滚到底时就这样）。滚动不改数据，直接再看一眼：
                    // 找到了就算找到，没找到就停下并写明原因，不把整次查找报成 unknown。
                    hit = backend.findText(text)
                    if (hit == null) stopped = "第 ${scrolls + 1} 次滚动确认不了有没有动，可能已经到头"
                    break
                }
                if (scrolls == 0) return verdictOf(step)
                stopped = when (step) {
                    is UiScrollResult.NotActionable -> "无法继续滚动：${step.reason}"
                    else -> "无障碍不可用，无法继续滚动"
                }
                break
            }
            packageName = step.afterPackage ?: packageName
            atBoundary = step.atBoundary
            if (!step.moved) break
            scrolls++
            hit = backend.findText(text)
            if (step.atBoundary == true) break
        }
        val output = UiScrollOutput(
            moved = scrolls > 0,
            atBoundary = atBoundary,
            packageName = packageName,
            until = UntilText(text, found = hit != null, scrolls = scrolls, stopped = stopped),
        )
        return when {
            hit != null -> Verdict.Done(output, Evidence.ReadBack("until_text"))
            scrolls > 0 -> Verdict.Done(output, Evidence.ReadBack("moved"))
            else -> Verdict.Dispatched(output)
        }
    }

    override fun uiTitle(input: UiScrollInput): String {
        val base = "${input.direction.label()}滚动"
        return input.untilText?.takeIf { it.isNotBlank() }?.let { "$base，找「${it.forTitle()}」" } ?: base
    }

    override fun renderForUi(input: UiScrollInput, output: UiScrollOutput): ToolUiView = ToolUiView(
        summary = output.until?.let { until ->
            val times = if (until.scrolls > 0) "滚动 ${until.scrolls} 次" else "没有滚动"
            val found = if (until.found) "找到「${until.text.forTitle()}」" else "没找到「${until.text.forTitle()}」"
            listOfNotNull(found, times, "到头了".takeIf { !until.found && output.atBoundary == true }).joinToString(" · ")
        } ?: when {
            output.moved && output.atBoundary == true -> "已滚动 · 到头了"
            output.moved -> "已滚动"
            output.atBoundary == true -> "到头了，没有再滚动"
            else -> "没有滚动"
        },
    )

    override fun renderForModel(output: UiScrollOutput): ModelContent {
        val json = JSONObject().put("moved", output.moved)
        output.atBoundary?.let { json.put("at_boundary", it) }
        output.until?.let { until ->
            json.put("found", until.found).put("scrolls", until.scrolls)
            until.stopped?.let { json.put("stopped", it) }
        }
        output.packageName?.let { json.put("after", afterJson(it, false)) }
        return ModelContent.Json(json)
    }

    companion object {
        /** until_text 最多滚几次：找不到时不至于一直滚下去。 */
        const val MAX_UNTIL_SCROLLS = 10
    }
}
