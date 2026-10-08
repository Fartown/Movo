package io.github.fartown.movo.agent.tools.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 [ToolOutcome] 投影成给模型的内容。
 *
 * - 默认是紧凑 JSON：{"status","data","warnings","truncated"} 或 {"status","code","message","retry","hint","detail"}。
 * - 带 [ToolOutcome.textBody] 的结果（终端输出等）用“头部 + 正文”纯文本，避免大段输出转义膨胀；
 *   正文超过 [MAX_TEXT_BODY_CHARS] 时每段保留开头和结尾（[clipTextBody]），完整正文仍在界面与日志中。
 */
internal object ToolProjection {
    /** 给模型的 JSON 内容上限；超出时按字段截短（[JsonResultClip]）并注明，完整结构化结果仍在界面与日志中。 */
    const val MAX_MODEL_CHARS = 24_000

    /** 纯文本正文（终端输出）给模型的上限，与重构前终端输出上限一致。 */
    const val MAX_TEXT_BODY_CHARS = 16_000

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
        // 结构化结果过大：按字段截短 data，保留结构、错误码、warnings 与续读游标，并写明截了哪些字段。
        return JsonResultClip.fit(json, MAX_MODEL_CHARS)
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
        val body = clipTextBody(outcome.textBody.orEmpty())
        if (body.omitted > 0) {
            append("truncated: 正文共 ").append(body.total).append(" 字，超过 ").append(MAX_TEXT_BODY_CHARS)
                .append(" 字，每段只给开头和结尾，中间省略 ").append(body.omitted).append(" 字。")
                .append("要看省略的部分：后台命令用 terminal_job read 带 cursor 分段读；")
                .append("已结束的命令把输出重定向到文件再用 file_read 分页读，或用 grep/head/tail 缩小输出\n")
        }
        append(body.text)
    }

    /** 截短后的正文：[omitted] 为省略的字数（0 表示没截）。 */
    internal data class ClippedBody(val text: String, val total: Int, val omitted: Int)

    /**
     * 正文超过 [max] 时截短：终端正文（头部 + `--- stdout ---` / `--- stderr ---` 两段）头部原样保留，
     * 两段分预算——短的一段整段给，余下给长的一段——每段留开头四分之一、结尾四分之三（日志结尾更要紧）；
     * 不是终端格式的正文整体留首尾。
     */
    internal fun clipTextBody(body: String, max: Int = MAX_TEXT_BODY_CHARS): ClippedBody {
        if (body.length <= max) return ClippedBody(body, body.length, 0)
        val outMarker = "--- stdout ---\n"
        val errMarker = "--- stderr ---\n"
        val outAt = body.indexOf(outMarker)
        val errAt = body.indexOf(errMarker, startIndex = maxOf(outAt, 0))
        if (outAt < 0 && errAt < 0) {
            val clip = TextClip.headTail(body, headChars = max / 4, tailChars = max - max / 4)
            return ClippedBody(clip.text, body.length, body.length - max)
        }
        val headerEnd = if (outAt >= 0) outAt else errAt
        val header = body.substring(0, headerEnd)
        val stdout = if (outAt >= 0) body.substring(outAt + outMarker.length, if (errAt >= 0) errAt else body.length) else ""
        val stderr = if (errAt >= 0) body.substring(errAt + errMarker.length) else ""
        val budget = (max - header.length).coerceAtLeast(MIN_SECTION_BUDGET)
        val half = budget / 2
        val (outBudget, errBudget) = when {
            stdout.length <= half -> stdout.length to budget - stdout.length
            stderr.length <= half -> budget - stderr.length to stderr.length
            else -> half to budget - half
        }
        fun clip(section: String, sectionBudget: Int) =
            TextClip.headTail(section, headChars = sectionBudget / 4, tailChars = sectionBudget - sectionBudget / 4).text
        val text = buildString {
            append(header)
            if (outAt >= 0) append(outMarker).append(clip(stdout, outBudget))
            if (errAt >= 0) append(errMarker).append(clip(stderr, errBudget))
        }
        val kept = minOf(stdout.length, outBudget) + minOf(stderr.length, errBudget)
        return ClippedBody(text, body.length, stdout.length + stderr.length - kept)
    }

    /** 头部异常长时每段仍至少留这么多字。 */
    private const val MIN_SECTION_BUDGET = 2_000

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
