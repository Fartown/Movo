package io.github.fartown.movo.agent.tools.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

private val DATE_SPACE_TIME = Regex("""^(\d{4}-\d{2}-\d{2}) +(\d)""")

/**
 * 工具参数里的时间 → 毫秒时间戳；解析不了返回 null，调用方据此报 INVALID_ARGUMENTS。
 * 接受：毫秒数字；带时区的 ISO 8601；不带时区的日期时间（按手机时区，秒可省，日期和时间之间可用空格）；
 * 只有日期（起始取当天 0 点；[endOfDay] 为 true 时取次日 0 点，即包含这一天）。
 */
internal fun parseToolTime(value: String, endOfDay: Boolean = false, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    trimmed.toLongOrNull()?.let { return it }
    val text = trimmed.replace(DATE_SPACE_TIME, "$1T$2")
    runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { LocalDate.parse(text) }.getOrNull()?.let { date ->
        return (if (endOfDay) date.plusDays(1) else date).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    return null
}

/** 参数说明里统一的时间写法。 */
internal const val TOOL_TIME_FORMATS = "如 2026-10-07、2026-10-07 08:00 或 2026-10-07T08:00:00+08:00，不带时区按手机时区；也可给毫秒"
