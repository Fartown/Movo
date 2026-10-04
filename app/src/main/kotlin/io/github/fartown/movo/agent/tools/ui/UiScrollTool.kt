package io.github.fartown.movo.agent.tools.ui

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
 * 滚动是导航动作，不读提交点，不触发确认。
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
) : ToolOutput

internal class UiScrollTool(
    private val registry: UiObservationRegistry,
    private val backend: UiActionBackend,
) : ToolContract<UiScrollInput, UiScrollOutput> {
    override val name = "ui_scroll"
    override val domain = ToolDomain.UI
    override val summary =
        "按想看到的内容方向滚动：up、down、left、right。方向明确的滑动/翻页（“往下滑/往上翻”）直接用它，不必先 ui_observe。" +
            "可用 index（需 observation_id）指定可滚动节点；返回是否移动、是否到边界。"

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
        string("until_text", "滚动到该文字出现为止（可选）")
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
        (resolution.target as? TargetIdentity.Observed)?.let {
            checkGen(registry, it.observationId, it.gen)?.let { stale -> return stale }
        }
        val result = backend.scroll(
            UiScrollRequest(input.direction, input.element, input.untilText, resolution.backend), ctx.env,
        )
        return when (result) {
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
                ToolError(ToolErrorCode.NOT_ACTIONABLE, "无法滚动：${result.reason}", hint = "该区域可能不可滚动"),
            )
            is UiScrollResult.OutcomeUnknown -> Verdict.Unknown(
                reason = "滚动已派发但无法确认", next = "先 ui_observe 确认，不要直接重复",
            )
            is UiScrollResult.PermissionRequired -> Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法滚动"),
            )
        }
    }

    override fun renderForModel(output: UiScrollOutput): ModelContent {
        val json = JSONObject().put("moved", output.moved)
        output.atBoundary?.let { json.put("at_boundary", it) }
        output.packageName?.let { json.put("after", afterJson(it, false)) }
        return ModelContent.Json(json)
    }
}
