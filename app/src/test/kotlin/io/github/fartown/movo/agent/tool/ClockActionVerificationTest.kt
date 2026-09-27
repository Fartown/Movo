package io.github.fartown.movo.agent.tool

import io.github.fartown.movo.agent.tool.ClockActionVerification.Status
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockActionVerificationTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 2026-09-27（周日）07:50:00 */
    private val now = at(2026, Calendar.SEPTEMBER, 27, 7, 50)

    @Test
    fun timerIsVerifiedOnlyWhenNextAlarmClockLandsAtItsEnd() {
        val end = now + 180_000L
        // 时钟晚一点处理请求：结束时刻稍晚于发起时刻 + 时长。
        assertEquals(Status.VERIFIED, ClockActionVerification.judgeTimer(null, end + 800, now, 180, 4_000))
        assertEquals(Status.VERIFIED, ClockActionVerification.judgeTimer(end + 3_600_000, end + 800, now, 180, 4_000))
        // 被静默拦截：下一次闹钟没变化。
        assertEquals(Status.NOT_OBSERVED, ClockActionVerification.judgeTimer(null, null, now, 180, 4_000))
        assertEquals(
            Status.NOT_OBSERVED,
            ClockActionVerification.judgeTimer(end + 3_600_000, end + 3_600_000, now, 180, 4_000),
        )
        // 下一次闹钟晚于预期结束时刻：本次计时器肯定没登记上。
        assertEquals(Status.NOT_OBSERVED, ClockActionVerification.judgeTimer(null, end + 60_000, now, 180, 4_000))
        // 早于预期结束时刻的是别的闹钟，挡住了确认。
        assertEquals(Status.SHADOWED, ClockActionVerification.judgeTimer(null, end - 10_000, now, 180, 4_000))
    }

    @Test
    fun timerMatchingAPreexistingNextAlarmIsNotCountedAsCreated() {
        val end = now + 180_000L
        assertEquals(Status.NOT_OBSERVED, ClockActionVerification.judgeTimer(end, end, now, 180, 4_000))
    }

    @Test
    fun timerHiddenBehindAnEarlierAlarmIsShadowed() {
        val earlier = now + 60_000L
        assertEquals(Status.SHADOWED, ClockActionVerification.judgeTimer(earlier, earlier, now, 180, 4_000))
        assertEquals(Status.SHADOWED, ClockActionVerification.judgeTimer(null, earlier, now, 180, 4_000))
    }

    @Test
    fun alarmVerificationComparesAgainstTheNextOccurrence() {
        val expected = at(2026, Calendar.SEPTEMBER, 28, 7, 30)
        assertEquals(Status.VERIFIED, ClockActionVerification.judgeAlarm(expected, expected))
        assertEquals(Status.VERIFIED, ClockActionVerification.judgeAlarm(expected + 20_000, expected))
        // 系统里没有任何闹钟，或下一次闹钟晚于预期：本次闹钟肯定没登记上。
        assertEquals(Status.NOT_OBSERVED, ClockActionVerification.judgeAlarm(null, expected))
        assertEquals(Status.NOT_OBSERVED, ClockActionVerification.judgeAlarm(expected + 3_600_000L, expected))
        // 小米：日历 0 点的 alarm clock、时钟提前 1 小时的预提醒都会挡在前面，只能报无法确认。
        val midnight = at(2026, Calendar.SEPTEMBER, 28, 0, 0)
        val preAlarm = at(2026, Calendar.SEPTEMBER, 28, 6, 30)
        assertEquals(Status.SHADOWED, ClockActionVerification.judgeAlarm(midnight, expected))
        assertEquals(Status.SHADOWED, ClockActionVerification.judgeAlarm(preAlarm, expected))
    }

    @Test
    fun nextAlarmTriggerRollsToTomorrowAndHonoursRepeatDays() {
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 28, 7, 30),
            ClockActionVerification.nextAlarmTrigger(now, 7, 30, timeZone = zone),
        )
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 27, 8, 0),
            ClockActionVerification.nextAlarmTrigger(now, 8, 0, timeZone = zone),
        )
        // 同一分钟内视为已过，排到明天。
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 28, 7, 50),
            ClockActionVerification.nextAlarmTrigger(now, 7, 50, timeZone = zone),
        )
        assertEquals(
            at(2026, Calendar.OCTOBER, 4, 7, 30),
            ClockActionVerification.nextAlarmTrigger(now, 7, 30, listOf(Calendar.SUNDAY), zone),
        )
        assertEquals(
            at(2026, Calendar.SEPTEMBER, 30, 7, 30),
            ClockActionVerification.nextAlarmTrigger(now, 7, 30, listOf(Calendar.WEDNESDAY, Calendar.FRIDAY), zone),
        )
    }

    @Test
    fun onlyVerifiedResultReportsSuccess() {
        val trigger = now + 180_000L
        val verified = ClockActionVerification.result("set_timer", Status.VERIFIED, trigger, false, zone)
        assertTrue(verified.getBoolean("ok"))
        assertTrue(verified.getBoolean("verified"))
        assertEquals("2026-09-27 07:53:00", verified.getString("next_alarm_clock_at"))
        assertFalse(verified.has("code"))

        val missing = ClockActionVerification.result("set_timer", Status.NOT_OBSERVED, null, true, zone)
        assertFalse(missing.getBoolean("ok"))
        assertFalse(missing.getBoolean("verified"))
        assertTrue(missing.getBoolean("dispatched"))
        assertEquals("CLOCK_ACTION_UNVERIFIED", missing.getString("code"))
        assertEquals("not_observed", missing.getString("reason"))
        assertTrue(missing.getString("message").contains("不要告诉用户已设置成功"))
        assertTrue(missing.getString("next_step").contains("observe_screen"))
        assertTrue(missing.getString("next_step").contains("后台弹出界面"))
        assertFalse(missing.has("next_alarm_clock_at"))

        val shadowed = ClockActionVerification.result("set_alarm", Status.SHADOWED, trigger, true, zone)
        assertFalse(shadowed.getBoolean("ok"))
        assertEquals("shadowed_by_earlier_alarm", shadowed.getString("reason"))
        assertTrue(shadowed.getString("next_step").contains("闹钟列表"))
        // 挡在前面的可能是日历或预提醒，不把它的时刻交给模型，免得被当成用户的闹钟转述。
        assertFalse(shadowed.has("next_alarm_clock_at"))
        // 被更早闹钟挡住不是权限问题，不提示去开权限。
        assertFalse(shadowed.getString("next_step").contains("后台弹出界面"))

        val otherBrand = ClockActionVerification.result("set_timer", Status.NOT_OBSERVED, null, false, zone)
        assertFalse(otherBrand.getString("next_step").contains("后台弹出界面"))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
