package io.github.fartown.movo.ui.components.movo

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleMorphTest {
    private val row = Rect(64f, 300f, 160f, 322f)
    private val topBar = Rect(170f, 60f, 240f, 82f)

    @Test
    fun titleFliesToTheSubpageAndBackToTheSameRow() {
        // 规范 9.3.2 Q4：设置行标题 → 二级页顶栏；返回时飞回原来那一行。
        TitleMorph.launch("记忆", row)
        TitleMorph.bindRoute("memory")
        TitleMorph.reportTarget("记忆", topBar)
        val outbound = TitleMorph.flight!!
        assertEquals(topBar, outbound.target)
        assertTrue(TitleMorph.hides("记忆"))
        assertFalse(TitleMorph.hidesRow("记忆"))
        TitleMorph.finish(outbound)
        assertNull(TitleMorph.flight)

        TitleMorph.onPop("memory")
        val back = TitleMorph.flight!!
        assertTrue(back.returning)
        assertEquals(topBar, back.start)
        assertEquals(row, back.target)
        assertTrue(TitleMorph.hidesRow("记忆"))
        TitleMorph.finish(back)
    }

    @Test
    fun otherTitlesAndOtherRoutesAreIgnored() {
        TitleMorph.launch("语言", row)
        TitleMorph.bindRoute("language")
        TitleMorph.reportTarget("外观", topBar)
        assertNull(TitleMorph.flight!!.target)
        TitleMorph.finish(TitleMorph.flight!!)
        TitleMorph.onPop("appearance")
        assertNull(TitleMorph.flight)
    }
}
