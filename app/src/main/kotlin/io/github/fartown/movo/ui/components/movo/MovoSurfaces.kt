package io.github.fartown.movo.ui.components.movo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.fartown.movo.ui.theme.MovoColors
import io.github.fartown.movo.ui.theme.MovoElevation
import io.github.fartown.movo.ui.theme.MovoIcon
import io.github.fartown.movo.ui.theme.MovoIcons
import io.github.fartown.movo.ui.theme.MovoRadius
import io.github.fartown.movo.ui.theme.MovoSize
import io.github.fartown.movo.ui.theme.MovoSpacing
import io.github.fartown.movo.ui.theme.MovoTypography
import top.yukonga.miuix.kmp.basic.Text

/** 规范 7：暖灰双层阴影（阴影色 #2B2419），E0 无阴影。 */
internal fun Modifier.movoElevation(elevation: MovoElevation, shape: Shape): Modifier = when (elevation) {
    MovoElevation.E0 -> this
    MovoElevation.Card -> this
        .dropShadow(shape, Shadow(radius = 24.dp, spread = (-6).dp, offset = DpOffset(0.dp, 8.dp), color = MovoColors.shadow, alpha = 0.07f))
        .dropShadow(shape, Shadow(radius = 2.dp, offset = DpOffset(0.dp, 1.dp), color = MovoColors.shadow, alpha = 0.05f))
    MovoElevation.Composer -> this
        .dropShadow(shape, Shadow(radius = 40.dp, spread = (-12).dp, offset = DpOffset(0.dp, 16.dp), color = MovoColors.shadow, alpha = 0.12f))
        .dropShadow(shape, Shadow(radius = 3.dp, offset = DpOffset(0.dp, 1.dp), color = MovoColors.shadow, alpha = 0.06f))
    MovoElevation.Overlay -> this
        .dropShadow(shape, Shadow(radius = 64.dp, spread = (-12).dp, offset = DpOffset(0.dp, 24.dp), color = MovoColors.shadow, alpha = 0.18f))
        .dropShadow(shape, Shadow(radius = 6.dp, offset = DpOffset(0.dp, 2.dp), color = MovoColors.shadow, alpha = 0.06f))
}

/** 白底 + 0.5 发丝描边的表面；列表页卡片不加阴影（E0），内容卡片用 E1。 */
internal fun Modifier.movoSurface(
    shape: Shape = RoundedCornerShape(MovoRadius.xl),
    elevation: MovoElevation = MovoElevation.E0,
): Modifier = this
    .movoElevation(elevation, shape)
    .clip(shape)
    .background(MovoColors.bgSurface)
    .border(MovoSize.hairline, MovoColors.borderHairline, shape)

/**
 * 列表页分组卡片（规范 8.7）：宽随页面、圆角 28、白底 + 发丝描边、E0；
 * 上内边距 0（由 [CardTitle] 提供 16）、下内边距 4。
 */
@Composable
internal fun MovoCard(
    modifier: Modifier = Modifier,
    elevation: MovoElevation = MovoElevation.E0,
    bottomPadding: androidx.compose.ui.unit.Dp = MovoSpacing.xs,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .movoSurface(elevation = elevation)
            .padding(bottom = bottomPadding),
        content = content,
    )
}

/** `Card/Title` 卡内标题：13 Medium 次要色，左右 16，上 16、下 4；右侧可放补充（三级色）。 */
@Composable
internal fun CardTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = MovoSpacing.lg, end = MovoSpacing.lg, top = MovoSpacing.lg, bottom = MovoSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MovoTypography.labelMedium,
            color = MovoColors.textSecondary,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Spacer(Modifier.width(MovoSpacing.sm))
            Text(trailing, style = MovoTypography.labelRegular, color = MovoColors.textTertiary, maxLines = 1)
        }
    }
}

/**
 * `Card/Footer` 卡片页脚：0.5 分隔线（左右内缩 16）→ 上下 12：ⓘ 14 次要色 + 6 + 13 Regular 次要色；
 * 一句一行（规范 10.1），多句传多行。
 */
@Composable
internal fun CardFooter(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        MovoDivider(start = MovoSpacing.lg)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MovoSpacing.lg, vertical = MovoSpacing.md),
        ) {
            Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.Center) {
                MovoIcon(MovoIcons.Info, contentDescription = null, size = MovoSize.iconLabel, tint = MovoColors.textSecondary)
            }
            Spacer(Modifier.width(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                lines.forEach { line ->
                    Text(line, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
                }
            }
        }
    }
}

/** 0.5 分隔线；[start] 与 [end] 为左右内缩。 */
@Composable
internal fun MovoDivider(
    modifier: Modifier = Modifier,
    start: androidx.compose.ui.unit.Dp = 0.dp,
    end: androidx.compose.ui.unit.Dp = MovoSpacing.lg,
) {
    Box(
        modifier = modifier
            .padding(start = start, end = end)
            .fillMaxWidth()
            .height(MovoSize.hairline)
            .background(MovoColors.borderHairline),
    )
}

/** `Section/Header` 内容页分组标题：16 Medium 主色，右侧可放「全部 ›」链接。 */
@Composable
internal fun MovoSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MovoTypography.titleSection,
            color = MovoColors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            Row(
                modifier = Modifier.movoClickable(PressKind.Link, onClick = onAction),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(actionLabel, style = MovoTypography.labelRegular, color = MovoColors.textSecondary)
                MovoIcon(MovoIcons.ChevronRight, null, size = MovoSize.iconSmall, tint = MovoColors.textSecondary)
            }
        }
    }
}
