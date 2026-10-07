package io.github.fartown.movo.agent.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDomScriptsTest {
    @Test
    fun `readable extraction is bounded and preserves absolute urls`() {
        val script = BrowserDomScripts.wrap(BrowserDomScripts.readable(offset = 0, maxChars = 8_000))

        assertTrue(script.contains("!visible(node)"))
        assertTrue(script.contains("remainingNodes: 8000"))
        assertTrue(script.contains("deadline: Date.now() + 750"))
        assertTrue(script.contains("return boundedString(parsed.href"))
        assertFalse(script.contains("parsed.protocol !== 'https:'"))
        assertFalse(script.contains("innerText"))
        assertFalse(script.contains("textContent"))
    }

    @Test
    fun `target resolution prefers the visible match like inspect, without hit target guards`() {
        val script = BrowserDomScripts.wrap(
            BrowserDomScripts.click(selector = "#submit", x = null, y = null)
        )

        // 执行和动作前的检查取同一个元素：第一个可见的，都不可见再取第一个（不拒绝看不见的）。
        val resolve = script.substringAfter("function resolveTarget(").substringBefore("function markdownEscape(")
        assertTrue(resolve.contains("if (visible(matches[index])) { target = matches[index]; break; }"))
        assertTrue(resolve.contains("if (!target && matches.length) target = matches[0];"))
        val inspect = BrowserDomScripts.inspectTarget("#submit", null, null)
        assertTrue(inspect.contains("if (visible(matches[i])) { target = matches[i]; break; }"))
        assertTrue(inspect.contains("if (!target && matches.length) target = matches[0];"))
        assertFalse(script.contains("requireHitTarget"))
        assertFalse(script.contains("TARGET_OCCLUDED"))
    }

    @Test
    fun `find elements reports how many matched`() {
        val script = BrowserDomScripts.findElements(null)
        assertTrue(script.contains("match_count: matches.length"))
        assertTrue(script.contains("truncated: scanned < matches.length"))
    }

    @Test
    fun `page info exposes device pixel ratio for coordinate scaling`() {
        val script = BrowserDomScripts.wrap(BrowserDomScripts.pageInfo())
        assertTrue(script.contains("device_pixel_ratio: window.devicePixelRatio"))
        assertTrue(script.contains("viewport_width: window.innerWidth"))
    }

    @Test
    fun `inspect target probes editability and submit signals read-only`() {
        val script = BrowserDomScripts.wrap(
            BrowserDomScripts.inspectTarget(selector = "#pay", x = null, y = null)
        )
        // 精确可编辑复用 editable()；提交点以原始信号返回，不在脚本里改页面。
        assertTrue(script.contains("editable: editable(target)"))
        assertTrue(script.contains("is_password:"))
        assertTrue(script.contains("is_submit_control:"))
        assertTrue(script.contains("form_method:"))
        assertTrue(script.contains("in_search:"))
        assertFalse("探测是只读的，不点击", script.contains("target.click()"))
    }

    @Test
    fun `inspect target by coordinate uses elementFromPoint`() {
        val script = BrowserDomScripts.wrap(
            BrowserDomScripts.inspectTarget(selector = null, x = 120, y = 240)
        )
        assertTrue(script.contains("document.elementFromPoint(px, py)"))
        assertTrue(script.contains("var px = 120;"))
        assertTrue(script.contains("var py = 240;"))
    }
}
