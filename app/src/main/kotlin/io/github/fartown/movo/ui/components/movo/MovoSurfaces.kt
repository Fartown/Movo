package io.github.fartown.movo.ui.components.movo

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.github.fartown.movo.ui.theme.LocalReducedMotion
import io.github.fartown.movo.ui.theme.MovoMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.widthIn
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
    /**
     * 卡内行增删、说明出现时卡片高度按 `standard` 过渡（9.3「展开 / 收起」，默认开：卡片高度不能跳变）。
     * 高度动画挂在卡片底色里面：挂在外层（传进 [modifier]）时会按动画高度裁掉卡片自己的底边，过渡期间底部圆角变成直角。
     * 高度变化时通知所在 Lazy 列表（[MovoListResize]），下方各项跟着卡片的实际高度走，不落后、不重叠；
     * 卡内有展开区（[MovoExpandable]）正在长高 / 收起时，卡片直接跟随它的每一帧，不再叠一层自己的过渡。
     */
    animateHeight: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val animate = animateHeight && !reduced
    val inner = remember { MovoListResize() }
    val listResize = LocalMovoListResize.current
    val scope = rememberCoroutineScope()
    val report = remember { MovoCardHeightReport() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .movoSurface(elevation = elevation)
            .then(
                if (animate) {
                    Modifier.animateContentSize(if (inner.active) snap() else MovoMotion.standard<IntSize>())
                } else {
                    Modifier
                },
            )
            // 在高度动画内侧量内容的目标高度：只在目标变化时回调一次，不逐帧。
            .then(
                if (animate && listResize != null) {
                    Modifier.onSizeChanged { size -> report.onTargetHeight(size.height, inner.active, scope, listResize) }
                } else {
                    Modifier
                },
            )
            .padding(bottom = bottomPadding),
    ) {
        CompositionLocalProvider(LocalMovoCardResize provides inner) { content() }
    }
}

/** 记录卡片内容的目标高度；变化时让所在列表在一次高度过渡的时长里跟随（重新计时，不叠加）。 */
private class MovoCardHeightReport {
    private var lastHeight = -1
    private var hold: Job? = null

    fun onTargetHeight(height: Int, innerActive: Boolean, scope: CoroutineScope, list: MovoListResize) {
        val previous = lastHeight
        lastHeight = height
        // 首次排版不算变化；卡内展开区在动时由它自己通知列表。
        if (previous < 0 || previous == height || innerActive) return
        hold?.cancel()
        hold = scope.launch { list.hold() }
    }
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
        // 右侧补充变化（出现、换字、消失）交叉淡化 `fast`（9.3「值变化」），单行过长省略，不挤动标题所在行的高度。
        androidx.compose.animation.Crossfade(
            targetState = trailing,
            animationSpec = MovoMotion.fast(),
            label = "cardTitleTrailing",
        ) { value ->
            if (value != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(max = CardTitleTrailingMax)) {
                    Spacer(Modifier.width(MovoSpacing.sm))
                    Text(
                        value,
                        style = MovoTypography.labelRegular,
                        color = MovoColors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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

/** 卡内标题右侧补充的最大宽度：再长就省略，不挤掉标题。 */
private val CardTitleTrailingMax = 200.dp
