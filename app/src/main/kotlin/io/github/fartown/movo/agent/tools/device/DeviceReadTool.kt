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
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.ToolWarning
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.fail
import io.github.fartown.movo.agent.tools.core.objectSchema
import io.github.fartown.movo.agent.tools.core.TaintKind
import org.json.JSONObject

/** 纵向链路第一条真工具：device_read（只读）。验证合同接线，不走审批、不占资源。 */

internal enum class DeviceSection { BATTERY, MEMORY, STORAGE, SYSTEM, NETWORK, ENVIRONMENT, LOCATION }

internal data class DeviceReadInput(val sections: List<DeviceSection>) : ToolInput

internal data class DeviceReadOutput(
    val data: JSONObject,
    val failed: List<DeviceSection>,
) : ToolOutput

/** 可测后端：真实实现从 Context/系统服务读；测试用假实现。null 表示该 section 暂不可读。 */
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
        "只读设备自身的电量、内存、存储空间、系统信息、网络连接、环境、位置这些系统指标（按 sections 选）。" +
            "仅限这些系统状态；找文件/照片用 file_search、Wi‑Fi 密码用 wifi_password_read、App 用量用 usage_read、" +
            "系统设置用 setting_read。当前时间已在环境信息中。"

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
        val failed = mutableListOf<DeviceSection>()
        for (section in input.sections) {
            ctx.checkCancelled()
            val value = runCatching { backend.read(section, ctx.env) }.getOrNull()
            if (value == null) failed += section else data.put(section.name.lowercase(), value)
        }
        if (data.length() == 0) {
            return Verdict.Failed(
                io.github.fartown.movo.agent.tools.core.ToolError(
                    code = ToolErrorCode.SOURCE_UNAVAILABLE,
                    message = "设备状态暂时读不到",
                ),
            )
        }
        return Verdict.Read(DeviceReadOutput(data, failed))
    }

    /** 只有位置算个人数据；电量、内存、网络等系统状态不算。 */
    override fun taintKinds(input: DeviceReadInput): Set<TaintKind> =
        if (DeviceSection.LOCATION in input.sections) setOf(TaintKind.PERSONAL) else emptySet()

    override fun renderForModel(output: DeviceReadOutput): ModelContent = ModelContent.Json(output.data)

    /** 部分 section 读失败时作为 warning（合同：整体 ok + warnings）。 */
    override fun warnings(output: DeviceReadOutput): List<ToolWarning> =
        output.failed.map { ToolWarning(ToolErrorCode.SOURCE_UNAVAILABLE, "${it.name.lowercase()} 读取失败") }
}
