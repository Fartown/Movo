package io.github.fartown.movo.agent.tools.device

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.CallResolution
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
import io.github.fartown.movo.agent.tools.core.ToolWarning
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.invalidArgs
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

/** Android Settings 命名空间。 */
internal enum class SettingNamespace { SYSTEM, SECURE, GLOBAL }

internal data class SettingReadInput(
    val namespace: SettingNamespace,
    val keys: List<String>,
) : ToolInput

/**
 * 一个键的读取结果。「没值」和「没权限读」要分开：Android 12 起非公开的设置键对普通应用抛 SecurityException，
 * 以前被吞成 null，模型会当成「没设置」。
 */
internal sealed interface SettingValue {
    data class Value(val value: String) : SettingValue
    /** 读到了，但这个键没有值（system/secure/global 接口分不清「键不存在」和「值为 null」）。 */
    data object Unset : SettingValue
    /** 读不了：[error] 说明是没权限（ROOT_REQUIRED / SYSTEM_REJECTED）还是设置服务出错。 */
    data class Unreadable(val error: ToolError) : SettingValue
}

internal data class SettingReadOutput(
    val namespace: SettingNamespace,
    /** 读到的键：有值为字符串，没值为 null。读不了的键不在这里。 */
    val values: Map<String, String?>,
    /** 读不了的键与原因。 */
    val unreadable: Map<String, ToolError> = emptyMap(),
) : ToolOutput

/**
 * 可测后端：读取 Settings 值，返回 [SettingValue]。
 * [accessible] 为 false 表示系统不允许读取该命名空间（→ SYSTEM_REJECTED）。
 */
internal interface SettingReadBackend {
    fun readValue(namespace: SettingNamespace, key: String): SettingValue
    fun accessible(namespace: SettingNamespace): Boolean = true
}

/** setting_read（只读，private）：读一个或多个 Android Settings 值。 */
internal class SettingReadTool(
    private val backend: SettingReadBackend,
) : ToolContract<SettingReadInput, SettingReadOutput> {
    override val name = "setting_read"
    override val domain = ToolDomain.DEVICE
    override val summary =
        "读取/查询 Android 系统设置的值（只读，不修改；要改设置用 setting_write）。" +
            "namespace：system、secure、global；keys 最多 20 个。未设置的键返回 null，没权限读的键列在 unreadable；" +
            "常见键附 meanings（人话说明）。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string(
            "namespace", "设置命名空间：system、secure、global",
            required = true, enum = SettingNamespace.entries.map { it.name.lowercase() },
        )
        stringArray("keys", "要读取的键，最多 20 个", required = true, minItems = 1, maxItems = 20)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): SettingReadInput {
        val keys = args.stringList("keys").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty()) invalidArgs("keys 至少 1 项")
        if (keys.size > 20) invalidArgs("keys 最多 20 个")
        return SettingReadInput(args.enum<SettingNamespace>("namespace"), keys)
    }

    override fun resolve(input: SettingReadInput, env: ToolEnvironment): CallResolution =
        CallResolution(risk = Risk.READ, sensitivity = Sensitivity.PRIVATE, resources = emptySet())

    override fun execute(
        input: SettingReadInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<SettingReadOutput> {
        if (!backend.accessible(input.namespace)) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.SYSTEM_REJECTED, "系统不允许读取 ${input.namespace.name.lowercase()} 命名空间"),
            )
        }
        val values = LinkedHashMap<String, String?>()
        val unreadable = LinkedHashMap<String, ToolError>()
        for (key in input.keys) {
            ctx.checkCancelled()
            val result = runCatching { backend.readValue(input.namespace, key) }.getOrElse {
                SettingValue.Unreadable(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "设置服务暂时读不到"))
            }
            when (result) {
                is SettingValue.Value -> values[key] = result.value
                SettingValue.Unset -> values[key] = null
                is SettingValue.Unreadable -> unreadable[key] = result.error
            }
        }
        // 一个都没读到：整体失败，原因原样给出（没权限就是没权限，不是「没值」）。
        if (values.isEmpty()) {
            val first = unreadable.values.first()
            val message = unreadable.entries.joinToString("；") { (key, error) -> "$key：${error.message}" }
            return Verdict.Failed(first.copy(message = message))
        }
        return Verdict.Read(SettingReadOutput(input.namespace, values, unreadable))
    }

    override fun uiTitle(input: SettingReadInput): String =
        "读取设置 · " + input.keys.take(2).joinToString("、") { settingLabel(it) } + if (input.keys.size > 2) " 等" else ""

    override fun renderForUi(input: SettingReadInput, output: SettingReadOutput): ToolUiView {
        val shown = output.values.mapValues { (key, value) -> value?.let { settingMeaning(key, it) ?: it } ?: "未设置" } +
            output.unreadable.mapValues { "读不到" }
        return ToolUiView(
            summary = shown.entries.singleOrNull()?.value ?: "${shown.size} 项",
            blocks = listOf(
                ToolUiBlock.Fields(shown.map { (k, v) -> ToolUiBlock.Field(settingLabel(k), v) }),
            ),
        )
    }

    override fun renderForModel(output: SettingReadOutput): ModelContent {
        val values = JSONObject()
        output.values.forEach { (key, value) -> values.put(key, value ?: JSONObject.NULL) }
        val json = JSONObject()
            .put("namespace", output.namespace.name.lowercase())
            .put("values", values)
        // 原值不动，另附人能看懂的说明（2147483647 毫秒 → 永不息屏），回复用户时用说明，不要念原始键名和数字。
        val meanings = JSONObject()
        output.values.forEach { (key, value) ->
            val meaning = value?.let { settingMeaning(key, it) } ?: return@forEach
            val label = settingLabel(key)
            meanings.put(key, if (label == key) meaning else "$label：$meaning")
        }
        if (meanings.length() > 0) json.put("meanings", meanings)
        if (output.unreadable.isNotEmpty()) {
            val unreadable = JSONObject()
            output.unreadable.forEach { (key, error) -> unreadable.put(key, error.message) }
            json.put("unreadable", unreadable)
        }
        return ModelContent.Json(json)
    }

    /** 部分键读不了：整体 ok + warnings，写明是没权限，不是没值。 */
    override fun warnings(output: SettingReadOutput): List<ToolWarning> =
        output.unreadable.map { (key, error) -> ToolWarning(error.code, "$key：${error.message}") }
}

/**
 * 常见设置值的人话说明（只说值，不带设置名）；不认识或值不合法时返回 null，只给原值。
 * 亮度原始值的量程随机型不同（标称 0–255，不少机型更大，亮度条也不是线性的），不换算成百分比。
 */
internal fun settingMeaning(key: String, value: String): String? {
    val raw = value.trim()
    val number = raw.toLongOrNull()
    fun onOff(): String? = when (raw) {
        "0" -> "关"
        "1" -> "开"
        else -> null
    }
    return when (key.lowercase()) {
        "screen_off_timeout" -> when {
            number == null || number <= 0L -> null
            number >= Int.MAX_VALUE.toLong() -> "永不息屏"
            else -> "无操作 ${durationText(number)}后息屏"
        }
        "screen_brightness" -> number?.let { "原始值 $it（量程随机型不同，不等于亮度条百分比）" }
        "screen_brightness_mode" -> when (raw) {
            "1" -> "开（系统按环境光调节亮度）"
            "0" -> "关（手动亮度）"
            else -> null
        }
        "accelerometer_rotation" -> when (raw) {
            "1" -> "开"
            "0" -> "关（锁定方向）"
            else -> null
        }
        "wifi_on" -> when (raw) {
            "0" -> "关"
            "1", "2" -> "开"
            "3" -> "关（飞行模式）"
            else -> null
        }
        "zen_mode" -> when (raw) {
            "0" -> "关"
            "1" -> "开（仅允许优先打扰）"
            "2" -> "开（完全静音）"
            "3" -> "开（仅闹钟）"
            else -> null
        }
        "location_mode" -> when (raw) {
            "0" -> "关"
            "1" -> "开（仅设备 GPS）"
            "2" -> "开（省电模式）"
            "3" -> "开"
            else -> null
        }
        "time_12_24" -> when (raw) {
            "12" -> "12 小时制"
            "24" -> "24 小时制"
            else -> null
        }
        "font_scale" -> raw.toDoubleOrNull()?.takeIf { it > 0 }?.let { "${raw} 倍" }
        "stay_on_while_plugged_in" -> number?.let { if (it == 0L) "关" else "开" }
        "airplane_mode_on", "bluetooth_on", "mobile_data", "haptic_feedback_enabled", "sound_effects_enabled",
        "auto_time", "auto_time_zone", "show_touches", "development_settings_enabled", "adb_enabled",
        -> onOff()
        else -> null
    }
}

/** 毫秒 → 「30 秒」「2 分钟」「1 小时」；不整的退到更小单位。 */
private fun durationText(ms: Long): String = when {
    ms % 3_600_000L == 0L -> "${ms / 3_600_000L} 小时"
    ms % 60_000L == 0L -> "${ms / 60_000L} 分钟"
    ms % 1_000L == 0L -> "${ms / 1_000L} 秒"
    else -> "$ms 毫秒"
}
