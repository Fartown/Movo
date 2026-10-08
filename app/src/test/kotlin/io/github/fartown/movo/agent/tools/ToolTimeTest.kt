package io.github.fartown.movo.agent.tools

import io.github.fartown.movo.agent.tools.core.parseToolTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 工具参数里的时间写法：日期、无秒、空格分隔、无时区都按手机时区解析（2026-10-07 小米真机上 since="2026-10-07" 被拒）。 */
class ToolTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    @Test
    fun acceptsTheCommonWritings() {
        assertEquals(at(2026, 10, 7), parseToolTime("2026-10-07", zone = zone))
        assertEquals(at(2026, 10, 7, 22, 55), parseToolTime("2026-10-07T22:55", zone = zone))
        assertEquals(at(2026, 10, 7, 22, 55), parseToolTime("2026-10-07 22:55", zone = zone))
        assertEquals(at(2026, 10, 7, 22, 55, 30), parseToolTime("2026-10-07 22:55:30", zone = zone))
        assertEquals(at(2026, 10, 7, 8), parseToolTime("2026-10-07T08:00:00+08:00", zone = zone))
        assertEquals(at(2026, 10, 7, 8), parseToolTime("2026-10-07T00:00:00Z", zone = zone))
        assertEquals(1234L, parseToolTime("1234", zone = zone))
    }

    @Test
    fun dateOnlyUntilIncludesThatDay() {
        assertEquals(at(2026, 10, 8), parseToolTime("2026-10-07", endOfDay = true, zone = zone))
        assertEquals("带时刻的 until 不改", at(2026, 10, 7, 9), parseToolTime("2026-10-07 09:00", endOfDay = true, zone = zone))
    }

    @Test
    fun rejectsWhatIsNotATime() {
        assertNull(parseToolTime("", zone = zone))
        assertNull(parseToolTime("昨天", zone = zone))
        assertNull(parseToolTime("2026-13-40", zone = zone))
    }
}
