package io.github.fartown.movo.agent.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 页面内跳转的协议守卫：拦 file/content/intent/javascript，放行 http(s) 与相对地址。 */
class BrowserSchemeGuardTest {

    @Test
    fun `blocks local and dangerous schemes case insensitively`() {
        assertTrue(BrowserSchemeGuard.isBlocked("file:///etc/passwd"))
        assertTrue(BrowserSchemeGuard.isBlocked("content://media/external/images"))
        assertTrue(BrowserSchemeGuard.isBlocked("intent://scan/#Intent;scheme=zxing;end"))
        assertTrue(BrowserSchemeGuard.isBlocked("javascript:alert(1)"))
        assertTrue(BrowserSchemeGuard.isBlocked("JavaScript:void(0)"))
        assertTrue(BrowserSchemeGuard.isBlocked("  FILE:///x  "))
    }

    @Test
    fun `allows http https and other schemes`() {
        assertFalse(BrowserSchemeGuard.isBlocked("https://example.com/"))
        assertFalse(BrowserSchemeGuard.isBlocked("http://example.com/"))
        assertFalse(BrowserSchemeGuard.isBlocked("about:blank"))
        assertFalse(BrowserSchemeGuard.isBlocked("data:text/html,hi"))
    }

    @Test
    fun `allows relative and malformed urls`() {
        assertFalse(BrowserSchemeGuard.isBlocked(null))
        assertFalse(BrowserSchemeGuard.isBlocked(""))
        assertFalse(BrowserSchemeGuard.isBlocked("/path/only"))
        assertFalse(BrowserSchemeGuard.isBlocked("example.com/no-scheme"))
        // 前导非字母的伪 scheme 不解析（不会误伤）。
        assertFalse(BrowserSchemeGuard.isBlocked("123:not-a-scheme"))
    }
}
