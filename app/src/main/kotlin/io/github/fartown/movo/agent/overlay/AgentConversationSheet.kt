package io.github.fartown.movo.agent.overlay

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
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
    expandProgress: () -> Float = { 0f },
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val drag = remember { AgentResultSheetScreenDrag() }
    val velocity = remember { VelocityTracker() }
    val chromeWidth = remember { intArrayOf(0) }
    val density = LocalDensity.current
    val handleHeight = with(density) { 16.dp.toPx() }
    val buttonsWidth = with(density) { 92.dp.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    // `Overlay/Sheet`（规范 8.9）：bg/canvas，顶部圆角 28、底部 0；把手区 16（32 × 4）；头部 44，
    // 标题 Body/Strong 一行省略（最大宽 300），右侧「展开到 App」「关闭」图标 24、热区 44。
    // Q4 浮层 → App：推满全屏时顶部圆角 28 → 0、把手淡出（规范 9.5）。
    val topRadius = 28.dp * (1f - expandProgress().coerceIn(0f, 1f))
    Scaffold(
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = topRadius, topEnd = topRadius)),
        containerColor = io.github.fartown.movo.ui.theme.MovoColors.bgCanvas,
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
                Box(
                    Modifier.fillMaxWidth().height(16.dp)
                        .graphicsLayer { alpha = 1f - expandProgress().coerceIn(0f, 1f) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(32.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(io.github.fartown.movo.ui.theme.MovoColors.borderStrong))
                }
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(start = 20.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = io.github.fartown.movo.ui.theme.MovoTypography.bodyStrong,
                        color = io.github.fartown.movo.ui.theme.MovoColors.textPrimary,
                        modifier = Modifier.weight(1f).widthIn(max = 300.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    io.github.fartown.movo.ui.components.movo.MovoIconButton(
                        icon = io.github.fartown.movo.ui.theme.MovoIcons.Maximize2,
                        contentDescription = stringResource(R.string.overlay_result_expand),
                        onClick = onOpenConversation,
                    )
                    io.github.fartown.movo.ui.components.movo.MovoIconButton(
                        icon = io.github.fartown.movo.ui.theme.MovoIcons.X,
                        contentDescription = stringResource(R.string.action_close),
                        onClick = onClose,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}
