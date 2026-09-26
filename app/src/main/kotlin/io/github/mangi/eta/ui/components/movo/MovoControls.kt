package io.github.mangi.eta.ui.components.movo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.theme.LocalReducedMotion
import io.github.mangi.eta.ui.theme.MovoColors
import io.github.mangi.eta.ui.theme.MovoIcon
import io.github.mangi.eta.ui.theme.MovoIconData
import io.github.mangi.eta.ui.theme.MovoMotion
import io.github.mangi.eta.ui.theme.MovoRadius
import io.github.mangi.eta.ui.theme.MovoSize
import io.github.mangi.eta.ui.theme.MovoSpacing
import io.github.mangi.eta.ui.theme.MovoTypography
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
    val thumbWidth by animateDpAsState(
        targetValue = if (pressed && enabled && !reduced) 26.dp else 22.dp,
        animationSpec = MovoMotion.fast(),
        label = "switchThumbWidth",
    )
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = MovoMotion.fast(),
        label = "switchProgress",
    )
    val trackColor by animateColorAsState(
        if (checked) MovoColors.actionPrimaryBg else MovoColors.bgSurfaceMuted,
        MovoMotion.fast(),
        label = "switchTrack",
    )
    val thumbColor by animateColorAsState(
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
            .background(trackColor)
            .border(1.dp, MovoColors.borderStrong.copy(alpha = MovoColors.borderStrong.alpha * (1f - progress)), CircleShape),
    ) {
        // 滑块左缘在 3 → 23（位移 20）；拉长时朝移动方向伸展，另一侧不动。
        val travel = 20.dp
        val extra = thumbWidth - 22.dp
        val left = 3.dp + travel * progress - extra * progress
        Box(
            modifier = Modifier
                .offset(x = left, y = 3.dp)
                .size(width = thumbWidth, height = 22.dp)
                .dropShadow(
                    CircleShape,
                    Shadow(radius = 3.dp, offset = DpOffset(0.dp, 1.dp), color = MovoColors.shadow, alpha = 0.18f * (1f - progress)),
                )
                .clip(CircleShape)
                .background(thumbColor),
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
        Text(
            label,
            style = MovoTypography.bodyStrong,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/** 占满一行的容器，给整行按钮并排使用。 */
@Composable
internal fun MovoButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MovoSpacing.xs),
        content = content,
    )
}

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
    val rotation = if (reduced) {
        0f
    } else {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "movoSpinner")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(MovoMotion.SPINNER_PERIOD, easing = MovoMotion.EasingLinear),
            ),
            label = "movoSpinnerRotation",
        )
        value
    }
    val strokeDp = io.github.mangi.eta.ui.theme.MovoIconData.strokeFor(size).dp
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation },
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
