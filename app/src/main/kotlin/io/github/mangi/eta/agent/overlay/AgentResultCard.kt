package io.github.mangi.eta.agent.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

/** Resizable result surface. Reading scrolls independently; only its header resizes the window. */
@Composable
internal fun AgentResultCard(
    state: AgentOverlayState,
    expanded: Boolean,
    canContinue: Boolean,
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onContinue: (String) -> Boolean,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val colors = MiuixTheme.colorScheme
    val content = state.detailText.ifBlank { state.status.localizedText() }
    val bodyStyle = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, color = colors.onSurface)
    val toggleLabel = stringResource(if (expanded) R.string.overlay_result_collapse else R.string.overlay_result_expand)
    val send: () -> Unit = {
        val text = draft.trim()
        if (canContinue && text.isNotEmpty() && !submitting) {
            submitting = true
            keyboard?.hide()
            focusManager.clearFocus()
            if (onContinue(text)) draft = "" else submitting = false
        }
    }

    Box(
        Modifier.fillMaxSize().imePadding().navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Card(modifier = Modifier.fillMaxSize(), insideMargin = PaddingValues(0.dp)) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().draggable(
                        state = rememberDraggableState(onDelta = onDrag),
                        orientation = Orientation.Vertical,
                        onDragStopped = { onDragStopped(it) },
                    ),
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(24.dp)
                            .clickable(onClickLabel = toggleLabel) { onExpandedChange(!expanded) },
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
                        IconButton(onClick = { onExpandedChange(!expanded) }, minWidth = 48.dp, minHeight = 48.dp) {
                            Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, toggleLabel,
                                Modifier.size(20.dp), tint = colors.onSurfaceVariantActions)
                        }
                        IconButton(onClick = { keyboard?.hide(); onClose() }, minWidth = 48.dp, minHeight = 48.dp) {
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
                Markdown(
                    annotator = rememberCitationMarkdownAnnotator(),
                    markdownState = rememberMarkdownState(content = content, retainState = true),
                    typography = typography,
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    loading = { Text(content, style = bodyStyle, modifier = it) },
                    error = { Text(content, style = bodyStyle, modifier = it) },
                )
                if (canContinue) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(colors.surfaceContainer).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicTextField(
                            value = draft, onValueChange = { draft = it },
                            modifier = Modifier.weight(1f).heightIn(min = 40.dp, max = 112.dp)
                                .onFocusChanged { if (it.isFocused) onExpandedChange(true) },
                            enabled = !submitting,
                            textStyle = bodyStyle.copy(lineHeight = 22.sp),
                            cursorBrush = SolidColor(colors.primary),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            decorationBox = { field ->
                                Box(Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.CenterStart) {
                                    if (draft.isEmpty()) Text(stringResource(R.string.overlay_follow_up_hint),
                                        style = bodyStyle.copy(color = colors.onSurfaceVariantSummary, lineHeight = 22.sp))
                                    field()
                                }
                            },
                        )
                        IconButton(onClick = send, enabled = draft.isNotBlank() && !submitting, minWidth = 48.dp, minHeight = 48.dp) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(if (draft.isNotBlank()) colors.primary else colors.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.overlay_send), Modifier.size(20.dp),
                                    tint = if (draft.isNotBlank()) colors.onPrimary else colors.onSurfaceVariantActions)
                            }
                        }
                    }
                }
            }
        }
    }
}
