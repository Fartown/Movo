package io.github.fartown.movo.agent.tools.clockmedia

/**
 * `dumpsys alarm` / `dumpsys media_session` 的纯文本解析器。
 *
 * 全部为无副作用纯函数，只吃字符串、吐结构化结果，便于喂真实 dumpsys 样例做单测。
 * 真机上各机型输出格式差异大，这里按 AOSP/ColorOS 常见格式做尽力解析，解析不到即返回空，不冒领。
 */

/** 闹钟项类别：按 tag 里的 ALARM_ALERT / TIMER_ALERT 归类，识别不到为 [UNKNOWN]。 */
internal enum class ClockEntryKind { ALARM, TIMER, UNKNOWN }

/** 从 `dumpsys alarm` 解析出的一条触发项：有触发时刻（本地 HH:MM），可能带类别。 */
internal data class ParsedClockAlarm(
    val kind: ClockEntryKind,
    val hour: Int,
    val minute: Int,
    val tag: String?,
)

internal object DumpsysAlarmParser {

    // 一条 Alarm{...} 记录块起点。各机型都用 "Alarm{" 开头描述一条待触发闹钟。
    private const val ALARM_BLOCK_DELIMITER = "Alarm{"

    // tag 行，形如：tag=*walarm*:com.android.deskclock.ALARM_ALERT
    private val TAG_LINE = Regex("""tag=(\S+)""")

    // 带 when= 前缀的墙钟时间：when=2026-10-06 07:30:00(.000)
    private val WHEN_WALLCLOCK =
        Regex("""when=(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})""")

    // 兜底：块内任意墙钟时间 2026-10-06 07:30:00
    private val ANY_WALLCLOCK =
        Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})""")

    /**
     * 把 `dumpsys alarm` 文本解析为触发项列表。按 "Alarm{" 切块，每块取 tag 判类别、取 when 墙钟时间定 HH:MM。
     * 解析不到时刻的块会被丢弃（不产出无时刻的噪声）。
     */
    fun parse(text: String): List<ParsedClockAlarm> {
        if (text.isBlank()) return emptyList()
        val chunks = text.split(ALARM_BLOCK_DELIMITER)
        // 第一段是 "Alarm{" 之前的前言，跳过。
        return chunks.drop(1).mapNotNull { chunk ->
            val time = WHEN_WALLCLOCK.find(chunk) ?: ANY_WALLCLOCK.find(chunk) ?: return@mapNotNull null
            val groups = time.groupValues
            val hour = groups[4].toIntOrNull() ?: return@mapNotNull null
            val minute = groups[5].toIntOrNull() ?: return@mapNotNull null
            if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
            val tag = TAG_LINE.find(chunk)?.groupValues?.get(1)
            ParsedClockAlarm(kind = classify(tag), hour = hour, minute = minute, tag = tag)
        }
    }

    private fun classify(tag: String?): ClockEntryKind = when {
        tag == null -> ClockEntryKind.UNKNOWN
        tag.contains("ALARM_ALERT", ignoreCase = true) -> ClockEntryKind.ALARM
        tag.contains("TIMER_ALERT", ignoreCase = true) -> ClockEntryKind.TIMER
        else -> ClockEntryKind.UNKNOWN
    }

    /** 文本里是否有「类别兼容且 HH:MM 命中」的触发项。alarm 接受 ALARM/UNKNOWN，timer 接受 TIMER/UNKNOWN。 */
    fun hasAlarmAt(text: String, hour: Int, minute: Int): Boolean =
        parse(text).any { it.kind != ClockEntryKind.TIMER && it.hour == hour && it.minute == minute }

    fun hasTimerAt(text: String, hour: Int, minute: Int): Boolean =
        parse(text).any { it.kind != ClockEntryKind.ALARM && it.hour == hour && it.minute == minute }
}

/** 从 `dumpsys media_session` 解析出的一个会话：包名 + 播放状态码（PlaybackState.state，解析不到为 null）。 */
internal data class ParsedMediaSession(
    val packageName: String,
    val playbackStateCode: Int?,
) {
    /** PlaybackState.STATE_PLAYING == 3。 */
    val isPlaying: Boolean get() = playbackStateCode == PLAYBACK_STATE_PLAYING

    companion object {
        const val PLAYBACK_STATE_PLAYING = 3
    }
}

internal object DumpsysMediaSessionParser {

    // package=com.foo.bar  （会话块里标识所属包名）
    private val PACKAGE_LINE = Regex("""package=([a-zA-Z0-9._]+)""")

    // PlaybackState {state=3, ...}  /  state=PlaybackState {state=3
    private val PLAYBACK_STATE = Regex("""PlaybackState\s*\{\s*state=(-?\d+)""")

    /**
     * 把 `dumpsys media_session` 文本解析为会话列表。以 package= 作为会话分界，
     * 把紧随其后的 PlaybackState.state 归属到该会话；下一个 package= 出现即翻页。
     */
    fun parse(text: String): List<ParsedMediaSession> {
        if (text.isBlank()) return emptyList()
        val sessions = mutableListOf<ParsedMediaSession>()
        var currentPackage: String? = null
        var currentState: Int? = null

        fun flush() {
            val pkg = currentPackage ?: return
            sessions += ParsedMediaSession(pkg, currentState)
            currentPackage = null
            currentState = null
        }

        text.lineSequence().forEach { line ->
            val pkg = PACKAGE_LINE.find(line)?.groupValues?.get(1)
            if (pkg != null) {
                flush()
                currentPackage = pkg
            }
            PLAYBACK_STATE.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { state ->
                if (currentPackage != null) currentState = state
            }
        }
        flush()
        return sessions
    }

    /** 选出「当前」会话：优先正在播放（state=3），否则取第一个。没有会话返回 null。 */
    fun activeSession(sessions: List<ParsedMediaSession>): ParsedMediaSession? =
        sessions.firstOrNull { it.isPlaying } ?: sessions.firstOrNull()
}
