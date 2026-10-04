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
 * §10 ui_tap（送达型）。点击或长按一个目标（index / 点 / 区域）。ok 只代表已送达，需再观察确认。
 * 读不到可信节点就动作（纯坐标、受保护窗、FLAG_SECURE、root 裸坐标）→ readableTarget=false，
 * 由中央派生确认（合同 §5.3）。提交点识别见后端 readableNodeAtPoint（含 TODO）。
 */

internal data class UiTapInput(
    val target: UiTarget,
    val holdMs: Int,
    val effect: UiEffect?,
) : ToolInput

internal class UiTapTool(
    private val registry: UiObservationRegistry,
    private val backend: UiActionBackend,
) : ToolContract<UiTapInput, UiAfter> {
    override val name = "ui_tap"
    override val domain = ToolDomain.UI
    override val summary =
        "点击或长按目标：index（需 observation_id）、或 x,y、或 x,y,x2,y2 区域。hold_ms>0 为长按。" +
            "ok 只代表已送达（effect_verified=false），需再观察确认。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.accessibilityUsable || env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.PERMISSION_REQUIRED, "需要无障碍权限才能点击")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        flatTarget(allowCoordinates = true, allowArea = true)
        integer("hold_ms", "长按毫秒 0–3000，默认 0（即普通点击）", min = 0, max = 3000)
        string(
            "effect", "声明动作后果（只增加确认）",
            enum = UiEffect.entries.map { it.name.lowercase() },
        )
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): UiTapInput = UiTapInput(
        target = parseFlatTarget(args, allowCoordinates = true, allowArea = true),
        holdMs = args.int("hold_ms", 0, 0..3000),
        effect = if (args.has("effect")) args.enum<UiEffect>("effect") else null,
    )

    override fun resolve(input: UiTapInput, env: ToolEnvironment): CallResolution {
        val backendKind = backend.backend(env)
        return when (val t = input.target) {
            is UiTarget.Element -> {
                val gen = registry.genOf(t.observationId) ?: -1L
                val pkg = registry.observationPackage(t.observationId)
                buildUiActionResolution(
                    backend = backendKind,
                    target = TargetIdentity.Observed(t.observationId, gen, t.index),
                    pkg = pkg,
                    selfPackage = registry.selfPackage,
                    // 节点来自观察，读到了可信节点。
                    readableTarget = true,
                    effect = input.effect,
                    selfProtect = true,
                )
            }
            is UiTarget.Point -> coordinateResolution(backendKind, t.x, t.y, input.effect)
            is UiTarget.Area -> coordinateResolution(backendKind, t.centerX, t.centerY, input.effect)
        }
    }

    private fun coordinateResolution(backendKind: InjectionBackend, x: Double, y: Double, effect: UiEffect?): CallResolution {
        val latest = registry.latest()
        val pkg = registry.foregroundPackage()
        // 纯坐标：实时抓树命中最深可点击节点判断提交点；读不到（TODO 占位→null）即 readableTarget=false。
        val probe = backend.readableNodeAtPoint(x, y)
        return buildUiActionResolution(
            backend = backendKind,
            target = TargetIdentity.Coordinate(latest?.observationId ?: "", latest?.gen ?: -1L),
            pkg = pkg,
            selfPackage = registry.selfPackage,
            readableTarget = probe != null,
            effect = effect,
            selfProtect = true,
        )
    }

    override fun execute(input: UiTapInput, resolution: CallResolution, ctx: ToolContext): Verdict<UiAfter> {
        ctx.checkCancelled()
        val backendKind = resolution.backend
        if (backendKind == InjectionBackend.NONE) {
            return Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法点击"))
        }
        // 代际再校验。
        when (val target = resolution.target) {
            is TargetIdentity.Observed -> checkGen(registry, target.observationId, target.gen)?.let { return it }
            is TargetIdentity.Coordinate -> checkGen(registry, target.observationId, target.gen)?.let { return it }
            else -> Unit
        }
        val result = backend.tap(UiTapRequest(input.target, input.holdMs, backendKind), ctx.env)
        return when (result) {
            is UiInjectResult.Dispatched -> Verdict.Dispatched(
                UiAfter(result.afterPackage, result.windowChanged, method = result.method),
            )
            is UiInjectResult.NotActionable -> Verdict.Failed(
                ToolError(ToolErrorCode.NOT_ACTIONABLE, "目标不可点击：${result.reason}", hint = "重新观察后换目标"),
            )
            is UiInjectResult.SystemRejected -> Verdict.Failed(
                ToolError(ToolErrorCode.SYSTEM_REJECTED, "手势未被系统派发，确定未执行"),
            )
            is UiInjectResult.OutcomeUnknown -> Verdict.Unknown(
                reason = "点击已派发但无法确认是否生效", next = "先 ui_observe 确认，不要直接重复点击",
            )
            is UiInjectResult.PermissionRequired -> Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法点击"),
            )
        }
    }

    override fun renderForModel(output: UiAfter): ModelContent =
        ModelContent.Json(
            JSONObject()
                .apply { output.method?.let { put("method", it) } }
                .put("after", afterJson(output.packageName, output.windowChanged)),
        )
}
