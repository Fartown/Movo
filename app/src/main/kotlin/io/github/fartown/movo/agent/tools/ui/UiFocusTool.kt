package io.github.fartown.movo.agent.tools.ui

import io.github.fartown.movo.agent.tools.core.*
import org.json.JSONObject

internal data class UiFocusInput(val element: UiTarget.Element, val direction: ScrollDirection?) : ToolInput

internal class UiFocusTool(private val registry: UiObservationRegistry, private val backend: UiActionBackend) : ToolContract<UiFocusInput, UiAfter> {
    override val name = "ui_focus"
    override val domain = ToolDomain.UI
    override val summary = "移动电视遥控焦点：指定观察中的 index；direction 可选，表示从该节点向 up/down/left/right 移动。不会点击或播放，之后重新观察确认。"
    override fun availability(env: ToolEnvironment): ToolAvailability = when {
        env.touchscreen -> ToolAvailability.Unavailable(ToolErrorCode.NOT_ACTIONABLE, "触屏设备无需遥控焦点工具")
        !env.accessibilityUsable -> ToolAvailability.Unavailable(ToolErrorCode.PERMISSION_REQUIRED, "需要无障碍权限")
        else -> ToolAvailability.Available
    }
    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("observation_id", "ui_observe 返回的观察 ID", required = true)
        integer("index", "观察中的节点序号", required = true, min = 0)
        string("direction", "可选，沿方向移动到相邻节点；省略则聚焦 index 本身", enum = ScrollDirection.entries.map { it.name.lowercase() })
    }
    override fun parse(args: ToolArgs, env: ToolEnvironment) = UiFocusInput(
        UiTarget.Element(args.nonBlank("observation_id"), args.int("index").also { if (it < 0) invalidArgs("index 不能为负数") }),
        if (args.has("direction")) args.enum<ScrollDirection>("direction") else null,
    )
    override fun resolve(input: UiFocusInput, env: ToolEnvironment): CallResolution = buildUiActionResolution(
        backend = InjectionBackend.ACCESSIBILITY,
        target = TargetIdentity.Observed(input.element.observationId, registry.genOf(input.element.observationId) ?: -1, input.element.index),
        pkg = registry.observationPackage(input.element.observationId), selfPackage = registry.selfPackage,
        readableTarget = true, effect = null, selfProtect = true,
    )
    override fun execute(input: UiFocusInput, resolution: CallResolution, ctx: ToolContext): Verdict<UiAfter> {
        ctx.checkCancelled()
        val target = resolution.target as TargetIdentity.Observed
        checkGen(registry, target.observationId, target.gen)?.let { return it }
        return when (val result = backend.focus(input.element, input.direction, ctx.env)) {
            is UiInjectResult.Dispatched -> Verdict.Dispatched(UiAfter(result.afterPackage, result.windowChanged, method = result.method))
            is UiInjectResult.NotActionable -> Verdict.Failed(ToolError(ToolErrorCode.NOT_ACTIONABLE, result.reason))
            UiInjectResult.OutcomeUnknown -> Verdict.Unknown("焦点结果未确认", "重新 ui_observe 确认，避免重复移动")
            UiInjectResult.PermissionRequired -> Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍服务未连接"))
            UiInjectResult.SystemRejected -> Verdict.Failed(ToolError(ToolErrorCode.SYSTEM_REJECTED, "系统拒绝焦点动作"))
        }
    }
    override fun renderForModel(output: UiAfter): ModelContent = ModelContent.Json(JSONObject()
        .put("method", output.method).put("after", afterJson(output.packageName, output.windowChanged)))
}
