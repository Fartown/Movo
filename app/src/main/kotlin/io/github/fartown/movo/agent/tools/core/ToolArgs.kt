package io.github.fartown.movo.agent.tools.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 已通过 Schema 校验的工具参数。读取方法在缺失或类型不符时抛 [ToolFailure]（INVALID_ARGUMENTS），
 * 让工具实现不用自己处理 JSONException。
 */
internal class ToolArgs(val raw: JSONObject) {
    fun has(key: String): Boolean = raw.has(key) && !raw.isNull(key)

    fun string(key: String): String {
        if (!has(key)) invalidArgs("缺少参数 $key")
        val value = raw.opt(key)
        if (value !is String) invalidArgs("参数 $key 应为字符串")
        return value
    }

    fun stringOrNull(key: String): String? = if (has(key)) string(key) else null

    fun string(key: String, default: String): String = stringOrNull(key) ?: default

    fun nonBlank(key: String): String = string(key).trim().ifEmpty { invalidArgs("参数 $key 不能为空") }

    fun int(key: String): Int {
        if (!has(key)) invalidArgs("缺少参数 $key")
        val value = raw.opt(key)
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toDouble().let { d ->
                if (d % 1.0 != 0.0) invalidArgs("参数 $key 应为整数")
                d.toInt()
            }
            else -> invalidArgs("参数 $key 应为整数")
        }
    }

    fun intOrNull(key: String): Int? = if (has(key)) int(key) else null

    fun int(key: String, default: Int, range: IntRange? = null): Int {
        val value = intOrNull(key) ?: return default
        if (range != null && value !in range) {
            invalidArgs("参数 $key 应在 ${range.first}–${range.last} 之间，收到 $value")
        }
        return value
    }

    fun long(key: String, default: Long, range: LongRange? = null): Long {
        if (!has(key)) return default
        val value = (raw.opt(key) as? Number)?.toLong() ?: invalidArgs("参数 $key 应为整数")
        if (range != null && value !in range) {
            invalidArgs("参数 $key 应在 ${range.first}–${range.last} 之间，收到 $value")
        }
        return value
    }

    fun double(key: String): Double {
        if (!has(key)) invalidArgs("缺少参数 $key")
        return (raw.opt(key) as? Number)?.toDouble() ?: invalidArgs("参数 $key 应为数字")
    }

    fun bool(key: String, default: Boolean): Boolean {
        if (!has(key)) return default
        return raw.opt(key) as? Boolean ?: invalidArgs("参数 $key 应为布尔值")
    }

    fun obj(key: String): ToolArgs? {
        if (!has(key)) return null
        val value = raw.opt(key) as? JSONObject ?: invalidArgs("参数 $key 应为对象")
        return ToolArgs(value)
    }

    fun array(key: String): JSONArray? {
        if (!has(key)) return null
        return raw.opt(key) as? JSONArray ?: invalidArgs("参数 $key 应为数组")
    }

    fun stringList(key: String): List<String> {
        val array = array(key) ?: return emptyList()
        return (0 until array.length()).map { index ->
            array.opt(index) as? String ?: invalidArgs("参数 $key[$index] 应为字符串")
        }
    }

    inline fun <reified E : Enum<E>> enum(key: String): E {
        val value = string(key)
        return enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: invalidArgs(
                "参数 $key 不支持 $value",
                "可用值：${enumValues<E>().joinToString(", ") { it.name.lowercase() }}",
            )
    }

    inline fun <reified E : Enum<E>> enum(key: String, default: E): E =
        if (has(key)) enum(key) else default

    override fun toString(): String = raw.toString()

    companion object {
        fun parse(json: String): ToolArgs =
            ToolArgs(runCatching { JSONObject(json.ifBlank { "{}" }) }
                .getOrElse { invalidArgs("参数不是有效的 JSON object") })
    }
}

/** 统一按 Unicode 码点计长，与 Schema 校验器一致。 */
internal fun String.codePointLength(): Int = codePointCount(0, length)
