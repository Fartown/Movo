package io.github.fartown.movo.agent.tools.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 结果超过给模型的上限时按字段截短，不把整份结果降成一段纯文本：
 * 每次挑 data 里能省出最多字数的一处——数组去掉末尾的项（至少留一项），长字符串只留开头——直到放得下。
 * status、错误码与提示、warnings、truncated（含 next_cursor）原样保留，截了哪些字段写进 output_clipped。
 * 实在截不下（例如 data 是成千上万个短字段）才退回只给 data 开头的原始文本，错误码、提示和 warnings 仍保留。
 */
internal object JsonResultClip {
    /** 短于这个长度的字符串不截；截短的字符串至少留这么多字。 */
    private const val MIN_STRING_CHARS = 200

    /** 每次截短多省出的余量，抵掉 output_clipped 自己变长的部分。 */
    private const val SLACK_CHARS = 400

    private const val MAX_STEPS = 40
    private const val MAX_LISTED_FIELDS = 20

    private data class Clip(val shown: Int, val total: Int, val unit: String)

    /** 一处可截的位置：[reducible] 是最多能省出的字数（数组留第一项、字符串留 [MIN_STRING_CHARS] 字）。 */
    private class Candidate(val path: String, val container: Any, val key: Any, val reducible: Int)

    fun fit(json: JSONObject, maxChars: Int): String {
        val root = JSONObject(json.toString())
        val data = root.optJSONObject("data") ?: return fallback(json, maxChars)
        val clips = LinkedHashMap<String, Clip>()
        repeat(MAX_STEPS) {
            if (clips.isNotEmpty()) root.put("output_clipped", describe(clips, maxChars))
            val text = root.toString()
            if (text.length <= maxChars) return text
            val best = arrayOfNulls<Candidate>(1)
            measure(data, "data", root, "data", best)
            val target = best[0] ?: return fallback(json, maxChars)
            shrink(target, text.length - maxChars + SLACK_CHARS, clips)
        }
        return fallback(json, maxChars)
    }

    /** 返回 [value] 序列化后的长度（与 JSONObject.toString 一致），顺带记下能省出最多字数的位置。 */
    private fun measure(value: Any?, path: String, container: Any, key: Any, best: Array<Candidate?>): Int = when (value) {
        is JSONObject -> {
            var total = 2
            var index = 0
            for (name in value.keys()) {
                if (index++ > 0) total += 1
                total += JSONObject.quote(name).length + 1 + measure(value.opt(name), "$path.$name", value, name, best)
            }
            total
        }
        is JSONArray -> {
            var total = 2
            var first = 0
            for (i in 0 until value.length()) {
                if (i > 0) total += 1
                val size = measure(value.opt(i), "$path[$i]", value, i, best)
                if (i == 0) first = size
                total += size
            }
            if (value.length() > 1) consider(best, Candidate(path, container, key, total - 2 - first))
            total
        }
        is String -> {
            if (value.length > MIN_STRING_CHARS) {
                consider(best, Candidate(path, container, key, value.length - MIN_STRING_CHARS))
            }
            JSONObject.quote(value).length
        }
        null, JSONObject.NULL -> 4
        is Number -> JSONObject.numberToString(value).length
        else -> value.toString().length
    }

    private fun consider(best: Array<Candidate?>, candidate: Candidate) {
        if (candidate.reducible > 0 && candidate.reducible > (best[0]?.reducible ?: 0)) best[0] = candidate
    }

    private fun shrink(target: Candidate, needed: Int, clips: MutableMap<String, Clip>) {
        when (val value = target.container.childAt(target.key)) {
            is JSONArray -> {
                val total = clips[target.path]?.total ?: value.length()
                var removed = 0
                while (value.length() > 1 && removed < needed) {
                    val last = value.length() - 1
                    removed += value.opt(last).toString().length + 1
                    value.remove(last)
                }
                // 去掉的项里先前截过的字段不再单列。
                clips.keys.removeAll { path -> droppedElement(path, target.path, value.length()) }
                clips[target.path] = Clip(value.length(), total, "items")
            }
            is String -> {
                // 截过一次的字符串末尾带着上次的省略说明：先取回上次留下的开头，省略字数按原文算。
                val prior = clips[target.path]
                val base = prior?.let { value.take(it.shown) } ?: value
                val total = prior?.total ?: value.length
                val keep = (base.length - needed).coerceAtLeast(MIN_STRING_CHARS)
                val head = TextClip.head(base, keep).text
                target.container.putChild(target.key, head + "…（后面省略 ${total - head.length} 字）")
                clips[target.path] = Clip(head.length, total, "chars")
            }
        }
    }

    /** [path] 是否落在数组 [arrayPath] 已去掉的项（下标 ≥ [kept]）里。 */
    private fun droppedElement(path: String, arrayPath: String, kept: Int): Boolean {
        if (!path.startsWith("$arrayPath[")) return false
        val index = path.substring(arrayPath.length + 1).substringBefore(']').toIntOrNull() ?: return false
        return index >= kept
    }

    private fun Any.childAt(key: Any): Any? = when (this) {
        is JSONObject -> opt(key as String)
        is JSONArray -> opt(key as Int)
        else -> null
    }

    private fun Any.putChild(key: Any, value: Any) {
        when (this) {
            is JSONObject -> put(key as String, value)
            is JSONArray -> put(key as Int, value)
        }
    }

    private fun describe(clips: Map<String, Clip>, maxChars: Int): JSONObject = JSONObject()
        .put(
            "note",
            "结果超过 $maxChars 字，fields 里的字段被截短了：数组去掉了末尾的项，字符串只留了开头。" +
                "被截掉的内容不在 next_cursor、next_offset 这类续读位置之后；要看就缩小范围、减小 limit 或分段读取。",
        )
        .put(
            "fields",
            JSONArray().also { array ->
                clips.entries.take(MAX_LISTED_FIELDS).forEach { (path, clip) ->
                    array.put(
                        JSONObject().put("path", path).put("shown", clip.shown).put("total", clip.total).put("unit", clip.unit),
                    )
                }
            },
        )

    /** 按字段截不下来：保留状态、错误码与提示、truncated，data 只给开头的原始文本。 */
    private fun fallback(json: JSONObject, maxChars: Int): String {
        val out = JSONObject().put("status", json.opt("status"))
        listOf("code", "retry").forEach { key -> json.opt(key)?.let { out.put(key, it) } }
        listOf("message", "hint", "detail").forEach { key ->
            json.optString(key).takeIf { it.isNotEmpty() }?.let { out.put(key, TextClip.head(it, 1_000).text) }
        }
        listOf("effect_verified", "images_attached", "truncated").forEach { key -> json.opt(key)?.let { out.put(key, it) } }
        json.optJSONArray("warnings")?.let { warnings ->
            out.put(
                "warnings",
                JSONArray().also { kept ->
                    for (i in 0 until minOf(warnings.length(), 5)) {
                        val warning = warnings.optJSONObject(i) ?: continue
                        kept.put(JSONObject(warning.toString()).put("message", TextClip.head(warning.optString("message"), 300).text))
                    }
                },
            )
        }
        val dataText = json.opt("data")?.toString().orEmpty()
        // 放进 JSON 字符串后引号、反斜杠要转义，按转义后的长度收。
        val room = (maxChars - out.toString().length - 600).coerceAtLeast(0)
        var keep = room
        var head = TextClip.head(dataText, keep).text
        while (keep > 0 && JSONObject.quote(head).length > room) {
            keep = (keep - (JSONObject.quote(head).length - room)).coerceAtLeast(0)
            head = TextClip.head(dataText, keep).text
        }
        if (dataText.isNotEmpty()) out.put("data_text", head)
        out.put(
            "output_clipped",
            JSONObject()
                .put("shown", head.length).put("total", dataText.length).put("unit", "chars")
                .put(
                    "note",
                    "结果过大，按字段截不下来，data_text 只是开头的原始文本；没给出的部分不在 next_cursor 之后，" +
                        "请缩小查询范围或减小 limit 重读",
                ),
        )
        return out.toString()
    }
}
