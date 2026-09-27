package io.github.fartown.movo.agent.tool

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * 时钟直达动作（set_timer / set_alarm）的确认判定。
 *
 * startActivity 不抛异常不代表时钟真的收到了请求：小米「后台弹出界面」未授权时，系统会在
 * ActivityStarter 里静默丢弃（logcat：`MIUILOG- Permission Denied Activity` + `Abort background
 * activity starts`），调用方拿不到任何错误。所以只把系统「下一次闹钟」
 * （AlarmManager.getNextAlarmClock，时钟 App 用 setAlarmClock 登记闹钟与计时器结束时刻）
 * 出现对应时刻当作成功证据；看不到就如实报「未确认」，交给模型去界面核实。
 */
internal object ClockActionVerification {
    enum class Status {
        /** 系统下一次闹钟正好是本次请求的时刻。 */
        VERIFIED,

        /**
         * 下一次闹钟比预期更早，本次结果被挡住，无法用系统接口确认。小米上很常见：日历每天登记
         * 一个 0 点的 alarm clock，时钟还会在闹钟前 1 小时 / 15 分钟登记预提醒。
         */
        SHADOWED,

        /** 下一次闹钟不存在或晚于预期：本次动作没有生效（被拦截或需要在时钟里确认）。 */
        NOT_OBSERVED,
    }

    /** 计时器结束时刻允许的误差：时钟冷启动处理请求会晚于发起时刻，轮询期内都算。 */
    const val TIMER_EARLY_TOLERANCE_MS = 2_000L
    const val TIMER_LATE_TOLERANCE_MS = 10_000L

    /** 闹钟按分钟对齐，秒级误差都算同一个闹钟。 */
    const val ALARM_TOLERANCE_MS = 60_000L

    fun judgeTimer(
        beforeTriggerMs: Long?,
        afterTriggerMs: Long?,
        dispatchedAtMs: Long,
        durationSeconds: Int,
        pollTimeoutMs: Long = 0L,
    ): Status {
        val expected = dispatchedAtMs + durationSeconds * 1_000L
        val earliest = expected - TIMER_EARLY_TOLERANCE_MS
        val latest = expected + pollTimeoutMs + TIMER_LATE_TOLERANCE_MS
        return when {
            afterTriggerMs == null -> Status.NOT_OBSERVED
            // 发起前就已是同一时刻的下一次闹钟，分不清是不是本次创建的，不算确认。
            afterTriggerMs in earliest..latest ->
                if (afterTriggerMs != beforeTriggerMs) Status.VERIFIED else Status.NOT_OBSERVED
            afterTriggerMs < earliest -> Status.SHADOWED
            else -> Status.NOT_OBSERVED
        }
    }

    fun judgeAlarm(
        afterTriggerMs: Long?,
        expectedTriggerMs: Long,
    ): Status = when {
        afterTriggerMs == null -> Status.NOT_OBSERVED
        kotlin.math.abs(afterTriggerMs - expectedTriggerMs) < ALARM_TOLERANCE_MS -> Status.VERIFIED
        afterTriggerMs < expectedTriggerMs -> Status.SHADOWED
        else -> Status.NOT_OBSERVED
    }

    /**
     * 闹钟下一次响铃时刻：[nowMs] 之后第一个 hour:minute:00；给了 [repeatDays]
     * （Calendar.SUNDAY..SATURDAY）时还要落在这些星期里。
     */
    fun nextAlarmTrigger(
        nowMs: Long,
        hour: Int,
        minute: Int,
        repeatDays: Collection<Int> = emptyList(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val candidate = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (candidate.timeInMillis > nowMs &&
                (repeatDays.isEmpty() || candidate.get(Calendar.DAY_OF_WEEK) in repeatDays)
            ) {
                return candidate.timeInMillis
            }
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return candidate.timeInMillis
    }

    /** 组装给模型的结果：只有 [Status.VERIFIED] 才是 ok=true。 */
    fun result(
        tool: String,
        status: Status,
        observedTriggerMs: Long?,
        xiaomiFamily: Boolean,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): JSONObject {
        val target = if (tool == "set_timer") "计时器" else "闹钟"
        val page = if (tool == "set_timer") "计时器页" else "闹钟列表"
        val base = JSONObject()
            .put("tool", tool)
            .put("mode", "direct")
            .put("dispatched", true)
            .put("verified", status == Status.VERIFIED)
            .put("verification", "next_alarm_clock")
        if (status == Status.VERIFIED) {
            observedTriggerMs?.let { base.put("next_alarm_clock_at", formatLocal(it, timeZone)) }
            return base.put("ok", true)
                .put("message", "系统已确认${target}生效，响铃时刻见 next_alarm_clock_at")
        }
        val reason = when (status) {
            Status.SHADOWED -> "shadowed_by_earlier_alarm"
            else -> "not_observed"
        }
        val cause = when (status) {
            Status.SHADOWED ->
                "系统登记的下一次提醒早于本次${target}（可能来自日历、其他闹钟或时钟自己的提前提醒，" +
                    "不一定是用户设的闹钟），无法通过系统接口确认本次${target}是否已创建"
            else -> "系统里没有出现对应的${target}，请求可能被系统拦截了后台打开界面，或时钟需要手动确认"
        }
        val permissionHint = if (xiaomiFamily && status == Status.NOT_OBSERVED) {
            "；若确认被拦截，可提示用户在「应用设置 → Movo → 其他权限」允许「后台弹出界面」后重试"
        } else {
            ""
        }
        return base.put("ok", false)
            .put("code", "CLOCK_ACTION_UNVERIFIED")
            .put("reason", reason)
            .put("message", "已把请求发给时钟，但$cause。不要告诉用户已设置成功。")
            .put(
                "next_step",
                "用 launch_app 打开时钟的$page，observe_screen 核实是否已有这个$target；" +
                    "没有就在界面上完成设置并再次核实；仍无法完成时如实告诉用户未设置成功$permissionHint。" +
                    "向用户只说核实到的结果，不要推测系统里还有哪些闹钟",
            )
    }

    private fun formatLocal(timeMs: Long, timeZone: TimeZone): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .apply { this.timeZone = timeZone }
            .format(Date(timeMs))
}
