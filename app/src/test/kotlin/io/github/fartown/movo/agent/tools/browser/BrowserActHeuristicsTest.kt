package io.github.fartown.movo.agent.tools.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** browser 域纯逻辑：坐标换算、提交点判定、跳转判定、续读游标。 */
class BrowserActHeuristicsTest {

    // ---- 坐标换算 ----

    @Test
    fun `scale is image width over css viewport width`() {
        // 截图宽 720，CSS 视口宽 360 → dpr 2.0。
        assertEquals(2.0, BrowserCoordinates.scale(720, 360.0), 1e-9)
    }

    @Test
    fun `scale falls back to one on bad input`() {
        assertEquals(1.0, BrowserCoordinates.scale(0, 360.0), 1e-9)
        assertEquals(1.0, BrowserCoordinates.scale(720, 0.0), 1e-9)
    }

    @Test
    fun `screenshot pixel converts back to css pixel`() {
        // 截图里的 500px，scale=2.0 → CSS 250px。
        assertEquals(250, BrowserCoordinates.toCssPixel(500, 2.0))
        // 1:1 原样。
        assertEquals(500, BrowserCoordinates.toCssPixel(500, 1.0))
        // 非法 scale 不换算。
        assertEquals(500, BrowserCoordinates.toCssPixel(500, 0.0))
    }

    // ---- 提交点判定 ----

    private fun signals(
        tag: String = "button",
        type: String = "",
        role: String = "",
        formPresent: Boolean = false,
        formMethod: String = "",
        formRole: String = "",
        inSearch: Boolean = false,
        isPassword: Boolean = false,
        isSubmitControl: Boolean = false,
        buttonText: String = "",
    ) = BrowserTargetSignals(
        tag, type, role, formPresent, formMethod, formRole, inSearch, isPassword, isSubmitControl, buttonText,
    )

    @Test
    fun `password field is a submit point and not search`() {
        val c = BrowserSubmitHeuristics.classify(signals(tag = "input", type = "password", formPresent = true, formMethod = "get"))
        assertTrue(c.submitPoint)
        assertFalse("密码即便在 GET 表单也不当搜索", c.searchRole)
    }

    @Test
    fun `submit control in post form is confirmed submit point`() {
        val c = BrowserSubmitHeuristics.classify(signals(type = "submit", formPresent = true, formMethod = "post", isSubmitControl = true, buttonText = "登录"))
        assertTrue(c.submitPoint)
        assertFalse(c.searchRole)
    }

    @Test
    fun `button text keyword triggers submit point`() {
        assertTrue(BrowserSubmitHeuristics.classify(signals(buttonText = "立即支付")).submitPoint)
        assertTrue(BrowserSubmitHeuristics.classify(signals(tag = "a", role = "button", buttonText = "Checkout")).submitPoint)
    }

    @Test
    fun `plain button without keyword is not a submit point`() {
        assertFalse(BrowserSubmitHeuristics.classify(signals(buttonText = "展开更多")).submitPoint)
    }

    @Test
    fun `get form submit control is treated as search`() {
        val c = BrowserSubmitHeuristics.classify(signals(type = "submit", formPresent = true, formMethod = "get", isSubmitControl = true, buttonText = "搜索"))
        assertTrue("GET 表单仍是提交控件", c.submitPoint)
        assertTrue("但 GET 表单视作搜索，不确认", c.searchRole)
    }

    @Test
    fun `role search is not confirmed`() {
        val c = BrowserSubmitHeuristics.classify(signals(role = "search", isSubmitControl = true))
        assertTrue(c.searchRole)
    }

    @Test
    fun `input type search is not confirmed`() {
        val c = BrowserSubmitHeuristics.classify(signals(tag = "input", type = "search", inSearch = true))
        assertTrue(c.searchRole)
    }

    // ---- 跳转判定 ----

    @Test
    fun `url change counts as navigation`() {
        val r = BrowserNavigation.detect("https://a/", "A", "https://b/", "B")
        assertTrue(r.navigated)
        assertEquals("https://b/", r.url)
        assertEquals("B", r.title)
    }

    @Test
    fun `no change is not navigation and title is null`() {
        val r = BrowserNavigation.detect("https://a/", "A", "https://a/", "A")
        assertFalse(r.navigated)
        assertEquals("https://a/", r.url)
        assertEquals(null, r.title)
    }

    @Test
    fun `title change alone counts as navigation`() {
        val r = BrowserNavigation.detect("https://a/", "A", "https://a/", "A2")
        assertTrue(r.navigated)
        assertEquals("A2", r.title)
    }

    @Test
    fun `blank after url keeps before url`() {
        val r = BrowserNavigation.detect("https://a/", "A", "", "")
        assertFalse(r.navigated)
        assertEquals("https://a/", r.url)
    }

    // ---- 续读游标 ----

    @Test
    fun `cursor round trips offset within same generation`() {
        val encoded = BrowserReadCursor.encode(generation = 7L, offset = 1200)
        assertEquals(CursorResult.Offset(1200), BrowserReadCursor.decode(encoded, currentGeneration = 7L))
    }

    @Test
    fun `cursor from a different generation is stale`() {
        val encoded = BrowserReadCursor.encode(generation = 7L, offset = 1200)
        assertEquals(CursorResult.Stale, BrowserReadCursor.decode(encoded, currentGeneration = 8L))
    }

    @Test
    fun `null cursor starts at zero`() {
        assertEquals(CursorResult.Offset(0), BrowserReadCursor.decode(null, currentGeneration = 3L))
    }

    @Test
    fun `legacy plain integer cursor is accepted as offset`() {
        assertEquals(CursorResult.Offset(500), BrowserReadCursor.decode("500", currentGeneration = 3L))
    }
}
