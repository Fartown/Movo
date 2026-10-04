package io.github.fartown.movo.agent.tools.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [ToolOutcome] 投影成给模型的内容。
 *
 * - 默认是紧凑 JSON：{"status","data","warnings","truncated"} 或 {"status","code","message","retry","hint","detail"}。
 * - 带 [ToolOutcome.textBody] 的结果（终端输出等）用“头部 + 正文”纯文本，避免大段输出转义膨胀。
 */
internal object ToolProjection {
    /** 给模型的 JSON 内容上限；超出时保留开头并注明截断，完整结构化结果仍在界面与日志中。 */
    const val MAX_MODEL_CHARS = 24_000

    fun render(outcome: ToolOutcome): String =
        if (outcome.textBody != null && outcome.error == null) renderText(outcome) else renderJson(outcome)

    private fun renderJson(outcome: ToolOutcome): String {
        val json = JSONObject().put("status", outcome.status.wire)
        outcome.error?.let { error ->
            json.put("code", error.code.name)
                .put("message", error.message)
                .put("retry", error.retry.wire)
            error.hint?.takeIf { it.isNotBlank() }?.let { json.put("hint", it) }
            error.detail?.takeIf { it.isNotBlank() }?.let { json.put("detail", it) }
        }
        outcome.data?.takeIf { it.length() > 0 }?.let { json.put("data", it) }
        outcome.effectVerified?.let { json.put("effect_verified", it) }
        if (outcome.images.isNotEmpty()) json.put("images_attached", outcome.images.size)
        if (outcome.warnings.isNotEmpty()) {
            json.put("warnings", JSONArray().also { array ->
                outcome.warnings.forEach { warning ->
                    array.put(JSONObject().put("code", warning.code.name).put("message", warning.message))
                }
            })
        }
        outcome.truncation?.let { json.put("truncated", truncationJson(it)) }
        val text = json.toString()
        if (text.length <= MAX_MODEL_CHARS) return text
        // 结构化结果过大时退化：保留状态与截断说明，data 以文本形式截断。
        val dataText = outcome.data?.toString().orEmpty()
        return JSONObject().put("status", outcome.status.wire)
            .put("data_text", dataText.take(MAX_MODEL_CHARS - 400))
            .put("truncated", JSONObject().put("shown", MAX_MODEL_CHARS - 400).put("total", dataText.length)
                .put("unit", "chars").put("note", "结果过大，只保留了开头；请缩小查询范围或分页读取"))
            .toString()
    }

    private fun renderText(outcome: ToolOutcome): String = buildString {
        append("status: ").append(outcome.status.wire).append('\n')
        outcome.data?.let { data ->
            data.keys().forEach { key ->
                val value = data.opt(key)
                if (value is JSONObject || value is JSONArray) {
                    append(key).append(": ").append(value.toString()).append('\n')
                } else if (value != null && value != JSONObject.NULL) {
                    append(key).append(": ").append(value.toString()).append('\n')
                }
            }
        }
        outcome.warnings.forEach { warning ->
            append("warning: ").append(warning.code.name).append(' ').append(warning.message).append('\n')
        }
        outcome.truncation?.let { truncation ->
            append("truncated: shown ").append(truncation.shown)
            truncation.total?.let { append(" of ").append(it) }
            append(' ').append(truncation.unit)
            truncation.spillPath?.let { append("; full output: ").append(it) }
            truncation.nextCursor?.let { append("; next_cursor: ").append(it) }
            append('\n')
        }
        append(outcome.textBody.orEmpty())
    }

    private fun truncationJson(truncation: Truncation): JSONObject = JSONObject()
        .put("shown", truncation.shown)
        .put("unit", truncation.unit)
        .apply {
            truncation.total?.let { put("total", it) }
            truncation.nextCursor?.let { put("next_cursor", it) }
            truncation.spillPath?.let { put("spill_path", it) }
        }
}

/** 文本截断工具：文档、列表保留开头；命令输出、日志保留开头和结尾。 */
internal object TextClip {
    data class Clip(val text: String, val truncated: Boolean, val total: Int)

    fun head(text: String, max: Int): Clip {
        if (text.length <= max) return Clip(text, false, text.length)
        var end = max
        if (end > 0 && text[end - 1].isHighSurrogate()) end--
        return Clip(text.substring(0, end), true, text.length)
    }

    /** 保留开头 [headChars] 与结尾 [tailChars]，中间注明省略量。 */
    fun headTail(text: String, headChars: Int = 2_000, tailChars: Int = 10_000): Clip {
        if (text.length <= headChars + tailChars) return Clip(text, false, text.length)
        var headEnd = headChars
        if (headEnd > 0 && text[headEnd - 1].isHighSurrogate()) headEnd--
        var tailStart = text.length - tailChars
        if (tailStart < text.length && text[tailStart].isLowSurrogate()) tailStart++
        val omitted = tailStart - headEnd
        return Clip(
            text.substring(0, headEnd) + "\n…(省略 $omitted 字符)…\n" + text.substring(tailStart),
            true,
            text.length,
        )
    }
}
