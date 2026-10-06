package io.github.fartown.movo.agent.tools.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 执行卡里一步的界面视图（docs/research/tool-redesign/Movo 工具可视化方案.md §5.1）。
 * 由工具从自己的结构化输出派生（[ToolContract.renderForUi]），与给模型的投影分开。
 *
 * - [summary]：收起时的第二行，一个关键结果（「找到 5 条」「退出码 0 · 输出 12 行」「40% → 70%」）。
 * - [blocks]：展开后的结构化内容。
 * - [transient]：只在本次运行中显示，不存进对话记录（截图、个人数据，见方案 §6 决策 1、2）。
 */
data class ToolUiView(
    val summary: String? = null,
    val blocks: List<ToolUiBlock> = emptyList(),
    val transient: Boolean = false,
) {
    /** 按上限裁剪（方案 §5.1 第 4 条）：列表 ≤ 20 项、输出 ≤ 200 行、单步 ≤ 16 KB。 */
    fun bounded(): ToolUiView {
        val clipped = copy(summary = summary?.oneLine(MAX_SUMMARY_CHARS), blocks = blocks.map { it.bounded() })
        var json = clipped.toJson().toString()
        if (json.length <= MAX_VIEW_CHARS) return clipped
        // 仍超出：从后往前丢块，留下摘要。
        val kept = clipped.blocks.toMutableList()
        while (kept.isNotEmpty() && json.length > MAX_VIEW_CHARS) {
            kept.removeAt(kept.lastIndex)
            json = clipped.copy(blocks = kept).toJson().toString()
        }
        return clipped.copy(blocks = kept)
    }

    /** 机密结果（验证码、密码）：只留摘要，不留任何内容块。 */
    fun summaryOnly(): ToolUiView = copy(blocks = emptyList())

    fun toJson(): JSONObject = JSONObject().apply {
        summary?.let { put("summary", it) }
        if (blocks.isNotEmpty()) put("blocks", JSONArray().apply { blocks.forEach { put(it.toJson()) } })
        if (transient) put("transient", true)
    }

    companion object {
        const val MAX_ITEMS = 20
        const val MAX_OUTPUT_LINES = 200
        const val MAX_OUTPUT_CHARS = 8_000
        const val MAX_PREVIEW_CHARS = 2_000
        const val MAX_SUMMARY_CHARS = 120
        const val MAX_FIELD_CHARS = 200
        const val MAX_VIEW_CHARS = 16_000

        fun fromJson(json: JSONObject?): ToolUiView? {
            if (json == null) return null
            val blocks = json.optJSONArray("blocks")?.let { array ->
                (0 until array.length()).mapNotNull { ToolUiBlock.fromJson(array.optJSONObject(it)) }
            }.orEmpty()
            return ToolUiView(
                summary = json.optString("summary").takeIf { json.has("summary") },
                blocks = blocks,
                transient = json.optBoolean("transient", false),
            )
        }

        fun fromJsonString(raw: String?): ToolUiView? =
            raw?.takeIf { it.isNotBlank() }?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
    }
}

/** 展开后的一种内容块。 */
sealed interface ToolUiBlock {
    fun bounded(): ToolUiBlock
    fun toJson(): JSONObject

    /** 键值：设备状态、设置项、参数。 */
    data class Fields(val rows: List<Field>) : ToolUiBlock {
        override fun bounded() = copy(rows = rows.take(ToolUiView.MAX_ITEMS).map {
            Field(it.label.oneLine(40), it.value.oneLine(ToolUiView.MAX_FIELD_CHARS))
        })
        override fun toJson() = JSONObject().put("type", "fields").put("rows", JSONArray().apply {
            rows.forEach { put(JSONObject().put("label", it.label).put("value", it.value)) }
        })
    }

    /** 列表：搜索结果、文件、应用、闹钟。[more] 是没列出的条数。 */
    data class Items(val items: List<Item>, val more: Int = 0) : ToolUiBlock {
        override fun bounded(): Items {
            val kept = items.take(ToolUiView.MAX_ITEMS).map {
                Item(it.title.oneLine(80), it.subtitle?.oneLine(120), it.trailing?.oneLine(40), it.icon)
            }
            return Items(kept, more + (items.size - kept.size))
        }
        override fun toJson() = JSONObject().put("type", "items").put("more", more).put("items", JSONArray().apply {
            items.forEach { item ->
                put(JSONObject().put("title", item.title).apply {
                    item.subtitle?.let { put("subtitle", it) }
                    item.trailing?.let { put("trailing", it) }
                    item.icon?.let { put("icon", it) }
                })
            }
        })
    }

    /** 输出块（等宽）：终端输出、诊断日志。[more] 是没显示的行数。 */
    data class Output(val text: String, val label: String? = null, val more: Int = 0) : ToolUiBlock {
        override fun bounded(): Output {
            val lines = text.lines()
            var kept = lines.take(ToolUiView.MAX_OUTPUT_LINES).joinToString("\n")
            var dropped = (lines.size - ToolUiView.MAX_OUTPUT_LINES).coerceAtLeast(0)
            if (kept.length > ToolUiView.MAX_OUTPUT_CHARS) {
                val cut = kept.take(ToolUiView.MAX_OUTPUT_CHARS)
                dropped += kept.lines().size - cut.lines().size
                kept = cut
            }
            return copy(text = kept, more = more + dropped)
        }
        override fun toJson() = JSONObject().put("type", "output").put("text", text).put("more", more)
            .apply { label?.let { put("label", it) } }
    }

    /** 内容预览（正文字体）：文件内容、记忆、网页正文开头。 */
    data class Preview(val text: String, val label: String? = null, val more: Boolean = false) : ToolUiBlock {
        override fun bounded(): Preview =
            if (text.length <= ToolUiView.MAX_PREVIEW_CHARS) this
            else copy(text = text.take(ToolUiView.MAX_PREVIEW_CHARS), more = true)
        override fun toJson() = JSONObject().put("type", "preview").put("text", text).put("more", more)
            .apply { label?.let { put("label", it) } }
    }

    /** 前后对比：改设置、改记忆。 */
    data class Change(val before: String?, val after: String?, val label: String? = null) : ToolUiBlock {
        override fun bounded() = copy(
            before = before?.take(ToolUiView.MAX_PREVIEW_CHARS),
            after = after?.take(ToolUiView.MAX_PREVIEW_CHARS),
        )
        override fun toJson() = JSONObject().put("type", "change").apply {
            before?.let { put("before", it) }
            after?.let { put("after", it) }
            label?.let { put("label", it) }
        }
    }

    /** 图片：只存进程内缓存的键（[io.github.fartown.movo.agent.media.ToolStepImages]），重启后不再有图。 */
    data class Images(val keys: List<String>) : ToolUiBlock {
        override fun bounded() = copy(keys = keys.take(4))
        override fun toJson() = JSONObject().put("type", "images").put("keys", JSONArray(keys))
    }

    data class Field(val label: String, val value: String)

    /** [icon] 是应用包名等可选的图标来源，界面按需加载。 */
    data class Item(val title: String, val subtitle: String? = null, val trailing: String? = null, val icon: String? = null)

    companion object {
        fun fromJson(json: JSONObject?): ToolUiBlock? {
            json ?: return null
            return when (json.optString("type")) {
                "fields" -> Fields(json.optJSONArray("rows").objects().map { Field(it.optString("label"), it.optString("value")) })
                "items" -> Items(
                    json.optJSONArray("items").objects().map {
                        Item(
                            title = it.optString("title"),
                            subtitle = it.optString("subtitle").takeIf { _ -> it.has("subtitle") },
                            trailing = it.optString("trailing").takeIf { _ -> it.has("trailing") },
                            icon = it.optString("icon").takeIf { _ -> it.has("icon") },
                        )
                    },
                    more = json.optInt("more", 0),
                )
                "output" -> Output(json.optString("text"), json.optString("label").takeIf { json.has("label") }, json.optInt("more", 0))
                "preview" -> Preview(json.optString("text"), json.optString("label").takeIf { json.has("label") }, json.optBoolean("more", false))
                "change" -> Change(
                    json.optString("before").takeIf { json.has("before") },
                    json.optString("after").takeIf { json.has("after") },
                    json.optString("label").takeIf { json.has("label") },
                )
                "images" -> Images(json.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())
                else -> null
            }
        }

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}

/** 压成一行并截断：界面单行字段不能被换行或超长内容撑开。 */
internal fun String.oneLine(max: Int): String {
    val flat = replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max) + "…"
}

/** 截短写在标题里的对象（「点按「…」」「搜索「…」」）。 */
internal fun String.forTitle(max: Int = 20): String = oneLine(max)

/** 把一个 JSON 值压成一行人能读的文字：对象写「键 值 · 键 值」，数组写前几项，布尔写是 / 否。 */
internal fun Any?.uiText(max: Int = ToolUiView.MAX_FIELD_CHARS): String = when (this) {
    null, JSONObject.NULL -> "—"
    is Boolean -> if (this) "是" else "否"
    is JSONObject -> keys().asSequence().mapNotNull { key ->
        opt(key).takeUnless { it == null || it == JSONObject.NULL }?.let { "$key ${it.uiText(60)}" }
    }.joinToString(" · ")
    is JSONArray -> (0 until length()).take(5).joinToString("、") { opt(it).uiText(40) } +
        if (length() > 5) " 等 ${length()} 项" else ""
    else -> toString()
}.oneLine(max)

/** JSON 对象 → 键值行；[labels] 把字段名换成中文叫法，没给的保留原名。 */
internal fun JSONObject.uiFields(labels: Map<String, String> = emptyMap()): ToolUiBlock.Fields =
    ToolUiBlock.Fields(
        keys().asSequence().map { key -> ToolUiBlock.Field(labels[key] ?: key, opt(key).uiText()) }.toList(),
    )

/** JSON 数组（对象）→ 列表项：[titleKeys] 里第一个有值的字段做标题，其余字段压成副标题。 */
internal fun JSONArray?.uiItems(vararg titleKeys: String): ToolUiBlock.Items {
    if (this == null) return ToolUiBlock.Items(emptyList())
    val items = (0 until length()).mapNotNull { index ->
        when (val value = opt(index)) {
            is JSONObject -> {
                val titleKey = titleKeys.firstOrNull { value.optString(it).isNotBlank() }
                val title = titleKey?.let { value.optString(it) } ?: value.uiText(80)
                val rest = JSONObject(value.toString()).apply { titleKey?.let { remove(it) } }
                ToolUiBlock.Item(title, rest.uiText(120).takeIf { titleKey != null && it.isNotBlank() && it != "—" })
            }
            null, JSONObject.NULL -> null
            else -> ToolUiBlock.Item(value.toString())
        }
    }
    return ToolUiBlock.Items(items)
}

/** 列表里的时刻：今天写「14:05」，今年写「10月6日 14:05」，更早写年份。 */
internal fun uiTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val zone = java.time.ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochMilli(millis).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val pattern = when {
        at.toLocalDate() == today -> "HH:mm"
        at.year == today.year -> "M月d日 HH:mm"
        else -> "yyyy年M月d日"
    }
    return at.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}

/** 时长：「3 小时 12 分」「45 分钟」「20 秒」。 */
internal fun uiDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60} 小时" + (minutes % 60).takeIf { it > 0 }?.let { " $it 分" }.orEmpty()
        minutes >= 1 -> "$minutes 分钟"
        else -> "${ms / 1000} 秒"
    }
}

/** 文件大小：「512 B」「2.1 KB」「3.4 MB」。 */
internal fun uiBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
}

/** 路径的文件名部分。 */
internal fun String.fileName(): String = trimEnd('/').substringAfterLast('/').ifBlank { this }

/** 终端结果正文（`exit_code: N` 头 + `--- stdout ---` / `--- stderr ---` 段）拆开给界面用。 */
internal data class TerminalBody(val exitCode: Int?, val stdout: String, val stderr: String) {
    companion object {
        fun parse(text: String?): TerminalBody? {
            if (text.isNullOrBlank()) return null
            val exit = text.lineSequence().take(10).firstOrNull { it.startsWith("exit_code:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
            val stdout = text.substringAfter("--- stdout ---\n", "").substringBefore("\n--- stderr ---")
            val stderr = text.substringAfter("--- stderr ---\n", "")
            return TerminalBody(exit, stdout.trimEnd(), stderr.trimEnd())
        }
    }

    fun outputBlocks(): List<ToolUiBlock> = listOfNotNull(
        stdout.takeIf { it.isNotBlank() }?.let { ToolUiBlock.Output(it, label = "输出") },
        stderr.takeIf { it.isNotBlank() }?.let { ToolUiBlock.Output(it, label = "错误输出") },
    )

    fun summary(): String {
        val lines = (if (exitCode == 0) stdout else stderr.ifBlank { stdout }).lines().count { it.isNotBlank() }
        return when (exitCode) {
            null -> if (lines > 0) "输出 $lines 行" else "完成"
            0 -> "退出码 0" + if (lines > 0) " · 输出 $lines 行" else " · 没有输出"
            else -> "失败 · 退出码 $exitCode"
        }
    }
}
