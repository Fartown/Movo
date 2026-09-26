package io.github.fartown.movo.ui.components.movo

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoElevation
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/**
 * 自定义内容的 `Popover/Menu`（规范 8「弹出菜单」、9.3）：与 [MovoPopoverMenu] 同一表面（圆角 20、内边距 8、白底 + 发丝描边、E3）
 * 与同一进退场（从锚点缩放 0.96 → 1 并淡入 `fast` + `enter`，退场淡出 120ms；减少动画时只淡入淡出）。
 *
 * 与 [MovoPopoverMenu] 的区别：菜单出现在 [aboveYPx]（窗口坐标）上方 [ChatPopoverGap]，用于输入框的菜单——整块出现在输入框上方，
 * 不盖住正在编辑的文字；超过 [maxHeight] 时框内滚动；内容由调用方组合（分组、单选、说明）。
 * 放在锚点所在的 Box 里使用，锚点即父布局；[alignEnd] 时右缘与锚点右缘对齐，否则左缘对齐。
 * 弹层不抢焦点：打开菜单时键盘保持（输入框不因键盘收起而跳动）；点外面或按返回关闭。
 *
 * @param width 固定宽度；不给时按最宽一项，最小 [PopoverMinWidth]。
 */
@Composable
internal fun MovoPopover(
    show: Boolean,
    onDismiss: () -> Unit,
    aboveYPx: Int,
    alignEnd: Boolean = false,
    maxHeight: Dp = Dp.Unspecified,
    width: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = show
    if (!visibleState.currentState && !visibleState.targetState) return
    val density = LocalDensity.current
    val gapPx = with(density) { ChatPopoverGap.roundToPx() }
    val shadowPadPx = with(density) { MovoSpacing.xxl.roundToPx() }
    val positionProvider = remember(aboveYPx, gapPx, shadowPadPx, alignEnd) {
        AbovePopoverPositionProvider(aboveYPx, gapPx, shadowPadPx, alignEnd)
    }
    val reduced = LocalReducedMotion.current
    // 弹层窗口不可聚焦（保持键盘），返回键由所在窗口处理。
    androidx.activity.compose.BackHandler(enabled = show, onBack = onDismiss)
    // 弹层窗口固定为「屏幕顶到输入框上方」这一整块，卡片贴底放在里面：分组展开 / 收起时只在窗口内重新排版，
    // 窗口本身的大小和位置不变（原来窗口随内容逐帧变高，系统每帧重新摆放窗口，真机明显卡顿）。
    // 这块区域里卡片以外的地方点一下即关闭，与点窗口外一致。没有 [aboveYPx] 时仍按内容大小摆放。
    val regionHeight = if (aboveYPx > 0) with(density) { (aboveYPx - gapPx + shadowPadPx).coerceAtLeast(0).toDp() } else Dp.Unspecified
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
    ) {
        Box(
            modifier = if (regionHeight != Dp.Unspecified) {
                Modifier
                    .height(regionHeight)
                    .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            } else {
                Modifier
            },
            contentAlignment = if (alignEnd) Alignment.BottomEnd else Alignment.BottomStart,
        ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter = if (reduced) {
                fadeIn(MovoMotion.fast())
            } else {
                fadeIn(MovoMotion.fast(MovoMotion.EasingEnter)) +
                    scaleIn(
                        MovoMotion.fast(MovoMotion.EasingEnter),
                        initialScale = 0.96f,
                        transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 1f),
                    )
            },
            exit = fadeOut(MovoMotion.fastExit()),
        ) {
            val shape = RoundedCornerShape(MovoRadius.lg)
            Column(
                modifier = Modifier
                    .padding(MovoSpacing.xxl)
                    .then(
                        if (width != Dp.Unspecified) {
                            Modifier.width(width)
                        } else {
                            Modifier.width(IntrinsicSize.Max).widthIn(min = PopoverMinWidth)
                        },
                    )
                    .then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier)
                    // 卡片自己吃掉点击，不落到外层「点空白关闭」。
                    .pointerInput(Unit) { detectTapGestures { } }
                    .movoSurface(shape, MovoElevation.Overlay)
                    .verticalScroll(rememberScrollState())
                    .padding(MovoSpacing.sm),
                content = content,
            )
        }
        }
    }
}

/**
 * 菜单项（规范 8「弹出菜单」项高 44、圆角 12；9.3.1 列表行按压：整行叠加、不缩放，无水波纹）。
 * [selected] 不为 null 时是单选项：选中为 Indigo 浅底 + Indigo 文字 + ✓（规范 8 通用状态「选中」），
 * 底色与文字 `fast` 过渡；新 ✓ 缩放 0.72 → 1 淡入 `fast`，旧 ✓ 淡出 120ms（9.3.1「单选」）。
 */
@Composable
internal fun MovoPopoverItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: MovoIconData? = null,
    selected: Boolean? = null,
    enabled: Boolean = true,
) {
    val itemShape = RoundedCornerShape(MovoRadius.sm)
    val isSelected = selected == true
    val background by animateColorAsState(
        if (isSelected) MovoColors.indigoBg else Color.Transparent,
        MovoMotion.fast(),
        label = "popoverItemBg",
    )
    val tint by animateColorAsState(
        if (isSelected) MovoColors.indigoFg else MovoColors.textPrimary,
        MovoMotion.fast(),
        label = "popoverItemFg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MovoSize.touchTarget)
            .clip(itemShape)
            .background(background)
            .movoClickable(
                PressKind.Row,
                shape = itemShape,
                enabled = enabled,
                role = if (selected != null) Role.RadioButton else Role.Button,
                onClick = onClick,
            )
            .then(if (selected != null) Modifier.semantics { this.selected = isSelected } else Modifier)
            .padding(horizontal = MovoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            MovoIcon(icon, null, size = MovoSize.iconMedium, tint = tint)
            Spacer(Modifier.width(MovoSpacing.md))
        }
        Text(
            label,
            style = MovoTypography.bodyRegular,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected != null) {
            Spacer(Modifier.width(MovoSpacing.sm))
            PopoverCheck(visible = isSelected)
        }
    }
}

/**
 * 菜单里的分组头，写法同 `Card/Title`（规范 2.3）：`Label/Medium` 13 次要色；可点的分组头整行 44 热区，
 * 右侧 16 箭头次要色，展开 / 收起旋转 180° `fast`（9.3「展开 / 收起」，不换图标）。
 */
@Composable
internal fun MovoPopoverGroupHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    toggleDescription: String,
) {
    val reduced = LocalReducedMotion.current
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reduced) androidx.compose.animation.core.snap() else MovoMotion.fast(),
        label = "popoverGroupArrow",
    )
    val shape = RoundedCornerShape(MovoRadius.sm)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MovoSize.touchTarget)
            .clip(shape)
            .movoClickable(PressKind.Row, shape = shape, onClick = onToggle)
            .semantics { contentDescription = toggleDescription }
            .padding(horizontal = MovoSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MovoTypography.labelMedium,
            color = MovoColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Spacer(Modifier.width(MovoSpacing.sm))
        MovoIcon(
            MovoIcons.ChevronDown,
            null,
            size = MovoSize.iconSmall,
            tint = MovoColors.textSecondary,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

@Composable
private fun PopoverCheck(visible: Boolean) {
    Box(Modifier.size(MovoSize.iconMedium), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(MovoMotion.fast()) +
                if (LocalReducedMotion.current) fadeIn(MovoMotion.fast()) else scaleIn(MovoMotion.fast(), initialScale = 0.72f),
            exit = fadeOut(MovoMotion.fastExit()),
        ) {
            MovoIcon(MovoIcons.Check, null, size = MovoSize.iconMedium, tint = MovoColors.indigoFg)
        }
    }
}

/** 菜单与输入框之间的间距（与 `Composer/Notice`「输入框上方 8」一致）。 */
internal val ChatPopoverGap = MovoSpacing.sm

private val PopoverMinWidth = 200.dp

/**
 * 菜单可见底边落在 [aboveYPx] 上方 [gapPx]（[aboveYPx] ≤ 0 时退回锚点上缘）；左缘或右缘对齐锚点，放不下时收进窗口。
 * 抵消弹层四周 [shadowPadPx] 的阴影留白。
 */
internal class AbovePopoverPositionProvider(
    private val aboveYPx: Int,
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
        val end = if (layoutDirection == LayoutDirection.Ltr) alignEnd else !alignEnd
        val rawX = if (end) {
            anchorBounds.right - popupContentSize.width + shadowPadPx
        } else {
            anchorBounds.left - shadowPadPx
        }
        val x = rawX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val visibleBottom = (if (aboveYPx > 0) aboveYPx else anchorBounds.top) - gapPx
        val y = (visibleBottom + shadowPadPx - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
