package io.github.fartown.movo.agent.tools.device

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiFields
import io.github.fartown.movo.agent.tools.core.uiItems
import io.github.fartown.movo.agent.tools.core.uiText
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.Retry
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
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.objectSchema
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.json.JSONObject

/** 纵向链路第一条真工具：device_read（只读）。验证合同接线，不走审批、不占资源。 */

internal enum class DeviceSection { BATTERY, MEMORY, STORAGE, SYSTEM, NETWORK, ENVIRONMENT, LOCATION, TIME }

/**
 * device_read 的 time：读取那一刻的时间，到秒，带时区和星期（重构前 get_current_context 的字段）。
 * 环境信息里的时间只在任务开始时给一次、只到分钟，长任务中途要准确时间就读它。
 */
internal fun deviceTime(now: ZonedDateTime): JSONObject {
    val time = now.truncatedTo(ChronoUnit.SECONDS)
    return JSONObject()
        .put("datetime", time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        .put("timezone", time.zone.id)
        .put("weekday", time.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.SIMPLIFIED_CHINESE))
}

internal data class DeviceReadInput(val sections: List<DeviceSection>) : ToolInput

internal data class DeviceReadOutput(
    val data: JSONObject,
    /** 读失败的 section 与原因（按请求顺序）。 */
    val failed: Map<DeviceSection, ToolError>,
) : ToolOutput

/**
 * 某个 section 读不到、且知道原因时由后端抛出：缺权限、开关关着、暂时没数据各给各的码、提示与 retry。
 * 例如位置：没授权是用户要去开（PERMISSION_REQUIRED，retry=user），不是「稍后重试」。
 */
internal class DeviceSectionUnavailable(
    val code: ToolErrorCode,
    override val message: String,
    val hint: String? = null,
    val retry: Retry = code.retry,
    val detail: String? = null,
) : RuntimeException(message) {
    fun toError(): ToolError = ToolError(code, message, hint, detail, retry)
}

/**
 * 可测后端：真实实现从 Context/系统服务读；测试用假实现。null 表示该 section 暂不可读（原因不明）；
 * 知道原因时抛 [DeviceSectionUnavailable]。
 */
internal interface DeviceReadBackend {
    fun read(section: DeviceSection, env: ToolEnvironment): JSONObject?
    /** 该 section 是否敏感（network 的 SSID、位置、设备标识等）。 */
    fun sensitiveSections(): Set<DeviceSection> =
        setOf(DeviceSection.NETWORK, DeviceSection.ENVIRONMENT, DeviceSection.LOCATION)
}

internal class DeviceReadTool(
    private val backend: DeviceReadBackend,
) : ToolContract<DeviceReadInput, DeviceReadOutput> {
    override val name = "device_read"
    override val domain = ToolDomain.DEVICE
    override val summary =
        "只读设备自身的电量、内存、存储空间、系统信息、网络连接、环境、位置、当前时间这些系统指标（按 sections 选）。" +
            "仅限这些系统状态；找文件/照片用 file_search、Wi‑Fi 密码用 wifi_password_read、App 用量用 usage_read、" +
            "系统设置用 setting_read。time 是读取时的时间（到秒、带时区）；环境信息里的时间是任务开始时的，只到分钟。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        stringArray(
            "sections", "要读取的部分，至少 1 项",
            required = true, minItems = 1,
            enum = DeviceSection.entries.map { it.name.lowercase() },
        )
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): DeviceReadInput {
        val raw = args.stringList("sections")
        if (raw.isEmpty()) fail(ToolErrorCode.INVALID_ARGUMENTS, "sections 至少 1 项")
        val sections = raw.map { name ->
            DeviceSection.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: fail(ToolErrorCode.INVALID_ARGUMENTS, "未知 section：$name")
        }
        return DeviceReadInput(sections.distinct())
    }

    override fun resolve(input: DeviceReadInput, env: ToolEnvironment): CallResolution {
        val sensitive = input.sections.any { it in backend.sensitiveSections() }
        return CallResolution(
            risk = Risk.READ,
            sensitivity = if (sensitive) Sensitivity.PRIVATE else Sensitivity.NORMAL,
            resources = emptySet(),
        )
    }

    override fun execute(input: DeviceReadInput, resolution: CallResolution, ctx: ToolContext): Verdict<DeviceReadOutput> {
        val data = JSONObject()
        val failed = LinkedHashMap<DeviceSection, ToolError>()
        for (section in input.sections) {
            ctx.checkCancelled()
            val value = try {
                backend.read(section, ctx.env)
            } catch (unavailable: DeviceSectionUnavailable) {
                failed[section] = unavailable.toError()
                continue
            } catch (cancelled: io.github.fartown.movo.agent.runtime.AgentRunCancelledException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (value == null) failed[section] = unknownFailure(section) else data.put(section.name.lowercase(), value)
        }
        if (data.length() == 0) return Verdict.Failed(allFailed(failed))
        return Verdict.Read(DeviceReadOutput(data, failed))
    }

    private fun unknownFailure(section: DeviceSection) = ToolError(
        code = ToolErrorCode.SOURCE_UNAVAILABLE,
        message = "${section.label()}暂时读不到",
    )

    /**
     * 全部 section 都失败：只有一个（或原因同码）时原样给出它的码、提示与 retry（例如位置缺权限 → retry=user），
     * 不再一律报「暂时读不到 / 稍后重试」；原因各不相同时按 SOURCE_UNAVAILABLE 汇总，逐项写明，
     * 其中有要用户去开的（缺权限、开关关着）就按 retry=user。
     */
    private fun allFailed(failed: Map<DeviceSection, ToolError>): ToolError {
        val errors = failed.values.toList()
        if (errors.size == 1) return errors.single()
        val message = failed.entries.joinToString("；") { (section, error) -> "${section.name.lowercase()}：${error.message}" }
        val first = errors.first()
        return if (errors.all { it.code == first.code && it.retry == first.retry }) {
            first.copy(message = message, hint = errors.mapNotNull { it.hint }.distinct().joinToString("；").ifBlank { null })
        } else {
            ToolError(
                code = ToolErrorCode.SOURCE_UNAVAILABLE,
                message = message,
                hint = errors.mapNotNull { it.hint }.distinct().joinToString("；").ifBlank { null },
                retry = if (errors.any { it.retry == Retry.USER }) Retry.USER else ToolErrorCode.SOURCE_UNAVAILABLE.retry,
            )
        }
    }

    override fun uiTitle(input: DeviceReadInput): String {
        if (input.sections == listOf(DeviceSection.TIME)) return "看当前时间"
        return "查看设备状态" + input.sections.takeIf { it.isNotEmpty() && it.size <= 3 }
            ?.joinToString("、", prefix = " · ") { it.label() }.orEmpty()
    }

    override fun renderForUi(input: DeviceReadInput, output: DeviceReadOutput): ToolUiView {
        val battery = output.data.optJSONObject("battery")?.takeIf { it.has("percent") }
            ?.let { "电量 ${it.optInt("percent")}%" + if (it.optBoolean("charging")) "（充电中）" else "" }
        val read = output.data.keys().asSequence().mapNotNull { key ->
            DeviceSection.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }?.label()
        }.toList()
        val fields = output.data.keys().asSequence().map { key ->
            ToolUiBlock.Field(
                DeviceSection.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }?.label() ?: key,
                output.data.opt(key).uiText(),
            )
        }.toList()
        // 位置、网络、周边环境算个人数据，只在本次运行中显示。
        return ToolUiView(
            summary = battery ?: read.joinToString("、").ifBlank { "已读取" },
            blocks = listOf(ToolUiBlock.Fields(fields)).filter { fields.isNotEmpty() },
            transient = input.sections.any { it in backend.sensitiveSections() },
        )
    }

    private fun DeviceSection.label(): String = when (this) {
        DeviceSection.BATTERY -> "电池"
        DeviceSection.MEMORY -> "内存"
        DeviceSection.STORAGE -> "存储"
        DeviceSection.SYSTEM -> "系统"
        DeviceSection.NETWORK -> "网络"
        DeviceSection.ENVIRONMENT -> "环境"
        DeviceSection.LOCATION -> "位置"
        DeviceSection.TIME -> "时间"
    }

    override fun renderForModel(output: DeviceReadOutput): ModelContent = ModelContent.Json(output.data)

    /** 部分 section 读失败时作为 warning（合同：整体 ok + warnings），带上原因与下一步（warning 没有 retry 字段）。 */
    override fun warnings(output: DeviceReadOutput): List<ToolWarning> =
        output.failed.map { (section, error) ->
            val next = error.hint?.let { "（$it）" }.orEmpty()
            ToolWarning(error.code, "${section.name.lowercase()} 读取失败：${error.message}$next")
        }
}
