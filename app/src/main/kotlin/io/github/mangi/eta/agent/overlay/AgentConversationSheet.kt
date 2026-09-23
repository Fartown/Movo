package io.github.mangi.eta.agent.overlay

import androidx.compose.foundation.background
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Only the window chrome lives here. The body is the original App conversation. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun AgentConversationSheet(
    title: String,
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    onOpenConversation: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val drag = remember { AgentResultSheetScreenDrag() }
    val velocity = remember { VelocityTracker() }
    val chromeWidth = remember { intArrayOf(0) }
    val density = LocalDensity.current
    val handleHeight = with(density) { 20.dp.toPx() }
    val buttonsWidth = with(density) { 100.dp.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    Scaffold(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The handle alone owns window dragging; the message list keeps its scrolling gestures.
            Column(
                Modifier.fillMaxWidth().onSizeChanged { chromeWidth[0] = it.width }
                    .pointerInteropFilter { event ->
                        // Window resizing moves this node's local origin under the finger.
                        // Use raw screen coordinates for both distance and release velocity.
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                val onButton = event.y >= handleHeight &&
                                    event.x >= chromeWidth[0] - buttonsWidth
                                if (onButton) false else {
                                    drag.start(event.rawY)
                                    velocity.resetTracking()
                                    velocity.addPosition(event.eventTime, Offset(event.rawX, event.rawY))
                                    true
                                }
                            }
                            MotionEvent.ACTION_MOVE -> {
                                velocity.addPosition(event.eventTime, Offset(event.rawX, event.rawY))
                                drag.move(event.rawY, touchSlop)?.let(onDrag)
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                velocity.addPosition(event.eventTime, Offset(event.rawX, event.rawY))
                                if (drag.finish()) onDragStopped(
                                    if (event.actionMasked == MotionEvent.ACTION_UP)
                                        velocity.calculateVelocity().y else 0f,
                                )
                                else if (event.actionMasked == MotionEvent.ACTION_UP) onOpenConversation()
                                true
                            }
                            MotionEvent.ACTION_POINTER_UP -> {
                                // Do not jump to another finger when the primary finger leaves.
                                if (event.actionIndex == 0 && drag.finish()) onDragStopped(0f)
                                true
                            }
                            else -> true
                        }
                    },
            ) {
                Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(32.dp).height(4.dp).clip(CircleShape).background(colors.outline))
                }
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = colors.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = onOpenConversation, minWidth = 48.dp, minHeight = 48.dp) {
                        Icon(
                            Icons.Rounded.OpenInFull,
                            stringResource(R.string.overlay_result_expand),
                            Modifier.size(20.dp), tint = colors.onSurfaceVariantActions,
                        )
                    }
                    IconButton(onClick = onClose, minWidth = 48.dp, minHeight = 48.dp) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_close), Modifier.size(20.dp), tint = colors.onSurfaceVariantActions)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}
