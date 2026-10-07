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
    fun `fields inside a web page are never pasted into`() {
        // 真机：网页粘贴出来的是更早的剪贴板内容。
        assertFalse(TextEditPlanner.canPaste(inWebView = true, supportsPaste = true))
        assertTrue(TextEditPlanner.canPaste(inWebView = false, supportsPaste = true))
        assertFalse(TextEditPlanner.canPaste(inWebView = false, supportsPaste = false))
    }

    @Test
    fun `line breaks lost by a multi line field are detected`() {
        assertTrue(TextEditPlanner.lostLineBreaks("第一行\n\n第二行😋", "第一行  第二行😋", multiLine = true))
        assertTrue(TextEditPlanner.lostLineBreaks("第一行\n第二行", "第一行第二行", multiLine = true))
        // 单行框本来就不收换行。
        assertFalse(TextEditPlanner.lostLineBreaks("第一行\n第二行", "第一行 第二行", multiLine = false))
        // 其他差异（限长、自动格式化）不是换行问题。
        assertFalse(TextEditPlanner.lostLineBreaks("第一行\n第二行", "第一行", multiLine = true))
        assertFalse(TextEditPlanner.lostLineBreaks("13800138000", "138 0013 8000", multiLine = true))
        assertFalse(TextEditPlanner.lostLineBreaks("第一行\n第二行", "第一行\n第二行", multiLine = true))
        assertFalse(TextEditPlanner.lostLineBreaks("第一行\n第二行", null, multiLine = true))
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
