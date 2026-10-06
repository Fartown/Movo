package io.github.fartown.movo.agent.tools.device

import io.github.fartown.movo.agent.tools.core.ApprovalCategory
import io.github.fartown.movo.agent.tools.core.ApprovalPreview
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
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import org.json.JSONObject

/** 开关的目标开关。flashlight 走 CameraManager torch，不需 Root；wifi/bluetooth 走 Root 命令。 */
internal enum class ToggleTarget { WIFI, BLUETOOTH, FLASHLIGHT }

/** 后端派发结果：命令是否被系统接受。 */
internal enum class ToggleDispatch { OK, FAILED, ROOT_REQUIRED }

internal data class DeviceToggleInput(val target: ToggleTarget, val enabled: Boolean) : ToolInput

internal data class DeviceToggleOutput(val target: ToggleTarget, val enabled: Boolean) : ToolOutput

/**
 * 可测后端：flashlight 用相机 torch，wifi/bluetooth 用 Root 命令；[readState] 回读真实开关。
 * null 表示读不到（无法确认）。
 */
internal interface DeviceToggleBackend {
    /** 手电筒：不需 Root。返回派发结果（无相机/闪光灯 → FAILED）。 */
    fun setFlashlight(enabled: Boolean): ToggleDispatch
    /** wifi/bluetooth：需 Root。 */
    fun setRadio(target: ToggleTarget, enabled: Boolean): ToggleDispatch
    /** 回读当前开关状态；null 表示读不到。 */
    fun readState(target: ToggleTarget): Boolean?
}

/**
 * device_toggle（回读型，local）：开关 Wi‑Fi、蓝牙、手电筒，执行后回读确认。
 * 回读一致 → Done(ReadBack)；回读不一致或超时 → Unknown；wifi/bluetooth 无 Root → Failed(ROOT_REQUIRED)。
 */
internal class DeviceToggleTool(
    private val backend: DeviceToggleBackend,
) : ToolContract<DeviceToggleInput, DeviceToggleOutput> {
    override val name = "device_toggle"
    override val domain = ToolDomain.DEVICE
    override val summary =
        "开关 Wi‑Fi、蓝牙、手电筒并回读确认。target：wifi、bluetooth、flashlight；enabled 布尔。" +
            "wifi、bluetooth 需 Root，flashlight 不需。"

    override fun schema(env: ToolEnvironment): JSONObject = io.github.fartown.movo.agent.tools.core.objectSchema {
        string(
            "target", "要开关的目标：wifi、bluetooth、flashlight",
            required = true, enum = ToggleTarget.entries.map { it.name.lowercase() },
        )
        boolean("enabled", "true 开启，false 关闭", required = true)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): DeviceToggleInput {
        if (!args.has("enabled")) io.github.fartown.movo.agent.tools.core.invalidArgs("缺少参数 enabled")
        return DeviceToggleInput(
            target = args.enum<ToggleTarget>("target"),
            enabled = args.bool("enabled", default = false),
        )
    }

    override fun resolve(input: DeviceToggleInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            risk = Risk.LOCAL,
            sensitivity = Sensitivity.NORMAL,
            // 并发应独占无线电/手电筒资源；核心 ToolResource 暂无对应枚举，留空（见返回报告）。
            resources = emptySet(),
            // 开关网络、蓝牙归为「改系统设置」；手电筒不算。
            category = if (input.target == ToggleTarget.FLASHLIGHT) null else ApprovalCategory.SYSTEM,
        )

    override fun approvalPreview(input: DeviceToggleInput): ApprovalPreview {
        val what = when (input.target) {
            ToggleTarget.WIFI -> "Wi‑Fi"
            ToggleTarget.BLUETOOTH -> "蓝牙"
            ToggleTarget.FLASHLIGHT -> "手电筒"
        }
        val verb = if (input.enabled) "打开" else "关闭"
        return ApprovalPreview("$verb$what？", "$verb$what")
    }

    override fun execute(
        input: DeviceToggleInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<DeviceToggleOutput> {
        val needsRoot = input.target != ToggleTarget.FLASHLIGHT
        if (needsRoot && !ctx.env.rootAvailable) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.ROOT_REQUIRED, "开关 ${input.target.name.lowercase()} 需要 Root 授权，本次未执行"),
            )
        }
        val dispatch = if (input.target == ToggleTarget.FLASHLIGHT) {
            backend.setFlashlight(input.enabled)
        } else {
            backend.setRadio(input.target, input.enabled)
        }
        when (dispatch) {
            ToggleDispatch.ROOT_REQUIRED -> return Verdict.Failed(
                ToolError(ToolErrorCode.ROOT_REQUIRED, "开关 ${input.target.name.lowercase()} 需要 Root 授权，本次未执行"),
            )
            ToggleDispatch.FAILED -> return Verdict.Failed(
                ToolError(ToolErrorCode.SYSTEM_REJECTED, "系统拒绝了开关 ${input.target.name.lowercase()}"),
            )
            ToggleDispatch.OK -> Unit
        }
        // 回读确认：无线电 ENABLING 要轮询到稳定（约 1–3 秒）。
        val attempts = if (input.target == ToggleTarget.FLASHLIGHT) 3 else 6
        var state: Boolean? = null
        repeat(attempts) { index ->
            ctx.checkCancelled()
            state = backend.readState(input.target)
            if (state == input.enabled) return done(input, state!!)
            if (index < attempts - 1) Thread.sleep(500L)
        }
        return if (state == input.enabled) {
            done(input, state!!)
        } else {
            Verdict.Unknown(
                reason = "已派发开关命令，但回读状态未与请求一致（当前=${state ?: "读不到"}）",
                next = "用 device_read 或稍后重新回读确认，不要直接重复本动作",
            )
        }
    }

    private fun done(input: DeviceToggleInput, state: Boolean): Verdict<DeviceToggleOutput> =
        Verdict.Done(
            DeviceToggleOutput(input.target, state),
            Evidence.ReadBack("${input.target.name.lowercase()}=$state"),
        )

    override fun renderForModel(output: DeviceToggleOutput): ModelContent =
        ModelContent.Json(
            JSONObject()
                .put("target", output.target.name.lowercase())
                .put("enabled", output.enabled),
        )
}
