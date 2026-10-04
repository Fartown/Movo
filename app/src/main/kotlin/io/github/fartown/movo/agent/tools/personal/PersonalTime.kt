package io.github.fartown.movo.agent.tools.personal

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 个人数据领域共享的时间转换：对外统一用 ISO 8601，对内按毫秒比较。 */

/** 毫秒时间戳 → 本地时区 ISO 8601 字符串。 */
internal fun isoOf(millis: Long): String =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

/**
 * ISO 8601（可带时区；不带时区按本地时区）或纯毫秒数字字符串 → 毫秒时间戳。
 * 解析不了返回 null，调用方据此报 INVALID_ARGUMENTS。
 */
internal fun parseIsoOrMillis(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    trimmed.toLongOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()?.let { return it }
    runCatching {
        java.time.LocalDateTime.parse(trimmed).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()?.let { return it }
    return null
}
