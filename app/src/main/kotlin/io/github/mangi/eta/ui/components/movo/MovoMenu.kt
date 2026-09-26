package io.github.mangi.eta.ui.components.movo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoElevation
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIconData
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/** 弹出菜单的一项；[destructive] 为 Rose（删除类）。 */
internal data class MovoMenuItem(
    val icon: MovoIconData,
    val label: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * `Popover/Menu`（规范 8「弹出菜单」、9.3）：圆角 20、内边距 8、项高 44 圆角 12、E3；
 * 出现在锚点下方 4、左缘对齐（放不下时在上方）；从锚点缩放 0.96 → 1 并淡入 `fast` + `enter`，退场淡出 120ms。
 * 放在锚点所在的 Box 里使用，锚点即父布局。
 */
@Composable
internal fun MovoPopoverMenu(
    show: Boolean,
    onDismiss: () -> Unit,
    items: List<MovoMenuItem>,
    alignEnd: Boolean = false,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = show
    if (!visibleState.currentState && !visibleState.targetState) return
    val density = LocalDensity.current
    val gapPx = with(density) { MovoSpacing.xs.roundToPx() }
    val shadowPadPx = with(density) { MovoSpacing.xxl.roundToPx() }
    val positionProvider = remember(gapPx, shadowPadPx, alignEnd) { AnchorMenuPositionProvider(gapPx, shadowPadPx, alignEnd) }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(MovoMotion.fast(MovoMotion.EasingEnter)) +
                scaleIn(
                    MovoMotion.fast(MovoMotion.EasingEnter),
                    initialScale = 0.96f,
                    transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0f),
                ),
            exit = fadeOut(MovoMotion.fastExit()),
        ) {
            val shape = RoundedCornerShape(MovoRadius.lg)
            Column(
                modifier = Modifier
                    .padding(MovoSpacing.xxl)
                    .widthIn(min = MenuMinWidth)
                    .movoElevation(MovoElevation.Overlay, shape)
                    .clip(shape)
                    .background(MovoColors.bgSurface)
                    .padding(MovoSpacing.sm),
            ) {
                items.forEach { item ->
                    val itemShape = RoundedCornerShape(MovoRadius.sm)
                    val tint = if (item.destructive) MovoColors.roseFg else MovoColors.textPrimary
                    Row(
                        modifier = Modifier
                            .widthIn(min = MenuMinWidth - MovoSpacing.lg)
                            .height(MovoSize.touchTarget)
                            .clip(itemShape)
                            .movoClickable(PressKind.Row, shape = itemShape, enabled = item.enabled) {
                                onDismiss()
                                item.onClick()
                            }
                            .padding(horizontal = MovoSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MovoIcon(item.icon, null, size = MovoSize.iconMedium, tint = tint)
                        Spacer(Modifier.width(MovoSpacing.md))
                        Text(item.label, style = MovoTypography.bodyRegular, color = tint)
                    }
                }
            }
        }
    }
}

private val MenuMinWidth = 200.dp

/** 锚点下方 [gapPx]、左缘（或右缘）对齐；抵消弹层四周 [shadowPadPx] 的阴影留白。 */
private class AnchorMenuPositionProvider(
    private val gapPx: Int,
    private val shadowPadPx: Int,
    private val alignEnd: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val rawX = if (alignEnd) anchorBounds.right - popupContentSize.width + shadowPadPx else anchorBounds.left - shadowPadPx
        val x = rawX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx - shadowPadPx
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - gapPx - popupContentSize.height + shadowPadPx).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}
