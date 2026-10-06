package io.github.fartown.movo.agent.tools.device

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiFields
import io.github.fartown.movo.agent.tools.core.uiItems
import io.github.fartown.movo.agent.tools.core.uiText
import io.github.fartown.movo.agent.tools.core.ApprovalCategory
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
import io.github.fartown.movo.agent.tools.core.ResourceKey
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolResource
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.objectSchema
import io.github.fartown.movo.agent.tools.core.ApprovalPreview
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import org.json.JSONObject

internal data class SettingWriteInput(
    val namespace: SettingNamespace,
    val key: String,
    val value: String,
) : ToolInput

internal data class SettingWriteOutput(
    val namespace: SettingNamespace,
    val key: String,
    val previous: String?,
    val value: String?,
) : ToolOutput

/**
 * 可测后端：写 Settings（需 Root）并回读。[write] 返回命令是否被系统接受；[read] 回读真实值。
 */
internal interface SettingWriteBackend {
    fun write(namespace: SettingNamespace, key: String, value: String): ToggleDispatch
    fun read(namespace: SettingNamespace, key: String): String?
}

/**
 * setting_write（回读型，external，归为「改系统设置」）：改一个 Android Settings 值（需 Root），写后回读。
 * 命中自我保护黑名单 → Failed(UNSUPPORTED, policy_denied)；回读到值 → Done(ReadBack，基于回读值)。
 */
internal class SettingWriteTool(
    private val backend: SettingWriteBackend,
) : ToolContract<SettingWriteInput, SettingWriteOutput> {
    override val name = "setting_write"
    override val domain = ToolDomain.DEVICE
    override val summary =
        "写入/修改一个 Android 系统设置值（如亮度、音量模式等，需 Root），写后回读确认。" +
            "要“改/设置/调整”系统设置就用它，只读取用 setting_read。namespace、key、value 必填。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string(
            "namespace", "设置命名空间：system、secure、global",
            required = true, enum = SettingNamespace.entries.map { it.name.lowercase() },
        )
        string("key", "设置键", required = true)
        string("value", "要写入的值（字符串）", required = true)
    }

    /** 没有 Root 时不进目录（以前先弹确认卡、批完才报「需要 Root」）。 */
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.ROOT_REQUIRED, "写系统设置需要 Root")
        }

    override fun approvalPreview(input: SettingWriteInput): ApprovalPreview = ApprovalPreview(
        title = "修改系统设置？",
        detail = "把「${settingLabel(input.key)}」改为 ${input.value.take(40)}",
    )

    override fun parse(args: ToolArgs, env: ToolEnvironment): SettingWriteInput =
        SettingWriteInput(
            namespace = args.enum<SettingNamespace>("namespace"),
            key = args.nonBlank("key"),
            value = args.string("value"),
        )

    override fun resolve(input: SettingWriteInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            risk = Risk.EXTERNAL,
            sensitivity = Sensitivity.PRIVATE,
            resources = setOf(ResourceKey(ToolResource.DEVICE)),   // 系统设置写入独占设备
            category = ApprovalCategory.SYSTEM,
            // 自我保护黑名单在审批前拒绝：不先弹确认卡再拒。
            reject = if (isProtected(input.namespace, input.key)) {
                ToolError(ToolErrorCode.POLICY_DENIED, "出于自我保护，拒绝写入 ${input.key}", detail = "policy_denied")
            } else {
                null
            },
        )

    override fun execute(
        input: SettingWriteInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<SettingWriteOutput> {
        if (isProtected(input.namespace, input.key)) {
            return Verdict.Failed(
                ToolError(
                    ToolErrorCode.UNSUPPORTED,
                    "出于自我保护，拒绝写入 ${input.key}",
                    detail = "policy_denied",
                ),
            )
        }
        if (!ctx.env.rootAvailable) {
            return Verdict.Failed(ToolError(ToolErrorCode.ROOT_REQUIRED, "写系统设置需要 Root 授权，本次未执行"))
        }
        val previous = runCatching { backend.read(input.namespace, input.key) }.getOrNull()
        when (backend.write(input.namespace, input.key, input.value)) {
            ToggleDispatch.ROOT_REQUIRED ->
                return Verdict.Failed(ToolError(ToolErrorCode.ROOT_REQUIRED, "写系统设置需要 Root 授权，本次未执行"))
            ToggleDispatch.FAILED ->
                return Verdict.Failed(ToolError(ToolErrorCode.SYSTEM_REJECTED, "系统拒绝了写入 ${input.key}"))
            ToggleDispatch.OK -> Unit
        }
        ctx.checkCancelled()
        val readBack = runCatching { backend.read(input.namespace, input.key) }.getOrNull()
        if (readBack == null) {
            return Verdict.Unknown(
                reason = "已写入 ${input.key}，但回读不到结果，无法确认",
                next = "用 setting_read 回读 ${input.namespace.name.lowercase()}/${input.key} 确认",
            )
        }
        // 被系统规范化（亮度钳制等）时，effect_verified 基于回读到的真实值。
        return Verdict.Done(
            SettingWriteOutput(input.namespace, input.key, previous, readBack),
            Evidence.ReadBack("${input.key}=$readBack"),
        )
    }

    override fun uiTitle(input: SettingWriteInput): String = "修改设置 · ${settingLabel(input.key)}"

    override fun renderForUi(input: SettingWriteInput, output: SettingWriteOutput): ToolUiView = ToolUiView(
        summary = "${output.previous ?: "未设置"} → ${output.value ?: "未设置"}",
        blocks = listOf(ToolUiBlock.Change(output.previous, output.value, label = settingLabel(output.key))),
    )

    override fun renderForModel(output: SettingWriteOutput): ModelContent =
        ModelContent.Json(
            JSONObject()
                .put("namespace", output.namespace.name.lowercase())
                .put("key", output.key)
                .put("previous", output.previous ?: JSONObject.NULL)
                .put("value", output.value ?: JSONObject.NULL),
        )

    private fun isProtected(namespace: SettingNamespace, key: String): Boolean =
        key.lowercase() in SELF_PROTECT_KEYS

    private companion object {
        /** 自我保护黑名单：改了会削弱 Movo 自身或无障碍/输入法/调试能力。 */
        val SELF_PROTECT_KEYS = setOf(
            "enabled_accessibility_services",
            "accessibility_enabled",
            "default_input_method",
            "adb_enabled",
            "development_settings_enabled",
            "install_non_market_apps",
        )
    }
}

/** 常见设置项的叫法；不认识的显示原键名。 */
internal fun settingLabel(key: String): String = when (key.lowercase()) {
    "screen_brightness" -> "屏幕亮度"
    "screen_brightness_mode" -> "自动亮度"
    "screen_off_timeout" -> "自动锁屏时间"
    "accelerometer_rotation" -> "自动旋转"
    "haptic_feedback_enabled" -> "触感反馈"
    "sound_effects_enabled" -> "触摸提示音"
    "airplane_mode_on" -> "飞行模式"
    "bluetooth_on" -> "蓝牙"
    "wifi_on" -> "Wi‑Fi"
    "mobile_data" -> "移动数据"
    "location_mode" -> "定位"
    "zen_mode" -> "勿扰模式"
    "font_scale" -> "字体大小"
    "system_locales" -> "系统语言"
    "time_12_24" -> "时间格式"
    "auto_time" -> "自动设置时间"
    "stay_on_while_plugged_in" -> "充电时保持亮屏"
    "show_touches" -> "显示点按操作"
    "development_settings_enabled" -> "开发者选项"
    "adb_enabled" -> "USB 调试"
    else -> key
}
