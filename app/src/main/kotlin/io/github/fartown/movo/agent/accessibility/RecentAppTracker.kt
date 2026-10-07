package io.github.fartown.movo.agent.accessibility

/**
 * 用户最近在用的其他应用（不算 Movo 自己、系统界面、输入法）。用户在 Movo 里说「当前网页」「这个页面」时，指的是
 * 打开 Movo 之前在看的那个应用：真机上从 Movo 发「在当前网页的框里输入…」，前台是 Movo，模型找不到网页，16 次里 10 次没做成。
 * 由无障碍服务按窗口切换记录，每次任务开始时写进环境信息。
 */
internal object RecentAppTracker {
    data class Entry(val packageName: String, val atElapsedMillis: Long)

    @Volatile
    var last: Entry? = null
        private set

    /** 一个应用的窗口到了前台。[ignored] 判断要跳过的包（Movo 自己、系统界面、输入法）。 */
    fun record(packageName: String?, atElapsedMillis: Long, ignored: (String) -> Boolean) {
        if (packageName.isNullOrBlank() || ignored(packageName)) return
        last = Entry(packageName, atElapsedMillis)
    }

    /**
     * 写进环境信息的一行；[maxAgeMillis] 之前的不算「最近」。[label] 把包名换成应用名，取不到就只写包名。
     * 「用户最近在用的其他应用：小米浏览器（com.android.browser，3 分钟前在前台）」。
     */
    fun environmentLine(nowElapsedMillis: Long, maxAgeMillis: Long, label: (String) -> String?): String {
        val entry = last ?: return ""
        val age = (nowElapsedMillis - entry.atElapsedMillis).coerceAtLeast(0)
        if (age > maxAgeMillis) return ""
        val minutes = age / 60_000
        val ago = if (minutes < 1) "不到 1 分钟前" else "$minutes 分钟前"
        val name = label(entry.packageName)?.takeIf { it.isNotBlank() && it != entry.packageName }
        val app = if (name != null) "$name（${entry.packageName}，${ago}在前台）" else "${entry.packageName}（${ago}在前台）"
        return "用户最近在用的其他应用：$app"
    }

    internal fun resetForTest() {
        last = null
    }
}
