package io.github.fartown.movo.diagnostics.runlog

/**
 * 一行日志（方案 §5.1）。[at]、[el] 在事件发生处取；序号由写线程按写入顺序编，丢掉的行也占号（见 `gap`）。
 * [control] 记录（run_start、run_end）不受队列和单次任务上限约束。
 */
internal class RunLogRecord(
    val type: String,
    val at: Long,
    val el: Long,
    val fields: Map<String, Any?>,
    val control: Boolean = false,
) {
    /** 排队时的内存估算（UTF-16 两字节一个字符），不含图片：图片只是引用，单独计入 [referenceChars]。 */
    val estimatedBytes: Long = 64L + estimate(fields)

    /** 记录里引用的图片数据（data URL）有多少字符。 */
    val referenceChars: Long = references(fields)

    /** 图片排不下时：这一行照写，图片换成“没存”的记号。 */
    @Suppress("UNCHECKED_CAST")
    fun withoutImages(reason: String): RunLogRecord =
        RunLogRecord(type, at, el, dropImages(fields, reason) as Map<String, Any?>, control)

    private companion object {
        fun estimate(value: Any?): Long = when (value) {
            null -> 4L
            is String -> value.length * 2L
            is RunLogImage -> 128L
            is Map<*, *> -> value.entries.sumOf { (key, item) -> key.toString().length * 2L + estimate(item) }
            is Iterable<*> -> value.sumOf { estimate(it) }
            else -> 16L
        }

        fun references(value: Any?): Long = when (value) {
            is RunLogImage -> if (value.dropReason == null) value.reference.length.toLong() else 0L
            is Map<*, *> -> value.values.sumOf { references(it) }
            is Iterable<*> -> value.sumOf { references(it) }
            else -> 0L
        }

        fun dropImages(value: Any?, reason: String): Any? = when (value) {
            is RunLogImage -> value.copy(reference = "", dropReason = reason)
            is Map<*, *> -> LinkedHashMap<String, Any?>(value.size).also { out ->
                value.forEach { (key, item) -> out[key.toString()] = dropImages(item, reason) }
            }
            is List<*> -> value.map { dropImages(it, reason) }
            else -> value
        }
    }
}

/** 记录里的一张图片：写线程把 data URL 存进 `img/`，远程 URL 和其他引用原样记。不为记日志读取任何文件。 */
internal data class RunLogImage(
    val reference: String,
    val mimeType: String,
    val bytes: Int,
    val width: Int? = null,
    val height: Int? = null,
    val source: String? = null,
    /** 排队时就没能保存（引用通道已满）的原因。 */
    val dropReason: String? = null,
)

/** 单个字符串值的上限（方案 §5.5）：超出时截断并写明原长度。 */
internal object RunLogLimits {
    const val THINKING_CHARS = 200_000
    const val OTHER_CHARS = 64_000

    /** 工具结果按模型实际收到的原文记，不另设更小的上限（ToolProjection 已限制在 24,000 字左右）。 */
    fun cap(text: String, max: Int = OTHER_CHARS): String {
        if (text.length <= max) return text
        var end = max
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end) + "…[已截断，原长 ${text.length} 字]"
    }
}
