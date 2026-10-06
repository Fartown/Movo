package io.github.fartown.movo.agent.tools.device

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.uiFields
import io.github.fartown.movo.agent.tools.core.uiItems
import io.github.fartown.movo.agent.tools.core.uiText
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

internal data class SettingReadOutput(val namespace: SettingNamespace, val values: Map<String, String?>) : ToolOutput

/**
 * 可测后端：读取 Settings 值。[read] 返回 null 表示未设置
 *（system/secure/global 接口无法区分“键不存在”和“值为 null”）。
 * [accessible] 为 false 表示系统不允许读取该命名空间（→ SYSTEM_REJECTED）。
 */
internal interface SettingReadBackend {
    fun read(namespace: SettingNamespace, key: String): String?
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
            "namespace：system、secure、global；keys 最多 20 个。未设置的键返回 null。"

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
        for (key in input.keys) {
            ctx.checkCancelled()
            values[key] = runCatching { backend.read(input.namespace, key) }.getOrNull()
        }
        return Verdict.Read(SettingReadOutput(input.namespace, values))
    }

    override fun uiTitle(input: SettingReadInput): String =
        "读取设置 · " + input.keys.take(2).joinToString("、") { settingLabel(it) } + if (input.keys.size > 2) " 等" else ""

    override fun renderForUi(input: SettingReadInput, output: SettingReadOutput): ToolUiView = ToolUiView(
        summary = output.values.entries.singleOrNull()?.let { (_, v) -> v ?: "未设置" } ?: "${output.values.size} 项",
        blocks = listOf(
            ToolUiBlock.Fields(output.values.map { (k, v) -> ToolUiBlock.Field(settingLabel(k), v ?: "未设置") }),
        ),
    )

    override fun renderForModel(output: SettingReadOutput): ModelContent {
        val values = JSONObject()
        output.values.forEach { (key, value) -> values.put(key, value ?: JSONObject.NULL) }
        return ModelContent.Json(
            JSONObject()
                .put("namespace", output.namespace.name.lowercase())
                .put("values", values),
        )
    }
}
