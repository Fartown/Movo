package io.github.fartown.movo.ui.components

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import io.github.fartown.movo.ui.markdown.citationMarkdownAnnotator
import io.github.fartown.movo.ui.markdown.normalizeBrowserLink
import io.github.fartown.movo.agent.model.CitationAnnotation
import io.github.fartown.movo.agent.model.ResponsesCitationFormatter
import io.github.fartown.movo.ui.markdown.StreamingGfmParserSession
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.assertEquals
import org.junit.Test

class CitationLinkRenderingTest {
    @Test
    fun fallbackSourcesRetainClickableLinkAnnotations() {
        val url = "https://example.com/article"
        val text = ResponsesCitationFormatter.apply("回答", listOf(CitationAnnotation(null, null, url, "米家集成指南")))
        assertLink(text, url)
    }

    @Test
    fun previouslySavedAngleCitationsRemainClickable() {
        assertLink("来源：\n- [1] [旧来源](<https://example.com/old>)", "https://example.com/old")
        assertLink("中文回答 [[1]](<https://example.com/old>)", "https://example.com/old")
    }

    @Test
    fun titleBracketsAndUrlParenthesesCannotBreakCitations() {
        val text = ResponsesCitationFormatter.apply("回答", listOf(CitationAnnotation(null, null, "https://example.com/a(b)?q=x%20y", "指南 [新版]")))
        assertLink(text, "https://example.com/a%28b%29?q=x%20y")
    }

    @Test
    fun inlineCitationsRemainClickable() {
        val text = ResponsesCitationFormatter.apply("中文回答", listOf(CitationAnnotation(0, 2, "https://example.com/inline", "来源")))
        assertLink(text, "https://example.com/inline")
    }

    @Test
    fun browserLinksAcceptWebUrlsAndLegacyDelimiters() {
        assertEquals("https://example.com/a", normalizeBrowserLink("<https://example.com/a>"))
        assertEquals(null, normalizeBrowserLink("javascript:alert(1)"))
        assertEquals(null, normalizeBrowserLink("https:///missing-host"))
    }

    private fun assertLink(text: String, url: String) {
        val parsed = StreamingGfmParserSession().parse(text, isComplete = true)
        val paragraphs = mutableListOf<ASTNode>()
        fun visit(node: ASTNode) {
            if (node.type == MarkdownElementTypes.PARAGRAPH) paragraphs += node
            node.children.forEach(::visit)
        }
        visit(parsed.state.node)
        val settings = DefaultAnnotatorSettings(TextLinkStyles(style = SpanStyle()), SpanStyle(), citationMarkdownAnnotator(TextLinkStyles(style = SpanStyle())), parsed.state.referenceLinkHandler)
        val links = paragraphs.flatMap { node ->
            val annotated = buildAnnotatedString {
                buildMarkdownAnnotatedString(text, node, settings)
            }
            annotated.getLinkAnnotations(0, annotated.length)
        }
        assertEquals("Source titles must remain clickable after the production Markdown parser", 1, links.size)
        assertEquals(url, (links.single().item as androidx.compose.ui.text.LinkAnnotation.Url).url)
    }
}
