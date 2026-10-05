package io.github.fartown.movo.agent.tools.device

import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Risk
import io.github.fartown.movo.agent.tools.core.Sensitivity
import io.github.fartown.movo.agent.tools.core.ToolArgs
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import io.github.fartown.movo.agent.tools.core.ApprovalPreview
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import org.json.JSONObject

internal enum class AppControlAction { FORCE_STOP, FREEZE, UNFREEZE }

internal data class AppControlInput(val packageName: String, val action: AppControlAction) : ToolInput

internal data class AppControlOutput(
    val packageName: String,
    val action: AppControlAction,
    /** 回读到的状态：force_stop→stopped；freeze→frozen；unfreeze→enabled。 */
    val state: String,
) : ToolOutput

/** 应用状态回读。null 表示读不到（无法确认）。 */
internal data class AppControlState(
    val stopped: Boolean? = null,
    val frozen: Boolean? = null,
)

/**
 * 可测后端：全部走 Root。[exists] 判断已安装；动作命令 + 回读分离，确保有强证据才报成功。
 */
internal interface AppControlBackend {
    fun exists(packageName: String): Boolean
    /** 执行动作命令，返回系统是否接受（退出码为 0）。 */
    fun run(packageName: String, action: AppControlAction): ToggleDispatch
    /** 回读状态：force_stop 读 dumpsys package 的 stopped 标记；freeze/unfreeze 读 enabled setting。 */
    fun readState(packageName: String, action: AppControlAction): AppControlState
}

/**
 * app_control（回读型，external，需确认，需 Root）：强制停止、冻结、解冻一个应用。
 * 回读到预期状态 → Done(ReadBack)；只有退出码没有回读 → Unknown；自我保护名单 → Failed(UNSUPPORTED, policy_denied)。
 */
internal class AppControlTool(
    private val backend: AppControlBackend,
) : ToolContract<AppControlInput, AppControlOutput> {
    override val name = "app_control"
    override val domain = ToolDomain.APP
    override val summary =
        "强制停止、冻结或解冻一个应用（精确包名，需 Root，需确认）。" +
            "action：force_stop、freeze、unfreeze。可能影响系统应用。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("package", "精确包名", required = true)
        string(
            "action", "动作：force_stop、freeze、unfreeze",
            required = true, enum = AppControlAction.entries.map { it.name.lowercase() },
        )
    }

    /** 没有 Root 时不进目录（以前先弹确认卡、批完才报「需要 Root」）。 */
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.ROOT_REQUIRED, "停止、冻结应用需要 Root")
        }

    override fun approvalPreview(input: AppControlInput): ApprovalPreview {
        val app = io.github.fartown.movo.agent.tools.ui.appLabel(input.packageName)
        return when (input.action) {
            AppControlAction.FORCE_STOP -> ApprovalPreview("强行停止「$app」？", "强行停止「$app」\n它会立刻退出，没保存的内容可能丢失。")
            AppControlAction.FREEZE -> ApprovalPreview("冻结「$app」？", "冻结「$app」\n冻结后它不能打开、也收不到消息，解冻后恢复。")
            AppControlAction.UNFREEZE -> ApprovalPreview("解冻「$app」？", "解冻「$app」\n解冻后它恢复正常使用。")
        }
    }

    override fun approvalScope(input: AppControlInput): String = "${input.packageName}/${input.action.name.lowercase()}"

    override fun parse(args: ToolArgs, env: ToolEnvironment): AppControlInput {
        val pkg = args.nonBlank("package")
        if (!PACKAGE_NAME.matches(pkg)) invalidArgs("包名格式无效：$pkg")
        return AppControlInput(pkg, args.enum<AppControlAction>("action"))
    }

    override fun resolve(input: AppControlInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            risk = Risk.EXTERNAL,            // 中央派生要求确认
            sensitivity = Sensitivity.NORMAL,
            resources = setOf(ResourceKey(ToolResource.DEVICE)),   // 改应用状态独占设备
            // 自我保护名单在审批前拒绝：不先弹确认卡再拒。
            reject = if (isProtected(input.packageName)) {
                ToolError(
                    ToolErrorCode.POLICY_DENIED,
                    "出于自我保护，拒绝对 ${input.packageName} 执行该动作",
                    detail = "policy_denied",
                )
            } else {
                null
            },
        )

    override fun execute(
        input: AppControlInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<AppControlOutput> {
        if (isProtected(input.packageName)) {
            return Verdict.Failed(
                ToolError(
                    ToolErrorCode.UNSUPPORTED,
                    "出于自我保护，拒绝对 ${input.packageName} 执行该动作",
                    detail = "policy_denied",
                ),
            )
        }
        if (!ctx.env.rootAvailable) {
            return Verdict.Failed(ToolError(ToolErrorCode.ROOT_REQUIRED, "app_control 需要 Root 授权，本次未执行"))
        }
        if (!backend.exists(input.packageName)) {
            return Verdict.Failed(ToolError(ToolErrorCode.NOT_FOUND, "未找到应用：${input.packageName}"))
        }
        when (backend.run(input.packageName, input.action)) {
            ToggleDispatch.ROOT_REQUIRED ->
                return Verdict.Failed(ToolError(ToolErrorCode.ROOT_REQUIRED, "app_control 需要 Root 授权，本次未执行"))
            ToggleDispatch.FAILED ->
                return Verdict.Failed(ToolError(ToolErrorCode.SYSTEM_REJECTED, "系统拒绝了对 ${input.packageName} 的动作"))
            ToggleDispatch.OK -> Unit
        }
        ctx.checkCancelled()
        val state = backend.readState(input.packageName, input.action)
        return when (input.action) {
            AppControlAction.FORCE_STOP -> when (state.stopped) {
                true -> done(input, "stopped")
                // 推送进程会被重新拉起，不能用“进程还在不在”判定；读不到 stopped 标记就不冒领。
                else -> unknown(input, "已发送强制停止，但未回读到 stopped 标记")
            }
            AppControlAction.FREEZE -> when (state.frozen) {
                true -> done(input, "frozen")
                else -> unknown(input, "已发送冻结，但未回读到 disabled 状态")
            }
            AppControlAction.UNFREEZE -> when (state.frozen) {
                false -> done(input, "enabled")
                else -> unknown(input, "已发送解冻，但未回读到 enabled 状态")
            }
        }
    }

    private fun done(input: AppControlInput, state: String): Verdict<AppControlOutput> =
        Verdict.Done(
            AppControlOutput(input.packageName, input.action, state),
            Evidence.ReadBack("${input.packageName}=$state"),
        )

    private fun unknown(input: AppControlInput, reason: String): Verdict<AppControlOutput> =
        Verdict.Unknown(
            reason = reason,
            next = "用 device_diagnostics 或 app_search 等核实 ${input.packageName} 状态，不要直接重复动作",
        )

    private fun isProtected(packageName: String): Boolean =
        packageName == MOVO_PACKAGE || SELF_PROTECT_PREFIXES.any { packageName == it || packageName.startsWith("$it.") } ||
            packageName in SELF_PROTECT_PACKAGES

    override fun renderForModel(output: AppControlOutput): ModelContent =
        ModelContent.Json(
            JSONObject()
                .put("package", output.packageName)
                .put("action", output.action.name.lowercase())
                .put("state", output.state),
        )

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        const val MOVO_PACKAGE = "io.github.fartown.movo"

        /** 系统关键包：桌面、SystemUI、输入法、电话等，拒绝停止/冻结。 */
        val SELF_PROTECT_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.settings",
        )

        /** 常见桌面、SystemUI、输入法包名前缀。 */
        val SELF_PROTECT_PREFIXES = listOf(
            "com.android.launcher",
            "com.miui.home",
            "com.oplus.launcher",
            "com.coloros.launcher",
            "com.android.inputmethod",
            "com.baidu.input",
            "com.sohu.inputmethod",
            "com.iflytek.inputmethod",
            "com.google.android.inputmethod",
        )
    }
}
