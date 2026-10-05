package io.github.fartown.movo.agent.tools.clockmedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** dumpsys alarm / media_session 纯解析函数的单测：喂真实 dumpsys 样例文本断言解析结果。 */
class ClockMediaDumpsysParsersTest {

    // 取自 AOSP `dumpsys alarm` 的待触发闹钟块（精简保留 tag / when 等关键行）。
    private val alarmDump = """
        Alarm Manager state:
          Realtime wakeup (now=+5d1h3m elapsed): ...
          Pending alarm batches: 3
          Batch{a0 num=1 start=+7h12m end=+7h12m}:
            RTC_WAKEUP #0: Alarm{3f2a1b type 0 origWhen 1759729800000 whenElapsed +7h12m com.android.deskclock}
              tag=*walarm*:com.android.deskclock.ALARM_ALERT
              type=RTC_WAKEUP origWhen=1759729800000 window=0 repeatInterval=0 count=0 flags=0x1
              whenElapsed=+7h12m0s0ms
              when=2026-10-06 07:30:00.000
          Batch{a1 num=1 start=-10m end=-10m}:
            RTC_WAKEUP #0: Alarm{7c9d2e type 0 origWhen 1759690500000 com.android.deskclock}
              tag=*walarm*:com.android.deskclock.TIMER_ALERT
              type=RTC_WAKEUP origWhen=1759690500000 window=0 flags=0x1
              when=2026-10-05 15:05:00.000
          Batch{a2 num=1}:
            RTC_WAKEUP #0: Alarm{b4e6f8 type 0 com.google.android.calendar}
              tag=*walarm*:com.google.android.calendar.EXTENDER
              when=2026-10-06 00:00:00.000
    """.trimIndent()

    @Test
    fun alarmParser_classifiesByTagAndExtractsTime() {
        val entries = DumpsysAlarmParser.parse(alarmDump)
        assertEquals(3, entries.size)

        val alarm = entries[0]
        assertEquals(ClockEntryKind.ALARM, alarm.kind)
        assertEquals(7, alarm.hour)
        assertEquals(30, alarm.minute)

        val timer = entries[1]
        assertEquals(ClockEntryKind.TIMER, timer.kind)
        assertEquals(15, timer.hour)
        assertEquals(5, timer.minute)

        val calendar = entries[2]
        assertEquals(ClockEntryKind.UNKNOWN, calendar.kind)
        assertEquals(0, calendar.hour)
        assertEquals(0, calendar.minute)
    }

    @Test
    fun alarmParser_hasAlarmAt_matchesAlarmAndUnknownButNotTimer() {
        assertTrue(DumpsysAlarmParser.hasAlarmAt(alarmDump, 7, 30)) // ALARM 命中
        assertTrue(DumpsysAlarmParser.hasAlarmAt(alarmDump, 0, 0)) // UNKNOWN 也接受
        assertFalse(DumpsysAlarmParser.hasAlarmAt(alarmDump, 15, 5)) // TIMER 不算 alarm
        assertFalse(DumpsysAlarmParser.hasAlarmAt(alarmDump, 8, 0)) // 不存在
    }

    @Test
    fun alarmParser_hasTimerAt_matchesTimerAndUnknownButNotAlarm() {
        assertTrue(DumpsysAlarmParser.hasTimerAt(alarmDump, 15, 5)) // TIMER 命中
        assertTrue(DumpsysAlarmParser.hasTimerAt(alarmDump, 0, 0)) // UNKNOWN 也接受
        assertFalse(DumpsysAlarmParser.hasTimerAt(alarmDump, 7, 30)) // ALARM 不算 timer
    }

    @Test
    fun alarmParser_emptyText_returnsEmpty() {
        assertTrue(DumpsysAlarmParser.parse("").isEmpty())
        assertTrue(DumpsysAlarmParser.parse("Alarm Manager state:\n  (nothing)").isEmpty())
    }

    // 取自 `dumpsys media_session` 的会话栈（精简保留 package / PlaybackState）。
    private val mediaDump = """
        Sessions Stack - have 2 sessions:
          MediaSessionRecord {
            ownerPid=4821, ownerUid=10234, userId=0
            package=com.spotify.music
            launchIntent=PendingIntent{...}
            active=true
            state=PlaybackState {state=3, position=42000, buffered position=0, speed=1.0}
          }
          MediaSessionRecord {
            ownerPid=5190, ownerUid=10355, userId=0
            package=com.google.android.youtube
            active=true
            state=PlaybackState {state=2, position=0, buffered position=0, speed=0.0}
          }
        Global priority session is null
    """.trimIndent()

    @Test
    fun mediaParser_parsesPackagesAndStates() {
        val sessions = DumpsysMediaSessionParser.parse(mediaDump)
        assertEquals(2, sessions.size)
        assertEquals("com.spotify.music", sessions[0].packageName)
        assertEquals(3, sessions[0].playbackStateCode)
        assertTrue(sessions[0].isPlaying)
        assertEquals("com.google.android.youtube", sessions[1].packageName)
        assertEquals(2, sessions[1].playbackStateCode)
        assertFalse(sessions[1].isPlaying)
    }

    @Test
    fun mediaParser_activeSession_prefersPlaying() {
        val sessions = DumpsysMediaSessionParser.parse(mediaDump)
        assertEquals("com.spotify.music", DumpsysMediaSessionParser.activeSession(sessions)?.packageName)
    }

    @Test
    fun mediaParser_activeSession_fallsBackToFirstWhenNonePlaying() {
        val dump = """
            Sessions Stack - have 1 sessions:
              MediaSessionRecord {
                package=com.foo.player
                state=PlaybackState {state=2, position=0}
              }
        """.trimIndent()
        val sessions = DumpsysMediaSessionParser.parse(dump)
        assertEquals(1, sessions.size)
        assertEquals("com.foo.player", DumpsysMediaSessionParser.activeSession(sessions)?.packageName)
    }

    @Test
    fun mediaParser_emptyText_returnsEmptyAndNullActive() {
        val sessions = DumpsysMediaSessionParser.parse("Sessions Stack - have 0 sessions:")
        assertTrue(sessions.isEmpty())
        assertNull(DumpsysMediaSessionParser.activeSession(sessions))
    }
}
