package io.github.fartown.movo.agent.tools.personal

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
import io.github.fartown.movo.agent.tools.core.TaintKind
import org.json.JSONObject

internal data class HealthReadInput(val days: Int) : ToolInput

/**
 * 健康汇总结果。[summary] 是已聚合好的 JSON（步数、睡眠、运动、心率、体重、血氧）；
 * [hasData] 区分"无数据"与"数据源不可用"。
 */
internal data class HealthReadOutput(
    val days: Int,
    val summary: JSONObject,
    val hasData: Boolean,
) : ToolOutput

internal sealed interface HealthReadResult {
    data class Ok(val summary: JSONObject, val hasData: Boolean) : HealthReadResult

    /** 数据源不可用（Health Connect 缺权限、数据库读不到等），与"无数据"区分开。 */
    data class Unavailable(val error: ToolError) : HealthReadResult
}

/**
 * 可测后端：读最近 N 天健康汇总。真实实现优先 Health Connect aggregate（免 Root，声明健康权限），
 * 否则 Root 读 healthconnect.db；多来源按来源去重，不直接 SUM（手机 + 手表会翻倍）。
 */
internal interface HealthReadBackend {
    fun available(env: ToolEnvironment): Boolean
    fun summarize(days: Int, env: ToolEnvironment): HealthReadResult
}

/**
 * health_read（只读，secret）：汇总最近 N 天步数、睡眠、运动、心率、体重、血氧，不返回原始序列。
 * days 1–30 默认 7。数据源不可用时目录层隐藏；可读但无记录时返回空 summary 并标 has_data=false。
 */
internal class HealthReadTool(
    private val backend: HealthReadBackend,
) : ToolContract<HealthReadInput, HealthReadOutput> {
    override val name = "health_read"
    override val domain = ToolDomain.PERSONAL
    override val summary =
        "汇总最近 N 天的步数、睡眠、运动、心率、体重、血氧，不返回原始序列。days 1–30，默认 7。"

    override fun availability(env: ToolEnvironment): ToolAvailability =
        if (backend.available(env)) {
            ToolAvailability.Available
        } else {
            ToolAvailability.Unavailable(
                ToolErrorCode.PERMISSION_REQUIRED,
                "读取健康数据需要 Health Connect 健康权限或 Root 授权",
            )
        }

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        integer("days", "统计最近多少天，1–30，默认 7", min = 1, max = MAX_DAYS.toLong())
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): HealthReadInput =
        HealthReadInput(days = args.int("days", DEFAULT_DAYS, 1..MAX_DAYS))

    override fun resolve(input: HealthReadInput, env: ToolEnvironment): CallResolution =
        CallResolution(risk = Risk.READ, sensitivity = Sensitivity.SECRET, resources = emptySet())

    override fun execute(
        input: HealthReadInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<HealthReadOutput> {
        if (!backend.available(ctx.env)) {
            return Verdict.Failed(
                ToolError(ToolErrorCode.PERMISSION_REQUIRED, "没有可用的健康数据源"),
            )
        }
        ctx.checkCancelled()
        return when (val result = runCatching { backend.summarize(input.days, ctx.env) }.getOrNull()) {
            is HealthReadResult.Ok -> Verdict.Read(HealthReadOutput(input.days, result.summary, result.hasData))
            is HealthReadResult.Unavailable -> Verdict.Failed(result.error)
            null -> Verdict.Failed(ToolError(ToolErrorCode.SOURCE_UNAVAILABLE, "健康数据暂时读不到"))
        }
    }

    override fun taintKinds(input: HealthReadInput): Set<TaintKind> = setOf(TaintKind.PERSONAL)

    override fun renderForModel(output: HealthReadOutput): ModelContent =
        ModelContent.Json(
            JSONObject()
                .put("window_days", output.days)
                .put("has_data", output.hasData)
                .put("summary", output.summary),
        )

    private companion object {
        const val DEFAULT_DAYS = 7
        const val MAX_DAYS = 30
    }
}
