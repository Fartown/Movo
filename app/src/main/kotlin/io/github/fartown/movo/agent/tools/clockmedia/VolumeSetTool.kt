package io.github.fartown.movo.agent.tools.clockmedia

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
import io.github.fartown.movo.agent.tools.core.ToolWarning
import io.github.fartown.movo.agent.tools.core.Verdict
import io.github.fartown.movo.agent.tools.core.objectSchema
import org.json.JSONObject

internal enum class VolumeStream { MUSIC, RING, ALARM, NOTIFICATION, CALL }

internal data class VolumeSetInput(
    val stream: VolumeStream,
    val percent: Int,
) : ToolInput

internal data class VolumeSetOutput(
    val stream: VolumeStream,
    val requestedPercent: Int,
    val actualPercent: Int,
    /** 回读与请求不一致（系统钳制：如 getStreamMinVolume 下限、免打扰）。 */
    val clamped: Boolean,
) : ToolOutput

/** 回读结果；系统拒绝（免打扰/缺权限）由后端抛 [VolumeSetBackend.VolumeRejected]。 */
internal data class VolumeReadBack(
    val requestedPercent: Int,
    val actualPercent: Int,
    val level: Int,
    val maxLevel: Int,
    val minLevel: Int,
)

/** 可测后端：真实实现 setStreamVolume + 回读；测试用假实现。 */
internal interface VolumeSetBackend {
    fun setAndReadBack(stream: VolumeStream, percent: Int): VolumeReadBack

    /** 免打扰下调响铃到 0 等系统策略拒绝（缺 ACCESS_NOTIFICATION_POLICY 抛 SecurityException）。 */
    class VolumeRejected(message: String) : RuntimeException(message)
}

/** volume_set（回读型）。设置通道音量百分比并回读确认，四舍五入，考虑系统下限与钳制。 */
internal class VolumeSetTool(
    private val backend: VolumeSetBackend,
) : ToolContract<VolumeSetInput, VolumeSetOutput> {
    override val name = "volume_set"
    override val domain = ToolDomain.CLOCK_MEDIA
    override val summary =
        "设置某个音量通道的百分比（0–100）。stream：music、ring、alarm、notification、call。回读确认实际音量。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string(
            "stream", "音量通道", required = true,
            enum = VolumeStream.entries.map { it.name.lowercase() },
        )
        integer("percent", "目标百分比 0–100", required = true, min = 0, max = 100)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): VolumeSetInput = VolumeSetInput(
        stream = args.enum("stream"),
        percent = args.int("percent", 0, 0..100),
    )

    override fun resolve(input: VolumeSetInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            risk = Risk.LOCAL,
            sensitivity = Sensitivity.NORMAL,
            resources = emptySet(),
        )

    override fun execute(
        input: VolumeSetInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<VolumeSetOutput> {
        ctx.checkCancelled()
        val readBack = try {
            backend.setAndReadBack(input.stream, input.percent)
        } catch (rejected: VolumeSetBackend.VolumeRejected) {
            return Verdict.Failed(
                ToolError(
                    code = ToolErrorCode.SYSTEM_REJECTED,
                    message = "系统拒绝修改该音量（可能处于免打扰，或缺少勿扰访问权限）",
                    hint = "请用户关闭免打扰或授予勿扰访问权限后再试",
                    detail = rejected.message,
                ),
            )
        }
        // 达成判定：回读百分比相符即可；请求 0% 已到系统下限、请求 100% 已到上限也算达成。
        val reachedFloor = input.percent == 0 && readBack.level == readBack.minLevel
        val reachedCeiling = input.percent == 100 && readBack.level == readBack.maxLevel
        val consistent = readBack.actualPercent == input.percent || reachedFloor || reachedCeiling
        val output = VolumeSetOutput(
            stream = input.stream,
            requestedPercent = input.percent,
            actualPercent = readBack.actualPercent,
            clamped = !consistent,
        )
        // 系统钳制导致不一致仍算达成（Done + warning），回读本身就是本次动作的证据。
        return Verdict.Done(output, Evidence.ReadBack("${input.stream.name.lowercase()}=${readBack.actualPercent}%"))
    }

    override fun renderForModel(output: VolumeSetOutput): ModelContent = ModelContent.Json(
        JSONObject()
            .put("stream", output.stream.name.lowercase())
            .put("requested_percent", output.requestedPercent)
            .put("actual_percent", output.actualPercent),
    )

    override fun warnings(output: VolumeSetOutput): List<ToolWarning> =
        if (output.clamped) {
            listOf(
                ToolWarning(
                    ToolErrorCode.CONFLICT,
                    "系统把 ${output.stream.name.lowercase()} 钳制到 ${output.actualPercent}%（请求 ${output.requestedPercent}%）",
                ),
            )
        } else {
            emptyList()
        }
}
