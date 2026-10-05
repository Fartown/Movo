package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoElevation
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 弹出菜单的一项；[destructive] 为 Rose（删除类）。
 * [confirmIcon] 不为 null 时（如复制 → ✓，规范 9.3「复制」）：点击后菜单不立即关闭，图标原地交叉淡化为它（`fast`，
 * 缩放 0.72 ↔ 1），停留 [MovoMotion.MENU_CLOSE_DELAY] 后再关闭（同 9.3.1 单选「新 ✓ 出现后停留再关闭」），不另弹提示。
 */
internal data class MovoMenuItem(
    val icon: MovoIconData,
    val label: String,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val confirmIcon: MovoIconData? = null,
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
    // 点了带确认图标的项（复制）：图标换成 ✓ 后停留再关闭。
    var confirmedIndex by remember { mutableIntStateOf(-1) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val hidden = !visibleState.currentState && !visibleState.targetState
    // 退场播完再复位，下次打开不会先闪一帧 ✓。
    LaunchedEffect(hidden) { if (hidden) confirmedIndex = -1 }
    LaunchedEffect(confirmedIndex) {
        if (confirmedIndex >= 0) {
            delay((MovoMotion.FAST + MovoMotion.MENU_CLOSE_DELAY).toLong())
            currentOnDismiss()
        }
    }
    if (hidden) return
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
                items.forEachIndexed { index, item ->
                    val itemShape = RoundedCornerShape(MovoRadius.sm)
                    val tint = if (item.destructive) MovoColors.roseFg else MovoColors.textPrimary
                    val confirmed = index == confirmedIndex && item.confirmIcon != null
                    Row(
                        modifier = Modifier
                            .widthIn(min = MenuMinWidth - MovoSpacing.lg)
                            .height(MovoSize.touchTarget)
                            .clip(itemShape)
                            .movoClickable(PressKind.Row, shape = itemShape, enabled = item.enabled) {
                                if (confirmedIndex >= 0) return@movoClickable
                                if (item.confirmIcon != null) {
                                    item.onClick()
                                    confirmedIndex = index
                                } else {
                                    onDismiss()
                                    item.onClick()
                                }
                            }
                            .padding(horizontal = MovoSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 图标状态切换：交叉淡化 + 缩放 0.72 ↔ 1，`fast`（规范 9.3.1）；✓ 用 Green（完成）。
                        AnimatedContent(
                            targetState = confirmed,
                            transitionSpec = {
                                (fadeIn(MovoMotion.fast()) + scaleIn(MovoMotion.fast(), initialScale = 0.72f))
                                    .togetherWith(fadeOut(MovoMotion.fastExit()) + scaleOut(MovoMotion.fastExit(), targetScale = 0.72f))
                            },
                            label = "menuItemIcon",
                        ) { showConfirm ->
                            val confirmIcon = item.confirmIcon
                            if (showConfirm && confirmIcon != null) {
                                MovoIcon(confirmIcon, null, size = MovoSize.iconMedium, tint = MovoColors.greenFg)
                            } else {
                                MovoIcon(item.icon, null, size = MovoSize.iconMedium, tint = tint)
                            }
                        }
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
