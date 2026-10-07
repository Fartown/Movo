package io.github.fartown.movo.agent.tools.ui

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.InjectionBackend
import io.github.fartown.movo.agent.tools.core.ModelContent
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
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

/**
 * §12 ui_swipe（送达型）。按手指轨迹从 (x,y) 滑到 (x2,y2)：轮播、拖动、解锁。浏览列表用 ui_scroll。
 * 起止点绑最近一次 ui_observe 的坐标系（[coordinateError]）：页面内容刷新不影响，换窗口、应用或横竖屏才要重新观察。
 * hold_ms 先在起点按住再拖（拖动排序）：无障碍用同一根手指的两段连续笔画实现；Root 的 input swipe 做不到
 * 「先按住不动」，只有 Root 时不提供这个参数，带了也直接报不支持。
 * 手动审批时只在用户选的应用里问（见 buildUiActionResolution）。成功后显示滑动指示并配触感（规范 9.5）。
 */

internal data class UiSwipeInput(
    val x: Double, val y: Double, val x2: Double, val y2: Double,
    val durationMs: Int, val holdMs: Int?,
) : ToolInput

internal class UiSwipeTool(
    private val registry: UiObservationRegistry,
    private val backend: UiActionBackend,
) : ToolContract<UiSwipeInput, UiAfter> {
    override val name = "ui_swipe"
    override val domain = ToolDomain.UI
    override val summary =
        "按手指轨迹从 (x,y) 滑到 (x2,y2)，用于轮播、拖动、解锁；hold_ms 先按住再拖（拖动排序）。浏览列表请用 ui_scroll。" +
            "坐标不受页面内容刷新影响，换了窗口、应用或横竖屏要重新 ui_observe。ok 只代表已送达。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (!env.touchscreen) {
            ToolAvailability.Unavailable(ToolErrorCode.NOT_ACTIONABLE, "本设备不支持触屏滑动，请使用节点操作或移动焦点")
        } else if (env.accessibilityUsable || env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.PERMISSION_REQUIRED, "需要无障碍权限才能滑动")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        number("x", "起点横坐标", required = true)
        number("y", "起点纵坐标", required = true)
        number("x2", "终点横坐标", required = true)
        number("y2", "终点纵坐标", required = true)
        integer("duration_ms", "滑动毫秒 100–2000，默认 300", min = 100, max = 2000)
        // 先按住再拖只有无障碍做得到：只有 Root 时不出现。
        if (env.accessibilityUsable) {
            integer(
                "hold_ms", "先在起点按住这么多毫秒再拖（拖动排序、拖图标，可选）；需要无障碍，只有 Root 时不支持",
                min = 0, max = 3000,
            )
        }
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): UiSwipeInput = UiSwipeInput(
        x = args.double("x"), y = args.double("y"), x2 = args.double("x2"), y2 = args.double("y2"),
        durationMs = args.int("duration_ms", 300, 100..2000),
        holdMs = args.intOrNull("hold_ms"),
    )

    override fun resolve(input: UiSwipeInput, env: ToolEnvironment): CallResolution {
        val latest = registry.latest()
        val pkg = registry.foregroundPackage()
        val backendKind = backend.backend(env)
        val holding = (input.holdMs ?: 0) > 0
        val rejected = coordinateError(registry, latest?.observationId)
            ?: coordinateRangeError(latest, input.x to input.y, input.x2 to input.y2)
            ?: if (holding && backendKind != InjectionBackend.ACCESSIBILITY) {
                ToolError(
                    ToolErrorCode.UNSUPPORTED,
                    "只有 Root 时不能先按住再拖",
                    hint = "开启 Movo 无障碍后再用 hold_ms；不需要按住就去掉 hold_ms 直接滑动",
                )
            } else {
                null
            }
        return buildUiActionResolution(
            backend = backendKind,
            target = TargetIdentity.Coordinate(latest?.observationId ?: "", latest?.gen ?: -1L),
            pkg = pkg,
            selfPackage = registry.selfPackage,
            // 确认卡写的是「在屏幕上滑动」，用不到起点下的节点：不抓树，readableTarget 只记录没读。
            readableTarget = false,
            effect = null,
            // 坐标手势：不走 index 自我保护拒绝（home/back 式全局手势不拒）。
            selfProtect = false,
            stale = rejected,
            action = if (holding) "在屏幕上按住拖动" else "在屏幕上滑动",
        )
    }

    override fun execute(input: UiSwipeInput, resolution: CallResolution, ctx: ToolContext): Verdict<UiAfter> {
        ctx.checkCancelled()
        if (resolution.backend == InjectionBackend.NONE) {
            return Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法滑动"))
        }
        // 复核坐标系（确认期间可能切走了），和 resolve 预检同一套规则。
        (resolution.target as? TargetIdentity.Coordinate)?.let {
            coordinateError(registry, it.observationId)?.let { stale -> return Verdict.Failed(stale) }
        }
        val result = backend.swipe(
            UiSwipeRequest(input.x, input.y, input.x2, input.y2, input.durationMs, input.holdMs, resolution.backend),
            ctx.env,
        )
        return when (result) {
            is UiInjectResult.Dispatched -> {
                // 送达后才显示滑动指示、配触感（重构方案「执行成功后再播放」）。
                if (ctx.env.touchscreen) result.touch?.let(backend::showTouch)
                Verdict.Dispatched(UiAfter(result.afterPackage, result.windowChanged))
            }
            is UiInjectResult.NotActionable -> Verdict.Failed(
                ToolError(ToolErrorCode.NOT_ACTIONABLE, "无法滑动：${result.reason}"),
            )
            is UiInjectResult.SystemRejected -> Verdict.Failed(
                ToolError(ToolErrorCode.SYSTEM_REJECTED, "手势未被系统派发，确定未执行"),
            )
            is UiInjectResult.OutcomeUnknown -> Verdict.Unknown(
                reason = "滑动已派发但无法确认", next = "先 ui_observe 确认，不要直接重复",
            )
            is UiInjectResult.PermissionRequired -> Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法滑动"),
            )
        }
    }

    override fun uiTitle(input: UiSwipeInput): String {
        val dx = input.x2 - input.x
        val dy = input.y2 - input.y
        val direction = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
            if (dx >= 0) "向右" else "向左"
        } else {
            if (dy >= 0) "向下" else "向上"
        }
        return if ((input.holdMs ?: 0) > 0) "按住${direction}拖动" else "${direction}滑动"
    }

    override fun renderForUi(input: UiSwipeInput, output: UiAfter): ToolUiView = ToolUiView(
        summary = afterSummary(if ((input.holdMs ?: 0) > 0) "已拖动" else "已滑动", output.packageName, output.windowChanged),
    )

    override fun renderForModel(output: UiAfter): ModelContent =
        ModelContent.Json(JSONObject().put("after", afterJson(output.packageName, output.windowChanged)))
}
