package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun rememberCitationMarkdownAnnotator(): MarkdownAnnotator {
    val uriHandler = LocalUriHandler.current
    val linkColor = MiuixTheme.colorScheme.primary
    return remember(uriHandler, linkColor) {
        citationMarkdownAnnotator(
            TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            LinkInteractionListener { link ->
                (link as? LinkAnnotation.Url)?.url?.let(uriHandler::openUri)
            },
        )
    }
}

/** The GFM parser represents legacy [title](<url>) destinations as AUTOLINK nodes.
 * The renderer only handles LINK_DESTINATION, so restore the missing link annotation.
 * Code spans and fences are left to the standard renderer.
 */
internal fun citationMarkdownAnnotator(
    linkStyles: TextLinkStyles,
    listener: LinkInteractionListener? = null,
): MarkdownAnnotator {
    val inlineSettings = DefaultAnnotatorSettings(linkStyles, SpanStyle(), markdownAnnotator(), linkInteractionListener = listener)
    return markdownAnnotator { content, child ->
        if (child.type != MarkdownElementTypes.INLINE_LINK ||
            child.findChildOfType(MarkdownElementTypes.LINK_DESTINATION) != null
        ) return@markdownAnnotator false
        val destination = child.findChildOfType(MarkdownElementTypes.AUTOLINK)
            ?.getTextInNode(content)?.toString()?.let(::normalizeBrowserLink)
            ?: return@markdownAnnotator false
        val label = child.findChildOfType(MarkdownElementTypes.LINK_TEXT)
            ?: return@markdownAnnotator false
        withLink(LinkAnnotation.Url(destination, linkStyles, listener)) {
            buildMarkdownAnnotatedString(content, label.children.drop(1).dropLast(1), inlineSettings)
        }
        true
    }
}
