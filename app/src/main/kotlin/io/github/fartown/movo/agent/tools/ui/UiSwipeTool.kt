package io.github.fartown.movo.agent.tools.ui

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
 * 起止点隐式绑最近一次 ui_observe 的 gen。手动审批时只在用户选的应用里问（见 buildUiActionResolution）。
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
        "按手指轨迹从 (x,y) 滑到 (x2,y2)，用于轮播、拖动、解锁。浏览列表请用 ui_scroll。" +
            "起止点绑最近一次 ui_observe 的代际。ok 只代表已送达。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.accessibilityUsable || env.rootAvailable) {
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
        integer("hold_ms", "起点按住毫秒后再拖（用于拖动排序，可选）", min = 0, max = 3000)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): UiSwipeInput = UiSwipeInput(
        x = args.double("x"), y = args.double("y"), x2 = args.double("x2"), y2 = args.double("y2"),
        durationMs = args.int("duration_ms", 300, 100..2000),
        holdMs = args.intOrNull("hold_ms"),
    )

    override fun resolve(input: UiSwipeInput, env: ToolEnvironment): CallResolution {
        val latest = registry.latest()
        val pkg = registry.foregroundPackage()
        // 起点下的节点只作记录（readableTarget），不单独触发确认。
        val probe = backend.readableNodeAtPoint(input.x, input.y)
        return buildUiActionResolution(
            backend = backend.backend(env),
            target = TargetIdentity.Coordinate(latest?.observationId ?: "", latest?.gen ?: -1L),
            pkg = pkg,
            selfPackage = registry.selfPackage,
            readableTarget = probe != null,
            effect = null,
            // 坐标手势：不走 index 自我保护拒绝（home/back 式全局手势不拒）。
            selfProtect = false,
            stale = genError(registry, latest?.observationId, latest?.gen ?: -1L),
            action = "在屏幕上滑动",
        )
    }

    override fun execute(input: UiSwipeInput, resolution: CallResolution, ctx: ToolContext): Verdict<UiAfter> {
        ctx.checkCancelled()
        if (resolution.backend == InjectionBackend.NONE) {
            return Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法滑动"))
        }
        (resolution.target as? TargetIdentity.Coordinate)?.let {
            checkGen(registry, it.observationId, it.gen)?.let { stale -> return stale }
        }
        val result = backend.swipe(
            UiSwipeRequest(input.x, input.y, input.x2, input.y2, input.durationMs, input.holdMs, resolution.backend),
            ctx.env,
        )
        return when (result) {
            is UiInjectResult.Dispatched -> Verdict.Dispatched(UiAfter(result.afterPackage, result.windowChanged))
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

    override fun renderForModel(output: UiAfter): ModelContent =
        ModelContent.Json(JSONObject().put("after", afterJson(output.packageName, output.windowChanged)))
}
