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
import io.github.fartown.movo.agent.tools.core.ToolAvailability
import io.github.fartown.movo.agent.tools.core.ToolContext
import io.github.fartown.movo.agent.tools.core.ToolContract
import io.github.fartown.movo.agent.tools.core.ToolDomain
import io.github.fartown.movo.agent.tools.core.ToolEnvironment
import io.github.fartown.movo.agent.tools.core.ToolError
import io.github.fartown.movo.agent.tools.core.ToolErrorCode
import io.github.fartown.movo.agent.tools.core.ToolInput
import io.github.fartown.movo.agent.tools.core.ToolOutput
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONArray
import org.json.JSONObject

/** 诊断类型。全部需要 Root。 */
internal enum class DiagnosticKind { TOP_PROCESSES, APP_STORAGE, LOGCAT }

internal data class DeviceDiagnosticsInput(
    val kind: DiagnosticKind,
    val limit: Int,
    val level: String?,
    val packageName: String?,
    val query: String?,
) : ToolInput

/** [data] 已是对应 kind 的结果对象；[kind] 用于投影区分。 */
internal data class DeviceDiagnosticsOutput(
    val kind: DiagnosticKind,
    val data: JSONObject,
    val truncated: Boolean,
) : ToolOutput

/** 诊断后端结果：成功带 data，失败带错误码（统一映射到核心错误码）。 */
internal sealed interface DiagnosticsResult {
    data class Ok(val data: JSONObject, val truncated: Boolean) : DiagnosticsResult
    data class Fail(val code: ToolErrorCode, val message: String) : DiagnosticsResult
}

/**
 * 可测后端：三类诊断都走 Root 执行器。无 Root 由工具层统一拦成 ROOT_REQUIRED。
 */
internal interface DeviceDiagnosticsBackend {
    fun topProcesses(limit: Int): DiagnosticsResult
    fun appStorage(limit: Int): DiagnosticsResult
    fun logcat(maxLines: Int, level: String?, packageName: String?, query: String?): DiagnosticsResult
}

/**
 * device_diagnostics（只读）：top_processes、app_storage、logcat。
 * 三项全靠 Root（ps -A、dumpsys diskstats、logcat 没 Root 只能看到自己）→ 没 Root 时整个工具不进目录，
 * schema 和说明都不出现，模型也就不会去调必报 ROOT_REQUIRED 的项（真机：没 Root 时模型调 top_processes 白跑一次）。
 * logcat 结果为 private。
 */
internal class DeviceDiagnosticsTool(
    private val backend: DeviceDiagnosticsBackend,
) : ToolContract<DeviceDiagnosticsInput, DeviceDiagnosticsOutput> {
    override val name = "device_diagnostics"
    override val domain = ToolDomain.DEVICE
    override val summary =
        "设备诊断（需 Root）：top_processes 按内存列进程、app_storage 按存储列应用、logcat 读系统日志。" +
            "limit 前两者 1–50 默认 10；logcat max_lines 1–500 默认 200，可按 level/package/query 过滤。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string(
            "kind", "诊断类型：top_processes、app_storage、logcat",
            required = true, enum = DiagnosticKind.entries.map { it.name.lowercase() },
        )
        integer("limit", "top_processes/app_storage 的条数，1–50，默认 10", min = 1, max = 50)
        integer("max_lines", "logcat 行数，1–500，默认 200", min = 1, max = 500)
        string("level", "仅 logcat：最低日志级别（V/D/I/W/E）")
        string("package", "仅 logcat：按包名过滤")
        string("query", "仅 logcat：在取到的行里按子串过滤")
    }

    /** 没有 Root 时不进目录：三项诊断没 Root 都必然失败。 */
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.rootAvailable) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(ToolErrorCode.ROOT_REQUIRED, "进程占用、应用存储明细、系统日志诊断需要 Root")
        }

    override fun parse(args: ToolArgs, env: ToolEnvironment): DeviceDiagnosticsInput {
        val kind = args.enum<DiagnosticKind>("kind")
        val limit = if (kind == DiagnosticKind.LOGCAT) {
            args.int("max_lines", default = 200, range = 1..500)
        } else {
            args.int("limit", default = 10, range = 1..50)
        }
        return DeviceDiagnosticsInput(
            kind = kind,
            limit = limit,
            level = args.stringOrNull("level")?.trim()?.takeIf { it.isNotEmpty() },
            packageName = args.stringOrNull("package")?.trim()?.takeIf { it.isNotEmpty() },
            query = args.stringOrNull("query")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    override fun resolve(input: DeviceDiagnosticsInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            risk = Risk.READ,
            // logcat 含其他应用数据 → private；前两者为普通设备信息。
            sensitivity = if (input.kind == DiagnosticKind.LOGCAT) Sensitivity.PRIVATE else Sensitivity.NORMAL,
            resources = emptySet(),
        )

    override fun execute(
        input: DeviceDiagnosticsInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<DeviceDiagnosticsOutput> {
        // 兜底：目录已按 Root 过滤，这里防执行期 Root 失效。
        if (!ctx.env.rootAvailable) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.ROOT_REQUIRED, "device_diagnostics 需要 Root 授权，本次未执行"),
            )
        }
        ctx.checkCancelled()
        val result = when (input.kind) {
            DiagnosticKind.TOP_PROCESSES -> backend.topProcesses(input.limit)
            DiagnosticKind.APP_STORAGE -> backend.appStorage(input.limit)
            DiagnosticKind.LOGCAT -> backend.logcat(input.limit, input.level, input.packageName, input.query)
        }
        return when (result) {
            is DiagnosticsResult.Ok ->
                Verdict.Read(DeviceDiagnosticsOutput(input.kind, result.data, result.truncated))
            is DiagnosticsResult.Fail -> Verdict.Failed(ToolError(result.code, result.message))
        }
    }

    override fun uiTitle(input: DeviceDiagnosticsInput): String = when (input.kind) {
        DiagnosticKind.TOP_PROCESSES -> "查看占用最高的进程"
        DiagnosticKind.APP_STORAGE -> "查看应用占用的存储"
        DiagnosticKind.LOGCAT -> "读取系统日志" + input.query?.takeIf { it.isNotBlank() }?.let { "「${it.forTitle()}」" }.orEmpty()
    }

    override fun renderForUi(input: DeviceDiagnosticsInput, output: DeviceDiagnosticsOutput): ToolUiView {
        if (output.kind == DiagnosticKind.LOGCAT) {
            val lines = output.data.optJSONArray("lines")
            val text = lines?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } }.orEmpty()
            // 系统日志里可能有别的应用写的账号、通知内容，只在本次运行中显示。
            return ToolUiView(
                summary = "${lines?.length() ?: 0} 行",
                blocks = listOf(ToolUiBlock.Output(text)).filter { text.isNotBlank() },
                transient = true,
            )
        }
        val items = output.data.optJSONArray("items").uiItems("name", "label", "package", "process")
        val summary = output.data.optString("summary").takeIf { it.isNotBlank() } ?: "${items.items.size} 项"
        return ToolUiView(summary = summary, blocks = listOf(items).filter { it.items.isNotEmpty() })
    }

    override fun renderForModel(output: DeviceDiagnosticsOutput): ModelContent {
        val json = JSONObject(output.data.toString())
            .put("kind", output.kind.name.lowercase())
        if (output.truncated) json.put("truncated", JSONObject().put("shown", shownCount(output)).put("unit", unit(output.kind)))
        return ModelContent.Json(json)
    }

    private fun shownCount(output: DeviceDiagnosticsOutput): Int = when (output.kind) {
        DiagnosticKind.LOGCAT -> (output.data.optJSONArray("lines") ?: JSONArray()).length()
        else -> (output.data.optJSONArray("items") ?: JSONArray()).length()
    }

    private fun unit(kind: DiagnosticKind): String = if (kind == DiagnosticKind.LOGCAT) "lines" else "items"
}
