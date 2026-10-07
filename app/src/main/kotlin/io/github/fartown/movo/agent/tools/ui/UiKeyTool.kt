package io.github.fartown.movo.agent.tools.ui

import android.os.Build
import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.InjectionBackend
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
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

/**
 * §14 ui_key（送达型）。系统键与全局动作：back、home、recents、enter、notifications、quick_settings、
 * lock_screen、screenshot、dismiss_notifications。全局键不触发自我保护拒绝（只拒“有目标”的 ui_*）。
 * 锁屏、截屏、收起通知栏用无障碍全局动作，有 Root 时能回退；当前设备做不到的键不出现在 schema 里（[keysFor]）。
 */

internal data class UiKeyInput(val key: UiKeyCode) : ToolInput

/**
 * 这个键在当前设备上做不做得到。锁屏、截屏的无障碍全局动作要 Android 9（两个版本的 minSdk 都够），
 * 收起通知栏要 Android 12；系统版本不够或没有无障碍时，有 Root 就用按键 / statusbar 命令回退。
 */
internal fun UiKeyCode.supported(env: ToolEnvironment, sdkInt: Int): Boolean {
    val needsSdk = when (this) {
        UiKeyCode.LOCK_SCREEN, UiKeyCode.SCREENSHOT -> Build.VERSION_CODES.P
        UiKeyCode.DISMISS_NOTIFICATIONS -> Build.VERSION_CODES.S
        else -> return true
    }
    return (env.accessibilityUsable && sdkInt >= needsSdk) || env.rootAvailable
}

/** 当前设备能按的键：没有触屏的设备只有 back / home，其余按 [supported] 过滤。 */
internal fun keysFor(env: ToolEnvironment, sdkInt: Int): List<UiKeyCode> =
    if (!env.touchscreen) listOf(UiKeyCode.BACK, UiKeyCode.HOME) else UiKeyCode.entries.filter { it.supported(env, sdkInt) }

internal class UiKeyTool(
    private val registry: UiObservationRegistry,
    private val backend: UiActionBackend,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ToolContract<UiKeyInput, UiAfter> {
    override val name = "ui_key"
    override val domain = ToolDomain.UI
    override val summary =
        "按系统键或全局动作：back、home、recents、enter、notifications、quick_settings、lock_screen（锁屏）、" +
            "screenshot（系统截屏存相册；看屏幕用 ui_observe）、dismiss_notifications（收起通知栏）。ok 只代表已送达。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.accessibilityUsable || env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.PERMISSION_REQUIRED, "需要无障碍权限才能按键")
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string(
            "key", "系统键或全局动作", required = true,
            enum = keysFor(env, sdkInt).map { it.name.lowercase() },
        )
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): UiKeyInput = UiKeyInput(args.enum<UiKeyCode>("key").also {
        if (it in keysFor(env, sdkInt)) return@also
        if (!env.touchscreen) invalidArgs("本设备只支持 back/home 全局键；其他操作请使用节点或焦点")
        invalidArgs(
            "本设备做不到 ${it.name.lowercase()}",
            if (it == UiKeyCode.DISMISS_NOTIFICATIONS) "收起通知栏要 Android 12 以上或 Root" else "需要无障碍或 Root",
        )
    })

    override fun resolve(input: UiKeyInput, env: ToolEnvironment): CallResolution = CallResolution(
        risk = Risk.LOCAL,
        sensitivity = Sensitivity.NORMAL,
        resources = setOf(ResourceKey(ToolResource.SCREEN)),
        backend = backend.backend(env),
        // 全局键不绑目标：target=None，readableTarget=true（不走 blindCoordinate 确认）。
        readableTarget = true,
    )

    override fun execute(input: UiKeyInput, resolution: CallResolution, ctx: ToolContext): Verdict<UiAfter> {
        ctx.checkCancelled()
        if (resolution.backend == InjectionBackend.NONE) {
            return Verdict.Failed(ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法按键"))
        }
        val result = backend.key(UiKeyRequest(input.key, resolution.backend), ctx.env)
        return when (result) {
            is UiInjectResult.Dispatched -> Verdict.Dispatched(
                UiAfter(result.afterPackage, result.windowChanged, key = input.key.name.lowercase()),
            )
            is UiInjectResult.NotActionable -> Verdict.Failed(
                ToolError(ToolErrorCode.NOT_ACTIONABLE, "无法执行 ${input.key.name.lowercase()}：${result.reason}"),
            )
            is UiInjectResult.SystemRejected -> Verdict.Failed(
                ToolError(ToolErrorCode.SYSTEM_REJECTED, "按键未被系统派发，确定未执行"),
            )
            is UiInjectResult.OutcomeUnknown -> Verdict.Unknown(
                reason = "按键已派发但无法确认", next = "先 ui_observe 确认",
            )
            is UiInjectResult.PermissionRequired -> Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "无障碍不可用，无法按键"),
            )
        }
    }

    override fun uiTitle(input: UiKeyInput): String = "按「${input.key.label()}」"

    override fun renderForUi(input: UiKeyInput, output: UiAfter): ToolUiView =
        ToolUiView(summary = afterSummary("已按下", output.packageName, output.windowChanged))

    override fun renderForModel(output: UiAfter): ModelContent {
        val json = JSONObject()
        output.key?.let { json.put("key", it) }
        json.put("after", afterJson(output.packageName, output.windowChanged))
        return ModelContent.Json(json)
    }
}
