package io.github.fartown.movo.ui.pages.providers

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
import io.github.fartown.movo.ui.components.movo.MovoIconButton
import io.github.fartown.movo.ui.components.movo.PressKind
import io.github.fartown.movo.ui.components.movo.movoClickable
import io.github.fartown.movo.ui.components.movo.movoSurface
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIconData
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoMotion
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

// 本文件的控件目前只在 Provider 页与工具能力页使用；稳定后建议并入 ui/components/movo。

/**
 * 分段 / 标签（规范 9.3.1「分段 / 标签」）：40 高白底胶囊 + 0.5 描边，内缩 4 放 32 高选中底块（Indigo 浅底，同心 20 = 16 + 4）；
 * 选中底块滑到新位置 `standard` + `easing/standard`，文字颜色过渡 `fast`（选中 Indigo、未选中次要色）。
 */
@Composable
internal fun MovoSegmentedTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(MovoSize.controlMedium)
            .movoSurface(CircleShape)
            .padding(MovoSpacing.xs),
    ) {
        val segmentWidth = maxWidth / tabs.size.coerceAtLeast(1)
        // 底块位移只在放置阶段读取：滑动期间整组分段不重组。
        val indicatorOffset = animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = if (reduced) snap() else MovoMotion.standard(),
            label = "segmentIndicator",
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(indicatorOffset.value.roundToPx(), 0) }
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MovoColors.indigoBg),
        )
        Row(modifier = Modifier.fillMaxSize().selectableGroup()) {
            tabs.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val color by animateColorAsState(
                    targetValue = if (selected) MovoColors.indigoFg else MovoColors.textSecondary,
                    animationSpec = MovoMotion.fast(),
                    label = "segmentText",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { this.selected = selected }
                        .movoClickable(PressKind.Row, shape = CircleShape, role = Role.Tab) {
                            if (!selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(index)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MovoTypography.labelMedium,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = MovoSpacing.sm),
                    )
                }
            }
        }
    }
}

/**
 * 搜索框：48 高白底胶囊 + 0.5 描边，外沿对齐边距线 20、图标对齐内容线 36；有内容时右侧出现清除按钮。
 */
@Composable
internal fun MovoSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MovoSize.controlLarge)
            .movoSurface(RoundedCornerShape(MovoRadius.pillLg))
            .padding(start = MovoSpacing.lg, end = MovoSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MovoIcon(MovoIcons.Search, contentDescription = null, size = MovoSize.iconMedium, tint = MovoColors.textSecondary)
        Spacer(Modifier.width(MovoSpacing.sm))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MovoTypography.bodyRegular,
                    color = MovoColors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MovoTypography.bodyRegular.copy(color = MovoColors.textPrimary),
                cursorBrush = SolidColor(MovoColors.indigoFg),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            MovoIconButton(
                icon = MovoIcons.X,
                contentDescription = stringResource(R.string.movo_action_clear_search),
                onClick = { onQueryChange("") },
                iconSize = MovoSize.iconMedium,
                tint = MovoColors.textSecondary,
            )
        } else {
            Spacer(Modifier.width(MovoSpacing.md))
        }
    }
}

/** 长列表拆成多个 Lazy 条目时，同一张卡片的位置：顶部（带圆角上沿）、中间、底部（带圆角下沿）。 */
internal enum class CardSegment { Top, Middle, Bottom, Single }

/**
 * 把一张列表卡片拆到多个 Lazy 条目：白底 + 0.5 发丝描边 + 28 圆角只画在卡片的上下两端，中间条目只画左右描边；
 * 行按压叠加层同样被卡片圆角裁切。中间条目没有圆角，用矩形底色与矩形裁剪，不做逐行路径裁剪。
 */
internal fun Modifier.movoCardSegment(segment: CardSegment): Modifier = drawWithCache {
    val stroke = MovoSize.hairline.toPx()
    if (segment == CardSegment.Middle) {
        return@drawWithCache onDrawWithContent {
            clipRect {
                drawRect(MovoColors.bgSurface)
                this@onDrawWithContent.drawContent()
                drawRect(MovoColors.borderHairline, size = Size(stroke, size.height))
                drawRect(MovoColors.borderHairline, topLeft = Offset(size.width - stroke, 0f), size = Size(stroke, size.height))
            }
        }
    }
    val radius = MovoRadius.xl.toPx()
    val extendTop = segment == CardSegment.Middle || segment == CardSegment.Bottom
    val extendBottom = segment == CardSegment.Top || segment == CardSegment.Middle
    val top = if (extendTop) -radius * 2 else 0f
    val bottom = if (extendBottom) size.height + radius * 2 else size.height
    val fill = Path().apply {
        addRoundRect(RoundRect(Rect(0f, top, size.width, bottom), CornerRadius(radius)))
    }
    val outline = Path().apply {
        addRoundRect(
            RoundRect(
                Rect(stroke / 2, top + stroke / 2, size.width - stroke / 2, bottom - stroke / 2),
                CornerRadius(radius - stroke / 2),
            ),
        )
    }
    onDrawWithContent {
        clipRect {
            clipPath(fill) {
                drawRect(MovoColors.bgSurface)
                this@onDrawWithContent.drawContent()
            }
            drawPath(outline, MovoColors.borderHairline, style = Stroke(stroke))
        }
    }
}

/**
 * 复选框（规范 9.3.1「复选」）：20 方框。勾选：`border/strong` 描边填充为 Indigo，白色 ✓ 从起点画到终点（`fast`）；
 * 取消：✓ 淡出 120ms，填充退回描边。圆角 6 由 20 尺寸推导（约 0.3）。
 */
@Composable
internal fun MovoCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val reduced = LocalReducedMotion.current
    val fill by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (reduced) snap() else if (checked) MovoMotion.fast() else MovoMotion.fastExit(),
        label = "checkboxFill",
    )
    val checkPath = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(
        modifier = modifier
            .size(MovoSize.iconMedium)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    ) {
        val corner = CornerRadius(6.dp.toPx())
        val strokeWidth = 1.dp.toPx()
        drawRoundRect(
            color = MovoColors.borderStrong,
            topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = corner,
            style = Stroke(strokeWidth),
            alpha = 1f - fill,
        )
        drawRoundRect(color = MovoColors.indigoFg, cornerRadius = corner, alpha = fill)
        if (fill > 0f) {
            // Lucide check（24 网格 M4 12 l5 5 L20 6）缩到 14 居中。
            val scale = size.width * 0.7f / 24f
            val inset = size.width * 0.15f
            val full = Path().apply {
                moveTo(inset + 4f * scale, inset + 12f * scale)
                lineTo(inset + 9f * scale, inset + 17f * scale)
                lineTo(inset + 20f * scale, inset + 6f * scale)
            }
            checkPath.reset()
            measure.setPath(full, false)
            val drawn = if (checked) measure.length * fill else measure.length
            measure.getSegment(0f, drawn, checkPath, true)
            drawPath(
                path = checkPath,
                color = MovoColors.textOnInverse,
                alpha = if (checked) 1f else fill,
                style = Stroke(
                    width = MovoIconData.strokeFor(MovoSize.iconSmall).dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

/**
 * 单选标记：未选中是 20 线条圆（与 Lucide circle 同几何），选中是 [MovoIcons.CircleCheck]。
 * 过渡按规范 9.3.1「单选」：旧 ✓ 淡出 120ms（`exit`）；新 ✓ 缩放 0.72 → 1 并淡入 `fast`，线条圆同时交叉淡化；
 * 减少动画时只淡入淡出（9.8）。进度只在图层 / 绘制阶段读取。
 */
@Composable
internal fun MovoRadioMark(selected: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val shown = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (selected) MovoMotion.fast() else MovoMotion.fastExit(),
        label = "radioMark",
    )
    Box(modifier = modifier.size(MovoSize.iconMedium), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val ring = 1f - shown.value
            if (ring <= 0f) return@Canvas
            val strokeWidth = MovoIconData.strokeFor(MovoSize.iconMedium).dp.toPx()
            drawCircle(
                color = MovoColors.textTertiary,
                radius = size.minDimension * 10f / 24f,
                style = Stroke(strokeWidth),
                alpha = ring,
            )
        }
        MovoIcon(
            MovoIcons.CircleCheck,
            contentDescription = null,
            size = MovoSize.iconMedium,
            tint = MovoColors.indigoFg,
            modifier = Modifier.graphicsLayer {
                val value = shown.value
                alpha = value
                // 只有选中（新 ✓ 出现）时缩放；取消选中只淡出。
                val scale = if (selected && !reduced) 0.72f + 0.28f * value else 1f
                scaleX = scale
                scaleY = scale
            },
        )
    }
}
