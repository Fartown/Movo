package io.github.fartown.movo.agent.tools.device

import org.json.JSONArray

/**
 * `dumpsys window` / `dumpsys activity activities` / `dumpsys diskstats` 的纯文本解析器。
 *
 * 全部为无副作用纯函数，便于喂真实 dumpsys 样例做单测。各机型格式差异大，这里按常见格式尽力解析，
 * 解析不到即返回 null/空，不冒领。
 */
internal object DumpsysWindowParser {

    // mCurrentFocus=Window{hash u0 com.foo.bar/com.foo.bar.MainActivity}
    private val CURRENT_FOCUS = Regex("""mCurrentFocus=Window\{[^}]*\bu\d+\s+([a-zA-Z0-9._]+)/""")

    // mFocusedApp=...ActivityRecord{hash u0 com.foo.bar/.MainActivity t123}
    private val FOCUSED_APP = Regex("""mFocusedApp=\S*ActivityRecord\{[^}]*\bu\d+\s+([a-zA-Z0-9._]+)/""")

    // mResumedActivity: ActivityRecord{hash u0 com.foo.bar/.MainActivity t123}
    private val RESUMED_ACTIVITY = Regex("""mResumedActivity:\s*ActivityRecord\{[^}]*\bu\d+\s+([a-zA-Z0-9._]+)/""")

    // topResumedActivity=ActivityRecord{hash u0 com.foo.bar/.MainActivity}
    private val TOP_RESUMED = Regex("""topResumedActivity=\S*ActivityRecord\{[^}]*\bu\d+\s+([a-zA-Z0-9._]+)/""")

    // ResumedActivity: ActivityRecord{...}  （dumpsys activity activities 的 Display/Stack 块）
    private val RESUMED_GENERIC = Regex("""ResumedActivity:\s*\S*ActivityRecord\{[^}]*\bu\d+\s+([a-zA-Z0-9._]+)/""")

    /**
     * 从 `dumpsys window` 或 `dumpsys activity activities` 文本解析前台包名。
     * 优先级：当前焦点窗口 → 焦点 App → resumed activity。都没有返回 null。
     */
    fun parseForegroundPackage(text: String): String? {
        if (text.isBlank()) return null
        return CURRENT_FOCUS.find(text)?.groupValues?.get(1)
            ?: FOCUSED_APP.find(text)?.groupValues?.get(1)
            ?: RESUMED_ACTIVITY.find(text)?.groupValues?.get(1)
            ?: TOP_RESUMED.find(text)?.groupValues?.get(1)
            ?: RESUMED_GENERIC.find(text)?.groupValues?.get(1)
    }
}

/** `dumpsys diskstats` 解析出的单个应用存储占用。 */
internal data class ParsedAppStorage(
    val packageName: String,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

internal object DumpsysDiskstatsParser {

    /**
     * 解析 `dumpsys diskstats` 的四条并行数组行（Package Names / App Sizes / App Data Sizes / Cache Sizes），
     * 按下标对齐成 [ParsedAppStorage]。任一行缺失或非法返回 null（上层据此回退/报不可用）。
     */
    fun parse(text: String): List<ParsedAppStorage>? {
        val packages = jsonArrayLine(text, "Package Names:") ?: return null
        val appSizes = longArrayLine(text, "App Sizes:") ?: return null
        val dataSizes = longArrayLine(text, "App Data Sizes:") ?: return null
        val cacheSizes = longArrayLine(text, "Cache Sizes:") ?: return null
        return (0 until packages.length()).mapNotNull { index ->
            val pkg = packages.optString(index).takeIf(String::isNotBlank) ?: return@mapNotNull null
            ParsedAppStorage(
                packageName = pkg,
                appBytes = appSizes.getOrElse(index) { 0L },
                dataBytes = dataSizes.getOrElse(index) { 0L },
                cacheBytes = cacheSizes.getOrElse(index) { 0L },
            )
        }
    }

    private fun jsonArrayLine(source: String, prefix: String): JSONArray? =
        source.lineSequence().firstOrNull { it.trim().startsWith(prefix) }
            ?.substringAfter(prefix)?.trim()
            ?.let { runCatching { JSONArray(it) }.getOrNull() }

    private fun longArrayLine(source: String, prefix: String): List<Long>? {
        val array = jsonArrayLine(source, prefix) ?: return null
        return (0 until array.length()).map { array.optLong(it) }
    }
}
