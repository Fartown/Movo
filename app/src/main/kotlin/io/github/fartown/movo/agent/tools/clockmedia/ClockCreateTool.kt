package io.github.fartown.movo.agent.tools.clockmedia

import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.RecoverySpec
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/** 时钟类型：闹钟或倒计时。clock_read 也复用本枚举。 */
internal enum class ClockType { ALARM, TIMER }

/** 重复星期，映射到 Calendar 的星期常量。 */
internal enum class WeekDay(val calendarDay: Int) {
    SUNDAY(Calendar.SUNDAY),
    MONDAY(Calendar.MONDAY),
    TUESDAY(Calendar.TUESDAY),
    WEDNESDAY(Calendar.WEDNESDAY),
    THURSDAY(Calendar.THURSDAY),
    FRIDAY(Calendar.FRIDAY),
    SATURDAY(Calendar.SATURDAY),
}

internal data class ClockCreateInput(
    val type: ClockType,
    /** alarm 专用：小时 0–23。 */
    val hour: Int = 0,
    /** alarm 专用：分钟 0–59。 */
    val minute: Int = 0,
    val repeatDays: List<WeekDay> = emptyList(),
    val vibrate: Boolean = true,
    /** timer 专用：时长秒 1–86400。 */
    val durationSeconds: Int = 0,
    val label: String? = null,
) : ToolInput

internal data class ClockCreateOutput(
    val type: ClockType,
    val verified: Boolean,
    /** 归因到本次请求的触发时刻（epoch 毫秒），核实不到时为 null。 */
    val matchedTriggerAtMs: Long?,
) : ToolOutput

/**
 * 核实结果（回读型，方案 a）。后端负责「挂透明窗 → 派发 Intent → 按证据核实 → 撤窗」整条链路，
 * 透明窗只负责让后台/语音/锁屏下真能设上，不参与「是否成功」判断。
 */
internal sealed interface ClockCreateResult {
    /** 核实到归因本次创建的触发项（Root 用 dumpsys alarm，无 Root 用 getNextAlarmClock）。 */
    data class Attributed(val matchedTriggerAtMs: Long, val requestMarker: String) : ClockCreateResult
    /** 已派发，但核实不到本次的触发项：绝不冒领 ok。 */
    data object NotAttributed : ClockCreateResult
    /** 设备上没有可处理该请求的时钟应用，连派发都没发生。 */
    data object NoClockApp : ClockCreateResult
}

/** 可测后端：真实实现挂透明窗并派发闹钟 Intent，再按证据核实；测试用假实现。 */
internal interface ClockCreateBackend {
    fun createAndVerify(input: ClockCreateInput, env: ToolEnvironment): ClockCreateResult
}

/**
 * clock_create（回读型，方案 a）。只有核实到归因本次创建的触发项才报 Done；核实不到报 Unknown。
 * 没有取消闹钟/计时器的能力，取消改走时钟界面。
 */
internal class ClockCreateTool(
    private val backend: ClockCreateBackend,
) : ToolContract<ClockCreateInput, ClockCreateOutput> {
    override val name = "clock_create"
    override val domain = ToolDomain.CLOCK_MEDIA
    override val summary =
        "创建闹钟或倒计时。type=alarm 需 hour/minute（取下一次到达该时刻，不能指定日期）；type=timer 需 " +
            "duration_seconds。相对时间按环境里的当前时间换算。只有 ok 才代表已确认创建，unknown 表示已提交未核实。" +
            "不能取消，取消改走时钟界面。"

    override fun schema(env: ToolEnvironment): JSONObject = objectSchema {
        string("type", "alarm 或 timer", required = true, enum = listOf("alarm", "timer"))
        integer("hour", "闹钟小时 0–23（type=alarm 必填）", min = 0, max = 23)
        integer("minute", "闹钟分钟 0–59（type=alarm 必填）", min = 0, max = 59)
        stringArray(
            "repeat_days", "闹钟重复的星期（可选）",
            enum = WeekDay.entries.map { it.name.lowercase() },
        )
        boolean("vibrate", "闹钟是否震动，默认 true")
        integer("duration_seconds", "倒计时时长秒 1–86400（type=timer 必填）", min = 1, max = 86_400)
        string("label", "标签（可选）", maxLength = 100)
    }

    override fun parse(args: ToolArgs, env: ToolEnvironment): ClockCreateInput {
        val type = args.enum<ClockType>("type")
        val label = args.stringOrNull("label")?.trim()?.takeIf { it.isNotEmpty() }
        return when (type) {
            ClockType.ALARM -> {
                if (!args.has("hour") || !args.has("minute")) {
                    invalidArgs("type=alarm 需要 hour 和 minute")
                }
                val repeatDays = args.stringList("repeat_days").map { raw ->
                    WeekDay.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                        ?: invalidArgs("未知 repeat_days：$raw")
                }
                ClockCreateInput(
                    type = ClockType.ALARM,
                    hour = args.int("hour", 0, 0..23),
                    minute = args.int("minute", 0, 0..59),
                    repeatDays = repeatDays.distinct(),
                    vibrate = args.bool("vibrate", true),
                    label = label,
                )
            }
            ClockType.TIMER -> {
                if (!args.has("duration_seconds")) invalidArgs("type=timer 需要 duration_seconds")
                ClockCreateInput(
                    type = ClockType.TIMER,
                    durationSeconds = args.int("duration_seconds", 0, 1..86_400),
                    label = label,
                )
            }
        }
    }

    override fun resolve(input: ClockCreateInput, env: ToolEnvironment): CallResolution =
        CallResolution(
            risk = Risk.LOCAL,
            sensitivity = Sensitivity.NORMAL,
            resources = emptySet(),
            // 不幂等（重复会多设一个），也没有可查询的 mutation 身份：中断后保留 unknown，不自动重放。
            recovery = RecoverySpec.NonReplayable,
        )

    override fun execute(
        input: ClockCreateInput,
        resolution: CallResolution,
        ctx: ToolContext,
    ): Verdict<ClockCreateOutput> {
        ctx.checkCancelled()
        return when (val result = backend.createAndVerify(input, ctx.env)) {
            is ClockCreateResult.Attributed -> Verdict.Done(
                ClockCreateOutput(input.type, verified = true, matchedTriggerAtMs = result.matchedTriggerAtMs),
                Evidence.AttributedNew(what = describe(input), requestMarker = result.requestMarker),
            )
            ClockCreateResult.NotAttributed -> Verdict.Unknown(
                reason = "已提交${label(input.type)}请求，但未能确认是否创建成功",
                next = "用 clock_read 查看是否已创建，或让用户在时钟界面确认；不要直接重复创建",
            )
            ClockCreateResult.NoClockApp -> Verdict.Failed(
                ToolError(
                    code = ToolErrorCode.UNSUPPORTED,
                    message = "设备上没有可处理${label(input.type)}的时钟应用",
                    hint = "让用户手动在时钟里创建",
                ),
            )
        }
    }

    override fun renderForModel(output: ClockCreateOutput): ModelContent {
        val json = JSONObject().put("type", output.type.name.lowercase())
        output.matchedTriggerAtMs?.let { json.put("matched_trigger_at", formatIso(it)) }
        return ModelContent.Json(json)
    }

    private fun describe(input: ClockCreateInput): String = when (input.type) {
        ClockType.ALARM -> "闹钟 ${requestMarker(input)}"
        ClockType.TIMER -> "计时器 ${input.durationSeconds} 秒"
    }

    private fun label(type: ClockType): String = if (type == ClockType.ALARM) "闹钟" else "计时器"

    companion object {
        /** 本次请求的归因标记：闹钟用 HH:MM，计时器用「时长 N 秒」。 */
        fun requestMarker(input: ClockCreateInput): String = when (input.type) {
            ClockType.ALARM -> "%02d:%02d".format(input.hour, input.minute)
            ClockType.TIMER -> "${input.durationSeconds}s"
        }

        private fun formatIso(timeMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(timeMs))
    }
}
