package io.github.fartown.movo.agent.browser

import java.util.Locale

/**
 * 离屏浏览器的协议白名单守卫（安全 B）。
 *
 * 模型主动导航在工具层已校验为 http/https；页面内跳转（含点击、脚本 location 改写）要靠
 * WebViewClient.shouldOverrideUrlLoading 拦住本地 / 危险协议——离屏浏览器不应被诱导打开
 * file: / content: / intent: / javascript:。纯字符串判断，便于单测。
 */
internal object BrowserSchemeGuard {

    /** 被拒绝的协议（小写，不含冒号）。 */
    val BLOCKED_SCHEMES: Set<String> = setOf("file", "content", "intent", "javascript")

    /** url 的协议是否应被拦截。无法解析 scheme 的（相对、空）一律放行。 */
    fun isBlocked(url: String?): Boolean {
        val scheme = schemeOf(url) ?: return false
        return scheme in BLOCKED_SCHEMES
    }

    private fun schemeOf(url: String?): String? {
        val raw = url?.trim().orEmpty()
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        val scheme = raw.substring(0, colon)
        // scheme 语法：ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
        if (!scheme.first().isLetter()) return null
        return scheme.lowercase(Locale.ROOT)
    }
}
