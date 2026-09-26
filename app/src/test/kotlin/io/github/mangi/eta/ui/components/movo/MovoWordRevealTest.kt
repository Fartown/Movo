package io.github.mangi.eta.ui.components.movo

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovoWordRevealTest {
    @Test
    fun englishSplitsOnWordsAndSkipsWhitespace() {
        val text = "Good evening, Movo"
        val words = revealWordRanges(text, Locale.ENGLISH).map { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("Good", "evening", ",", "Movo"), words)
    }

    @Test
    fun chineseRangesCoverEveryVisibleCharacterInOrder() {
        val text = "晚上好，有什么可以帮你？"
        val ranges = revealWordRanges(text, Locale.SIMPLIFIED_CHINESE)
        assertTrue(ranges.isNotEmpty())
        assertEquals(0, ranges.first().first)
        assertEquals(text.length - 1, ranges.last().last)
        ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun intervalIs24msButTheWholeSentenceStaysWithin400ms() {
        // 规范 9.3.2 Q2：每词间隔 24ms，整句 ≤ 400ms（最后一个词开始后还要 160ms 显现完）。
        assertEquals(0f, revealWordInterval(1), 0f)
        assertEquals(24f, revealWordInterval(6), 0f)
        val many = 40
        val interval = revealWordInterval(many)
        assertTrue((many - 1) * interval + 160 <= 400.001f)
    }
}
