package io.github.fartown.movo.agent.tools.core

import org.json.JSONObject

/**
 * 条数、字数、行数、等待时长这类上限参数越界时按 Schema 的边界处理（重构前的行为），在结果里注明，
 * 不让模型为一个上限多花一轮。时刻、序号、坐标、音量、倒计时这类值越界仍报参数错误：夹到边界会做错事。
 * 新增上限类参数名时登记到 [CLAMPED]。
 */
internal object ToolArgBounds {
    val CLAMPED: Set<String> = setOf(
        "limit", "max_nodes", "max_chars", "max_lines", "max_age_minutes", "days",
        "wait_ms", "timeout_ms", "timeout_seconds", "duration_ms", "hold_ms", "amount",
    )

    /** 把 [arguments] 顶层越界的上限参数改成边界值（原地修改），返回给模型的说明。 */
    fun clamp(arguments: JSONObject, schema: JSONObject): List<ToolWarning> {
        val properties = schema.optJSONObject("properties") ?: return emptyList()
        return CLAMPED.mapNotNull { name ->
            val property = properties.optJSONObject(name) ?: return@mapNotNull null
            if (property.optString("type") != "integer") return@mapNotNull null
            val value = (arguments.opt(name) as? Number)?.toDouble() ?: return@mapNotNull null
            if (value % 1.0 != 0.0) return@mapNotNull null
            val min = if (property.has("minimum")) property.optLong("minimum") else null
            val max = if (property.has("maximum")) property.optLong("maximum") else null
            val bound = when {
                min != null && value < min -> min
                max != null && value > max -> max
                else -> return@mapNotNull null
            }
            arguments.put(name, bound)
            val range = when {
                min != null && max != null -> "范围 $min–$max"
                min != null -> "下限 $min"
                else -> "上限 $max"
            }
            ToolWarning(ToolErrorCode.INVALID_ARGUMENTS, "$name=${value.toLong()} 超出$range，已按 $bound 处理")
        }
    }
}
