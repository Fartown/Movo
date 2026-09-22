package io.github.mangi.eta.agent.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.markdown.rememberCitationMarkdownAnnotator
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A result preview. Expanding, swiping up and continuing all open the original App conversation. */
@Composable
internal fun AgentResultCard(
    state: AgentOverlayState,
    canOpenConversation: Boolean,
    openingConversation: Boolean,
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    onOpenConversation: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val content = state.detailText.ifBlank { state.status.localizedText() }
    val bodyStyle = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, color = colors.onSurface)
    val openLabel = stringResource(R.string.overlay_result_open_conversation)
    val canOpen = canOpenConversation && !openingConversation

    Box(
        Modifier.fillMaxSize().navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Card(modifier = Modifier.fillMaxSize().draggable(
            state = rememberDraggableState(onDelta = onDrag),
            orientation = Orientation.Vertical,
            enabled = canOpen,
            onDragStopped = { onDragStopped(it) },
        ), insideMargin = PaddingValues(0.dp)) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth(),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(24.dp)
                            .clickable(enabled = canOpen, onClickLabel = openLabel, onClick = onOpenConversation),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.width(32.dp).height(4.dp).clip(CircleShape).background(colors.outline))
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(
                            if (state.phase == AgentOverlayPhase.FAILED) colors.error else colors.primary,
                        ))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(if (state.phase == AgentOverlayPhase.FAILED) R.string.overlay_substatus_failed else R.string.overlay_substatus_finished),
                            color = colors.onSurfaceVariantSummary, fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onOpenConversation, enabled = canOpen, minWidth = 48.dp, minHeight = 48.dp) {
                            Icon(Icons.Rounded.OpenInFull, openLabel,
                                Modifier.size(20.dp), tint = colors.onSurfaceVariantActions)
                        }
                        IconButton(onClick = onClose, minWidth = 48.dp, minHeight = 48.dp) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.action_close), Modifier.size(20.dp), tint = colors.onSurfaceVariantActions)
                        }
                    }
                }
                val typography = markdownTypography(
                    h1 = bodyStyle.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
                    h2 = bodyStyle.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
                    h3 = bodyStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    text = bodyStyle, paragraph = bodyStyle, ordered = bodyStyle, bullet = bodyStyle, list = bodyStyle,
                    code = bodyStyle.copy(fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace),
                    textLink = TextLinkStyles(style = SpanStyle(
                        color = colors.primary,
                        textDecoration = TextDecoration.Underline,
                    )),
                )
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    Markdown(
                        annotator = rememberCitationMarkdownAnnotator(),
                        markdownState = rememberMarkdownState(content = content, retainState = true),
                        typography = typography,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        loading = { Text(content, style = bodyStyle, modifier = it) },
                        error = { Text(content, style = bodyStyle, modifier = it) },
                    )
                }
                if (canOpenConversation) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(colors.surfaceContainer)
                            .clickable(enabled = canOpen, onClick = onOpenConversation)
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(if (openingConversation) R.string.overlay_result_opening_conversation
                                else R.string.overlay_result_continue_conversation),
                            modifier = Modifier.weight(1f), style = bodyStyle,
                        )
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(20.dp), tint = colors.primary)
                    }
                }
            }
        }
    }
}
