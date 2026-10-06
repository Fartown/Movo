package io.github.fartown.movo.agent.tools.clockmedia

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
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

internal data class ClockReadInput(
    /** 为空表示两类都返回。 */
    val type: ClockType?,
    val enabledOnly: Boolean,
    val limit: Int,
) : ToolInput

internal data class ClockReadOutput(
    val items: JSONArray,
    val limit: Int,
) : ToolOutput

/** 可测后端：真实实现读 ColorOS alarms.db 或解析 dumpsys alarm；测试用假实现。 */
internal interface ClockReadBackend {
    fun read(type: ClockType?, enabledOnly: Boolean, limit: Int, env: ToolEnvironment): ClockReadResult
}

internal sealed interface ClockReadResult {
    data class Items(val items: JSONArray) : ClockReadResult
    /** 数据源不可访问或结构不受支持。 */
    data class Unavailable(val reason: String) : ClockReadResult
}

/** clock_read：列出闹钟或倒计时（只读，需 Root）。 */
internal class ClockReadTool(
    private val backend: ClockReadBackend,
) : ToolContract<ClockReadInput, ClockReadOutput> {
    override val name = "clock_read"
    override val domain = ToolDomain.CLOCK_MEDIA
    override val summary =
        "列出闹钟或倒计时（需 Root）。type 可选，默认两类都返回；enabled_only 默认 false；limit 1–50 默认 20。"

    // 需 Root：无 Root 时目录层隐藏（execute 仍有 ROOT_REQUIRED 兜底）。
    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (env.rootAvailable) ToolAvailability.Available
        else ToolAvailability.Unavailable(ToolErrorCode.ROOT_REQUIRED, "读取闹钟/计时器需要 Root")

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("type", "alarm 或 timer，缺省返回两类", enum = listOf("alarm", "timer"))
        boolean("enabled_only", "只返回已启用的，默认 false")
        integer("limit", "最多返回多少条，1–50，默认 20", min = 1, max = 50)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): ClockReadInput = ClockReadInput(
        type = if (args.has("type")) args.enum<ClockType>("type") else null,
        enabledOnly = args.bool("enabled_only", false),
        limit = args.int("limit", 20, 1..50),
    )

    override fun resolve(input: ClockReadInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            risk = Risk.READ,
            sensitivity = Sensitivity.PRIVATE,
            resources = emptySet(),
        )

    override fun execute(
        input: ClockReadInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<ClockReadOutput> {
        ctx.checkCancelled()
        // ContractTool 不投影 availability，Root 门禁在这里兜底。
        if (!ctx.env.rootAvailable) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.ROOT_REQUIRED, "读取闹钟/计时器需要 Root 授权，本次未执行"),
            )
        }
        return when (val result = backend.read(input.type, input.enabledOnly, input.limit, ctx.env)) {
            is ClockReadResult.Items -> Verdict.Read(ClockReadOutput(result.items, input.limit))
            is ClockReadResult.Unavailable -> Verdict.Failed(
                ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, result.reason),
            )
        }
    }

    override fun uiTitle(input: ClockReadInput): String = when (input.type) {
        ClockType.ALARM -> "查看闹钟"
        ClockType.TIMER -> "查看计时器"
        null -> "查看闹钟和计时器"
    }

    override fun renderForUi(input: ClockReadInput, output: ClockReadOutput): ToolUiView {
        val items = (0 until output.items.length()).mapNotNull { output.items.optJSONObject(it) }.map { item ->
            val time = item.optString("trigger_clock").takeIf { it.isNotBlank() }
                ?: "%02d:%02d".format(item.optInt("hour"), item.optInt("minute"))
            val kind = if (item.optString("kind") == "timer") "计时器" else "闹钟"
            ToolUiBlock.Item(
                title = time,
                subtitle = listOfNotNull(kind, item.optString("label").takeIf { item.has("label") && !item.isNull("label") && it.isNotBlank() })
                    .joinToString(" · "),
            )
        }
        return ToolUiView(
            summary = if (items.isEmpty()) "没有" else "${items.size} 个",
            blocks = listOf(ToolUiBlock.Items(items)).filter { items.isNotEmpty() },
        )
    }

    override fun renderForModel(output: ClockReadOutput): ModelContent = ModelContent.Json(
        JSONObject()
            .put("items", output.items)
            .put("count", output.items.length())
            .put("truncated", output.items.length() >= output.limit),
    )
}
