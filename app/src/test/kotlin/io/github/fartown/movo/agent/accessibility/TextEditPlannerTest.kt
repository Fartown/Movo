package io.github.fartown.movo.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditPlannerTest {
    @Test
    fun `append goes after the existing text and replace writes the text as is`() {
        assertEquals("味道不错。下次还来", TextEditPlanner.target("味道不错。", "下次还来", append = true))
        assertEquals("下次还来", TextEditPlanner.target("", "下次还来", append = true))
        assertEquals("新的", TextEditPlanner.target("旧的", "新的", append = false))
        assertEquals("", TextEditPlanner.target("旧的", "", append = false))
    }

    @Test
    fun `append is refused when the existing text cannot be read`() {
        assertNull(TextEditPlanner.target(null, "下次还来", append = true))
        assertEquals("全部", TextEditPlanner.target(null, "全部", append = false))
    }

    @Test
    fun `edge whitespace in the readback does not count`() {
        // 真机：小米笔记写「AAA」读回「\nAAA」。
        assertTrue(TextEditPlanner.sameText("AAA", "\nAAA"))
        assertTrue(TextEditPlanner.sameText("AAA", "AAA"))
        assertFalse(TextEditPlanner.sameText("第一行\n第二行", "\n第一行 第二行"))
        assertFalse(TextEditPlanner.sameText("AAA", null))
    }

    @Test
    fun `a rejected web field is not pasted into`() {
        // 真机：网页粘贴出来的可能是更早的剪贴板内容，被拒时又没法用直接写入改回来。
        assertFalse(TextEditPlanner.canPasteWhenRejected(inWebView = true, supportsPaste = true))
        assertTrue(TextEditPlanner.canPasteWhenRejected(inWebView = false, supportsPaste = true))
        assertFalse(TextEditPlanner.canPasteWhenRejected(inWebView = false, supportsPaste = false))
    }

    @Test
    fun `line breaks lost by an editor are detected`() {
        assertTrue(TextEditPlanner.lostLineBreaks("第一行\n\n第二行😋", "第一行  第二行😋", multiLine = true, inWebView = false))
        assertTrue(TextEditPlanner.lostLineBreaks("第一行\n第二行", "第一行第二行", multiLine = true, inWebView = false))
        // 小米笔记：网页编辑器把换行改成空格，读回前面还多一个换行；报不准多行。
        assertTrue(TextEditPlanner.lostLineBreaks("加水。\n唯一的", "\n加水。 唯一的", multiLine = false, inWebView = true))
        // 原生单行框本来就不收换行。
        assertFalse(TextEditPlanner.lostLineBreaks("第一行\n第二行", "第一行 第二行", multiLine = false, inWebView = false))
        // 其他差异（限长、自动格式化）不是换行问题。
        assertFalse(TextEditPlanner.lostLineBreaks("第一行\n第二行", "第一行", multiLine = true, inWebView = false))
        assertFalse(TextEditPlanner.lostLineBreaks("13800138000", "138 0013 8000", multiLine = true, inWebView = false))
        assertFalse(TextEditPlanner.lostLineBreaks("第一行\n第二行", "第一行\n第二行", multiLine = true, inWebView = false))
        assertFalse(TextEditPlanner.lostLineBreaks("第一行\n第二行", null, multiLine = true, inWebView = false))
    }

    @Test
    fun `empty field that reports no text is treated as empty`() {
        // 真机：设置搜索框为空时无障碍读到的文字是 null，追加「蓝牙」被拒绝。
        assertEquals("", TextEditPlanner.existingText(null, showingHint = false, selectionStart = 0, selectionEnd = 0))
        assertEquals("", TextEditPlanner.existingText(null, showingHint = false, selectionStart = -1, selectionEnd = -1))
    }

    @Test
    fun `hint text is not existing content`() {
        assertEquals("", TextEditPlanner.existingText("搜索系统设置项", showingHint = true, selectionStart = 0, selectionEnd = 0))
    }

    @Test
    fun `unreadable text with cursor after start is not guessed`() {
        assertNull(TextEditPlanner.existingText(null, showingHint = false, selectionStart = 3, selectionEnd = 3))
        assertEquals("ABC", TextEditPlanner.existingText("ABC", showingHint = false, selectionStart = 3, selectionEnd = 3))
    }
}
