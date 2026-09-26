package io.github.fartown.movo.ui.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkEmphasisTest {
    private fun hasStrong(markdown: String): Boolean {
        fun ASTNode.any(): Boolean = type == MarkdownElementTypes.STRONG || children.any { it.any() }
        return MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown).any()
    }

    @Test
    fun boldAroundCjkPunctuationParsesAsStrong() {
        val source = "点击**「桌面」**进入"
        assertFalse("原文按 CommonMark 不是加粗", hasStrong(source))
        assertTrue(hasStrong(CjkEmphasis.normalize(source)))
    }

    @Test
    fun alreadyValidCjkBoldStaysBold() {
        assertTrue(hasStrong(CjkEmphasis.normalize("第一个结果是**自动调整亮度**。")))
        assertTrue(hasStrong(CjkEmphasis.normalize("往下依次还有**「显示与亮度」和「屏幕亮度」**两条")))
    }

    @Test
    fun latinTextIsUntouched() {
        for (source in listOf("plain **bold** text", "a**\"b\"**c", "没有星号", "**bold**.")) {
            assertEquals(source, CjkEmphasis.normalize(source))
        }
    }

    @Test
    fun codeIsUntouched() {
        val inline = "用 `a**「b」**c` 表示"
        assertEquals(inline, CjkEmphasis.normalize(inline))
        val fenced = "```\n点击**「桌面」**进入\n```"
        assertEquals(fenced, CjkEmphasis.normalize(fenced))
    }
}
