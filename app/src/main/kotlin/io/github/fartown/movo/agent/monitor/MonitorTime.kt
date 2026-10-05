package io.github.fartown.movo.agent.monitor

import android.content.Context
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 后台监听在界面与通知里的时刻（规范 8.12「监听事件·名称·15:03」「17:00 自动结束」）：
 * 跟随系统的 12 / 24 小时设置；不是今天时带上日期（8 小时的监听会跨过午夜）。
 */
internal object MonitorTime {
    fun clock(context: Context, millis: Long, nowMillis: Long = System.currentTimeMillis()): String =
        format(
            millis = millis,
            nowMillis = nowMillis,
            locale = context.resources.configuration.locales[0] ?: Locale.getDefault(),
            use24HourClock = android.text.format.DateFormat.is24HourFormat(context),
        )

    fun format(
        millis: Long,
        nowMillis: Long,
        locale: Locale,
        use24HourClock: Boolean,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val zone = timeZone.toZoneId()
        val sameDay = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val time = when {
            use24HourClock -> "HH:mm"
            locale.language == Locale.CHINESE.language || locale.language == Locale.JAPANESE.language -> "ah:mm"
            else -> "h:mm a"
        }
        val date = when {
            sameDay -> ""
            locale.language == Locale.CHINESE.language || locale.language == Locale.JAPANESE.language -> "M月d日 "
            locale.language == Locale.KOREAN.language -> "M월 d일 "
            else -> "MMM d, "
        }
        return SimpleDateFormat(date + time, locale).also { it.timeZone = timeZone }.format(Date(millis))
    }
}
