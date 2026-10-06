package io.github.fartown.movo.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditPlannerTest {
    @Test
    fun `refuses to reconstruct password or unreadable populated input`() {
        assertFalse(TextEditPlanner.canSafelyReconstruct(true, true, 3, 1, 1))
        assertFalse(TextEditPlanner.canSafelyReconstruct(false, false, 3, 3, 3))
        assertTrue(TextEditPlanner.canSafelyReconstruct(false, true, 3, 3, 3))
        assertTrue(TextEditPlanner.canSafelyReconstruct(false, true, 0, -1, -1))
    }

    @Test
    fun `inserts at cursor instead of always appending`() {
        assertEquals(
            TextEditPlanner.Plan("AXBC", 2),
            TextEditPlanner.insertAtSelection("ABC", "X", 1, 1),
        )
    }

    @Test
    fun `replaces selected range regardless of selection direction`() {
        assertEquals(
            TextEditPlanner.Plan("AXC", 2),
            TextEditPlanner.insertAtSelection("ABC", "X", 2, 1),
        )
    }

    @Test
    fun `uses UTF 16 offsets exposed by accessibility nodes`() {
        assertEquals(
            TextEditPlanner.Plan("😀X好", 3),
            TextEditPlanner.insertAtSelection("😀好", "X", 2, 2),
        )
    }

    @Test
    fun `invalid selection on existing text is rejected instead of appending`() {
        assertNull(TextEditPlanner.insertAtSelection("ABC", "X", -1, -1))
    }

    @Test
    fun `partially invalid selection is rejected`() {
        assertNull(TextEditPlanner.insertAtSelection("ABC", "X", -1, 0))
    }

    @Test
    fun `empty text has one safe insertion point before cursor exists`() {
        assertEquals(
            TextEditPlanner.Plan("X", 1),
            TextEditPlanner.insertAtSelection("", "X", -1, -1),
        )
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
