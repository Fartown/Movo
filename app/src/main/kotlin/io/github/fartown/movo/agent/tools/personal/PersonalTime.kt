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
