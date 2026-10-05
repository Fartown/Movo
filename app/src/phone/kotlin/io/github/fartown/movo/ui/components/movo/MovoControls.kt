package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.R
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

/**
 * 开关 `Switch`（规范 8，2026-09-25 定稿 S2）：轨道 48 × 28，滑块 22（距边 3）。
 * 开 = 轨道 action/primary-bg + 滑块 action/primary-fg（无阴影）；关 = 轨道 bg/surface-muted + 1 宽 border/strong + 白滑块（轻阴影）。
 * 按下时滑块朝将要移动的方向拉长 22 → 26；松手滑块位移并缩回，`fast` + `easing/standard`；轻触感（9.3.1、9.9）。
 */
@Composable
internal fun MovoSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 由整行负责切换时传入行的按压状态，开关随行按下拉长（规范 9.3.1「开关」）。 */
    interactionSource: MutableInteractionSource? = null,
) {
    val haptic = LocalHapticFeedback.current
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    // 动画值只保存 State，在放置 / 测量 / 绘制阶段读取：切换与按压期间开关不重组。
    val thumbWidth = animateDpAsState(
        targetValue = if (pressed && enabled && !reduced) 26.dp else 22.dp,
        animationSpec = MovoMotion.fast(),
        label = "switchThumbWidth",
    )
    val progress = animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = MovoMotion.fast(),
        label = "switchProgress",
    )
    val trackColor = animateColorAsState(
        if (checked) MovoColors.actionPrimaryBg else MovoColors.bgSurfaceMuted,
        MovoMotion.fast(),
        label = "switchTrack",
    )
    val thumbColor = animateColorAsState(
        if (checked) MovoColors.actionPrimaryFg else MovoColors.bgSurface,
        MovoMotion.fast(),
        label = "switchThumb",
    )
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = source,
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = {
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onCheckedChange(it)
            },
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 28.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .then(toggle)
            .clip(CircleShape)
            .drawBehind {
                drawRoundRect(trackColor.value, cornerRadius = CornerRadius(size.height / 2))
                // 关态 1 宽 border/strong 描边，开时淡出；与 Modifier.border 一样画在轨道内侧。
                val borderAlpha = 1f - progress.value
                if (borderAlpha > 0f) {
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                        color = MovoColors.borderStrong,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius((size.height - stroke) / 2),
                        style = Stroke(stroke),
                        alpha = borderAlpha,
                    )
                }
            },
    ) {
        // 滑块左缘在 3 → 23（位移 20）；拉长时朝移动方向伸展，另一侧不动。位移在放置阶段、宽度在测量阶段读取。
        val thumb = Modifier
            .offset {
                val p = progress.value
                val left = 3.dp + 20.dp * p - (thumbWidth.value - 22.dp) * p
                IntOffset(left.roundToPx(), 3.dp.roundToPx())
            }
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(thumbWidth.value.roundToPx(), 22.dp.roundToPx()))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
        // 关态轻阴影：参数固定只光栅化一次，开时只淡出图层透明度（ModulateAlpha 不开离屏缓冲，阴影可画出边界）。
        Box(
            modifier = thumb
                .graphicsLayer {
                    alpha = 1f - progress.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .dropShadow(
                    CircleShape,
                    Shadow(radius = 3.dp, offset = DpOffset(0.dp, 1.dp), color = MovoColors.shadow, alpha = 0.18f),
                ),
        )
        Box(
            modifier = thumb.drawBehind {
                drawRoundRect(thumbColor.value, cornerRadius = CornerRadius(size.height / 2))
            },
        )
    }
}

/** 无底色图标按钮：热区 44、图标 24，按压出现 40 圆形叠加层（规范 8「图标按钮」）。 */
@Composable
internal fun MovoIconButton(
    icon: MovoIconData,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = MovoSize.iconLarge,
    tint: Color = MovoColors.textPrimary,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(MovoSize.touchTarget)
            .movoClickable(PressKind.Icon, enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        MovoIcon(icon, contentDescription = null, size = iconSize, tint = tint)
    }
}

/** `VoiceMode/SplitControl`：语音退出与临时键盘输入保持为两个独立操作。 */
internal enum class VoiceModeSplitControlSize { Standard, Compact }

@Composable
internal fun VoiceModeSplitControl(
    onExitVoice: () -> Unit,
    onKeyboardInput: () -> Unit,
    modifier: Modifier = Modifier,
    size: VoiceModeSplitControlSize = VoiceModeSplitControlSize.Standard,
) {
    val compact = size == VoiceModeSplitControlSize.Compact
    val visualWidth = if (compact) 84.dp else 116.dp
    val visualHeight = if (compact) MovoSize.controlCompact else 36.dp
    val touchHeight = if (compact) MovoSize.controlSmall else MovoSize.touchTarget
    val exitWidth = if (compact) 52.dp else 72.dp
    val keyboardWidth = if (compact) 31.dp else 43.dp
    val dividerWidth = 1.dp
    val dividerHeight = if (compact) 12.dp else 18.dp
    val shape = RoundedCornerShape(visualHeight / 2)
    val exitShape = RoundedCornerShape(topStart = visualHeight / 2, bottomStart = visualHeight / 2)
    val keyboardShape = RoundedCornerShape(topEnd = visualHeight / 2, bottomEnd = visualHeight / 2)
    val exitDescription = stringResource(R.string.movo_voice_exit)
    val keyboardDescription = stringResource(R.string.movo_voice_temporary_keyboard_input)

    Box(
        modifier = modifier.size(width = visualWidth, height = touchHeight),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .size(width = visualWidth, height = visualHeight)
                .clip(shape)
                .background(MovoColors.bgSurfaceMuted)
                .border(MovoSize.hairline, MovoColors.borderHairline, shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(exitWidth))
            Box(Modifier.size(width = dividerWidth, height = dividerHeight).background(MovoColors.borderHairline))
            Spacer(Modifier.width(keyboardWidth))
        }
        Row(
            modifier = Modifier.size(width = visualWidth, height = touchHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(exitWidth)
                    .fillMaxHeight()
                    .movoClickable(PressKind.Row, shape = exitShape, onClick = onExitVoice)
                    .semantics(mergeDescendants = true) { contentDescription = exitDescription },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MovoIcon(
                        MovoIcons.X,
                        contentDescription = null,
                        size = if (compact) MovoSize.iconTiny else MovoSize.iconLabel,
                        tint = MovoColors.textPrimary,
                    )
                    Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
                    Text(
                        stringResource(R.string.movo_voice_exit_short),
                        style = if (compact) MovoTypography.microMedium else MovoTypography.labelMedium,
                        color = MovoColors.textPrimary,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(dividerWidth))
            Box(
                modifier = Modifier
                    .width(keyboardWidth)
                    .fillMaxHeight()
                    .movoClickable(PressKind.Row, shape = keyboardShape, onClick = onKeyboardInput)
                    .semantics { contentDescription = keyboardDescription },
                contentAlignment = Alignment.Center,
            ) {
                MovoIcon(
                    MovoIcons.Keyboard,
                    contentDescription = null,
                    size = if (compact) MovoSize.iconLabel else 18.dp,
                    tint = MovoColors.textPrimary,
                )
            }
        }
    }
}

/** `Button/Pill` 行内按钮：高 32、最小宽 64、圆角 16、bg/surface-muted、Label/Medium。 */
@Composable
internal fun MovoPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: MovoIconData? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val shape = RoundedCornerShape(MovoRadius.md)
    Row(
        modifier = modifier
            .height(MovoSize.controlSmall)
            .widthIn(min = 64.dp)
            .movoClickable(PressKind.Solid, shape = shape, enabled = enabled, onClick = onClick)
            .clip(shape)
            .background(if (primary) MovoColors.actionPrimaryBg else MovoColors.bgSurfaceMuted)
            .padding(horizontal = MovoSpacing.md),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (primary) MovoColors.actionPrimaryFg else MovoColors.textPrimary
        if (icon != null) {
            MovoIcon(icon, null, size = MovoSize.iconSmall, tint = fg)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MovoTypography.labelMedium, color = fg, maxLines = 1)
    }
}

/**
 * `Button/Block` 整行按钮：高 48、圆角 24；主 = action/primary，次 = bg/surface-muted，
 * 危险确认 = bg/inverse + 白字（规范 8.11）。
 */
internal enum class BlockTone { Primary, Secondary, Destructive }

@Composable
internal fun MovoBlockButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: BlockTone = BlockTone.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val shape = RoundedCornerShape(MovoRadius.pillLg)
    val (bg, fg) = when (tone) {
        BlockTone.Primary -> MovoColors.actionPrimaryBg to MovoColors.actionPrimaryFg
        BlockTone.Secondary -> MovoColors.bgSurfaceMuted to MovoColors.textPrimary
        BlockTone.Destructive -> MovoColors.bgInverse to MovoColors.textOnInverse
    }
    Box(
        modifier = modifier
            .heightIn(min = MovoSize.controlLarge)
            .movoClickable(PressKind.Solid, shape = shape, enabled = enabled, onClick = onClick)
            .clip(shape)
            .background(bg)
            .padding(horizontal = MovoSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        // 进行中：文字原位淡出、加载圈原位淡入（`fast`），按钮宽高不变，所在按钮区不会重排。
        val loadingProgress by animateFloatAsState(
            targetValue = if (loading) 1f else 0f,
            animationSpec = MovoMotion.fast(),
            label = "blockButtonLoading",
        )
        Text(
            label,
            style = MovoTypography.bodyStrong,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.graphicsLayer { alpha = 1f - loadingProgress },
        )
        if (loadingProgress > 0f) {
            MovoSpinner(
                color = fg,
                modifier = Modifier.graphicsLayer { alpha = loadingProgress },
            )
        }
    }
}

/** `Button/Circle` 40 圆形按钮：次要 bg/surface-muted，主操作 action/primary。 */
@Composable
internal fun MovoCircleButton(
    icon: MovoIconData,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val bg = when {
        primary -> MovoColors.actionPrimaryBg
        selected -> MovoColors.indigoBg
        else -> MovoColors.bgSurfaceMuted
    }
    val fg = when {
        primary -> MovoColors.actionPrimaryFg
        selected -> MovoColors.indigoFg
        else -> MovoColors.textPrimary
    }
    Box(
        modifier = modifier
            .size(MovoSize.controlMedium)
            .movoClickable(PressKind.Solid, shape = CircleShape, enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(bg)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        MovoIcon(icon, null, size = MovoSize.iconMedium, tint = fg)
    }
}

/**
 * 对话框按钮区（规范 8.11）：放在对话框底部，外边距用 [MovoDialogButtonPadding]（左右、下各 24，不贴对话框边缘）。
 * 按钮等宽并排、间距 12；任一按钮的文字在自己那一份宽度里放不下时改为上下排列、整行宽、间距 8，
 * 主操作（最后一个）在最上面——文字不截断。只有一个按钮时占满整行。
 */
@Composable
internal fun MovoButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val width = constraints.maxWidth
        if (measurables.isEmpty()) return@Layout layout(width, 0) {}
        val gap = MovoSpacing.md.roundToPx()
        val stackGap = MovoSpacing.sm.roundToPx()
        val count = measurables.size
        val slot = (width - gap * (count - 1)) / count
        val fitsSideBySide = measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= slot }
        if (fitsSideBySide) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(slot)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                var x = 0
                placeables.forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += slot + gap
                }
            }
        } else {
            val placeables = measurables.asReversed().map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + stackGap * (count - 1)
            layout(width, height) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(0, y)
                    y += it.height + stackGap
                }
            }
        }
    }
}

/** 对话框按钮区的外边距：左右、下各 24，与内容区对齐（内容区下内边距 24 即两者间距）。 */
internal val MovoDialogButtonPadding = androidx.compose.foundation.layout.PaddingValues(
    start = MovoSpacing.xxl,
    end = MovoSpacing.xxl,
    bottom = MovoSpacing.xxl,
)

/**
 * 加载圈（规范 9.1：800ms 一圈，`linear`）：Lucide loader-circle 的 3/4 圆弧。
 * 减少动画时为静态完整圆环（9.8），不会停在某一帧看起来像卡死。
 */
@Composable
internal fun MovoSpinner(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = MovoSize.iconSmall,
    color: Color = MovoColors.indigoFg,
) {
    val reduced = LocalReducedMotion.current
    // 旋转角只保存 State，在 graphicsLayer 里读：转动时不重组。
    val rotation: State<Float>? = if (reduced) {
        null
    } else {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "movoSpinner")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(MovoMotion.SPINNER_PERIOD, easing = MovoMotion.EasingLinear),
            ),
            label = "movoSpinnerRotation",
        )
    }
    val strokeDp = io.github.fartown.movo.ui.theme.MovoIconData.strokeFor(size).dp
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation?.value ?: 0f },
    ) {
        val stroke = strokeDp.toPx()
        // 24 网格里圆弧半径 9：按比例换算，与 Lucide 图标同样的视觉大小。
        val radius = this.size.minDimension * 9f / 24f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = if (reduced) 360f else 270f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}
