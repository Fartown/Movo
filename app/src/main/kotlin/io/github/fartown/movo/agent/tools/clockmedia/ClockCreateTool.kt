package io.github.fartown.movo.agent.tools.clockmedia

import io.github.fartown.movo.agent.tools.core.ToolUiBlock
import io.github.fartown.movo.agent.tools.core.ToolUiView
import io.github.fartown.movo.agent.tools.core.forTitle
import io.github.fartown.movo.agent.tools.core.CallResolution
import io.github.fartown.movo.agent.tools.core.Evidence
import io.github.fartown.movo.agent.tools.core.ModelContent
import io.github.fartown.movo.agent.tools.core.RecoverySpec
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
    /** 已交给时钟应用但没核实到时，写给模型的说明（怎么如实告诉用户、怎么确认）。 */
    val note: String? = null,
) : ToolOutput

/**
 * 核实结果（回读型，方案 a）。后端负责「挂透明窗 → 派发 Intent → 按证据核实 → 撤窗」整条链路，
 * 透明窗只负责让后台/语音/锁屏下真能设上，不参与「是否成功」判断。
 */
internal sealed interface ClockCreateResult {
    /** 核实到归因本次创建的触发项（Root 用 dumpsys alarm，无 Root 用 getNextAlarmClock）。 */
    data class Attributed(val matchedTriggerAtMs: Long, val requestMarker: String) : ClockCreateResult
    /**
     * 已交给时钟应用，但核实不到本次的触发项（已有更早的闹钟时新闹钟成不了「下一个」，计时器多数时钟不登记）：
     * 按已派发、未核实报（和重构前派发成功就报 ok 一致），不说已确认创建。
     */
    data object NotAttributed : ClockCreateResult
    /** 设备上没有可处理该请求的时钟应用，连派发都没发生。 */
    data object NoClockApp : ClockCreateResult
    /** 有时钟应用，但启动它失败了（后台启动被拦等）；[clockPageOpened]：已改为打开时钟的闹钟 / 计时器页。 */
    data class LaunchFailed(val clockPageOpened: Boolean) : ClockCreateResult
}

/** 可测后端：真实实现挂透明窗并派发闹钟 Intent，再按证据核实；测试用假实现。 */
internal interface ClockCreateBackend {
    fun createAndVerify(input: ClockCreateInput, env: ToolEnvironment): ClockCreateResult
}

/**
 * clock_create（回读型，方案 a）。核实到归因本次创建的触发项报 Done；已交给时钟应用但核实不到报 Dispatched
 * （effect_verified=false，说明里写清只是已提交）；启动时钟失败报 SYSTEM_REJECTED，打开了时钟页就请用户自己设。
 * 没有取消闹钟/计时器的能力，取消改走时钟界面。
 */
internal class ClockCreateTool(
    private val backend: ClockCreateBackend,
) : ToolContract<ClockCreateInput, ClockCreateOutput> {
    override val name = "clock_create"
    override val domain = ToolDomain.CLOCK_MEDIA
    override val summary =
        "创建闹钟或倒计时。type=alarm 需 hour/minute（取下一次到达该时刻，不能指定日期）；type=timer 需 " +
            "duration_seconds。相对时间按环境里的当前时间换算。effect_verified=true 才代表已确认创建，false 表示已交给时钟应用但没核实到。" +
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
            ClockCreateResult.NotAttributed -> Verdict.Dispatched(
                ClockCreateOutput(
                    input.type, verified = false, matchedTriggerAtMs = null,
                    note = "已把${label(input.type)}交给时钟应用，但没核实到（已有更早的闹钟、或系统拦了后台启动都会这样）；" +
                        "告诉用户已提交，" +
                        (if (ctx.env.rootAvailable) "可以用 clock_read 查看，" else "请用户在时钟里看一眼，") +
                        "不要重复创建",
                ),
            )
            ClockCreateResult.NoClockApp -> Verdict.Failed(
                ToolError(
                    code = ToolErrorCode.UNSUPPORTED,
                    message = "设备上没有可处理${label(input.type)}的时钟应用",
                    hint = "让用户手动在时钟里创建",
                ),
            )
            is ClockCreateResult.LaunchFailed -> Verdict.Failed(
                if (result.clockPageOpened) {
                    ToolError(
                        code = ToolErrorCode.SYSTEM_REJECTED,
                        message = "没能直接创建${label(input.type)}，已打开时钟的${label(input.type)}页",
                        hint = "请用户在打开的时钟页里自己设${describeForUser(input)}；不要重复调用",
                        retry = Retry.USER,
                    )
                } else {
                    ToolError(
                        code = ToolErrorCode.SYSTEM_REJECTED,
                        message = "时钟应用没能启动（可能被系统拦了后台启动），${label(input.type)}没有设上",
                        hint = "请用户给 Movo 打开「后台弹出界面」权限后再试，或自己在时钟里设${describeForUser(input)}",
                        retry = Retry.USER,
                    )
                },
            )
        }
    }

    /** 写给用户的这次要设的东西：「07:30 的闹钟」「5 分钟的计时器」。 */
    private fun describeForUser(input: ClockCreateInput): String = when (input.type) {
        ClockType.ALARM -> "%02d:%02d 的闹钟".format(input.hour, input.minute)
        ClockType.TIMER -> "${durationLabel(input.durationSeconds)}的计时器"
    }

    override fun uiTitle(input: ClockCreateInput): String = when (input.type) {
        ClockType.ALARM -> "新建闹钟 · %02d:%02d".format(input.hour, input.minute) +
            input.repeatDays.takeIf { it.isNotEmpty() }?.let { " · ${repeatLabel(it)}" }.orEmpty()
        ClockType.TIMER -> "新建计时器 · ${durationLabel(input.durationSeconds)}"
    } + input.label?.takeIf { it.isNotBlank() }?.let { "「${it.forTitle(12)}」" }.orEmpty()

    override fun renderForUi(input: ClockCreateInput, output: ClockCreateOutput): ToolUiView =
        ToolUiView(summary = if (output.verified) "已创建（已核实）" else "已交给时钟，没能核实")

    private fun repeatLabel(days: List<WeekDay>): String {
        val set = days.toSet()
        return when {
            set.size == 7 -> "每天"
            set == setOf(WeekDay.MONDAY, WeekDay.TUESDAY, WeekDay.WEDNESDAY, WeekDay.THURSDAY, WeekDay.FRIDAY) -> "工作日"
            set == setOf(WeekDay.SATURDAY, WeekDay.SUNDAY) -> "周末"
            else -> days.sortedBy { (it.calendarDay + 5) % 7 }.joinToString("") { "一二三四五六日"[(it.calendarDay + 5) % 7].toString() }
                .let { "周$it" }
        }
    }

    private fun durationLabel(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} 小时"
        seconds >= 3600 -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分"
        seconds % 60 == 0 -> "${seconds / 60} 分钟"
        seconds > 60 -> "${seconds / 60} 分 ${seconds % 60} 秒"
        else -> "$seconds 秒"
    }

    override fun renderForModel(output: ClockCreateOutput): ModelContent {
        val json = JSONObject().put("type", output.type.name.lowercase())
        output.matchedTriggerAtMs?.let { json.put("matched_trigger_at", formatIso(it)) }
        output.note?.let { json.put("note", it) }
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
